## jetty4s - Jetty client and server backends for http4s

### Usage

Add this library to your **build.sbt**:

```scala
libraryDependencies ++= List(
  "com.github.IndiscriminateCoding" %% "jetty4s-client" % jetty4sVersion,
  "com.github.IndiscriminateCoding" %% "jetty4s-server" % jetty4sVersion
)
```

Now you can use `jetty4s.client.JettyClientBuilder` to create a `Client[F]`:

```scala
val clientResource: Resource[IO, Client[IO]] = JettyClientBuilder[IO]
  .withRequestTimeout(FiniteDuration(5, TimeUnit.SECONDS))
  .withExecutor(Executors.newWorkStealingPool())
  .resource
```

And `jetty4s.server.JettyServerBuilder` to run your `HttpApp[F]`:

```scala
val serverResource: Resource[IO, List[Server[IO]]] = JettyServerBuilder[IO]
  .withHttpApp(app)
  .resource
```