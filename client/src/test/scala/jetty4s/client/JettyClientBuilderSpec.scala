package jetty4s.client

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2._
import org.http4s._
import org.http4s.blaze.server.BlazeServerBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress

class JettyClientBuilderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val stop = BlazeServerBuilder[IO]
    .bindHttp(8080)
    .withHttpApp(HttpApp(r => IO.pure(Response[IO](body = r.body))))
    .allocated
    .unsafeRunSync()
    ._2

  override def afterAll(): Unit = stop.unsafeRunSync()

  it should "build proper client" in {
    val client = JettyClientBuilder[IO]
      .withResolver { (host: String, port: Int) =>
        IO.delay {
          host shouldBe "test-host"
          List(new InetSocketAddress("127.0.0.1", port))
        }
      }
      .allocated
      .unsafeRunSync()
      ._1
    val chunks = List("hello", " ", "world")
    val body = chunks.map(s => Stream.chunk(Chunk.array(s.getBytes))).reduce(_ ++ _)
    client
      .expect[String](
        Request[IO](
          method = Method.POST,
          uri = Uri(
            scheme = Some(Uri.Scheme.http),
            authority = Some(
              Uri.Authority(host = Uri.RegName("test-host"), port = Some(8080))
            )
          ),
          body = body
        )
      )
      .unsafeRunSync() shouldBe chunks.mkString
  }
}
