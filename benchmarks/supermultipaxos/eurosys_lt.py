from .supermultipaxos import *


def main(args) -> None:
    class EuroSysLtSuperMultiPaxosSuite(SuperMultiPaxosSuite):
        def args(self) -> Dict[Any, Any]:
            return vars(args)

        def inputs(self) -> Collection[multipaxos.Input]:
            return [
                multipaxos.Input(
                    f = 1,
                    num_client_procs = num_client_procs,
                    num_warmup_clients_per_proc = num_clients_per_proc,
                    num_clients_per_proc = num_clients_per_proc,
                    num_batchers = num_batchers,
                    num_leaders = 3,
                    num_proxy_leaders = 3,
                    num_acceptor_groups = 1,
                    num_replicas = 3,
                    num_proxy_replicas = 3,
                    distribution_scheme = \
                            multipaxos.DistributionScheme.COLOCATED,
                    client_jvm_heap_size = '16g',
                    batcher_jvm_heap_size = '32g',
                    leader_jvm_heap_size = '32g',
                    proxy_leader_jvm_heap_size = '32g',
                    acceptor_jvm_heap_size = '32g',
                    replica_jvm_heap_size = '32g',
                    proxy_replica_jvm_heap_size = '32g',
                    warmup_duration = datetime.timedelta(seconds=5),
                    warmup_timeout = datetime.timedelta(seconds=10),
                    warmup_sleep = datetime.timedelta(seconds=5),
                    duration = datetime.timedelta(seconds=8),
                    timeout = datetime.timedelta(seconds=13),
                    client_lag = datetime.timedelta(seconds=5),
                    state_machine = 'Noop',
                    workload = workload.StringWorkload(size_mean=1, size_std=0),
                    profiled = args.profile,
                    monitored = args.monitor,
                    prometheus_scrape_interval =
                        datetime.timedelta(milliseconds=200),
                    batcher_options = multipaxos.BatcherOptions(
                        batch_size = batch_size,
                    ),
                    batcher_log_level = args.log_level,
                    leader_options = multipaxos.LeaderOptions(
                        resend_phase1as_period = datetime.timedelta(seconds=60),
                        flush_phase2as_every_n = leader_flush_every_n,
                        election_options = multipaxos.ElectionOptions(
                            ping_period = datetime.timedelta(seconds=60),
                            no_ping_timeout_min = \
                                datetime.timedelta(seconds=120),
                            no_ping_timeout_max = \
                                datetime.timedelta(seconds=240),
                        ),
                    ),
                    leader_log_level = args.log_level,
                    proxy_leader_options = multipaxos.ProxyLeaderOptions(
                        flush_phase2as_every_n = proxy_leader_flush_every_n,
                    ),
                    proxy_leader_log_level = args.log_level,
                    acceptor_options = multipaxos.AcceptorOptions(),
                    acceptor_log_level = args.log_level,
                    replica_options = multipaxos.ReplicaOptions(
                        log_grow_size = 5000,
                        unsafe_dont_use_client_table = False,
                        send_chosen_watermark_every_n_entries = 100000,
                        recover_log_entry_min_period = \
                            datetime.timedelta(seconds=120),
                        recover_log_entry_max_period = \
                            datetime.timedelta(seconds=240),
                        unsafe_dont_recover = False,
                    ),
                    replica_log_level = args.log_level,
                    proxy_replica_options = multipaxos.ProxyReplicaOptions(
                        flush_every_n = proxy_replica_flush_every_n,
                    ),
                    proxy_replica_log_level = args.log_level,
                    client_options = multipaxos.ClientOptions(
                        resend_client_request_period = \
                            datetime.timedelta(seconds=120),
                    ),
                    client_log_level = args.log_level,
                )

                for (
                    num_client_procs,
                    num_clients_per_proc,
                    num_batchers,
                    batch_size,
                    leader_flush_every_n,
                    proxy_leader_flush_every_n,
                    proxy_replica_flush_every_n
                ) in [
                    ( 1,   1, 0, 0, 1, 1, 1),
                    ( 1,  10, 0, 0, 5, 1, 1),
                    ( 5,  10, 0, 0, 10, 1, 1),
                    ( 5,  20, 0, 0, 10, 10, 10),
                    ( 6,  50, 0, 0, 10, 10, 10),
                    ( 6, 100, 0, 0, 10, 10, 10),
                    (10, 100, 0, 0, 10, 10, 10),

                    ( 1,   1, 3, 1, 1, 1, 1),
                    ( 1,  10, 3, 5, 1, 1, 1),
                    ( 5,  10, 3, 10, 1, 1, 1),
                    ( 5,  20, 3, 10, 1, 1, 10),
                    ( 6,  50, 3, 10, 1, 1, 10),
                    ( 6, 100, 3, 10, 1, 1, 10),
                    (10, 100, 3, 10, 1, 1, 10),
                    (20, 100, 3, 20, 1, 1, 20),
                    (20, 200, 3, 40, 1, 1, 40),
                ]
            ] * 3

        def summary(self, input: multipaxos.Input,
                    output: multipaxos.Output) -> str:
            return str({
                'f':
                    input.f,
                'num_client_procs':
                    input.num_client_procs,
                'num_clients_per_proc':
                    input.num_clients_per_proc,
                'num_batchers':
                    input.num_batchers,
                'batch_size':
                    input.batcher_options.batch_size,
                'leader_flush_every_n':
                    input.leader_options.flush_phase2as_every_n,
                'proxy_leader_flush_every_n':
                    input.proxy_leader_options.flush_phase2as_every_n,
                'proxy_replica_flush_every_n':
                    input.proxy_replica_options.flush_every_n,
                'latency.median_ms':
                    f'{output.latency.median_ms:.6}',
                'start_throughput_1s.p90':
                    f'{output.start_throughput_1s.p90:.7}',
            })

    suite = EuroSysLtSuperMultiPaxosSuite()
    with benchmark.SuiteDirectory(args.suite_directory,
                                  'super_multipaxos_eurosys_lt') as dir:
        suite.run_suite(dir)


if __name__ == '__main__':
    main(get_parser().parse_args())
