# FFmpeg experiments and preparation kit

The Lite, Plus and Ready variants are distinct experiments. Keep their source locks,
license notices, validation and packaging together. They are not temporary editors.

- `ffmpeg-lite-experiment.py`, `measure-ffmpeg-lite.py`, `smoke-ffmpeg-lite.py`: Lite build, measurement and host checks.
- `ffmpeg-plus-sources.py`, `build-ffmpeg-plus.py`, `measure-ffmpeg-plus.py`, `package-ffmpeg-plus.py`: pinned Plus dependencies and preparation.
- `probe-ffmpeg-plus-android.py`, `probe-mediacodec-modes.py`: explicitly authorized synthetic device probes.
- `ffmpeg-ready/`: Ready/AV1 contracts, build, host tests, recipes and artifact verification.

All 17 migrated inputs are byte-identical to their dated originals. Ready pins the
SHA-256 of `build-ffmpeg-plus.py`; changing that file requires review of the preparation
contract. Archive member names under `scripts/debug/2026-10-01/` are a frozen package
format consumed by `prepare-ffmpeg-proot.py`, not current source lookup paths. The
initial script relocation preserved the vendor ZIP bytes. Later reviewed source
or notice changes update its inventory and exact archive hash separately. The
suite's relative directory depth is preserved so its pinned builder still resolves
the repository and sibling sources.

Outputs retain their original ignored `build/` locations. This relocation does not
rebuild or requalify binaries. Historical commands are translated with the
[script curation inventory](../../debug/archive/2026-10-15-script-curation.json).

The Ready recipe also pins `mediacodec-no-encoder-flush.patch` and the exact
upstream/patched file hashes. `source_patches.py` keeps the verified upstream tree
unchanged and materializes a separate checked source tree for builds. The package
includes the patch, helper, recipe and LGPL notice. This correction removes the
undefined dummy-EOS/encoder-flush initialization path, not synchronous encoding
or frame/output verification. Android acceptance remains separate from host tests.
