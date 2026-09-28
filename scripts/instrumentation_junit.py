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
