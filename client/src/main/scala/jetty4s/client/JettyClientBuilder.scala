package jetty4s.client

import cats.effect._
import cats.effect.std.Dispatcher
import fs2._
import jetty4s.common.SSLKeyStore
import jetty4s.common.SSLKeyStore._
import org.eclipse.jetty.client._
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic
import org.eclipse.jetty.io.ClientConnector
import org.eclipse.jetty.util.HttpCookieStore
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener
import org.eclipse.jetty.util.component.LifeCycle
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.http4s.client.Client

import java.net.URI
import java.util.concurrent.{ Executor, TimeUnit }
import scala.concurrent.duration._

final class JettyClientBuilder[F[_] : Async] private(
  requestTimeout: Duration = 15.seconds,
  idleTimeout: FiniteDuration = 1.minute,
  connectTimeout: FiniteDuration = 5.seconds,
  maxConnections: Int = 64,
  maxRequestsQueued: Int = 128,
  resolver: Option[Resolver[F]] = None,
  executor: Option[Executor] = None,
  keyStore: Option[SSLKeyStore] = None,
  keyStoreType: Option[String] = None,
  trustStore: Option[SSLKeyStore] = None,
  trustStoreType: Option[String] = None,
  trustAll: Boolean = false,
  sslProvider: Option[String] = None
) {
  private[this] def copy(
    requestTimeout: Duration = requestTimeout,
    idleTimeout: FiniteDuration = idleTimeout,
    connectTimeout: FiniteDuration = connectTimeout,
    maxConnections: Int = maxConnections,
    maxRequestsQueued: Int = maxRequestsQueued,
    resolver: Option[Resolver[F]] = resolver,
    executor: Option[Executor] = executor,
    keyStore: Option[SSLKeyStore] = keyStore,
    keyStoreType: Option[String] = keyStoreType,
    trustStore: Option[SSLKeyStore] = trustStore,
    trustStoreType: Option[String] = trustStoreType,
    trustAll: Boolean = trustAll,
    sslProvider: Option[String] = sslProvider
  ): JettyClientBuilder[F] = new JettyClientBuilder[F](
    requestTimeout = requestTimeout,
    idleTimeout = idleTimeout,
    connectTimeout = connectTimeout,
    maxConnections = maxConnections,
    maxRequestsQueued = maxRequestsQueued,
    resolver = resolver,
    executor = executor,
    keyStore = keyStore,
    keyStoreType = keyStoreType,
    trustStore = trustStore,
    trustStoreType = trustStoreType,
    trustAll = trustAll,
    sslProvider = sslProvider
  )

  def withKeyStore(keyStore: SSLKeyStore): JettyClientBuilder[F] = copy(keyStore = Some(keyStore))

  def withKeyStoreType(keyStoreType: String): JettyClientBuilder[F] =
    copy(keyStoreType = Some(keyStoreType))

  def withTrustStore(trustStore: SSLKeyStore): JettyClientBuilder[F] =
    copy(trustStore = Some(trustStore))

  def withTrustStoreType(trustStoreType: String): JettyClientBuilder[F] =
    copy(trustStoreType = Some(trustStoreType))

  def withoutTlsValidation: JettyClientBuilder[F] =
    copy(trustAll = true)

  def withSslProvider(sslProvider: String): JettyClientBuilder[F] =
    copy(sslProvider = Some(sslProvider))

  def withRequestTimeout(requestTimeout: Duration): JettyClientBuilder[F] =
    copy(requestTimeout = requestTimeout)

  def withResolver(resolver: Resolver[F]): JettyClientBuilder[F] =
    copy(resolver = Some(resolver))

  def withExecutor(executor: Executor): JettyClientBuilder[F] =
    copy(executor = Some(executor))

  def withIdleTimeout(idleTimeout: FiniteDuration): JettyClientBuilder[F] =
    copy(idleTimeout = idleTimeout)

  def withConnectTimeout(connectTimeout: FiniteDuration): JettyClientBuilder[F] =
    copy(connectTimeout = connectTimeout)

  def withMaxConnections(maxConnections: Int): JettyClientBuilder[F] =
    copy(maxConnections = maxConnections)

  def withMaxRequestsQueued(maxRequestsQueued: Int): JettyClientBuilder[F] =
    copy(maxRequestsQueued = maxRequestsQueued)

  def resource: Resource[F, Client[F]] = Dispatcher[F].flatMap { dispatcher =>
    val acquire = Sync[F].delay {
      val cf = new SslContextFactory.Client
      keyStore foreach {
        case FileKeyStore(path, password) =>
          cf.setKeyStorePath(path)
          cf.setKeyStorePassword(password)
        case JavaKeyStore(jks, password) =>
          cf.setKeyStore(jks)
          cf.setKeyStorePassword(password)
      }
      keyStoreType.foreach(cf.setKeyStoreType)
      trustStore foreach {
        case FileKeyStore(path, password) =>
          cf.setTrustStorePath(path)
          cf.setTrustStorePassword(password)
        case JavaKeyStore(jks, password) =>
          cf.setTrustStore(jks)
          cf.setTrustStorePassword(password)
      }
      trustStoreType.foreach(cf.setTrustStoreType)
      cf.setTrustAll(trustAll)
      sslProvider.foreach(cf.setProvider)

      val transport = {
        val conn = new ClientConnector
        conn.setSslContextFactory(cf)

        new HttpClientTransportDynamic(conn)
      }
      val c =
        if (requestTimeout.isFinite) {
          new HttpClient(transport) {
            override def newHttpRequest(c: HttpConversation, u: URI): HttpRequest = {
              val r = super.newHttpRequest(c, u)
              r.timeout(requestTimeout.toMillis, TimeUnit.MILLISECONDS)
              r
            }
          }
        } else new HttpClient(transport)

      c.getTransport.setConnectionPoolFactory(dst =>
        new RoundRobinConnectionPool(dst, maxConnections, dst)
      )
      c.getProtocolHandlers.clear()
      c.setIdleTimeout(idleTimeout.toMillis)
      c.setConnectTimeout(connectTimeout.toMillis)
      c.setMaxConnectionsPerDestination(maxConnections)
      c.setMaxRequestsQueuedPerDestination(maxRequestsQueued)
      resolver.foreach(r => c.setSocketAddressResolver(Resolver.asJetty(r, dispatcher)))
      executor.foreach(c.setExecutor)
      c.setCookieStore(new HttpCookieStore.Empty)
      c.setFollowRedirects(false)
      c.setDefaultRequestContentType(null) // scalafix:ok
      c.start()
      c
    }

    def release(c: HttpClient): F[Unit] = Async[F].async_ { cb =>
      c.addEventListener(new AbstractLifeCycleListener {
        override def lifeCycleStopped(lc: LifeCycle): Unit = cb(Right(()))

        override def lifeCycleFailure(lc: LifeCycle, t: Throwable): Unit = cb(Left(t))
      })
      c.stop()
    }

    Resource.make[F, HttpClient](acquire)(release).map(c => FromHttpClient[F](c, dispatcher))
  }

  def allocated: F[(Client[F], F[Unit])] = resource.allocated

  def stream: Stream[F, Client[F]] = Stream.resource(resource)
}

object JettyClientBuilder {
  def apply[F[_] : Async]: JettyClientBuilder[F] = new JettyClientBuilder()
}
