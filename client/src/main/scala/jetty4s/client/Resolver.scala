package jetty4s.client

import java.net.InetSocketAddress
import java.util

import cats.effect.{ Effect, IO }
import org.eclipse.jetty.util.{ Promise, SocketAddressResolver }

import scala.collection.JavaConverters._

trait Resolver[F[_]] {
  def resolve(host: String, port: Int): F[List[InetSocketAddress]]
}

object Resolver {
  def asJetty[F[_] : Effect](r: Resolver[F]): SocketAddressResolver =
    (host: String, port: Int, res: Promise[util.List[InetSocketAddress]]) => Effect[F]
      .runAsync(r.resolve(host, port)) {
        case Left(t) => IO delay res.failed(t)
        case Right(hs) => IO delay res.succeeded(hs.asJava)
      }
      .unsafeRunSync()
}
