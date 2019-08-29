package frankenpaxos.simplegcbpaxos

import VertexIdHelpers.vertexIdOrdering
import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.Serializer
import frankenpaxos.Util
import frankenpaxos.clienttable.ClientTable
import frankenpaxos.depgraph.DependencyGraph
import frankenpaxos.depgraph.JgraphtDependencyGraph
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.Gauge
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.monitoring.Summary
import frankenpaxos.statemachine.StateMachine
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js.annotation._
import scala.util.Random

@JSExportAll
object ReplicaInboundSerializer extends ProtoSerializer[ReplicaInbound] {
  type A = ReplicaInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

@JSExportAll
case class ReplicaOptions(
    // If a replica commits a vertex v that depends on uncommitted vertex u,
    // the replica will eventually recover u to ensure that v will eventually
    // be executed. The time that the replica waits before recovering u is
    // drawn uniformly at random between recoverVertexTimerMinPeriod and
    // recoverVertexTimerMaxPeriod.
    recoverVertexTimerMinPeriod: java.time.Duration,
    recoverVertexTimerMaxPeriod: java.time.Duration,
    // If `unsafeDontRecover` is true, replicas don't make any attempt to
    // recover vertices. This is not live and should only be used for
    // performance debugging.
    unsafeDontRecover: Boolean,
    // See frankenpaxos.epaxos.Replica for information on the following options.
    executeGraphBatchSize: Int,
    executeGraphTimerPeriod: java.time.Duration,
    unsafeSkipGraphExecution: Boolean,
    // A replica sends a GarbageCollect message to the proposers and acceptors
    // every `sendWatermarkEveryNCommands` commands that it receives.
    sendWatermarkEveryNCommands: Int,
    // If true, the replica records how long various things take to do and
    // reports them using the `simple_bpaxos_replica_requests_latency` metric.
    measureLatencies: Boolean
)

@JSExportAll
object ReplicaOptions {
  val default = ReplicaOptions(
    recoverVertexTimerMinPeriod = java.time.Duration.ofMillis(500),
    recoverVertexTimerMaxPeriod = java.time.Duration.ofMillis(1500),
    unsafeDontRecover = false,
    executeGraphBatchSize = 1,
    executeGraphTimerPeriod = java.time.Duration.ofSeconds(1),
    unsafeSkipGraphExecution = false,
    sendWatermarkEveryNCommands = 10000,
    measureLatencies = true
  )
}

@JSExportAll
class ReplicaMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val requestsLatency: Summary = collectors.summary
    .build()
    .name("simple_bpaxos_replica_requests_latency")
    .labelNames("type")
    .help("Latency (in milliseconds) of a request.")
    .register()

  val executeGraphTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_execute_graph_total")
    .help("Total number of times the replica executed the dependency graph.")
    .register()

  val executeGraphTimerTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_execute_graph_timer_total")
    .help(
      "Total number of times the replica executed the dependency graph from " +
        "a timer."
    )
    .register()

  val executedCommandsTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_executed_commands_total")
    .help("Total number of executed state machine commands.")
    .register()

  val executedNoopsTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_executed_noops_total")
    .help("Total number of \"executed\" noops.")
    .register()

  val executedSnapshotsTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_executed_snapshots_total")
    .help("Total number of \"executed\" snapshots.")
    .register()

  val repeatedCommandsTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_repeated_commands_total")
    .help("Total number of commands that were redundantly chosen.")
    .register()

  val recoverVertexTotal: Counter = collectors.counter
    .build()
    .name("simple_bpaxos_replica_recover_vertex_total")
    .help("Total number of times the replica recovered an instance.")
    .register()

  val dependencyGraphNumVertices: Gauge = collectors.gauge
    .build()
    .name("simple_bpaxos_replica_dependency_graph_num_vertices")
    .help("The number of vertices in the dependency graph.")
    .register()

  val dependencies: Summary = collectors.summary
    .build()
    .name("simple_bpaxos_replica_dependencies")
    .help("The number of dependencies that a command has.")
    .register()

  val uncompactedDependencies: Summary = collectors.summary
    .build()
    .name("simple_bpaxos_replica_uncompacted_dependencies")
    .help("The number of uncompacted dependencies that a command has.")
    .register()

  val uncommittedDependencies: Summary = collectors.summary
    .build()
    .name("simple_bpaxos_replica_uncommitted_dependencies")
    .help("The number of uncommitted dependencies that a command has.")
    .register()

  val timerDependencies: Summary = collectors.summary
    .build()
    .name("simple_bpaxos_replica_timer_dependencies")
    .help("The number of timer dependencies that a command has.")
    .register()
}

@JSExportAll
object Replica {
  val serializer = ReplicaInboundSerializer

  type ClientPseudonym = Int

  @JSExportAll
  case class Committed(
      proposal: Proposal,
      dependencies: VertexIdPrefixSet
  )
}

@JSExportAll
class Replica[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    // Public for Javascript visualizations.
    val stateMachine: StateMachine,
    // Public for Javascript visualizations.
    val dependencyGraph: DependencyGraph[VertexId, Unit, VertexIdPrefixSet],
    options: ReplicaOptions = ReplicaOptions.default,
    metrics: ReplicaMetrics = new ReplicaMetrics(PrometheusCollectors),
    seed: Long = System.currentTimeMillis()
) extends Actor(address, transport, logger) {
  import Replica._

  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = ReplicaInbound
  override def serializer = Replica.serializer

  // Fields ////////////////////////////////////////////////////////////////////
  // Sanity check the configuration and get our index.
  logger.check(config.valid())
  logger.check(config.replicaAddresses.contains(address))
  private val index = config.replicaAddresses.indexOf(address)

  // Random number generator.
  val rand = new Random(seed)

  // Channels to the co-located garbage collector.
  private val garbageCollector: Chan[GarbageCollector[Transport]] =
    chan[GarbageCollector[Transport]](config.garbageCollectorAddresses(index),
                                      GarbageCollector.serializer)

  // Channels to the proposers.
  private val proposers: Seq[Chan[Proposer[Transport]]] =
    for (a <- config.proposerAddresses)
      yield chan[Proposer[Transport]](a, Proposer.serializer)

  // Channels to the other replicas.
  private val otherReplicas: Seq[Chan[Replica[Transport]]] =
    for (a <- config.replicaAddresses if a != address)
      yield chan[Replica[Transport]](a, Replica.serializer)

  // The number of committed commands that the replica has received since the
  // last time it sent a GarbageCollect message to the garbage collectors.
  // Every `options.sendWatermarkEveryNCommands` commands, this value is reset
  // and GarbageCollect messages are sent.
  @JSExport
  protected var numCommandsPendingWatermark: Int = 0

  // The number of committed commands that are in the graph that have not yet
  // been processed. We process the graph every `options.executeGraphBatchSize`
  // committed commands and every `options.executeGraphTimerPeriod` seconds. If
  // the timer expires, we clear this number.
  @JSExport
  protected var numCommandsPendingExecution: Int = 0

  // The committed commands. Recall that logically, commands forms a
  // two-dimensional array indexed by leader index and id.
  //
  //            .   .   .
  //            .   .   .
  //            .   .   .
  //          +---+---+---+
  //        3 |   |   | f |
  //          +---+---+---+
  //        2 |   | c | e |
  //     i    +---+---+---+
  //     d  1 | a |   |   |
  //          +---+---+---+
  //        0 | b |   | d |
  //          +---+---+---+
  //            0   1   2
  //          leader index
  //
  // We represent this log as a map, where commands[VertexId(leaderIndex, id)]
  // stores entry found in row id and column leaderIndex in the array.
  //
  // TODO(mwhittaker): Garbage collect commands. This is challenging. It will
  // likely involve checkpointing prefixes of the dependency graph. The tricky
  // bit is that a prefix of the graph is not a prefix of the array.
  val commands = mutable.Map[VertexId, Committed]()

  // `committedVertices` stores the set of vertex ids that appear in
  // `commands`. It should be identical to commands.keys except that it is
  // compact.
  @JSExport
  protected val committedVertices = VertexIdPrefixSet(
    config.leaderAddresses.size
  )

  // The client table, which records the latest commands for each client.
  implicit val addressSerializer = transport.addressSerializer
  @JSExport
  protected val clientTable =
    ClientTable[(Transport#Address, ClientPseudonym), Array[Byte]]()

  // If a replica commits a command in vertex A with a dependency on uncommitted
  // vertex B, then the replica sets a timer to recover vertex B. This prevents
  // a vertex from being forever stalled.
  @JSExport
  protected val recoverVertexTimers = mutable.Map[VertexId, Transport#Timer]()

  // A timer to execute the dependency graph. If the batch size is 1 or if
  // graph execution is disabled, then there is no need for the timer.
  @JSExport
  protected val executeGraphTimer: Option[Transport#Timer] =
    if (options.executeGraphBatchSize == 1 ||
        options.unsafeSkipGraphExecution) {
      None
    } else {
      lazy val t: Transport#Timer = timer(
        "executeGraphTimer",
        options.executeGraphTimerPeriod,
        () => {
          metrics.executeGraphTimerTotal.inc()
          execute()
          numCommandsPendingExecution = 0
          t.start()
        }
      )
      t.start()
      Some(t)
    }

  // TODO(mwhittaker): Set an offset timer to start the periodic GC timer.
  // TODO(mwhittaker): Set a GC timer that every time it goes off, it takes
  // snapshots, contacts the CAS leader to get its watermark chosen. When the
  // future is fulfilled, if the watermark is the same, throw away old stuff.
  // TODO(mwhittaker): Switch states to buffer map.
  // TODO(mwhittaker): Change handle recover to return a snapshot if the needed
  // thing is old.
  // TODO(mwhittaker): Add handle snapshot function to swap out a snapshot.

  // Helpers ///////////////////////////////////////////////////////////////////
  private def timed[T](label: String)(e: => T): T = {
    if (options.measureLatencies) {
      val startNanos = System.nanoTime
      val x = e
      val stopNanos = System.nanoTime
      metrics.requestsLatency
        .labels(label)
        .observe((stopNanos - startNanos).toDouble / 1000000)
      x
    } else {
      e
    }
  }

  // Add a committed command to `commands`. The reason this is pulled into a
  // helper function is to ensure that we don't forget to also update
  // `committedVertices`.
  private def recordCommitted(
      vertexId: VertexId,
      committed: Committed
  ): Unit = {
    commands(vertexId) = committed
    committedVertices.add(vertexId)
  }

  private def execute(): Unit = {
    val executable: Seq[VertexId] = dependencyGraph.execute()
    metrics.executeGraphTotal.inc()
    metrics.dependencyGraphNumVertices.set(dependencyGraph.numVertices)

    for (v <- executable) {
      import Proposal.Value
      commands.get(v) match {
        case None =>
          logger.fatal(
            s"Vertex $v is ready for execution but the replica doesn't have " +
              s"a Committed entry for it."
          )

        case Some(committed: Committed) =>
          executeProposal(v, committed.proposal)
      }
    }
  }

  private def executeProposal(
      vertexId: VertexId,
      proposal: Proposal
  ): Unit = {
    import Proposal.Value

    proposal.value match {
      case Value.Empty =>
        logger.fatal("Empty Proposal.")

      case Value.Noop(Noop()) =>
        metrics.executedNoopsTotal.inc()

      case Value.Snapshot(_) =>
        metrics.executedSnapshotsTotal.inc()
        // TODO(mwhittaker): Implement.
        ???

      case Value.Command(command) =>
        val clientAddress = transport.addressSerializer.fromBytes(
          command.clientAddress.toByteArray
        )
        val clientIdentity = (clientAddress, command.clientPseudonym)
        clientTable.executed(clientIdentity, command.clientId) match {
          case ClientTable.Executed(None) =>
            // Don't execute the same command twice.
            metrics.repeatedCommandsTotal.inc()

          case ClientTable.Executed(Some(output)) =>
            // Don't execute the same command twice. Also, replay the output
            // to the client.
            metrics.repeatedCommandsTotal.inc()
            val client =
              chan[Client[Transport]](clientAddress, Client.serializer)
            client.send(
              ClientInbound().withClientReply(
                ClientReply(clientPseudonym = command.clientPseudonym,
                            clientId = command.clientId,
                            result = ByteString.copyFrom(output))
              )
            )

          case ClientTable.NotExecuted =>
            val output = stateMachine.run(command.command.toByteArray)
            clientTable.execute(clientIdentity, command.clientId, output)
            metrics.executedCommandsTotal.inc()

            // TODO(mwhittaker): Think harder about if this is live.
            if (index == vertexId.leaderIndex % config.replicaAddresses.size) {
              val client =
                chan[Client[Transport]](clientAddress, Client.serializer)
              client.send(
                ClientInbound().withClientReply(
                  ClientReply(clientPseudonym = command.clientPseudonym,
                              clientId = command.clientId,
                              result = ByteString.copyFrom(output))
                )
              )
            }
        }
    }
  }

  // Timers ////////////////////////////////////////////////////////////////////
  private def makeRecoverVertexTimer(vertexId: VertexId): Transport#Timer = {
    lazy val t: Transport#Timer = timer(
      s"recoverVertex [$vertexId]",
      Util.randomDuration(options.recoverVertexTimerMinPeriod,
                          options.recoverVertexTimerMaxPeriod),
      () => {
        metrics.recoverVertexTotal.inc()

        // Sanity check.
        commands.get(vertexId) match {
          case Some(_) =>
            logger.fatal(
              s"Replica recovering vertex $vertexId, but that vertex is " +
                s"already committed."
            )
          case None =>
        }

        // Send a recover message to a randomly selected leader. We randomly
        // select a leader to avoid dueling leaders.
        //
        // TODO(mwhittaker): Send the recover message intially to the proposer
        // that led the instance. Only if that proposer is dead should we send
        // to another proposer.
        val proposer = proposers(rand.nextInt(proposers.size))
        proposer.send(
          ProposerInbound().withRecover(
            Recover(vertexId = vertexId)
          )
        )

        // We also send recovery messages to all other replicas. If proposers
        // have garbage collected the vertex that we're trying to recover,
        // they'll ignore us, so it's up to us to contact the replicas.
        otherReplicas.foreach(
          _.send(ReplicaInbound().withRecover(Recover(vertexId = vertexId)))
        )

        t.start()
      }
    )
    t.start()
    t
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(
      src: Transport#Address,
      inbound: ReplicaInbound
  ): Unit = {
    import ReplicaInbound.Request

    val label = inbound.request match {
      case Request.Commit(_)  => "Commit"
      case Request.Recover(_) => "Recover"
      case Request.Empty => {
        logger.fatal("Empty ReplicaInbound encountered.")
      }
    }
    metrics.requestsTotal.labels(label).inc()

    timed(label) {
      inbound.request match {
        case Request.Commit(r)  => handleCommit(src, r)
        case Request.Recover(r) => handleRecover(src, r)
        case Request.Empty => {
          logger.fatal("Empty ReplicaInbound encountered.")
        }
      }
    }
  }

  private def handleCommit(
      src: Transport#Address,
      commit: Commit
  ): Unit = {
    // If we've already recorded this command as committed, don't record it as
    // committed again.
    if (commands.contains(commit.vertexId)) {
      return
    }

    // Record the committed command.
    val dependencies = timed("Commit/parseDependencies") {
      VertexIdPrefixSet.fromProto(commit.dependencies)
    }
    timed("Commit/recordCommitted") {
      recordCommitted(commit.vertexId, Committed(commit.proposal, dependencies))
    }
    metrics.dependencies.observe(dependencies.size)
    metrics.uncompactedDependencies.observe(dependencies.uncompactedSize)

    if (options.unsafeDontRecover) {
      // Don't fuss with timers.
    } else {
      // Stop any recovery timer for the current vertex, and start recovery
      // timers for any uncommitted vertices on which we depend.
      recoverVertexTimers.get(commit.vertexId).foreach(_.stop())
      recoverVertexTimers -= commit.vertexId

      val uncommittedDependencies = timed("Commit/uncommittedDependencies") {
        dependencies.diff(committedVertices)
      }
      val materialized = timed("Commit/materialize") {
        uncommittedDependencies.materialize()
      }
      val timerDependencies = timed("Commit/timerDependencies") {
        materialized.filter(!recoverVertexTimers.contains(_))
      }
      metrics.uncommittedDependencies.observe(materialized.size)
      metrics.timerDependencies.observe(timerDependencies.size)

      timed("Commit/startTimers") {
        for (v <- timerDependencies) {
          recoverVertexTimers(v) = makeRecoverVertexTimer(v)
        }
      }
    }

    // If we're skipping the graph, execute the command right away. Otherwise,
    // commit the command to the dependency graph and execute the graph if we
    // have sufficiently many commands pending execution.
    if (options.unsafeSkipGraphExecution) {
      timed("Commit/unsafeExecuteCommand") {
        executeProposal(commit.vertexId, commit.proposal)
      }
    } else {
      timed("Commit/dependencyGraphCommit") {
        dependencyGraph.commit(commit.vertexId, (), dependencies)
      }
      numCommandsPendingExecution += 1
      if (numCommandsPendingExecution % options.executeGraphBatchSize == 0) {
        timed("Commit/execute") { execute() }
        numCommandsPendingExecution = 0
        executeGraphTimer.foreach(_.reset())
      }
    }

    // Send GarbageCollect messages if needed.
    numCommandsPendingWatermark += 1
    if (numCommandsPendingWatermark % options.sendWatermarkEveryNCommands == 0) {
      timed("Commit/sendGarbageCollect") {
        garbageCollector.send(
          GarbageCollectorInbound().withGarbageCollect(
            GarbageCollect(replicaIndex = index,
                           frontier = committedVertices.getWatermark())
          )
        )
      }
      numCommandsPendingWatermark = 0
    }
  }

  private def handleRecover(
      src: Transport#Address,
      recover: Recover
  ): Unit = {
    commands.get(recover.vertexId) match {
      case None =>
      // If we don't have the vertex, we simply ignore the message.

      case Some(committed: Committed) =>
        val replica = chan[Replica[Transport]](src, Replica.serializer)
        replica.send(
          ReplicaInbound().withCommit(
            Commit(vertexId = recover.vertexId,
                   proposal = committed.proposal,
                   dependencies = committed.dependencies.toProto)
          )
        )
    }
  }
}