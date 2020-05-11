package jetty4s.server

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import cats.effect._
import fs2._
import jetty4s.common.SSLKeyStore
import jetty4s.common.SSLKeyStore.{ FileKeyStore, JavaKeyStore }
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
import org.eclipse.jetty.http.HttpFields
import org.eclipse.jetty.http2.server._
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.{ HttpConfiguration, HttpConnectionFactory, SslConnectionFactory }
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener
import org.eclipse.jetty.util.component.LifeCycle
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.util.thread.ThreadPool
import org.eclipse.jetty.{ server => jetty }
import org.http4s.server.{ SSLClientAuthMode, Server, defaults }
import org.http4s.{ HttpApp, Request, Response }

import scala.concurrent.duration.{ Duration, FiniteDuration }

final class JettyServerBuilder[F[_] : ConcurrentEffect] private(
  http: Option[InetSocketAddress] = None,
  https: Option[InetSocketAddress] = None,
  threadPool: Option[ThreadPool] = None,
  asyncTimeout: Duration = Duration.Inf,
  idleTimeout: Option[FiniteDuration] = None,
  keyStore: Option[SSLKeyStore] = None,
  keyStoreType: Option[String] = None,
  trustStore: Option[SSLKeyStore] = None,
  trustStoreType: Option[String] = None,
  sniRequired: Boolean = true,
  clientAuth: SSLClientAuthMode = SSLClientAuthMode.NotRequested,
  sslProvider: Option[String] = None,
  handler: Option[jetty.Handler] = None,
  sendDateHeader: Boolean = true,
  sendServerHeader: Boolean = true
) {
  private[this] def copy(
    http: Option[InetSocketAddress] = http,
    https: Option[InetSocketAddress] = https,
    threadPool: Option[ThreadPool] = threadPool,
    asyncTimeout: Duration = asyncTimeout,
    idleTimeout: Option[FiniteDuration] = idleTimeout,
    keyStore: Option[SSLKeyStore] = keyStore,
    keyStoreType: Option[String] = keyStoreType,
    trustStore: Option[SSLKeyStore] = trustStore,
    trustStoreType: Option[String] = trustStoreType,
    sniRequired: Boolean = sniRequired,
    clientAuth: SSLClientAuthMode = clientAuth,
    sslProvider: Option[String] = sslProvider,
    handler: Option[jetty.Handler] = handler,
    sendDateHeader: Boolean = sendDateHeader,
    sendServerHeader: Boolean = sendServerHeader
  ): JettyServerBuilder[F] = new JettyServerBuilder[F](
    http = http,
    https = https,
    threadPool = threadPool,
    asyncTimeout = asyncTimeout,
    idleTimeout = idleTimeout,
    keyStore = keyStore,
    keyStoreType = keyStoreType,
    trustStore = trustStore,
    trustStoreType = trustStoreType,
    sniRequired = sniRequired,
    clientAuth = clientAuth,
    sslProvider = sslProvider,
    handler = handler,
    sendDateHeader = sendDateHeader,
    sendServerHeader = sendServerHeader
  )

  def withHandler(handler: jetty.Handler): JettyServerBuilder[F] = copy(handler = Some(handler))

  def withHttpResource(http: Request[F] => Resource[F, Response[F]]): JettyServerBuilder[F] =
    withHandler(new HttpResourceHandler[F](
      http,
      if (asyncTimeout.isFinite) asyncTimeout.toMillis else 0L
    ))

  def withHttpApp(http: HttpApp[F]): JettyServerBuilder[F] = withHttpResource { req =>
    Resource.liftF(http.run(req))
  }

  def withThreadPool(threadPool: ThreadPool): JettyServerBuilder[F] =
    copy(threadPool = Some(threadPool))

  def withAsyncTimeout(asyncTimeout: Duration): JettyServerBuilder[F] =
    copy(asyncTimeout = asyncTimeout)

  def withIdleTimeout(idleTimeout: FiniteDuration): JettyServerBuilder[F] =
    copy(idleTimeout = Some(idleTimeout))

  def withKeyStore(keyStore: SSLKeyStore): JettyServerBuilder[F] = copy(keyStore = Some(keyStore))

  def withKeyStoreType(keyStoreType: String): JettyServerBuilder[F] =
    copy(keyStoreType = Some(keyStoreType))

  def withTrustStore(trustStore: SSLKeyStore): JettyServerBuilder[F] =
    copy(trustStore = Some(trustStore))

  def withTrustStoreType(trustStoreType: String): JettyServerBuilder[F] =
    copy(trustStoreType = Some(trustStoreType))

  def withSniRequired(sniRequired: Boolean): JettyServerBuilder[F] =
    copy(sniRequired = sniRequired)

  def withClientAuth(clientAuth: SSLClientAuthMode): JettyServerBuilder[F] =
    copy(clientAuth = clientAuth)

  def withSslProvider(sslProvider: String): JettyServerBuilder[F] =
    copy(sslProvider = Some(sslProvider))

  def bindSocketAddress(socketAddress: InetSocketAddress): JettyServerBuilder[F] =
    copy(http = Some(socketAddress))

  def bindSecureSocketAddress(socketAddress: InetSocketAddress): JettyServerBuilder[F] =
    copy(https = Some(socketAddress))

  def bindHttp(port: Int = 8080, host: String = "0.0.0.0"): JettyServerBuilder[F] =
    bindSocketAddress(InetSocketAddress.createUnresolved(host, port))

  def bindHttps(port: Int = 8443, host: String = "0.0.0.0"): JettyServerBuilder[F] =
    bindSecureSocketAddress(InetSocketAddress.createUnresolved(host, port))

  def withoutDateHeader: JettyServerBuilder[F] = copy(sendDateHeader = false)

  def withoutServerHeader: JettyServerBuilder[F] = copy(sendServerHeader = false)

  def resource: Resource[F, List[Server[F]]] = {
    val acquire: F[jetty.Server] = Sync[F].delay {
      val s = threadPool.fold(new jetty.Server())(new jetty.Server(_))
      val conf = new HttpConfiguration

      conf.setSendDateHeader(sendDateHeader)
      conf.setSendServerVersion(sendServerHeader)

      def httpConnector(socket: InetSocketAddress) = {
        val h1 = new HttpConnectionFactory(conf)
        val h2c = new HTTP2CServerConnectionFactory(conf)
        val conn = new jetty.ServerConnector(s, h1, h2c)

        idleTimeout.foreach(t => conn.setIdleTimeout(t.toMillis))
        conn.setPort(socket.getPort)
        conn.setHost(socket.getHostName)
        conn
      }

      def httpsConnector(socket: InetSocketAddress) = {
        val h1 = new HttpConnectionFactory(conf)
        val h2 = new HTTP2ServerConnectionFactory(conf)
        val alpn = new ALPNServerConnectionFactory()
        val ks = keyStore.getOrElse(throw new IllegalArgumentException("SSLKeyStore isn't set!"))

        alpn.setDefaultProtocol(h1.getProtocol)

        val cf = new SslContextFactory.Server

        cf.setSniRequired(sniRequired)
        clientAuth match {
          case SSLClientAuthMode.NotRequested => ()
          case SSLClientAuthMode.Requested =>
            cf.setWantClientAuth(true)
          case SSLClientAuthMode.Required =>
            cf.setWantClientAuth(true)
            cf.setNeedClientAuth(true)
        }
        ks match {
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
        sslProvider.foreach(cf.setProvider)

        val ssl = new SslConnectionFactory(cf, alpn.getProtocol)
        val conn = new jetty.ServerConnector(s, ssl, alpn, h2, h1)

        idleTimeout.foreach(t => conn.setIdleTimeout(t.toMillis))
        conn.setPort(socket.getPort)
        conn.setHost(socket.getHostName)
        conn
      }

      (http, https) match {
        case (None, None) => s.addConnector(httpConnector(defaults.SocketAddress))
        case _ =>
          http foreach (socket => s.addConnector(httpConnector(socket)))
          https foreach (socket => s.addConnector(httpsConnector(socket)))
      }
      s.setErrorHandler(new ErrorHandler {
        override def badMessageError(code: Int, reason: String, f: HttpFields): ByteBuffer = null

        override def errorPageForMethod(method: String): Boolean = false
      })
      s.setHandler(
        handler.getOrElse(throw new IllegalArgumentException("HTTP handler isn't set!"))
      )
      s.start()
      s
    }

    def release(s: jetty.Server): F[Unit] = Async[F].async { cb =>
      s.addLifeCycleListener(new AbstractLifeCycleListener {
        override def lifeCycleStopped(lc: LifeCycle): Unit = cb(Right(()))

        override def lifeCycleFailure(lc: LifeCycle, t: Throwable): Unit = cb(Left(t))
      })
      s.stop()
    }

    Resource.make[F, jetty.Server](acquire)(release).map { _ =>
      def insecure(s: InetSocketAddress) = new Server[F] {
        def address: InetSocketAddress = defaults.SocketAddress

        def isSecure: Boolean = false
      }

      def secure(s: InetSocketAddress) = new Server[F] {
        def address: InetSocketAddress = defaults.SocketAddress

        def isSecure: Boolean = true
      }

      (http, https) match {
        case (None, None) => insecure(defaults.SocketAddress) :: Nil
        case _ => http.map(insecure).toList ++ https.map(secure).toList
      }
    }
  }

  def allocated: F[(List[Server[F]], F[Unit])] = resource.allocated

  def stream: Stream[F, List[Server[F]]] = Stream.resource(resource)
}

object JettyServerBuilder {
  def apply[F[_] : ConcurrentEffect] = new JettyServerBuilder[F]()
}
