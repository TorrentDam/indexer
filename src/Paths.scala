import com.github.lavrov.bittorrent.InfoHash
import os.Path
import os.RelPath


class Paths(
  metadataPath: os.Path,
) {

  def torrentPath(infoHash: InfoHash): TorrentPath = {
    val depth = 3
    val str = infoHash.toString()
    val directory = str.take(depth).foldLeft(RelPath.rel)(_ / _.toString)
    TorrentPath(metadataPath / directory / str)
  }
}

case class TorrentPath(root: os.Path) {

  def metadata: os.Path = root / "metadata.json"
}