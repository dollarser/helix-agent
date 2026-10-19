#!/usr/bin/env python3
"""Deterministic ASR error counts. Scores measure this corpus, not general accuracy."""
import argparse
import collections
import json
from pathlib import Path
import re
import statistics
import unicodedata


def normalize(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text).casefold().replace('<unk>', '\ue000')
                   if not c.isspace() and not unicodedata.category(c).startswith(('P', 'S')))


def mixed_tokens(text):
    return re.findall(r'[\u3400-\u9fff]|[a-z]+|\d+|\ue000|[^\W_]',
                      unicodedata.normalize('NFKC', text).casefold().replace('<unk>', '\ue000'))


def distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        current = [i]
        for j, right in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (left != right)))
        previous = current
    return previous[-1]


def summarize(rows, simplifier=None):
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[(row['model'], row['group'], row.get('paced', False))].append(row)
    result = []
    for (model, group, paced), values in sorted(grouped.items()):
        errors = count = strict_errors = strict_count = 0
        for row in values:
            tokenizer = mixed_tokens if group in ('mixed', 'english') else normalize
            ref, hyp = tokenizer(row['reference']), tokenizer(row['text'])
            strict_errors += distance(ref, hyp)
            strict_count += len(ref)
            if simplifier is not None:
                ref = tokenizer(simplifier(row['reference']))
                hyp = tokenizer(simplifier(row['text']))
            errors += distance(ref, hyp)
            count += len(ref)
        audio = sum(x['audioMs'] for x in values)
        compute = sum(x['computeMs'] for x in values)
        first = [x['firstTextMs'] for x in values if x['firstTextMs'] >= 0]
        result.append(dict(model=model, group=group, paced=paced, samples=len(values),
                           metric="MER" if group == "mixed" else "WER" if group == "english" else "CER",
                           referenceUnits=count, editErrors=errors,
                           errorRate=errors / count if count else None,
                           strictErrorRate=strict_errors / strict_count if strict_count else None,
                           strictReferenceUnits=strict_count,
                           simplified=simplifier is not None,
                           nonemptySilence=sum(bool(normalize(x['text'])) for x in values) if group == 'silence' else None,
                           audioMs=audio, computeMs=compute, rtf=compute / audio,
                           medianLoadMs=statistics.median(x['loadMs'] for x in values),
                           medianFirstTextMs=statistics.median(first) if first else None,
                           medianFinishMs=statistics.median(x['finishMs'] for x in values),
                           medianAudioEndToFinalMs=statistics.median(max(0, x['elapsedMs'] - x['audioMs']) for x in values) if paced else None,
                           maxSampledProcessPssKb=max(x.get('sampledProcessPssKb', 0) for x in values)))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('files', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--simplify', action='store_true')
    args = parser.parse_args()
    rows = [json.loads(line) for file in args.files for line in file.read_text().splitlines() if line]
    converter = None
    if args.simplify:
        from opencc import OpenCC
        converter = OpenCC('t2s').convert
    args.output.write_text(json.dumps(summarize(rows, converter), ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
