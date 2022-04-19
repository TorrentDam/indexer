import cats.implicits.*
import com.github.lavrov.bittorrent.InfoHash
import com.github.torrentdam.bencode.decode
import scodec.bits.ByteVector
import com.github.lavrov.bittorrent.TorrentMetadata


case class Metadata(
    name: String,
    infoHash: InfoHash,
    size: Long,
    ext: Set[String]
)

object Metadata {

  case class File(path: List[String], length: Long)

  object extractors {

    import com.github.torrentdam.bencode.format.*

    val name = field[String]("name")

    val files = fieldOptional("files")(
      BencodeFormat.listFormat(
        (field[List[String]]("path"), field[Long]("length")).imapN(File.apply)(f => (f.path, f.length))
      )
    )

    val length = fieldOptional[Long]("length")
  }

  val supportedExtensions = Set(
    "avi", "mkv", "mp4",
    "mp3", "flac", "ogg",
    "png", "jpeg",
    "txt", "md", "str"
  )

  def fromTorrentMetadata(infoHash: InfoHash, torrentMetadata: TorrentMetadata): Metadata = {
    val name = torrentMetadata.name
    val fileExtensions =
      torrentMetadata.files
        .view
        .flatMap(_.path.lastOption)
        .map(_.toLowerCase)
        .flatMap(_.split('.').lastOption)
        .filter(supportedExtensions)
        .toSet
    val size = torrentMetadata.files.map(_.length).sum
    Metadata(
      name = name,
      infoHash = infoHash,
      size = size,
      ext = fileExtensions,
    )
  }

  import upickle.default.*

  given Writer[InfoHash] = summon[Writer[String]].comap(_.toHex)
  given Writer[Metadata] = macroW
}