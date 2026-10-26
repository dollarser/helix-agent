# ModelScope offline ASR selection — 2026-10-26

Owner authorized evaluating ModelScope alternatives and replacing the offline default. Online account speech remains the default for a signed-in account without a manual override. This is a local fallback decision, not an all-round speed/accuracy claim.

## Inputs and method

Reuse the existing [ASR experiment](../../../scripts/experiments/asr-benchmark/README.md) and its frozen corpus: 20 FLEURS Mandarin test clips (CC-BY-4.0), five synthetic white-noise derivatives, one public JFK English excerpt, three constructed Chinese→English concatenations and two synthetic silence clips. Each model receives the same verified 16 kHz mono PCM; recognizers/streams are fresh per clip, CPU two threads, no microphone or cloud ASR. Strict CER/WER/MER uses the existing scorer; no spelling repair, word deletion or relaxed assertions. One English clip and constructed mixtures do not establish general English/code-switching accuracy. Noise is synthetic, not a real environment recording.

Public model files were enumerated through ModelScope's repository API, fetched at fixed file revisions and checked against its SHA-256/size metadata. Existing sherpa-onnx 1.13.8 host and Android CPU APIs load the selected bytes without installing the author's Python package. Source model metadata identifies `zipformer2`. Operational hashes, raw hypotheses, process measurements and candidate receipts remain under ignored `build/asr-modelscope-2026-10-26/` and the existing benchmark result root; immutable inputs are in the experiment/model locks.

## Bounded comparison

Host results below are this frozen small corpus, not author leaderboard numbers. Size is required runtime files, not the full repository. Peak RSS is the whole host process, not model-only RAM.

| Candidate | Required MiB | Mandarin CER, 20 | Noise CER, 5 | English WER, 1 | Constructed mixed MER, 3 | Host peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Previous small bilingual INT8 | 57.4 | 16.94% | 26.15% | 0.00% | 35.00% | 233.6 |
| pkufool small streaming INT8 CTC, chunk16/left64 | 27.9 | 29.84% | 40.00% | 31.82% | 46.43% | 169.8 |
| pkufool small streaming FP16 transducer, chunk16/left64 | 79.4 | 14.84% | 23.08% | 4.55% | 19.29% | 396.2 |
| pkufool medium streaming INT8 CTC, chunk16/left64 | 71.1 | 20.69% | 34.36% | 4.55% | 24.29% | 305.3 |
| k2-fsa WenetSpeech small streaming INT8 transducer | 39.6 | 16.04% | 20.51% | Not an English replacement | Not an equivalent bilingual replacement | 216.5 |

The small model card declares Chinese/English and Apache-2.0; it offers FP16 transducer and INT8 CTC files, not an INT8 transducer in the enumerated repository. The medium model and WenetSpeech small also declare Apache-2.0. A Chinese-only CTC repository with unspecified/`other` licensing was not adopted. The separately inspected Paraformer small is an offline/end-of-input candidate, so it does not replace continuous partial recognition here.

## Android evidence and decision

An owned API36 ARM64 emulator, two vCPUs/2 GiB, ran both the previous model and the selected small transducer over all 31 clips, then three Mandarin clips each paced in 100 ms chunks. All four instrumentation runs passed. No account, microphone, paid service, phone or daily application was used. The experiment is opt-in and remains outside normal production dependencies.

| Android observation | Previous | Selected small FP16 transducer |
| --- | ---: | ---: |
| Mandarin CER, 20 | 16.64% | 14.84% |
| Constructed mixed MER, 3 | 35.71% | 19.29% |
| English WER, one 22-word excerpt | 0 errors | 1 error |
| Mandarin throughput RTF, 20 | 0.063 | 0.073 |
| Median fresh-recognizer load, Mandarin 20 | 881 ms | 2252 ms |
| Sampled whole-process peak PSS, Mandarin 20 | 224.0 MiB | 356.3 MiB |
| Median first text, paced 3 | 2409 ms | 2845 ms |
| Median audio-end to final, paced 3 | 15 ms | 27 ms |

First text includes each clip's initial silence and excludes model loading; it is not the author's algorithmic chunk latency. Emulator throughput/load/PSS is not a phone benchmark. Host and Android hypotheses can differ slightly with kernels/platforms; both raw outputs are retained.

Adopt **small FP16 transducer** for the quality-oriented bilingual offline fallback and reliable ModelScope delivery. It improves this Mandarin/mixed/noise sample set, costs more memory/loading time, and is not universally faster or better in English. Do not select small CTC just for size, or medium CTC just for the name. Only the four required files are downloaded; no product model selector, new SDK or automatic cloud→offline fallback is added. The old in-app two-model comparison is replaced by this maintained opt-in experiment; old benchmark baselines remain legitimate comparison inputs, not product compatibility paths.

The production factory/download smoke and final gates are separate from the above experimental model comparison. Their current results belong in the [speech integration record](../../development/cloud-speech-integration.md); comparison success alone does not prove the final installed production factory/download path.

## Primary sources

- [Author ModelScope small streaming model](https://modelscope.cn/models/pkufool/zipformer-small-streaming), [medium streaming model](https://modelscope.cn/models/pkufool/zipformer-medium-streaming).
- [Author project](https://github.com/pkufool/zipformer), [deployment guidance](https://pkufool.github.io/zipformer/en/deployment/), [model/latency descriptions](https://pkufool.github.io/zipformer/en/models/). Published metrics for different heads/contexts are not substituted for this run.
- [WenetSpeech small ModelScope repository](https://modelscope.cn/models/k2-fsa/icefall-asr-zipformer-wenetspeech-streaming-small).
- [sherpa small online model guidance](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/small-online-models.html).
