import cats.effect.*

import io.nats.client.*
import java.nio.charset.StandardCharsets


trait Output {
  def write(metadata: Metadata): IO[Unit]
}

object Output {

  class Filesystem(paths: Paths) extends Output {
    def write(metadata: Metadata) = IO {
      val path = paths.torrentPath(metadata.infoHash).metadata
      if !os.exists(path) then
        val json = upickle.default.write(metadata, indent = 2)
        os.write(path, json, createFolders = true)
    }
  }

  class NatsStream(connection: Connection) extends Output {
    def write(metadata: Metadata) = IO {
      val json = upickle.default.write(metadata, indent = 2)
      connection.publish("discovered", json.getBytes(StandardCharsets.UTF_8))
    }
  }

  object NatsStream {
    def fromUrl(url: String): Resource[IO, NatsStream] =
      Resource.fromAutoCloseable(IO(Nats.connect(url))).map(NatsStream(_))
  }
}