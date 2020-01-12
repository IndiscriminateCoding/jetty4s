## jetty4s - Jetty client backend for http4s

### Usage

Add this library to your **build.sbt**:

```scala
libraryDependencies += "com.github.IndiscriminateCoding" %% "jetty4s-client" % "0.0.4"
```

Now you can use `jetty4s.client.JettyClientBuilder` to create a `Client`:

```scala
val clientResource: Resource[IO, Client[IO]] = JettyClientBuilder[IO]
  .withRequestTimeout(FiniteDuration(5, TimeUnit.SECONDS))
  .withExecutor(Executors.newWorkStealingPool())
  .resource
```
