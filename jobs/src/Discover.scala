import cats.syntax.all.*
import cats.effect.*
import cats.effect.kernel.Ref
import cats.effect.std.{Random, Queue}
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata}
import com.github.lavrov.bittorrent.dht.{Node, NodeId, NodeInfo, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
import fs2.Stream
import fs2.io.net.*
import com.github.lavrov.bittorrent.*
import com.github.torrentdam.bencode.encode
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration.*

object Discover extends IOApp {

  given logger: StructuredLogger[IO] = Slf4jLogger.getLogger

  def run(args: List[String]): IO[ExitCode] =
    components
      .use {
        case (infoHashSamples, peerDiscovery, connect) =>
          fetchMetadata(
            infoHashSamples,
            peerDiscovery,
            connect
          )
            .evalTap(save)
            .interruptAfter(10.minutes)
            .compile
            .drain
            .handleErrorWith {
              case _: java.lang.InterruptedException => IO.unit
            }
            .as(ExitCode.Success)
      }


  def components = {

    for
      given Random[IO] <- Resource.eval { Random.scalaUtilRandom[IO] }
      given SocketGroup[IO] <- Network[IO].socketGroup()
      given DatagramSocketGroup[IO] <- Network[IO].datagramSocketGroup()
      selfId <- Resource.eval { PeerId.generate[IO] }
      selfNodeId <- Resource.eval { NodeId.generate[IO] }
      (routingTable, dhtNode) <- {
        for
          routingTable <- Resource.eval { RoutingTable[IO](selfNodeId) }
          queryHandler <- Resource.pure[IO, QueryHandler[IO]] { QueryHandler(selfNodeId, routingTable) }
          node <- Node[IO](selfNodeId, queryHandler)
          _ <- Resource.eval { RoutingTableBootstrap(routingTable, node.client) }
        yield (routingTable, node)
      }
      peerDiscovery <- PeerDiscovery.make[IO](routingTable, dhtNode.client)
      connect = { (infoHash: InfoHash, peerInfo: PeerInfo) =>
        Connection.connect[IO](selfId, peerInfo, infoHash)
      }
      samples = infoHashSamples(routingTable)
    yield (samples, peerDiscovery, connect)
  }

  def infoHashSamples(masterTable: RoutingTable[IO])(
    using
    DatagramSocketGroup[IO],
    Random[IO]
  ): Stream[IO, InfoHash] = {

    val randomNodeId = NodeId.generate[IO]

    def spawn(reportInfoHash: InfoHash => IO[Unit]): IO[Unit] =
      for
        nodeId <- randomNodeId
        routingTable <- RoutingTable[IO](nodeId)
        queryHandler <- QueryHandler[IO](nodeId, routingTable).pure[IO]
        _ <- Node[IO](nodeId, queryHandler).use { node =>

          def loop(nodes: List[NodeInfo], visited: Set[NodeInfo]): IO[Unit] =
            nodes
              .traverse { nodeInfo =>
                node.client
                  .sampleInfoHashes(nodeInfo, nodeId)
                  .flatMap {
                    case Right(response) =>
                      response.samples
                        .traverse_(reportInfoHash)
                        .as(response.nodes.getOrElse(List.empty))
                    case Left(response) =>
                      response.nodes.pure[IO]
                  }
                  .handleErrorWith(_ => List.empty.pure[IO])
              }
              .map(_.flatten.filterNot(visited).take(10))
              .flatMap {
                case Nil      => IO.unit
                case nonEmpty => loop(nonEmpty, visited ++ nodes)
              }
          end loop

          masterTable.findNodes(nodeId).flatMap { nodes =>
            loop(nodes.toList, Set.empty)
          }
        }
      yield ()

    Stream
      .eval(Queue.unbounded[IO, InfoHash])
      .flatMap { queue =>
        Stream.fromQueueUnterminated(queue).concurrently {
          Stream
            .fixedRate[IO](10.seconds)
            .parEvalMapUnordered(1) { _ =>
              spawn(queue.offer).attempt.timeoutTo(10.minutes, IO.unit)
            }
        }
      }
  }

  def fetchMetadata(
      infoHashes: Stream[IO, InfoHash],
      peerDiscovery: PeerDiscovery[IO],
      connect: (InfoHash, PeerInfo) => Resource[IO, Connection[IO]]
  ): Stream[IO, (InfoHash, TorrentMetadata.Lossless)] = {

    val processed = Ref.unsafe[IO, Set[InfoHash]](Set.empty)

    infoHashes
      .evalFilter { infoHash =>
        processed.get.map(!_.contains(infoHash))
      }
      .evalTap { infoHash =>
        processed.update(_ + infoHash)
      }
      .parEvalMapUnordered(100) { infoHash =>
        peerDiscovery
          .discover(infoHash)
          .flatMap { peerInfo =>
            Stream
              .eval(logger.debug(s"Discovered $peerInfo")) >>
              Stream
                .resource(
                  connect(infoHash, peerInfo).timeout(2.second)
                )
                .attempt
                .evalTap {
                  case Right(_) =>
                    logger.debug(s"Connected to $peerInfo")
                  case Left(e) =>
                    IO.unit
                }
          }
          .collect { case Right(connection) => connection }
          .parEvalMapUnordered(300) { connection =>
            DownloadMetadata(connection)
              .timeout(1.minute)
              .attempt
          }
          .collectFirst { case Right(metadata) => metadata }
          .compile
          .lastOrError
          .timeout(1.minute)
          .attempt
          .flatMap[Option[(InfoHash, TorrentMetadata.Lossless)]] {
            case Right(metadata) =>
              logger.info(s"Metadata downloaded for $infoHash $metadata") >>
                (infoHash, metadata).some.pure[IO]
            case Left(_) =>
              logger.info(s"Could not download metadata for $infoHash") >>
                none.pure[IO]
          }
      }
      .collect {
        case Some(value) => value
      }
  }

  def save(infoHash: InfoHash, metadata: TorrentMetadata.Lossless): IO[Unit] = IO {
    val path = paths.torrentPath(infoHash).metadata
    if !os.exists(path) then
      val bytes = encode(metadata.raw).bytes
      if bytes.digest("SHA-1") == infoHash.bytes then
        val content = common.Metadata.fromTorrentMetadata(infoHash, metadata.parsed)
        val json = upickle.default.write(content, indent = 2)
        os.write(path, json, createFolders = true)
        println(s"Saved into $path")
  }

  extension [A](self: Resource[IO, A]) {

    def timeout(duration: FiniteDuration): Resource[IO, A] =
      Resource.make(self.allocated.timeout(duration))(_._2).map(_._1)
  }
}
