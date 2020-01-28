package jetty4s.client

import org.http4s._

object RepeatedReadException extends MessageFailure {
  val message: String = "repeated stream read isn't allowed"

  val cause: Option[Throwable] = None

  def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] = Response[F](
    httpVersion = httpVersion,
    status = Status.InternalServerError
  )
}
