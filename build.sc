import mill._, scalalib._

object indexer extends ScalaModule {
  def sources = T.sources(millOuterCtx.millSourcePath / "src")
  def scalaVersion = "3.1.1"
  def ivyDeps = Agg(
    ivy"io.github.torrentdam.bittorrent::bittorrent::1.0.0",
    ivy"io.github.torrentdam.bittorrent::dht::1.0.0",
    ivy"com.lihaoyi::os-lib::0.7.8",
    ivy"com.lihaoyi::upickle::1.4.0",
    ivy"ch.qos.logback:logback-classic:1.2.3",
  )
}
