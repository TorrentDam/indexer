import mill._, scalalib._
import coursier.maven.MavenRepository

object jobs extends ScalaModule {
  def scalaVersion = "2.13.3"
  def repositoriesTask = T.task {
      super.repositoriesTask() ++ Seq(
        MavenRepository("https://dl.bintray.com/lavrov/maven")
      )
  }
  def ivyDeps = Agg(
    ivy"com.github.torrentdam::bittorrent::0.1.0",
    ivy"com.github.torrentdam::dht::0.1.0",
    ivy"com.lihaoyi::os-lib::0.7.1",
    ivy"com.lihaoyi::upickle::1.2.2",
  )
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.0",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
}
