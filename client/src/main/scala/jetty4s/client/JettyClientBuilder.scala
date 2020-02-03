package jetty4s.client

import java.net.URI
import java.util.concurrent.{ Executor, TimeUnit }

import cats.Applicative
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import jetty4s.client.JettyClientBuilder._
import org.eclipse.jetty.client.{ HttpClient, HttpConversation, HttpRequest }
import org.eclipse.jetty.http.{ HttpVersion => JHttpVersion }
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.http4s._
import org.http4s.client.Client

import scala.concurrent.duration.FiniteDuration

final class JettyClientBuilder[F[_] : ConcurrentEffect] private(
  f: HttpClient => Unit = _ => (),
  cf: Option[() => SslContextFactory] = Some(() => new SslContextFactory.Client()),
  rt: Long = defaultRequestTimeoutMs
) {
  private[this] def copy(
    f: HttpClient => Unit = f,
    cf: Option[() => SslContextFactory] = cf,
    rt: Long = rt
  ): JettyClientBuilder[F] = new JettyClientBuilder[F](f, cf, rt)

  def resource: Resource[F, Client[F]] = Resource
    .make(Sync[F].delay {
      val c = new HttpClient(cf.map(_ ()).orNull) {
        override def newHttpRequest(c: HttpConversation, u: URI): HttpRequest = {
          val r = super.newHttpRequest(c, u)
          r.timeout(rt, TimeUnit.MILLISECONDS)
          r
        }
      }

      f(c)
      c.setFollowRedirects(false)
      c.setDefaultRequestContentType(null) // scalafix:ok
      c.start()
      c
    })(c => Sync[F].delay(c.stop()))
    .map(fromHttpClient[F])

  def allocated: F[(Client[F], F[Unit])] = resource.allocated

  def stream: fs2.Stream[F, Client[F]] = fs2.Stream.resource(resource)

  def withoutSslContextFactory: JettyClientBuilder[F] = copy(cf = None)

  def withSslContextFactory(cf: => SslContextFactory): JettyClientBuilder[F] =
    copy(cf = Some(() => cf))

  def withRequestTimeout(timeout: FiniteDuration): JettyClientBuilder[F] =
    copy(rt = timeout.toMillis)

  def withExecutor(e: Executor): JettyClientBuilder[F] = copy(f = { c =>
    f(c)
    c.setExecutor(e)
  })

  def withIdleTimeout(timeout: FiniteDuration): JettyClientBuilder[F] = copy(f = { c =>
    f(c)
    c.setIdleTimeout(timeout.toMillis)
  })

  def withConnectTimeout(timeout: FiniteDuration): JettyClientBuilder[F] = copy(f = { c =>
    f(c)
    c.setConnectTimeout(timeout.toMillis)
  })

  def withMaxConnections(n: Int): JettyClientBuilder[F] = copy(f = { c =>
    f(c)
    c.setMaxConnectionsPerDestination(n)
  })

  def withMaxRequestsQueued(n: Int): JettyClientBuilder[F] = copy(f = { c =>
    f(c)
    c.setMaxRequestsQueuedPerDestination(n)
  })
}

object JettyClientBuilder {
  private val defaultRequestTimeoutMs = 15000L
  private val defaultIdleTimeout = FiniteDuration(60, TimeUnit.SECONDS)
  private val defaultConnectTimeout = FiniteDuration(5, TimeUnit.SECONDS)
  private val defaultMaxConnections = 64
  private val defaultMaxRequestsQueued = 128

  def apply[F[_] : ConcurrentEffect]: JettyClientBuilder[F] = new JettyClientBuilder()
    .withIdleTimeout(defaultIdleTimeout)
    .withConnectTimeout(defaultConnectTimeout)
    .withMaxConnections(defaultMaxConnections)
    .withMaxRequestsQueued(defaultMaxRequestsQueued)

  private def fromHttpClient[F[_] : ConcurrentEffect](c: HttpClient): Client[F] = Client { r =>
    for {
      h <- Resource.liftF[F, StreamHandler[F]](StreamHandler[F])
      req = c
        .newRequest(r.uri.toString)
        .method(r.method.name)
        .version(r.httpVersion match {
          case HttpVersion.`HTTP/1.0` => JHttpVersion.HTTP_1_0
          case HttpVersion.`HTTP/1.1` => JHttpVersion.HTTP_1_1
          case HttpVersion.`HTTP/2.0` => JHttpVersion.HTTP_2
          case _ => JHttpVersion.HTTP_1_1
        })
        .content(h)
      _ = for (h <- r.headers) req.header(h.name.toString, h.value): Unit
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
