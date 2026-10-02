#!/usr/bin/env python3
"""Read exact HXA-241 class/method evidence using the project's existing acceptance parsers."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
from instrumentation_junit import parse, to_xml
spec=importlib.util.spec_from_file_location('acceptance_matrix',ROOT/'scripts/run-acceptance-matrix.py')
matrix=importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


def summarize(directory):
    requested=json.loads((directory/'classes.json').read_text())['classes']
    flavor=directory.name.split('-',1)[0]
    expected,sources=matrix.methods_for(requested,flavor)
    records={}
    classes={}
    for cls in requested:
        raw=(directory/'logs'/f'{cls}.log').read_text()
        parsed=parse(raw)
        if any(c!=cls for c,m in parsed) or set(records).intersection(parsed):
            raise ValueError('Wrong or duplicate test identities')
        records.update(parsed)
        classes[cls]={'tests':len(parsed),'passed':sum(code==0 for code,_ in parsed.values()),
                      'failed':sum(code in (-1,-2) for code,_ in parsed.values()),
                      'skipped':sum(code in (-3,-4) for code,_ in parsed.values())}
    actual={c+'#'+m for c,m in records}
    if actual!=set(expected):
        raise ValueError('Executed methods differ from source manifest: '+str(actual.symmetric_difference(expected)))
    if any(code!=0 for code,_ in records.values()):
        raise ValueError('Final acceptance has non-passing methods')
    (directory/'verified-methods.xml').write_text(to_xml(records))
    result={'flavor':flavor,'classes':classes,'class_count':len(classes),'method_count':len(records),
            'passed':len(records),'failed':0,'skipped':0,'sources':sources,
            'apk_identity':json.loads((directory/'apk-identity.json').read_text()),
            'method_ids':sorted(actual),'directory':str(directory.relative_to(ROOT))}
    (directory/'verified-methods.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('directories',nargs='+',type=Path)
    a=p.parse_args()
    reports=[summarize((ROOT/d).resolve()) for d in a.directories]
    print(json.dumps({'reports':[{k:r[k] for k in ('flavor','class_count','method_count','passed','failed','skipped','directory')} for r in reports],
                      'unique_methods':len(set().union(*(set(r['method_ids']) for r in reports)))},indent=2))
