import mill._, scalalib._
import coursier.maven.MavenRepository

object jobs extends ScalaModule {
  def scalaVersion = "3.0.1"
  def scalacOptions = Seq(
    "-source:future",
    "-Ykind-projector:underscores",
  )
  def repositoriesTask = T.task {
      super.repositoriesTask() ++ Seq(
        MavenRepository(
          "https://maven.pkg.github.com/TorrentDam/bittorrent",
          T.env.get("GITHUB_TOKEN").map { token =>
            coursier.core.Authentication("lavrov", token)
          }
        )
      )
  }
  def ivyDeps = Agg(
    ivy"com.github.torrentdam::bittorrent::1.0.0-RC3",
    ivy"com.github.torrentdam::dht::1.0.0-RC3",
    ivy"com.lihaoyi::os-lib::0.7.8",
    ivy"com.lihaoyi::upickle::1.4.0",
    ivy"ch.qos.logback:logback-classic:1.2.3",
  )
}
