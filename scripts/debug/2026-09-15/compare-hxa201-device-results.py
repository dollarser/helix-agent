"""Parse raw instrumentation cases and compare a captured baseline with the current run."""
from pathlib import Path
import json,sys

def cases(path):
    result={};fields={};last=None
    for line in Path(path).read_text().splitlines():
        prefix='INSTRUMENTATION_STATUS: '
        if line.startswith(prefix):
            name,_,value=line[len(prefix):].partition('=');fields[name]=value;last=name
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(': ',1)[1])
            if code!=1 and 'class' in fields and 'test' in fields:
                result[fields['class']+'#'+fields['test']]={'code':code,'stack':fields.get('stack','')}
            fields={};last=None
        elif last=='stack':fields[last]+='\n'+line
    if not result:raise RuntimeError('No cases parsed')
    return result
if __name__=='__main__':
    baseline=cases(sys.argv[1]);current=cases(sys.argv[2]) if len(sys.argv)>2 else {}
    bad=lambda v:v['code'] not in [0,-3,-4]
    report={'baseline_cases':len(baseline),'current_cases':len(current),
            'baseline_failed':{k:v for k,v in baseline.items() if bad(v)},
            'current_failed':{k:v for k,v in current.items() if bad(v)},
            'new_failures':{k:v for k,v in current.items() if bad(v) and (k not in baseline or not bad(baseline[k]))},
            'matched_failures':{k:v for k,v in current.items() if bad(v) and k in baseline and bad(baseline[k])}}
    print(json.dumps(report,indent=2))
