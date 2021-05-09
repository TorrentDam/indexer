import mill._, scalalib._
import coursier.maven.MavenRepository

object jobs extends ScalaModule {
  def scalaVersion = "2.13.5"
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
    ivy"com.github.torrentdam::bittorrent::0.3.0",
    ivy"com.github.torrentdam::dht::0.3.0",
    ivy"com.lihaoyi::os-lib::0.7.1",
    ivy"com.lihaoyi::upickle::1.2.2",
  )
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.3",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
}
