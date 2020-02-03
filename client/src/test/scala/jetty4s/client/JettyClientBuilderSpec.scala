package jetty4s.client

import fs2._
import cats.effect.{ ContextShift, IO, Timer }
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{ HttpApp, Method, Request, Response, Uri }
import org.scalatest.{ BeforeAndAfterAll, FlatSpec, Matchers }

import scala.concurrent.ExecutionContext

class JettyClientBuilderSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  private val stop = BlazeServerBuilder[IO]
    .bindHttp(8080)
    .withHttpApp(HttpApp(r => IO.pure(Response[IO](body = r.body))))
    .allocated
    .unsafeRunSync()
    ._2

  override def afterAll(): Unit = stop.unsafeRunSync()

  it should "build proper client" in {
    val client = JettyClientBuilder[IO].allocated.unsafeRunSync()._1
    val chunks = List("hello", " ", "world")
    val body = chunks.map(s => Stream.chunk(Chunk.bytes(s.getBytes))).reduce(_ ++ _)
    client
      .expect[String](
        Request[IO](
          method = Method.POST,
          uri = Uri(
            scheme = Some(Uri.Scheme.http),
            authority = Some(
              Uri.Authority(host = Uri.RegName("localhost"), port = Some(8080))
            )
          ),
          body = body
        )
      )
      .unsafeRunSync() shouldBe chunks.mkString
  }
}
