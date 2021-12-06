package jetty4s.client

import cats.effect._
import cats.effect.implicits._
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Dispatcher
import cats.implicits._
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http.{ HttpFields, HttpVersion => JHttpVersion }
import org.http4s.client.Client
import org.http4s.{ HttpVersion, Response }

object FromHttpClient {
  def apply[F[_] : Async](c: HttpClient, d: Dispatcher[F]): Client[F] = Client { r =>
    for {
      h <- Resource eval StreamHandler[F](d)
      req <- Resource.eval(Sync[F].delay {
        c
          .newRequest(r.uri.toString)
          .method(r.method.name)
          .version(r.httpVersion match {
            case HttpVersion.`HTTP/1.0` => JHttpVersion.HTTP_1_0
            case HttpVersion.`HTTP/1.1` => JHttpVersion.HTTP_1_1
            case HttpVersion.`HTTP/2` => JHttpVersion.HTTP_2
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
        val release: (Response[F], ExitCase) => F[Unit] = {
          case (_, ExitCase.Succeeded) => Sync[F].unit
          case (_, ExitCase.Canceled) => interrupt
          case (_, ExitCase.Errored(t)) => abort(t)
        }

        Resource.makeCase(acquire)(release)
      }
    } yield res
  }
}
