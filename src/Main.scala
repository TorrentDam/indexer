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
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import fs2.Chunk


object Main extends IOApp {

  given logger: StructuredLogger[IO] = Slf4jLogger.getLogger

  def run(args: List[String]): IO[ExitCode] =

    val targetDir = args.headOption match
      case Some(path) => args.foldLeft(os.root)(_ / _)
      case None => os.pwd / "metadata"
    val paths = Paths(targetDir)

    components(paths).use { components =>
      import components.*
      
      Stream
        .repeatEval(randomNodeId)
        .map(nodeId =>
          infoHashSamples(nodeId)
            .take(10)
            .interruptAfter(10.minutes)
            .parEvalMapUnordered(10) { infoHash =>
              downlaodMetadata(infoHash, discoverAndConnect(infoHash)).tupleLeft(infoHash)
            }
            .collect { case (infoHash, Some(metadata)) => (infoHash, metadata) }
        )
        .parJoin(100)
        .evalTap(save)
        .compile
        .drain
        .as(ExitCode.Success)
    }


  def components(paths: Paths) = {

    for
      given Random[IO] <- Resource.eval { Random.scalaUtilRandom[IO] }
      given SocketGroup[IO] <- Network[IO].socketGroup()
      given DatagramSocketGroup[IO] <- Network[IO].datagramSocketGroup()
      selfId <- Resource.eval { PeerId.generate[IO] }
      nodeId <- Resource.eval { NodeId.generate[IO] }
      routingTable <- Resource.eval { RoutingTable[IO](nodeId) }
      queryHandler <- Resource.pure { QueryHandler[IO](nodeId, routingTable) }
      node <- Node[IO](nodeId, queryHandler)
      _ <- Resource.eval { RoutingTableBootstrap(routingTable, node.client) }
      peerDiscovery <- PeerDiscovery.make(routingTable, node.client)
      connect = { (infoHash: InfoHash, peerInfo: PeerInfo) =>
        Connection.connect[IO](selfId, peerInfo, infoHash)
      }
    yield Components(connect, routingTable, node, peerDiscovery, paths)
  }

  class Components(
    connect: (InfoHash, PeerInfo) => Resource[IO, Connection[IO]],
    routingTable: RoutingTable[IO],
    node: Node[IO],
    peerDiscovery: PeerDiscovery[IO],
    paths: Paths
  )(using
    Random[IO]
  ) {

    def randomNodeId = NodeId.generate[IO]

    def infoHashSamples(target: NodeId): Stream[IO, InfoHash] = {

      def findNodes(nodes: List[NodeInfo], visited: Set[NodeId]): Stream[IO, NodeInfo] =
        Stream
          .eval {
            nodes
              .parTraverse { nodeInfo =>
                node.client.findNodes(nodeInfo, target)
                .map(_.nodes)
                .timeout(5.seconds)
                .handleError(_ => Nil)
              }
              .map { results =>
                results.flatten.filterNot(info => visited.contains(info.id))
              }
              .map { results =>
                results.sortBy(info => NodeId.distance(info.id, target))
              }
          }
          .flatMap {
            case Nil => Stream.empty
            case results => Stream.emits(results) ++ findNodes(results, visited ++ results.map(_.id))
          }

      def initialNodes = Stream.eval(routingTable.findNodes(target).map(_.take(10).toList))

      def getSampleHash(nodeInfo: NodeInfo) =
        node.client.sampleInfoHashes(nodeInfo, target)
          .map {
            case Right(sample) => sample.samples
            case Left(_) => Nil
          }
          .flatTap { samples =>
            logger.info(s"Samples: $samples")
          }
          .handleError(_ => Nil)
      
      def sampleHashStream(nodes: Stream[IO, NodeInfo]) =
        for
          unique <- Stream.eval(Ref.of[IO, Set[InfoHash]](Set.empty))
          infoHash <- nodes.parEvalMap(10)(getSampleHash)
            .map(Chunk.iterable)
            .unchunks
            .evalFilter(infoHash => unique.modify(current => (current + infoHash, !current(infoHash))))
        yield
          infoHash
      
      for
        nodes <- initialNodes
        infoHash <- sampleHashStream(findNodes(nodes, Set.empty))
      yield
        infoHash
    }

    def discoverAndConnect(
        infoHash: InfoHash
    ): Stream[IO, Connection[IO]] = {
      peerDiscovery
        .discover(infoHash)
        .evalTap { peerInfo =>
          logger.debug(s"Discovered $peerInfo")
        }
        .flatMap { peerInfo =>
          Stream.resourceWeak(connect(infoHash, peerInfo).attempt)
        }
        .collect { case Right(connection) => connection }
        .evalTap { connection =>
          logger.debug(s"Connected to ${connection.info}")
        }
    }

    def downlaodMetadata(infoHash: InfoHash, connections: Stream[IO, Connection[IO]]) = {
      connections
        .parEvalMapUnordered(10) { connection =>
          DownloadMetadata(connection).timeout(5.seconds).attempt
        }
        .collectFirst { case Right(metadata) => metadata }
        .compile
        .lastOrError
        .timeout(1.minute)
        .attempt
        .flatMap {
          case Right(metadata) =>
            logger.info(s"Metadata downloaded for $infoHash $metadata") >>
            metadata.some.pure[IO]
          case Left(_) =>
            logger.info(s"Could not download metadata for $infoHash") >>
            none.pure[IO]
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
  }

  extension [A](self: Resource[IO, A]) {

    def timeout(duration: FiniteDuration): Resource[IO, A] =
      self
        .race(Resource.sleep[IO](duration))
        .evalMap {
          case Left(result) => IO.pure(result)
          case Right(_) => IO.raiseError(new TimeoutException)
        }
  }
}
