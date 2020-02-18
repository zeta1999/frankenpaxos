# See https://stackoverflow.com/a/19521297/3187068
import matplotlib
matplotlib.use('pdf')
font = {'size': 14}
matplotlib.rc('font', **font)

from typing import Any, List
import argparse
import matplotlib.pyplot as plt
import numpy as np
import os
import pandas as pd
import re


def add_num_clients(df: pd.DataFrame) -> pd.DataFrame:
    df['num_clients'] = df['num_client_procs'] * df['num_clients_per_proc']
    return df


def plot_latency_throughput(df: pd.DataFrame, ax: plt.Axes, marker: str,
                            label: str) -> None:
    grouped = df.groupby('num_clients')
    throughput = grouped['stop_throughput_1s.p90'].agg(np.mean).sort_index()
    latency = grouped['latency.median_ms'].agg(np.mean).sort_index()
    print(throughput)
    print(latency)
    ax.plot(throughput / 1000, latency, marker, label=label, linewidth=2)


def make_figure(output_filename: str, coupled_df: pd.DataFrame,
                mencius_df: pd.DataFrame,
                unreplicated_df: pd.DataFrame) -> None:
    fig, ax = plt.subplots(1, 1, figsize=(6.4, 4.8))

    print('Coupled Mencius')
    plot_latency_throughput(coupled_df, ax, '^-', 'Mencius')
    print('Mencius')
    plot_latency_throughput(mencius_df, ax, 'o-', 'Compartmentalized Mencius')
    print('Unreplicated')
    plot_latency_throughput(unreplicated_df, ax, 's-', 'Unreplicated')

    ax.set_title('')
    ax.set_xlabel('Throughput (thousands of commands per second)')
    ax.set_ylabel('Median latency (ms)')
    ax.legend(loc='best')
    ax.grid()
    fig.savefig(output_filename, bbox_inches='tight')
    print(f'Wrote plot to {output_filename}.')


def main(args) -> None:
    unbatched_coupled_df = add_num_clients(
        pd.read_csv(args.unbatched_coupled_mencius_results))
    unbatched_mencius_df = add_num_clients(
        pd.read_csv(args.unbatched_mencius_results))
    unbatched_unreplicated_df = add_num_clients(
        pd.read_csv(args.unbatched_unreplicated_results))
    batched_coupled_df = add_num_clients(
        pd.read_csv(args.batched_coupled_mencius_results))
    batched_mencius_df = add_num_clients(
        pd.read_csv(args.batched_mencius_results))
    batched_unreplicated_df = add_num_clients(
        pd.read_csv(args.batched_unreplicated_results))

    make_figure(
        args.output_unbatched,
        unbatched_coupled_df,
        unbatched_mencius_df,
        unbatched_unreplicated_df,
    )

    make_figure(
        args.output_batched,
        batched_coupled_df,
        batched_mencius_df,
        batched_unreplicated_df,
    )


def get_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()

    parser.add_argument('--unbatched_coupled_mencius_results',
                        type=argparse.FileType('r'),
                        help='Unbatched coupled Mencius results.csv file')
    parser.add_argument('--unbatched_mencius_results',
                        type=argparse.FileType('r'),
                        help='Unatched Mencius results.csv file')
    parser.add_argument('--unbatched_unreplicated_results',
                        type=argparse.FileType('r'),
                        help='Unbatched unreplicated results.csv file')

    parser.add_argument('--batched_coupled_mencius_results',
                        type=argparse.FileType('r'),
                        help='Batched coupled Mencius results.csv file')
    parser.add_argument('--batched_mencius_results',
                        type=argparse.FileType('r'),
                        help='Batched Mencius results.csv file')
    parser.add_argument('--batched_unreplicated_results',
                        type=argparse.FileType('r'),
                        help='Batched unreplicated results.csv file')

    parser.add_argument('--output_unbatched',
                        type=str,
                        default='eurosys_fig2_unbatched_lt.pdf',
                        help='Unbatched output filename')
    parser.add_argument('--output_batched',
                        type=str,
                        default='eurosys_fig2_batched_lt.pdf',
                        help='Batched output filename')

    return parser


if __name__ == '__main__':
    parser = get_parser()
    main(parser.parse_args())
