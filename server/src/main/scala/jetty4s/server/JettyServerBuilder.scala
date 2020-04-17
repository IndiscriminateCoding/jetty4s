package jetty4s.server

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import cats.effect.{ ConcurrentEffect, Resource, Sync }
import jetty4s.common.SSLKeyStore
import jetty4s.common.SSLKeyStore.{ FileKeyStore, JavaKeyStore }
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
import org.eclipse.jetty.http.HttpFields
import org.eclipse.jetty.http2.server._
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.{ HttpConfiguration, HttpConnectionFactory, SslConnectionFactory }
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.{ server => jetty }
import org.http4s.server.{ Server, defaults }
import org.http4s.{ HttpApp, Request, Response }

class JettyServerBuilder[F[_]] private(
  http: Option[InetSocketAddress] = None,
  https: Option[InetSocketAddress] = None,
  keyStore: Option[SSLKeyStore] = None,
  keyStoreType: Option[String] = None,
  trustStore: Option[SSLKeyStore] = None,
  trustStoreType: Option[String] = None,
  sniRequired: Boolean = true,
  handler: Option[jetty.Handler] = None
)(implicit protected val F: ConcurrentEffect[F]) {
  private[this] def copy(
    http: Option[InetSocketAddress] = http,
    https: Option[InetSocketAddress] = https,
    keyStore: Option[SSLKeyStore] = keyStore,
    keyStoreType: Option[String] = keyStoreType,
    trustStore: Option[SSLKeyStore] = trustStore,
    trustStoreType: Option[String] = trustStoreType,
    sniRequired: Boolean = sniRequired,
    handler: Option[jetty.Handler] = handler
  ): JettyServerBuilder[F] = new JettyServerBuilder[F](
    http = http,
    https = https,
    keyStore = keyStore,
    keyStoreType = keyStoreType,
    trustStore = trustStore,
    trustStoreType = trustStoreType,
    sniRequired = sniRequired,
    handler = handler
  )

  def withHandler(handler: jetty.Handler): JettyServerBuilder[F] = copy(handler = Some(handler))

  def withHttpResource(http: Request[F] => Resource[F, Response[F]]): JettyServerBuilder[F] =
    withHandler(new HttpResourceHandler[F](http))

  def withHttpApp(http: HttpApp[F]): JettyServerBuilder[F] = withHttpResource { req =>
    Resource.liftF(http.run(req))
  }

  def withKeyStore(keyStore: SSLKeyStore): JettyServerBuilder[F] = copy(keyStore = Some(keyStore))

  def withKeyStoreType(keyStoreType: String): JettyServerBuilder[F] =
    copy(keyStoreType = Some(keyStoreType))

  def withTrustStore(trustStore: SSLKeyStore): JettyServerBuilder[F] =
    copy(trustStore = Some(trustStore))

  def withTrustStoreType(trustStoreType: String): JettyServerBuilder[F] =
    copy(trustStoreType = Some(trustStoreType))

  def bindSocketAddress(socketAddress: InetSocketAddress): JettyServerBuilder[F] =
    copy(http = Some(socketAddress))

  def bindSecureSocketAddress(socketAddress: InetSocketAddress): JettyServerBuilder[F] =
    copy(https = Some(socketAddress))

  def bindHttp(port: Int = 8080, host: String = "0.0.0.0"): JettyServerBuilder[F] =
    bindSocketAddress(InetSocketAddress.createUnresolved(host, port))

  def bindHttps(port: Int = 8443, host: String = "0.0.0.0"): JettyServerBuilder[F] =
    bindSecureSocketAddress(InetSocketAddress.createUnresolved(host, port))

  def resource: Resource[F, List[Server[F]]] = {
    val acquire: F[jetty.Server] = Sync[F].delay {
      val s = new jetty.Server()

      def httpConnector(socket: InetSocketAddress) = {
        val conf = new HttpConfiguration
        val h1 = new HttpConnectionFactory(conf)
        val h2c = new HTTP2CServerConnectionFactory(conf)
        val conn = new jetty.ServerConnector(s, h1, h2c)

        conn.setPort(socket.getPort)
        conn.setHost(socket.getHostName)
        conn
      }

      def httpsConnector(socket: InetSocketAddress) = {
        val conf = new HttpConfiguration
        val h1 = new HttpConnectionFactory(conf)
        val h2 = new HTTP2ServerConnectionFactory(conf)
        val alpn = new ALPNServerConnectionFactory()
        val ks = keyStore.getOrElse(throw new IllegalArgumentException("SSLKeyStore isn't set!"))

        alpn.setDefaultProtocol(h1.getProtocol)

        val cf = new SslContextFactory.Server

        cf.setSniRequired(sniRequired)
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

        val ssl = new SslConnectionFactory(cf, alpn.getProtocol)
        val conn = new jetty.ServerConnector(s, ssl, alpn, h2, h1)

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

    def release(s: jetty.Server): F[Unit] = Sync[F].delay(s.stop())

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
}

object JettyServerBuilder {
  def apply[F[_]: ConcurrentEffect] = new JettyServerBuilder[F]()
}
