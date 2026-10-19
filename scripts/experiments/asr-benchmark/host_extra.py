#!/usr/bin/env python3
"""Official Moonshine/Vosk host SDKs; fixed PCM, no audio device access."""
import argparse
import ctypes
import json
from pathlib import Path
import resource
import time
import numpy as np
from verify_inputs import verify_inputs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', choices=['moonshine', 'vosk'], required=True)
    args = parser.parse_args()
    root = Path('build/asr/benchmark')
    corpus = verify_inputs(root, [args.model])
    if args.model == 'moonshine':
        from moonshine_voice.transcriber import Transcriber
        from moonshine_voice.moonshine_api import ModelArch
    else:
        from vosk import Model, KaldiRecognizer
    with (root / ('host-' + args.model + '.jsonl')).open('w') as output:
        for item in corpus:
            samples = np.fromfile(root / item['file'], dtype='<i2').astype(np.float32) / 32768
            began = time.perf_counter_ns()
            if args.model == 'moonshine':
                engine = Transcriber(root / 'models/moonshine', ModelArch.TINY_STREAMING,
                                     options={'max_tokens_per_second': 13.0})
                stream = engine.create_stream()
                stream.start()
            else:
                engine = Model(str(root / 'models/vosk'))
                stream = KaldiRecognizer(engine, 16000)
            load = (time.perf_counter_ns() - began) / 1e6
            started = time.perf_counter_ns()
            first = -1
            text = ''
            completed = ''
            for pos in range(0, len(samples), 1600):
                chunk = samples[pos:pos+1600]
                if args.model == 'moonshine':
                    audio = (ctypes.c_float * len(chunk))(*chunk)
                    code = stream._lib.moonshine_transcribe_add_audio_to_stream(engine._handle, stream._handle,
                                                                               audio, len(chunk), 16000, 0)
                    if code != 0: raise RuntimeError(code)
                    if (pos // 1600 + 1) % 5 == 0:
                        transcript = stream.update_transcription(0)
                        text = ' '.join(line.text for line in transcript.lines)
                else:
                    pcm = np.clip(np.rint(chunk*32768), -32768, 32767).astype('<i2').tobytes()
                    if stream.AcceptWaveform(pcm): completed += json.loads(stream.Result())['text'] + ' '
                    text = completed + json.loads(stream.PartialResult()).get('partial', '')
                if first < 0 and text.strip(): first = (time.perf_counter_ns() - started) / 1e6
            stopped = time.perf_counter_ns()
            if args.model == 'moonshine':
                code = stream._lib.moonshine_stop_stream(engine._handle, stream._handle)
                if code != 0: raise RuntimeError(code)
                transcript = stream.update_transcription(1)
                text = ' '.join(line.text for line in transcript.lines)
            else:
                text = completed + json.loads(stream.FinalResult())['text']
            ended = time.perf_counter_ns()
            row = dict(model=args.model, sample=item['id'], group=item['group'], reference=item['reference'],
                       text=text, audioMs=len(samples)/16, loadMs=load, computeMs=(ended-started)/1e6,
                       elapsedMs=(ended-started)/1e6, finishMs=(ended-stopped)/1e6, firstTextMs=first,
                       paced=False, hostPeakRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
            output.write(json.dumps(row, ensure_ascii=False)+'\n');output.flush()
            print(item['id'], text, flush=True)
            if args.model == 'moonshine': stream.close();engine.close()
            del stream,engine


if __name__ == '__main__': main()
