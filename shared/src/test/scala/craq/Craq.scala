package craq

import frankenpaxos.craq.{ChainNode, ChainNodeMetrics, ChainNodeOptions, Client, ClientMetrics, ClientOptions, CommandBatchOrNoop, Config, Hash}
import frankenpaxos.monitoring.FakeCollectors
import frankenpaxos.simulator.{FakeLogger, FakeTransport, FakeTransportAddress, SimulatedSystem}
import frankenpaxos.statemachine.ReadableAppendLog
import org.scalacheck.Gen
import org.scalacheck.rng.Seed

import scala.collection.mutable

class Craq(val f: Int, batched: Boolean, seed: Long) {
  val logger = new FakeLogger()
  val transport = new FakeTransport(logger)
  val numClients = 2
  val numBatchers = if (batched) {
    f + 1
  } else {
    0
  }
  val numLeaders = f + 1
  val numProxyLeaders = f + 1
  val numAcceptorGroups = 2
  val numAcceptors = 2 * f + 1
  val numReplicas = f + 1
  val numProxyReplicas = f + 1
  val numChainNodes = 2 * f + 1

  val config = Config[FakeTransport](
    f,
    batcherAddresses =
      (1 to numBatchers).map(i => FakeTransportAddress(s"Batcher $i")),
    readBatcherAddresses =
      (1 to numBatchers).map(i => FakeTransportAddress(s"ReadBatcher $i")),
    leaderAddresses =
      (1 to numLeaders).map(i => FakeTransportAddress(s"Leader $i")),
    leaderElectionAddresses =
      (1 to numLeaders).map(i => FakeTransportAddress(s"LeaderElection $i")),
    proxyLeaderAddresses =
      (1 to numProxyLeaders).map(i => FakeTransportAddress(s"ProxyLeader $i")),
    acceptorAddresses = for (g <- 0 to numAcceptorGroups)
      yield {
        (1 to numAcceptors).map(i => FakeTransportAddress(s"Acceptor $g.$i")),
      },
    replicaAddresses =
      (1 to numReplicas).map(i => FakeTransportAddress(s"Replica $i")),
    proxyReplicaAddresses = (1 to numProxyReplicas)
      .map(i => FakeTransportAddress(s"ProxyReplica $i")),
    chainNodeAddresses = (1 to numChainNodes).map(i => FakeTransportAddress(s"ChainNode $i")),
    distributionScheme = Hash
  )

  // Clients.
  val clients = for (i <- 1 to numClients) yield {
    new Client[FakeTransport](
      address = FakeTransportAddress(s"Client $i"),
      transport = transport,
      logger = new FakeLogger(),
      config = config,
      options = ClientOptions.default,
      metrics = new ClientMetrics(FakeCollectors),
      seed = seed
    )
  }

  // ChainNodes
  val chainNodes = for (address <- config.chainNodeAddresses) yield {
    new ChainNode[FakeTransport](
      address = address,
      transport = transport,
      logger = new FakeLogger(),
      config = config,
      options = ChainNodeOptions.default,
      metrics = new ChainNodeMetrics(FakeCollectors),
      seed = seed
    )
  }
}

object SimulatedCraq {
  sealed trait Command

  case class Write(
      clientIndex: Int,
      clientPseudonym: Int,
      value: String
  ) extends Command

  case class Read(
      clientIndex: Int,
      clientPseudonym: Int
  ) extends Command

  case class TransportCommand(command: FakeTransport.Command) extends Command
}

class SimulatedCraq(val f: Int, batched: Boolean)
    extends SimulatedSystem {
  import SimulatedCraq._

  override type System = Craq
  // For every replica, we record the prefix of the log that has been executed.
  override type State = mutable.Buffer[mutable.Map[String, String]]
  override type Command = craq.SimulatedCraq.Command

  // True if some value has been chosen in some execution of the system. Seeing
  // whether any value has been chosen is a very coarse way of testing
  // liveness. If no value is every chosen, then clearly something is wrong.
  var valueChosen: Boolean = false

  override def newSystem(seed: Long): System = new Craq(f, batched, seed)

  override def getState(craq: System): State = {
    val logs = mutable.Buffer[mutable.Map[String, String]]()
    for (replica <- craq.chainNodes) {
      logs += (replica.stateMachine)
    }
    logs
  }

  override def generateCommand(paxos: System): Option[Command] = {
    val subgens = mutable.Buffer[(Int, Gen[Command])](
      // Write.
      paxos.numClients * 3 -> {
        for {
          clientId <- Gen.choose(0, paxos.numClients - 1)
          request <- Gen.alphaLowerStr.filter(_.size > 0)
        } yield Write(clientId, clientPseudonym = 0, request)

      },
      // Read.
      paxos.numClients -> {
        for {
          clientId <- Gen.choose(0, paxos.numClients - 1)
        } yield Read(clientId, clientPseudonym = 0)
      },
    )
    FakeTransport
      .generateCommandWithFrequency(paxos.transport)
      .foreach({
        case (frequency, gen) =>
          subgens += frequency -> gen.map(TransportCommand(_))
      })

    val gen: Gen[Command] = Gen.frequency(subgens: _*)
    gen.apply(Gen.Parameters.default, Seed.random())
  }

  override def runCommand(paxos: System, command: Command): System = {
    command match {
      case Write(clientId, clientPseudonym, request) =>
        paxos.clients(clientId).write(clientPseudonym, request)
      case Read(clientId, clientPseudonym) =>
        paxos.clients(clientId).read(clientPseudonym, "")
      case TransportCommand(command) =>
        FakeTransport.runCommand(paxos.transport, command)
    }
    paxos
  }

  private def isPrefix[A](lhs: Seq[A], rhs: Seq[A]): Boolean = {
    lhs.zipWithIndex.forall({
      case (x, i) => rhs.lift(i) == Some(x)
    })
  }

  override def stateInvariantHolds(
      state: State
  ): SimulatedSystem.InvariantResult = {
    for (logs <- state.combinations(2)) {
      val lhs = logs(0)
      val rhs = logs(1)
      for (key <- lhs.keys) {
        if (!lhs.get(key).get.equalsIgnoreCase(rhs.get(key).get)) {
          return SimulatedSystem.InvariantViolated(s"Logs $lhs and $rhs are not compatible.")
        }
      }
    }

    SimulatedSystem.InvariantHolds
  }

  override def stepInvariantHolds(
      oldState: State,
      newState: State
  ): SimulatedSystem.InvariantResult = {
    for ((oldLog, newLog) <- oldState.zip(newState)) {
      for (key <- oldLog.keys) {
        if (oldLog.get(key).get.equalsIgnoreCase(newLog.get(key).get)) {
          return SimulatedSystem.InvariantViolated(
            s"Logs $oldLog is not a prefix of $newLog."
          )
        }
      }
    }
    SimulatedSystem.InvariantHolds
  }

  def commandToString(command: Command): String = {
    val paxos = newSystem(System.currentTimeMillis())
    command match {
      case Write(clientIndex, clientPseudonym, value) =>
        val clientAddress = paxos.clients(clientIndex).address
        s"Write($clientAddress, $clientPseudonym, $value)"

      case Read(clientIndex, clientPseudonym) =>
        val clientAddress = paxos.clients(clientIndex).address
        s"Read($clientAddress, $clientPseudonym)"

      case TransportCommand(FakeTransport.DeliverMessage(msg)) =>
        val dstActor = paxos.transport.actors(msg.dst)
        val s = dstActor.serializer.toPrettyString(
          dstActor.serializer.fromBytes(msg.bytes.to[Array])
        )
        s"DeliverMessage(src=${msg.src.address}, dst=${msg.dst.address})\n$s"

      case TransportCommand(FakeTransport.TriggerTimer(address, name, id)) =>
        s"TriggerTimer(${address.address}:$name ($id))"
    }
  }

  def historyToString(history: Seq[Command]): String = {
    def indent(s: String, n: Int): String = {
      s.replaceAll("\n", "\n" + " " * n)
    }
    history.zipWithIndex
      .map({
        case (command, i) =>
          val num = "%3d".format(i)
          s"$num. ${indent(commandToString(command), 5)}"
      })
      .mkString("\n")
  }
}
