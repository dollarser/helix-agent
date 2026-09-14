"""Offline phase comparison only. Descriptor persistence is not proof of a leak's owner."""
import argparse
import collections
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
args = parser.parse_args()
rows = [json.loads(line) for line in args.evidence.read_text().splitlines() if line.strip()]
if not rows:
    raise SystemExit('Empty FD evidence')
identity = {(r['runId'], r['pid'], r['workload']) for r in rows}
if len(identity) != 1:
    raise SystemExit('Mixed run / process / workload evidence is not comparable')


def keys(row):
    return {(d['fd'], d.get('device'), d.get('inode'), d.get('targetHash'))
            for d in row['descriptors'] if 'unavailableErrno' not in d}


baseline = keys(rows[0])
previous = baseline
summary = []
for row in rows:
    current = keys(row)
    summary.append({
        'phase': row['phase'], 'cycle': row['cycle'], 'count': row['count'],
        'kinds': dict(collections.Counter(d.get('kind', 'unavailable') for d in row['descriptors'])),
        'appearedSincePrevious': len(current - previous),
        'absentSincePrevious': len(previous - current),
        'newSinceBaseline': len(current - baseline),
        'snapshotIncomplete': row['truncated'] or row['count'] is None or
                              any('unavailableErrno' in d for d in row['descriptors']),
    })
    previous = current
print(json.dumps({'runId': rows[0]['runId'], 'workload': rows[0]['workload'], 'phases': summary,
                  'interpretation': 'Snapshot identities may be reused between samples. Goldfish type does not establish cause. '
                                    'Compare same-APK independent idle/storage/notification/activity/combined arms; '
                                    'this output never changes resource gates or returns a leak verdict.'}, indent=2))
