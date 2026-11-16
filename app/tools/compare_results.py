#!/usr/bin/env python3
"""Compare every displayed metric against core's full-frame reference batch output."""
import argparse
import csv
import json
from pathlib import Path
import re

p = argparse.ArgumentParser()
p.add_argument('--tag', default='lite')
a = p.parse_args()
folder = Path('logs/app2')
rows = {Path(row['clip']).stem: row for row in csv.DictReader((folder / 'baseline-metrics.csv').open())}
report = []
for clip in ('jump1', 'jump2', 'jump3'):
    actual = json.loads((folder / f'{a.tag}-{clip}.json').read_text())
    reference = rows[clip]
    expected = {key: ('cmj' if key == 'test' else
                      reference[key].split('|') if key == 'warnings' and reference[key] else
                      [] if key == 'warnings' else
                      float(reference[key]) if reference[key] else None) for key in actual}
    differences = {key: {'full': expected[key], 'candidate': value} for key, value in actual.items() if value != expected[key]}
    log = (folder / f'{a.tag}-{clip}.log').read_text()
    timing = re.search(r'Analysis elapsedMs=(\d+)(?: phase=(\w+) frames=(\d+))?', log)
    row = {'clip': clip, 'elapsed_ms': int(timing[1]), 'phase': timing[2],
           'flight_ms': actual['flight_time_ms'], 'differences': differences}
    report.append(row)
    print(f"{clip}: {row['elapsed_ms'] / 1000:.3f} s; flight {row['flight_ms']} ms; changed fields: {', '.join(differences) or 'none'}")
(folder / f'{a.tag}-comparison.json').write_text(json.dumps(report, indent=2) + '\n')
raise SystemExit(1 if any(row['differences'] for row in report) else 0)
