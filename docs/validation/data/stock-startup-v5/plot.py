#!/usr/bin/env python3
"""Plot observed finish-time errors; never draw across missing telemetry."""
import argparse
import csv
import json
import math
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("replay_directory", type=Path)
parser.add_argument("--out", type=Path, default=Path("."))
args = parser.parse_args()
HERE = args.replay_directory
OUT = args.out
OUT.mkdir(exist_ok=True, parents=True)

def read(name):
    with name.open() as f:
        return list(csv.DictReader(f))

old = read(HERE / "scooter-a-baseline.csv")
new = read(HERE / "scooter-a-candidate.csv")
second_old = read(HERE / "scooter-b-baseline.csv")
second_new = read(HERE / "scooter-b-candidate.csv")
summary = json.loads((HERE / "summary.json").read_text())["results"]
old_summary = summary["baseline"]["scooter-a"]
new_summary = summary["candidate"]["scooter-a"]
second_summary = {k: summary[k]["scooter-b"] for k in ["baseline", "candidate"]}

def select(rows, case):
    return [r for r in rows if r.get('case') == case and r.get('mode') == 'runtime']

cases = [
    ('SESSION_2', 'Standard · 88% start', 'Scooter A', None),
    ('SESSION_3', 'Standard · 60% start', 'Scooter A', None),
    ('WARM_PHYSICAL', 'Standard · 22% start, warm battery', 'Scooter A · two joined recordings', None),
    ('SESSION_9', 'Standard · 51% start', 'Scooter A · latest recording', None),
    ('SECOND', 'Standard · 1% start', 'Scooter B · Home Assistant export', None),
    ('SESSION_4', 'High-speed · 16% start', 'Scooter A · unchanged', None),
    ('SESSION_6', 'High-speed · 73% start', 'Scooter A · unchanged; missing interval', None),
]
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11,
    'axes.spines.top': False, 'axes.spines.right': False,
    'axes.edgecolor': '#cbd2da', 'axes.labelcolor': '#44515e',
    'xtick.color': '#44515e', 'ytick.color': '#44515e',
    'figure.facecolor': 'white', 'axes.facecolor': '#f8fafc'})
fig, axes = plt.subplots(4, 2, figsize=(14, 15))
fig.subplots_adjust(top=.825, bottom=.12, left=.075, right=.98, hspace=.8, wspace=.19)
fig.text(.075, .965, 'Charging estimates, replayed against the actual finish', fontsize=21, weight='bold')
fig.text(.075, .935, 'Above zero: predicts a later finish (conservative). Below zero: predicts an earlier finish (optimistic).', fontsize=12)
fig.legend(handles=[Line2D([], [], color='#8b96a5', lw=2, ls='--', label='Released v4'),
    Line2D([], [], color='#e52631', lw=2, label='Proposed v5'),
    Line2D([], [], color='#34495e', lw=1.3, label='Observed finish (zero)'),
    Line2D([], [], color='#b88a31', lw=1.3, ls=':', label='−10 min: historical benchmark')],
    loc='upper left', bbox_to_anchor=(.068, .916), ncol=4, frameon=False, fontsize=11)
fig.text(.075, .87, 'Charge done by now uses a 20-minute buffer. ETA curves and the historical 10-minute benchmark are unchanged.', fontsize=11, color='#44515e')

def vectors(rows, field, time):
    x, y = [], []
    previous = None
    for r in rows:
        if not r[field] or not r['truth_full'] or float(r['soc']) >= 100:
            continue
        t = float(r[time]) / 60000
        if previous is not None and (r['gap'] == '1' or t - previous > 25/60):
            x.append(math.nan); y.append(math.nan)
        x.append(t); y.append(float(r[field]) - float(r['truth_full']))
        previous = t
    return x, y

for ax, (case, title, detail, _) in zip(axes.flat, cases):
    if case == 'SECOND':
        pairs = [second_old, second_new]
        field, time = 'estimate', 'elapsed_ms'
        metrics = [second_summary[k]['all'] for k in ['baseline', 'candidate']]
    else:
        pairs = [select(old, case), select(new, case)]
        field, time = 'full', 'physical_elapsed_ms'
        metrics = [s[case]['runtime']['all'] for s in [old_summary, new_summary]]
    ax.axhline(0, color='#34495e', lw=1.1)
    ax.axhline(-10, color='#b88a31', lw=1.3, ls=':')
    for rows, color, style in zip(pairs, ['#8b96a5', '#e52631'], ['--', '-']):
        x, y = vectors(rows, field, time)
        ax.plot(x, y, color=color, ls=style, lw=1.6, zorder=3)
        ax.scatter([x[0]], [y[0]], color=color, s=23, zorder=4)
    ax.set_title(title, loc='left', fontsize=13, weight='bold', pad=28)
    ax.text(0, 1.04, detail, transform=ax.transAxes, fontsize=10.5, color='#657383')
    ax.set_ylim((-25, 55) if not case in ['SESSION_4', 'SESSION_6'] else (-12, 87) if case == 'SESSION_6' else (-12, 8))
    ax.grid(axis='both', color='#dfe5eb', lw=.6, alpha=.8)
    ax.set_xlabel('Minutes since first charging sample', fontsize=10)
    ax.set_ylabel('Finish error (min)', fontsize=10)
    ax.text(.98, .17 if case in ['SECOND', 'WARM_PHYSICAL'] else .95, f"Average absolute error: {metrics[0]['mae_minutes']:.1f} → {metrics[1]['mae_minutes']:.1f} min",
        transform=ax.transAxes, ha='right', va='top', fontsize=10,
        bbox={'facecolor': 'white', 'alpha': .86, 'edgecolor': 'none', 'pad': 3})
    if case == 'SESSION_6':
        ax.annotate('Initial low-power fallback: +82 min', xy=(0, 81.93), xytext=(5, 60), fontsize=10,
            arrowprops={'arrowstyle': '-', 'color': '#657383'})
        ax.axvspan(0, 881866/60000, color='#aab6c2', alpha=.1)
        ax.text(7.3, 20, '14m 42s\nno samples', ha='center', color='#657383', fontsize=10)

axes.flat[-1].axis('off')
axes.flat[-1].text(0, .95, 'What this replay can tell us', weight='bold', fontsize=14, va='top')
axes.flat[-1].text(0, .78,
    'Five completed standard charges; two high-speed charges.\n'
    'Three partial recordings have no observed full-charge time.\n\n'
    'Personal learning uses only earlier charges on the same scooter.\n'
    'Gaps stay missing. Averages count observed samples only.\n\n'
    'Completion means the first reported 100%.\n'
    'This is retrospective testing, including calibration recordings;\n'
    'it is not an independent scheduled-charging trial.', fontsize=10.5, va='top', linespacing=1.45)
fig.text(.075, .025, 'v5 improves conservative full-charge planning. Some mid-charge estimates become longer and less accurate.\n80% / 95% target estimates are unchanged; even the 20-minute buffer does not cover every lower-target error.', fontsize=11, color='#44515e')
for extension in ['png', 'pdf']:
    fig.savefig(OUT / f'charge-finish-replay.{extension}', dpi=150)
print(OUT / 'charge-finish-replay.png')
