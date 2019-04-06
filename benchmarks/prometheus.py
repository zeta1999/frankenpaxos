from typing import Dict, FrozenSet, Tuple
import pandas as pd
import requests
import subprocess
import time

class PrometheusServer:
    """A queryable Prometheus server.

    PrometheusServer can be used to execute PromQL [1] queries against
    Prometheus data stored in a data directory named `tsdb_path`. For example,
    imagine we have Prometheus data stored in `data/`. We can do the following:

        with PrometheusServer('data/') as prometheus:
            df = prometheus.query('up[24h]')

    `df` is the result of running the PromQL query `up[24h]` and might look
    something like this:

                                       <metric_name>
        2019-04-05 18:51:08.917000055  1
        2019-04-05 18:51:09.117000103  1
        2019-04-05 18:51:09.316999912  1
        2019-04-05 18:51:09.516999960  1
        2019-04-05 18:51:09.717000008  1
        2019-04-05 18:51:09.917000055  1

    `df` is a DataFrame, indexed by time, with one column per returned metric.
    Every column is labeled with a frozenset of the corresponding Prometheus
    labels. For example, a metric name might look something like this:

        frozenset({(__name__, up), (job: foo)(instance, 10.0.0.1:8000)})

    [1]: https://prometheus.io/docs/prometheus/latest/querying/basics/
    """

    def __init__(self, tsdb_path: str) -> None:
        # We launch prometheus with an empty configuration file.
        empty_prometheus_config = '/tmp/empty_prometheus.yml'
        with open(empty_prometheus_config, 'w') as f:
            f.write('')

        # We run prometheus on an arbitrary port.
        self.address = 'localhost:12345'
        cmd = [
            'prometheus',
            f'--config.file={empty_prometheus_config}',
            f'--storage.tsdb.path={tsdb_path}',
            f'--web.listen-address={self.address}',
        ]
        self.proc = subprocess.Popen(cmd,
                                     stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL)

    def __enter__(self) -> 'PrometheusServer':
        return self

    def __exit__(self, cls, exn, traceback) -> None:
        self.proc.terminate()

    def query(self, q: str) -> pd.DataFrame:
        """
        If we query a prometheus server right after we start it, it may not
        be ready to receive HTTP requests yet. So, if we fail to connect, we
        sleep a bit and try again.
        """
        num_retries = 10
        for i in range(num_retries - 1):
            try:
                return self._query_once(q)
            except (ConnectionRefusedError,
                    requests.exceptions.ConnectionError):
                time.sleep(i * 0.1)
        return self._query_once(q)

    def _query_once(self, q: str) -> pd.DataFrame:
        """
        See [1] for what Prometheus results look like.

        [1]: https://prometheus.io/docs/prometheus/latest/querying/api/
        """
        r = requests.get(f'http://{self.address}/api/v1/query?query={q}').json()
        if r['status'] == 'success':
            pass
        elif r['status'] == 'error':
            raise ValueError(f'Query "{q}" resulted in error: {r["error"]}.')
        else:
            raise ValueError(f'Unknown status {r["status"]}')

        series: Dict[FrozenSet[Tuple[str, str]], pd.Series] = {}
        for stream in r['data']['result']:
            if r['data']['resultType'] == 'matrix':
                values = stream['values']
            else:
                values = [stream['value']]

            timestamps = [t for [t, x] in values]
            timestamps = pd.to_datetime(timestamps, unit='s', origin='unix')
            values = [x for [t, x] in values]
            s = pd.Series(values, index=timestamps)
            series[frozenset(stream['metric'].items())] = s

        return pd.DataFrame(series)
