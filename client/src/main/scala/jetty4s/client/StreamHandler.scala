package jetty4s.client

import cats.effect._
import cats.effect.implicits._
import cats.effect.std.{ Dispatcher, Queue }
import cats.implicits._
import fs2.{ Chunk, Pull, Stream }
import jetty4s.client.StreamHandler._
import jetty4s.common.RepeatedReadException
import org.eclipse.jetty.client.api.Response._
import org.eclipse.jetty.client.api.{ Result, Response => JResponse }
import org.eclipse.jetty.client.util.AsyncRequestContent
import org.eclipse.jetty.http.{ HttpVersion => JHttpVersion }
import org.eclipse.jetty.util.Callback
import org.http4s._
import org.slf4j.LoggerFactory
import org.typelevel.ci.CIString

import java.nio.ByteBuffer
import java.util.function.LongConsumer
import scala.collection.JavaConverters._

private[client] final class StreamHandler[F[_] : Async] private(
  q: Queue[F, Any],
  dispatcher: Dispatcher[F]
) extends AsyncRequestContent
  with HeadersListener
  with DemandedContentListener
  with CompleteListener {
  def write(body: Stream[F, Byte]): F[Unit] = body.chunks
    .evalMap { c =>
      Async[F].async_[Unit] { cb =>
        offer(c.toByteBuffer, new Callback {
          override def succeeded(): Unit = cb(Right(()))

          override def failed(t: Throwable): Unit = cb(Left(t))
        }): Unit
      }
    }
    .compile
    .drain
    .guarantee(Sync[F].delay {
      close()
    })
    .handleError(enqueueAndForget(_))

  private[this] def enqueue(x: Any, andThen: F[Unit] = Sync[F].unit): F[Unit] =
    q.offer(x).attempt.flatMap {
      case Right(_) => andThen
      case Left(t) => Sync[F].delay(logger.debug("Queue.enqueue1() failed", t))
    }

  private[this] def enqueueAndForget(x: Any, andThen: F[Unit] = Sync[F].unit): Unit =
    dispatcher.unsafeRunAndForget(enqueue(x, andThen))

  def onHeaders(r: JResponse): Unit =
    /* should be safe to semantically block as this is very first enqueue()
     * without blocking there is a race with onContent() */
    dispatcher.unsafeRunSync(enqueue(r))

  def onContent(r: JResponse, d: LongConsumer, b: ByteBuffer, cb: Callback): Unit = {
    val arr = new Array[Byte](b.remaining())
    b.get(arr)
    enqueueAndForget(Chunk.array(arr), Sync[F].delay {
      cb.succeeded()
      d.accept(1)
    })
  }

  def onComplete(r: Result): Unit = enqueueAndForget(if (r.isSucceeded) () else r.getFailure)

  val response: F[Response[F]] = for {
    fst <- q.take
    res <- fst match {
      case r: JResponse =>
        Sync[F].fromEither(Status.fromInt(r.getStatus)).map { status =>
          Response[F](
            status = status,
            httpVersion = r.getVersion match {
              case JHttpVersion.HTTP_1_1 => HttpVersion.`HTTP/1.1`
              case JHttpVersion.HTTP_2 => HttpVersion.`HTTP/2`
              case JHttpVersion.HTTP_1_0 => HttpVersion.`HTTP/1.0`
              case _ => HttpVersion.`HTTP/1.1`
            },
            headers = Headers(
              r.getHeaders.asScala.map(h => Header.Raw(CIString(h.getName), h.getValue)).toList
            ),
            body = {
              def loop: Pull[F, Byte, Unit] = Pull.eval(q.take) flatMap {
                case b: Chunk[_] => Pull.output(b.asInstanceOf[Chunk[Byte]]) >> loop
                case () => Pull.eval(q.offer(RepeatedReadException))
                case t: Throwable =>
                  Pull.eval(q.offer(RepeatedReadException)) >> Pull.raiseError[F](t)
                case x => throw new Exception(s"Expectation failed: received $x in body stream")
              }

              loop.stream
            }
          )
        }
      case t: Throwable => Sync[F].raiseError[Response[F]](t)
      case _ => throw new Exception(s"Expectation failed: received $fst as queue head")
    }
  } yield res
}

private[client] object StreamHandler {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val queueSizeBounds = 4

  def apply[F[_] : Async](d: Dispatcher[F]): F[StreamHandler[F]] =
    Queue.bounded[F, Any](queueSizeBounds).map(q => new StreamHandler(q, d))
}
