package jetty4s.client

import cats.effect._
import cats.effect.std.Dispatcher
import cats.implicits._
import org.eclipse.jetty.util.{ Promise, SocketAddressResolver }

import java.net.InetSocketAddress
import java.util
import scala.collection.JavaConverters._

trait Resolver[F[_]] {
  def resolve(host: String, port: Int): F[List[InetSocketAddress]]
}

object Resolver {
  def asJetty[F[_] : Sync](r: Resolver[F], d: Dispatcher[F]): SocketAddressResolver =
    (host: String, port: Int, res: Promise[util.List[InetSocketAddress]]) =>
      d.unsafeRunAndForget {
        r.resolve(host, port).attempt.flatMap {
          case Left(t) => Sync[F] delay res.failed(t)
          case Right(hs) => Sync[F] delay res.succeeded(hs.asJava)
        }
      }
}
