from .matchmakermultipaxos import *


def main(args) -> None:
    class AblationSuite(MatchmakerMultiPaxosSuite):
        def args(self) -> Dict[Any, Any]:
            return vars(args)

        def inputs(self) -> Collection[Input]:
            return ([
                Input(
                    f = 1,
                    num_client_procs = num_client_procs,
                    num_warmup_clients_per_proc = num_clients_per_proc,
                    num_clients_per_proc = num_clients_per_proc,
                    num_leaders = 2,
                    num_matchmakers = 3,
                    num_reconfigurers = 2,
                    num_acceptors = 6,
                    num_replicas = 3,
                    client_jvm_heap_size = '15g',
                    leader_jvm_heap_size = '15g',
                    matchmaker_jvm_heap_size = '15g',
                    reconfigurer_jvm_heap_size = '15g',
                    acceptor_jvm_heap_size = '15g',
                    replica_jvm_heap_size = '15g',
                    driver_jvm_heap_size = '15g',
                    warmup_duration = datetime.timedelta(seconds=10),
                    warmup_timeout = datetime.timedelta(seconds=15),
                    warmup_sleep = datetime.timedelta(seconds=5),
                    duration = datetime.timedelta(seconds=55),
                    timeout = datetime.timedelta(seconds=60),
                    client_lag = datetime.timedelta(seconds=5),
                    state_machine = 'Noop',
                    workload = workload.StringWorkload(size_mean=1, size_std=0),
                    driver_workload = \
                        driver_workload.LeaderReconfiguration(
                            reconfiguration_warmup_delay_ms = 15 * 1000,
                            reconfiguration_warmup_period_ms = 1000,
                            reconfiguration_warmup_num = 10,
                            reconfiguration_delay_ms = 35 * 1000,
                            reconfiguration_period_ms = 3000,
                            reconfiguration_num = 5,
                            failure_delay_ms = 1000 * 1000,
                            recover_delay_ms = 1000 * 1000,
                        ),
                    profiled = args.profile,
                    monitored = args.monitor,
                    prometheus_scrape_interval =
                        datetime.timedelta(milliseconds=200),
                    leader_options = LeaderOptions(
                        resend_match_requests_period = \
                            datetime.timedelta(seconds=60),
                        resend_reconfigure_period = \
                            datetime.timedelta(seconds=60),
                        resend_phase1as_period = \
                            datetime.timedelta(seconds=60),
                        resend_phase2as_period = \
                            datetime.timedelta(seconds=60),
                        resend_executed_watermark_requests_period = \
                            datetime.timedelta(seconds=60),
                        resend_persisted_period = \
                            datetime.timedelta(seconds=60),
                        resend_garbage_collects_period = \
                            datetime.timedelta(seconds=60),
                        send_chosen_watermark_every_n = 100,
                        stutter = 1000,
                        stall_during_matchmaking = stall_during_matchmaking,
                        stall_during_phase1 = stall_during_phase1,
                        disable_gc = disable_gc,
                        election_options = ElectionOptions(
                            ping_period = datetime.timedelta(seconds=60),
                            no_ping_timeout_min = \
                                datetime.timedelta(seconds=120),
                            no_ping_timeout_max = \
                                datetime.timedelta(seconds=240),
                        ),
                    ),
                    leader_log_level = args.log_level,
                    matchmaker_options = MatchmakerOptions(
                        match_request_delay = \
                            datetime.timedelta(milliseconds=match_delay_ms),
                    ),
                    matchmaker_log_level = args.log_level,
                    reconfigurer_options = ReconfigurerOptions(
                        resend_stops_period = \
                            datetime.timedelta(seconds=60),
                        resend_bootstraps_period = \
                            datetime.timedelta(seconds=60),
                        resend_match_phase1as_period = \
                            datetime.timedelta(seconds=60),
                        resend_match_phase2as_period = \
                            datetime.timedelta(seconds=60),
                    ),
                    reconfigurer_log_level = args.log_level,
                    acceptor_options = AcceptorOptions(
                        phase1a_delay = \
                            datetime.timedelta(milliseconds=phase1a_delay_ms),
                    ),
                    acceptor_log_level = args.log_level,
                    replica_options = ReplicaOptions(
                        log_grow_size = 5000,
                        unsafe_dont_use_client_table = False,
                        recover_log_entry_min_period = \
                            datetime.timedelta(seconds=120),
                        recover_log_entry_max_period = \
                            datetime.timedelta(seconds=240),
                        unsafe_dont_recover = False,
                    ),
                    replica_log_level = args.log_level,
                    client_options = ClientOptions(
                        resend_client_request_period = \
                            datetime.timedelta(seconds=120),
                        stutter = 1000,
                    ),
                    client_log_level = args.log_level,
                    driver_log_level = args.log_level,
                )
                for match_delay_ms in [250]
                for phase1a_delay_ms in [250]
                for (num_client_procs, num_clients_per_proc) in
                    # [(1, 1), (4, 1), (4, 2)]
                    [(4, 2)]
                for (stall_during_matchmaking,
                     stall_during_phase1,
                     disable_gc) in [
                    (True, True, True),
                    (True, True, False),
                    (True, False, False),
                    (False, False, False),
                ]
            ] * 1)[:]

        def summary(self, input: Input, output: Output) -> str:
            return str({
                'num_client_procs': input.num_client_procs,
                'num_clients_per_proc': input.num_clients_per_proc,
                'stall matchmaking': input.leader_options.stall_during_matchmaking,
                'stall phase1': input.leader_options.stall_during_phase1,
                'disable gc': input.leader_options.disable_gc,
                'median latency': f'{output.latency.median_ms:.6}',
                'throughput': f'{output.start_throughput_1s.p90:.6}',
            })

    suite = AblationSuite()
    with benchmark.SuiteDirectory(args.suite_directory,
                                  'matchmaker_multipaxos_ablation') as dir:
        suite.run_suite(dir)


if __name__ == '__main__':
    main(get_parser().parse_args())
