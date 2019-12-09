package jetty4s.client

import cats.Applicative
import org.http4s._

object RepeatedReadException extends MessageFailure {
  val message: String = "repeated stream read isn't allowed"

  val cause: Option[Throwable] = None

  def inHttpResponse[F[_], G[_]](
    httpVersion: HttpVersion
  )(implicit F: Applicative[F], G: Applicative[G]): F[Response[G]] = F.pure(Response[G](
    httpVersion = httpVersion,
    status = Status.InternalServerError
  ))
}
