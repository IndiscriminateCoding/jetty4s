package jetty4s.client

import cats.Applicative
import cats.effect.implicits._
import cats.effect.{ ConcurrentEffect, ExitCase, Resource, Sync }
import cats.implicits._
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http.{ HttpFields, HttpVersion => JHttpVersion }
import org.http4s.client.Client
import org.http4s.{ HttpVersion, Response }

object FromHttpClient {
  def apply[F[_] : ConcurrentEffect](c: HttpClient): Client[F] = Client { r =>
    for {
      h <- Resource.liftF[F, StreamHandler[F]](StreamHandler[F])
      req <- Resource.liftF(Sync[F].delay {
        c
          .newRequest(r.uri.toString)
          .method(r.method.name)
          .version(r.httpVersion match {
            case HttpVersion.`HTTP/1.0` => JHttpVersion.HTTP_1_0
            case HttpVersion.`HTTP/1.1` => JHttpVersion.HTTP_1_1
            case HttpVersion.`HTTP/2.0` => JHttpVersion.HTTP_2
            case _ => JHttpVersion.HTTP_1_1
          })
          .headers { (fields: HttpFields.Mutable) =>
            for (h <- r.headers) fields.add(h.name.toString, h.value): Unit
          }
          .body(h)
      })
      _ <- Resource.make(h.write(r.body).start)(_.cancel)
      res <- {
        def abort(t: Throwable): F[Unit] = Sync[F].delay(req.abort(t): Unit)

        val interrupt = abort(InterruptedRequestException)
        val acquire: F[Response[F]] = Sync[F].delay(req.send(h)) >> h.response
        val release: (Response[F], ExitCase[Throwable]) => F[Unit] = {
          case (_, ExitCase.Completed) => Applicative[F].unit
          case (_, ExitCase.Canceled) => interrupt
          case (_, ExitCase.Error(t)) => abort(t)
        }

        Resource.makeCase(acquire)(release)
      }
    } yield res
  }
}
