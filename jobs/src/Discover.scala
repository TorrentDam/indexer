import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata}
import com.github.lavrov.bittorrent.dht.{Node, NodeId, NodeInfo, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp.SocketGroup
import fs2.io.udp.{SocketGroup => UdpSocketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO

import scala.concurrent.duration._
import scala.util.Random
import com.github.lavrov.bittorrent._
import com.github.torrentdam.bencode.encode

object Discover extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger(IzLogger.Level.Info))

  def run(args: List[String]): IO[ExitCode] =
    components
      .use {
        case (samples, peerDiscovery, connect) =>
          discover(
            samples,
            peerDiscovery,
            connect
          )
            .evalTap((save _).tupled)
            .interruptAfter(10.minutes)
            .compile
            .drain
            .handleErrorWith {
              case _: java.lang.InterruptedException => IO.unit
            }
            .as(ExitCode.Success)
      }


  def components = {

    val rnd = new Random
    val selfId: PeerId = PeerId.generate(rnd)
    val selfNodeId: NodeId = NodeId.generate(rnd)

    for {
      blocker <- Blocker[IO]
      implicit0(socketGroup: SocketGroup) <- SocketGroup[IO](blocker)
      implicit0(udpSocketGroup: UdpSocketGroup) <- UdpSocketGroup[IO](blocker)
      result <- {
        for {
          routingTable <- Resource.liftF { RoutingTable[IO](selfNodeId) }
          queryHandler <- Resource.pure[IO, QueryHandler[IO]] { QueryHandler(selfNodeId, routingTable) }
          node <- Node[IO](selfNodeId, 0, queryHandler)
          seedNode <- Resource.liftF { RoutingTableBootstrap.resolveSeedNode(node.client) }
          _ <- Resource.liftF { routingTable.insert(seedNode) }
        } yield (routingTable, node, seedNode)
      }
      (routingTable, dhtNode, seedNode) = result
      peerDiscovery <- PeerDiscovery.make[IO](routingTable, dhtNode.client)
      connect = { (infoHash: InfoHash, peerInfo: PeerInfo) =>
        Connection.connect[IO](selfId, peerInfo, infoHash)
      }
      samples = infoHashSamples(seedNode)
    } yield (samples, peerDiscovery, connect)
  }

  def infoHashSamples(seedNode: NodeInfo)(implicit
      socketGroup: UdpSocketGroup,
  ): Stream[IO, InfoHash] = {

    val randomNodeId = {
      val rnd = new Random
      IO { NodeId.generate(rnd) }
    }

    def spawn(reportInfoHash: InfoHash => IO[Unit]): IO[Unit] =
      for {
        nodeId <- randomNodeId
        routingTable <- RoutingTable[IO](nodeId)
        queryHandler <- QueryHandler[IO](nodeId, routingTable).pure[IO]
        _ <- Node[IO](nodeId, 0, queryHandler).use { node =>
          def loop(nodes: List[NodeInfo], visited: Set[NodeInfo]): IO[Unit] = {
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
          }
          node.client.findNodes(seedNode, nodeId).flatMap { response =>
            loop(response.nodes, Set.empty)
          }
        }
      } yield ()

    Stream
      .eval(Queue.unbounded[IO, InfoHash])
      .flatMap { queue =>
        queue.dequeue.concurrently {
          Stream
            .fixedRate(10.seconds)
            .parEvalMapUnordered(1) { _ =>
              spawn(queue.enqueue1).attempt.timeoutTo(10.minutes, IO.unit)
            }
        }
      }
  }

  def discover(
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
    if (!os.exists(path)) {
      val bytes = encode(metadata.raw).bytes
      if (bytes.digest("SHA-1") == infoHash.bytes) {
        val content = common.Metadata.fromTorrentMetadata(infoHash, metadata.parsed)
        val json = upickle.default.write(content, indent = 2)
        os.write(path, json, createFolders = true)
        println(s"Saved into $path")
      }
    }
  }

  implicit class ResourceOps[A](self: Resource[IO, A]) {

    def timeout(duration: FiniteDuration)(implicit
                                          contextShift: ContextShift[IO],
                                          timer: Timer[IO]
    ): Resource[IO, A] =
      Resource.make(self.allocated.timeout(duration))(_._2).map(_._1)
  }
}
