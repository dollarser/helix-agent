"""Convert Android am instrument -r status bundles; never infer individual passes from totals."""
import re
import xml.etree.ElementTree as ET


def parse(raw):
    bundle, records, started = {}, {}, set()
    for line in raw.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                bundle[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            # Instrumentation.sendStatus(0, {stream: diagnostic}) is not a test
            # terminal. Accept only this exact diagnostic shape; it creates no pass.
            if code == 0 and set(bundle) == {'stream'}:
                bundle = {}
                continue
            key = (bundle.get('class'), bundle.get('test'))
            if not all(key):
                raise ValueError('Missing test identity in status bundle')
            if code == 1:
                if key in started or key in records:
                    raise ValueError('Duplicate test start')
                started.add(key)
            elif code in (0, -1, -2, -3, -4):
                if key in records or (key not in started and code != -3):
                    raise ValueError('Duplicate or unstarted test terminal')
                records[key] = (code, bundle.get('stack', bundle.get('stream', '')))
                started.discard(key)
            else:
                raise ValueError('Unsupported instrumentation status')
            bundle = {}
    if started or not records or bundle:
        raise ValueError('Incomplete or empty instrumentation')
    if not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', raw, re.M):
        raise ValueError('Missing successful runner termination')
    if 'INSTRUMENTATION_FAILED' in raw or 'Process crashed' in raw:
        raise ValueError('Runner failed')
    return records


def to_xml(records):
    suite = ET.Element('testsuite', name='android-instrumentation', tests=str(len(records)))
    for (class_name, method), (code, detail) in sorted(records.items()):
        case = ET.SubElement(suite, 'testcase', classname=class_name, name=method)
        if code != 0:
            tag = 'skipped' if code in (-3, -4) else 'error' if code == -1 else 'failure'
            ET.SubElement(case, tag, message=detail).text = detail
    return ET.tostring(suite, encoding='unicode') + '\n'


def require_passed(raw):
    """Validate actual per-method outcomes, without a hard-coded suite size."""
    records = parse(raw)
    if any(code != 0 for code, _ in records.values()) or 'FAILURES!!!' in raw:
        raise ValueError('Instrumentation contains failed or skipped tests')
    totals = {int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS: numtests=(\d+)\s*$', raw, re.M)}
    if totals and totals != {len(records)}:
        raise ValueError('Declared instrumentation total differs from observed methods')
    return records


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    import sys

    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('log', nargs='?', help='Raw am instrument -w -r log; defaults to stdin')
    args = arguments.parse_args()
    try:
        raw = Path(args.log).read_text() if args.log else sys.stdin.read()
        records = require_passed(raw)
    except (OSError, ValueError) as failure:
        arguments.exit(1, f'Instrumentation verification failed: {failure}\n')
    print(f'Instrumentation verification passed: {len(records)} methods')
