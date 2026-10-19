#!/usr/bin/env python3
"""One model per host process; same PCM and scoring input as Android."""
import argparse
import json
from pathlib import Path
import resource
import time
import numpy as np
from verify_inputs import verify_inputs
import sherpa_onnx


def create(model, directory):
    def p(name): return str(directory / name)
    if model == 'ctc':
        return sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(model=p('model.int8.onnx'), tokens=p('tokens.txt'), num_threads=2), True
    if model == 'paraformer':
        return sherpa_onnx.OnlineRecognizer.from_paraformer(encoder=p('encoder.int8.onnx'), decoder=p('decoder.int8.onnx'), tokens=p('tokens.txt'), num_threads=2), True
    if model == 'wenet':
        return sherpa_onnx.OfflineRecognizer.from_wenet_ctc(model=p('model.int8.onnx'), tokens=p('tokens.txt'), num_threads=2), False
    if model == 'sensevoice':
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(model=p('model.int8.onnx'), tokens=p('tokens.txt'), num_threads=2, use_itn=False), False
    if model == 'whisper':
        return sherpa_onnx.OfflineRecognizer.from_whisper(encoder=p('tiny-encoder.int8.onnx'), decoder=p('tiny-decoder.int8.onnx'), tokens=p('tokens.txt'), language='', num_threads=2), False
    return sherpa_onnx.OnlineRecognizer.from_transducer(encoder=p('encoder.int8.onnx'), decoder=p('decoder.onnx'), joiner=p('joiner.int8.onnx'), tokens=p('tokens.txt'), num_threads=2, enable_endpoint_detection=False), True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path('build/asr/benchmark'))
    parser.add_argument('--model', required=True)
    args = parser.parse_args()
    corpus = verify_inputs(args.root, [args.model])
    output = args.root / ('host-' + args.model + '.jsonl')
    with output.open('w') as target:
        for item in corpus:
            samples = np.fromfile(args.root / item['file'], dtype='<i2').astype(np.float32) / 32768
            start = time.perf_counter_ns()
            recognizer, online = create(args.model, args.root / 'models' / args.model)
            load = (time.perf_counter_ns() - start) / 1e6
            stream = recognizer.create_stream()
            started = time.perf_counter_ns()
            first = -1
            for pos in range(0, len(samples), 1600):
                stream.accept_waveform(16000, samples[pos:pos+1600])
                if online:
                    while recognizer.is_ready(stream): recognizer.decode_stream(stream)
                    if first < 0 and recognizer.get_result(stream).strip(): first = (time.perf_counter_ns() - started) / 1e6
            stopped = time.perf_counter_ns()
            if online:
                stream.accept_waveform(16000, np.zeros(8000, dtype=np.float32))
                stream.input_finished()
                while recognizer.is_ready(stream): recognizer.decode_stream(stream)
                text = recognizer.get_result(stream)
            else:
                recognizer.decode_stream(stream)
                text = stream.result.text
            ended = time.perf_counter_ns()
            row = dict(model=args.model, sample=item['id'], group=item['group'], reference=item['reference'], text=text,
                       audioMs=len(samples)/16, loadMs=load, computeMs=(ended-started)/1e6,
                       elapsedMs=(ended-started)/1e6, finishMs=(ended-stopped)/1e6,
                       firstTextMs=first, paced=False, hostPeakRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
            target.write(json.dumps(row, ensure_ascii=False)+'\n'); target.flush()
            print(item['id'], text, flush=True)
            del stream, recognizer


if __name__ == '__main__': main()
