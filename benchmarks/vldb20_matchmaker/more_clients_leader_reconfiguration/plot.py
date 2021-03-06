# See https://stackoverflow.com/a/19521297/3187068
import matplotlib
matplotlib.use('pdf')
font = {'size': 14}
matplotlib.rc('font', **font)

from ... import pd_util
from typing import Any, List, Tuple
import argparse
import datetime
import matplotlib.pyplot as plt
import numpy as np
import os
import pandas as pd
import re


def read_data(file,
              drop_head: float,
              drop_tail: float) -> Tuple[pd.DataFrame, Any]:
    # Read the data.
    df = pd.read_csv(file, parse_dates=['start', 'stop'])

    # Chop off the head and tail.
    start_time = df['start'].iloc[0]
    end_time = df['start'].iloc[-1]
    new_start_time = start_time + pd.DateOffset(seconds=drop_head)
    new_end_time = end_time - pd.DateOffset(seconds=drop_tail)
    df = df[df['start'] >= new_start_time]
    df = df[df['start'] <= new_end_time]

    # Normalize the times so all data fits on same axes.
    df['delta'] = pd.Timestamp(0) + (df['start'] - new_start_time)
    df.index = df['delta']

    return (df, new_start_time)


def plot_throughput(ax: plt.Axes, s: pd.Series, sample_every: int) -> None:
    tput = pd_util.throughput(s, 1000, trim=True)[::sample_every]
    ax.plot_date(tput.index, tput, fmt='-', label='100 clients')


def plot_latency(ax: plt.Axes, s: pd.Series, sample_every: int) -> None:
    median = s.rolling('1000ms').median()
    p95 = s.rolling('1000ms').quantile(0.95)
    line = ax.plot_date(s.index[::sample_every],
                        median[::sample_every],
                        label='100 clients',
                        fmt='-')[0]
    ax.fill_between(s.index[::sample_every],
                    median[::sample_every],
                    p95[::sample_every],
                    color=line.get_color(),
                    alpha=0.25)


def plot(df: pd.DataFrame,
         output_filename: str,
         start_time,
         sample_every: int) -> None:
    # Create figure.
    num_plots = 2
    fig, ax = plt.subplots(num_plots, 1, figsize=(6.4, num_plots * 4.8 * 0.5),
                           sharex=True)

    # Plot data.
    plot_latency(ax[0], df['latency_nanos'] / 1e6, sample_every)
    plot_throughput(ax[1], df['delta'], sample_every)

    # Format x ticks nicely.
    for axes in ax:
        axes.grid()
        # axes.legend(loc='best')
        axes.xaxis.set_major_formatter(matplotlib.dates.DateFormatter('%-M:%S'))
        for label in axes.get_xticklabels():
            label.set_ha('left')
            label.set_rotation(-20)

    # Draw vertical reconfiguration lines. The times are hardcoded and taken
    # from the Driver's output files. I know that's super jank, but oh well.
    origin = datetime.datetime(1970, 1, 1, second=0)
    naive_start_time = start_time.to_pydatetime().replace(tzinfo=None)
    reconfigurations = [
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=38, microsecond=268000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=39, microsecond=270000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=40, microsecond=272000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=41, microsecond=274000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=42, microsecond=275000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=43, microsecond=277000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=44, microsecond=279000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=45, microsecond=281000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=46, microsecond=283000),
        datetime.datetime(2020, 6, 22, hour=16, minute=48, second=47, microsecond=285000),
    ]
    failure = datetime.datetime(
            2020, 6, 22, hour=16, minute=48, second=52, microsecond=264000)
    recover = datetime.datetime(
            2020, 6, 22, hour=16, minute=48, second=57, microsecond=264000)

    for axes in ax:
        for t in reconfigurations:
            axes.axvline(x=origin + (t - naive_start_time), color='black')
        axes.axvline(x=origin + (failure - naive_start_time), color='red',
                     ls='--')
        axes.axvline(x=origin + (recover - naive_start_time), color='black')

    # Save figures.
    ax[1].set_xlabel('Time')
    ax[0].set_ylabel('Latency (ms)')
    ax[1].set_ylabel('Throughput\n(cmds/second)')
    fig.set_tight_layout(True)
    fig.savefig(output_filename, bbox_inches='tight')
    print(f'Wrote plot to {output_filename}.')


def main(args) -> None:
    # Read the data.
    (df, start_time) = read_data(args.f1n100, args.drop_head, args.drop_tail)

    # Plot the data.
    plot(df=df,
         output_filename=args.output,
         start_time=start_time,
         sample_every=args.sample_every)


def get_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()

    parser.add_argument(
        '--drop_head',
        type=float,
        default=0,
        help='Drop this number of seconds from the head of the benchmark.')
    parser.add_argument(
        '--drop_tail',
        type=float,
        default=0,
        help='Drop this number of seconds from the tail of the benchmark.')
    parser.add_argument(
        '--sample_every',
        type=int,
        default=1,
        help='Sample every n.')

    parser.add_argument('--f1n100',
                        type=argparse.FileType('r'),
                        help='f=1, n=100 data.csv file')
    parser.add_argument('--output',
                        type=str,
                        default='vldb_more_clients_leader_reconfiguration.pdf',
                        help='Output filename')
    return parser


if __name__ == '__main__':
    parser = get_parser()
    main(parser.parse_args())
