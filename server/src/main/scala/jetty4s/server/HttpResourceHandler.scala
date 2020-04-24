package jetty4s.server

import cats.Applicative
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2.Chunk.Bytes
import fs2._
import fs2.concurrent.Queue
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.servlet.{ ReadListener, WriteListener }
import jetty4s.common.RepeatedReadException
import jetty4s.server.HttpResourceHandler._
import org.eclipse.jetty.http.HttpScheme
import org.eclipse.jetty.io.ssl.SslConnection
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.{ server => jetty }
import org.http4s._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

private[server] class HttpResourceHandler[F[_]: ConcurrentEffect](
  app: Request[F] => Resource[F, Response[F]],
  asyncTimeout: Long,
  chunkSize: Int = 1024,
  queueSizeBounds: Int = 4
) extends AbstractHandler {
  private[this] val logger = LoggerFactory.getLogger(this.getClass)

  private[this] def unsafeFork[A](fa: F[A], op: String): Unit = fa
    .runAsync {
      case Right(_) => IO.unit
      case Left(t) => IO.delay(logger.debug(s"unsafeFork/$op failed!", t))
    }
    .unsafeRunSync()

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
      case _: SslConnection#DecryptedEndPoint => baseReq.setScheme(HttpScheme.HTTPS.asString())
      case _ => /* empty */
    }

    val requestBody: Stream[F, Byte] = Stream.suspend {
      def newBuf() = new Array[Byte](chunkSize)

      var buf = newBuf()
      val in = req.getInputStream
      val store = Queue.bounded[F, Any](queueSizeBounds).toIO.unsafeRunSync()

      in.setReadListener(new ReadListener {
        def onDataAvailable(): Unit = {
          def loop: F[Unit] =
            Sync[F].delay(in.read(buf)).flatMap {
              case len if len < 0 => Applicative[F].unit
              case len =>
                if (len == 0) logger.debug("InputStream#read(...) == 0")
                val bytes =
                  if (len == chunkSize) {
                    val res = Bytes(buf)
                    buf = newBuf()
                    res
                  } else Bytes(java.util.Arrays.copyOf(buf, len))
                store.enqueue1(bytes) >> {
                  if (in.isReady) loop
                  else Applicative[F].unit
                }
            }

          unsafeFork(loop, "read/loop")
        }

        def onAllDataRead(): Unit = unsafeFork(store.enqueue1(()), "request/enqueue")

        def onError(t: Throwable): Unit = unsafeFork(store enqueue1 t, "request/enqueue")
      })

      def loop: Pull[F, Byte, Unit] = Pull.eval(store.dequeue1).flatMap {
        case b: Bytes => Pull.output(b) >> loop
        case t: Throwable =>
          Pull.eval(store.enqueue1(RepeatedReadException)) >> Pull.raiseError[F](t)
        case () => Pull.eval(store.enqueue1(RepeatedReadException))
        case x => throw new IllegalStateException(s"Expectation failed (request): $x")
      }

      loop.stream
    }

    def makeWriter(): Pipe[F, Byte, Unit] = {
      val out = res.getOutputStream
      val store = Queue.bounded[F, Any](queueSizeBounds).toIO.unsafeRunSync()
      @volatile var err: Throwable = null // scalafix:ok

      out.setWriteListener(new WriteListener {
        def onWritePossible(): Unit = {
          def flush() =
            out.isReady match {
              case false => Applicative[F].unit
              case true if !autoFlush => loop
              case true /* if autoFlush */ =>
                out.flush()
                if (out.isReady) loop else Applicative[F].unit
            }

          def loop: F[Unit] = store.dequeue1.flatMap {
            case x: Chunk.Bytes =>
              out.write(x.values, x.offset, x.length)
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
            case c => store.enqueue1(c)
          }
          .onFinalizeWeak(store.enqueue1(()))
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

    Stream
      .resource(resource)
      .flatMap { response =>
        Stream.suspend {
          autoFlush = response.isChunked
          res.setStatus(response.status.code)
          for (header <- response.headers.toList if header.isNot(headers.`Transfer-Encoding`))
            res.addHeader(header.name.toString, header.value)
          writer(response.body)
        }
      }
      .compile
      .drain
      .runAsync {
        case Right(_) => IO.unit
        case Left(t) => IO.delay { logger.debug("Error sending response body", t) }
      }
      .unsafeRunSync()
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
    } yield Header(name, value)).toList),
    body = body
  )
}
