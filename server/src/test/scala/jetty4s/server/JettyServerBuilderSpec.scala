package jetty4s.server

import cats.effect._
import fs2._
import jetty4s.client.JettyClientBuilder
import org.http4s.Uri.Scheme
import org.http4s._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class JettyServerBuilderSpec extends AnyFlatSpec with Matchers {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  private val port = 11118

  private val sizes = List(1, 2, 178, 1023, 1024, 1025, 16000)
  private val bodyStream: List[Stream[Pure, Byte]] = for {
    a <- sizes
    b <- sizes
    c <- sizes
  } yield List(a, b, c)
    .map(sz =>
      Stream
        .chunk(Chunk.bytes(Array.fill(sz)((scala.util.Random.nextInt(256) - 128).toByte)))
    )
    .reduce(_ ++ _)

  private val jettyClient = JettyClientBuilder[IO]
    .allocated
    .unsafeRunSync()
    ._1
  private val app: Request[IO] => Resource[IO, Response[IO]] = r =>
    Resource.pure[IO, Response[IO]](Response(body = r.body))

  JettyServerBuilder[IO]
    .withHttpResource(app)
    .bindHttp(port = port)
    .resource
    .allocated
    .unsafeRunSync()

  it should "keep content on echo endpoint" in {
    bodyStream foreach { body =>
      val v = body.compile.toVector
      val res = jettyClient
        .fetch(
          Request[IO](
            body = body,
            headers = Headers.of(Header("transfer-encoding", "chunked"))
          ).withUri(
            Uri(
              authority = Some(Uri.Authority(host = Uri.RegName("localhost"), port = Some(port))),
              scheme = Some(Scheme.http)
            )
          )
        )(_.body.compile.toVector)
        .unsafeRunSync()
      res shouldBe v
    }
  }
}
