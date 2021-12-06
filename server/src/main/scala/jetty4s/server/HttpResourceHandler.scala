package jetty4s.server

import cats.effect._
import cats.effect.std.{ Dispatcher, Queue }
import cats.implicits._
import com.comcast.ip4s.{ IpAddress, Port, SocketAddress }
import fs2._
import jakarta.servlet.http.{ HttpServletRequest, HttpServletResponse }
import jakarta.servlet.{ ReadListener, WriteListener }
import jetty4s.common.RepeatedReadException
import jetty4s.server.HttpResourceHandler._
import org.eclipse.jetty.http.{ HttpScheme, HttpURI }
import org.eclipse.jetty.io.ssl.SslConnection
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.{ server => jetty }
import org.http4s._
import org.slf4j.LoggerFactory
import org.typelevel.ci.CIString
import org.typelevel.vault.Vault

import scala.collection.JavaConverters._

private[server] class HttpResourceHandler[F[_] : Async](
  app: Request[F] => Resource[F, Response[F]],
  asyncTimeout: Long,
  dispatcher: Dispatcher[F],
  chunkSize: Int = 1024,
  queueSizeBounds: Int = 4
) extends AbstractHandler {
  private[this] val logger = LoggerFactory.getLogger(this.getClass)

  private[this] def unsafeFork[A](fa: F[A], op: String): Unit =
    dispatcher.unsafeRunAndForget {
      fa.onError { case t => Sync[F].delay(logger.debug(s"unsafeFork/$op failed!", t)) }
    }

  def handle(
    target: String,
    baseReq: jetty.Request,
    req: HttpServletRequest,
    res: HttpServletResponse
  ): Unit = {
    val requestIsEmpty = "0" == req.getHeader("content-length")
    var autoFlush: Boolean = false
    val ctx = req.startAsync()
    ctx.setTimeout(asyncTimeout)

    baseReq.getHttpChannel.getEndPoint match {
      case _: SslConnection#DecryptedEndPoint =>
        baseReq.setHttpURI(HttpURI.build(baseReq.getHttpURI).scheme(HttpScheme.HTTPS))
        baseReq.setSecure(true)
      case _ => /* empty */
    }

    val requestBody: Stream[F, Byte] = Stream.suspend {
      def newBuf() = new Array[Byte](chunkSize)

      var buf = newBuf()
      val in = req.getInputStream
      val store = dispatcher.unsafeRunSync(Queue.bounded[F, Any](queueSizeBounds))

      in.setReadListener(new ReadListener {
        def onDataAvailable(): Unit = {
          def loop: F[Unit] =
            Sync[F].delay(in.read(buf)).flatMap {
              case len if len < 0 => Sync[F].unit
              case len =>
                if (len == 0) logger.debug("InputStream#read(...) == 0")
                val bytes =
                  if (len == chunkSize) {
                    val res = Chunk.array(buf)
                    buf = newBuf()
                    res
                  } else Chunk.array(java.util.Arrays.copyOf(buf, len))
                store.offer(bytes) >> {
                  if (in.isReady) loop
                  else Sync[F].unit
                }
            }

          unsafeFork(loop, "read/loop")
        }

        def onAllDataRead(): Unit = unsafeFork(store.offer(()), "request/enqueue")

        def onError(t: Throwable): Unit = unsafeFork(store offer t, "request/enqueue")
      })

      def loop: Pull[F, Byte, Unit] = Pull.eval(store.take).flatMap {
        case b: Chunk[_] => Pull.output(b.asInstanceOf[Chunk[Byte]]) >> loop
        case t: Throwable =>
          Pull.eval(store offer RepeatedReadException) >> Pull.raiseError[F](t)
        case () => Pull.eval(store offer RepeatedReadException)
        case x => throw new IllegalStateException(s"Expectation failed (request): $x")
      }

      loop.stream
    }

    def makeWriter(): Pipe[F, Byte, Unit] = {
      val out = res.getOutputStream
      val store = dispatcher.unsafeRunSync(Queue.bounded[F, Any](queueSizeBounds))
      @volatile var err: Throwable = null // scalafix:ok

      out.setWriteListener(new WriteListener {
        def onWritePossible(): Unit = {
          def flush() =
            out.isReady match {
              case false => Sync[F].unit
              case true if !autoFlush => loop
              case true /* if autoFlush */ =>
                out.flush()
                if (out.isReady) loop else Sync[F].unit
            }

          def loop: F[Unit] = store.take.flatMap {
            case x: Chunk.ArraySlice[_] =>
              out.write(x.values.asInstanceOf[Array[Byte]], x.offset, x.length)
              flush()
            case x: Chunk[_] =>
              out.write(x.asInstanceOf[Chunk[Byte]].toArray)
              flush()
            case () =>
              Sync[F].delay {
                out.flush()
                ctx.complete()
              }
            case x => throw new IllegalStateException(s"Expectation failed (response): $x")
          }

          unsafeFork(loop, "write/loop")
        }

        def onError(t: Throwable): Unit = err = t
      })

      body =>
        body.chunks
          .evalMap[F, Unit] {
            case _ if err != null => // scalafix:ok
              Sync[F].raiseError(err)
            case c => store.offer(c)
          }
          .onFinalizeWeak(store.offer(()))
    }

    val request = fromHttpServletRequest(req, if (requestIsEmpty) EmptyBody else requestBody)
    val resource = request
      .fold[Resource[F, Response[F]]](
        e =>
          Resource.pure[F, Response[F]](
            Response[F](status = Status.BadRequest).withEntity[String](e.sanitized)
          ),
        app.andThen(_.handleError { t =>
          logger.warn("HttpResource internal error", t)
          Response[F](status = Status.InternalServerError)
        })
      )
    val writer = makeWriter()

    val run = Stream
      .resource(resource)
      .flatMap { response =>
        Stream.suspend {
          autoFlush = response.isChunked
          res.setStatus(response.status.code)
          response.headers.foreach { hdr =>
            if (hdr.name != headers.`Transfer-Encoding`.name)
              res.addHeader(hdr.name.toString, hdr.value)
          }
          writer(response.body)
        }
      }
      .compile
      .drain
      .onError { case t => Sync[F].delay(logger.debug("Error sending response body", t)) }

    dispatcher.unsafeRunAndForget(run)
  }
}

private object HttpResourceHandler {
  def fromHttpServletRequest[F[_]](
    r: HttpServletRequest,
    body: EntityBody[F]
  ): Either[ParseFailure, Request[F]] = for {
    method <- Method.fromString(r.getMethod)
    uri <- Uri.fromString {
      val q = r.getQueryString
      if (q != null) // scalafix:ok
        s"${r.getRequestURL}?$q"
      else r.getRequestURL.toString
    }
    version <- HttpVersion.fromString(r.getProtocol)
  } yield Request(
    method = method,
    uri = uri,
    httpVersion = version,
    headers = Headers((for {
      name <- r.getHeaderNames.asScala
      value <- r.getHeaders(name).asScala
    } yield Header.Raw(CIString(name), value)).toList),
    body = body,
    attributes = Vault.empty.insert(Request.Keys.ConnectionInfo, Request.Connection(
      local = SocketAddress(
        IpAddress.fromString(strip(r.getLocalAddr)).get,
        Port.fromInt(r.getLocalPort).get,
      ),
      remote = SocketAddress(
        IpAddress.fromString(strip(r.getRemoteAddr)).get,
        Port.fromInt(r.getRemotePort).get,
      ),
      secure = r.isSecure
    ))
  )

  private def strip(s: String) = s.stripPrefix("[").stripSuffix("]")
}
