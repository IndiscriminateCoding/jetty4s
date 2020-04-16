package jetty4s.client

import java.nio.ByteBuffer
import java.util.function.LongConsumer

import cats.Applicative
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2.Chunk.Bytes
import fs2.concurrent.Queue
import fs2.{ Pull, Stream }
import jetty4s.client.StreamHandler._
import jetty4s.common.RepeatedReadException
import org.eclipse.jetty.client.api.Response._
import org.eclipse.jetty.client.api.{ Result, Response => JResponse }
import org.eclipse.jetty.client.util.DeferredContentProvider
import org.eclipse.jetty.http.{ HttpVersion => JHttpVersion }
import org.eclipse.jetty.util.Callback
import org.http4s._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

private[client] final class StreamHandler[F[_] : Effect] private(q: Queue[F, Any])
  extends DeferredContentProvider
    with HeadersListener
    with DemandedContentListener
    with CompleteListener {
  def write(body: Stream[F, Byte]): F[Unit] = body.chunks
    .evalMap { c =>
      Async[F].async[Unit] { cb =>
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
    .handleError(enqueue(_))

  private[this] def enqueue(x: Any, andThen: IO[Unit] = IO.unit): Unit = q
    .enqueue1(x)
    .runAsync {
      case Right(_) => andThen
      case Left(t) => IO.delay(logger.debug("Queue.enqueue1() failed", t))
    }
    .unsafeRunSync()

  def onHeaders(r: JResponse): Unit = enqueue(r)

  def onContent(r: JResponse, d: LongConsumer, b: ByteBuffer, cb: Callback): Unit = {
    val arr = new Array[Byte](b.remaining())
    b.get(arr)
    enqueue(Bytes(arr), IO.delay {
      cb.succeeded()
      d.accept(1)
    })
  }

  def onComplete(r: Result): Unit = enqueue(if (r.isSucceeded) () else r.getFailure)

  val response: F[Response[F]] = for {
    fst <- q.dequeue1
    res <- fst match {
      case r: JResponse =>
        Applicative[F].pure(
          Response[F](
            status = Status
              .fromInt(r.getStatus)
              .fold(_ => Status(r.getStatus, r.getReason), identity),
            httpVersion = r.getVersion match {
              case JHttpVersion.HTTP_1_1 => HttpVersion.`HTTP/1.1`
              case JHttpVersion.HTTP_2 => HttpVersion.`HTTP/2.0`
              case JHttpVersion.HTTP_1_0 => HttpVersion.`HTTP/1.0`
              case _ => HttpVersion.`HTTP/1.1`
            },
            headers = Headers(r.getHeaders.asScala.map(h => Header(h.getName, h.getValue)).toList),
            body = {
              def loop: Pull[F, Byte, Unit] = Pull.eval(q.dequeue1) flatMap {
                case b: Bytes => Pull.output(b) >> loop
                case () => Pull.eval(q.enqueue1(RepeatedReadException))
                case t: Throwable =>
                  Pull.eval(q.enqueue1(RepeatedReadException)) >> Pull.raiseError[F](t)
                case x => throw new Exception(s"Expectation failed: received $x in body stream")
              }

              loop.stream
            }
          )
        )
      case t: Throwable => Sync[F].raiseError[Response[F]](t)
      case _ => throw new Exception(s"Expectation failed: received $fst as queue head")
    }
  } yield res
}

private[client] object StreamHandler {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val queueSizeBounds = 4

  def apply[F[_] : ConcurrentEffect]: F[StreamHandler[F]] =
    Queue.bounded[F, Any](queueSizeBounds).map(new StreamHandler(_))
}
