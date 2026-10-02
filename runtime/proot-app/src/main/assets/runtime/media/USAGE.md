# FFmpeg through the existing Helix Linux Job

Advanced only. Run `ffmpeg` and `ffprobe` from the available Bash / code.linux.run tool or code.linux.job.start. This is the packaged Ready-AV1 Android/Bionic binary exposed inside PRoot through an explicit system-linker bridge, not a musl package, new Service, or separate execution engine. No new permission is granted. PRoot shares Helix UID and permissions and is not a security sandbox.

## Discover before processing

Use `ffmpeg -version`, `ffmpeg -encoders`, `ffmpeg -decoders`, `ffmpeg -filters`, `ffmpeg -formats`, `ffmpeg -protocols` and targeted `ffmpeg -h filter=NAME`. Compiled components are not a claim that every codec/device combination works. The bridge indicator is `HELIX_FFMPEG_BRIDGE`; absent commands or unavailable Runtime must be reported, not installed or replaced automatically.

## Input, output and time

Use the tool's `files` for authorized snapshots. Their guest location is /workspace. The first input retains its basename; later inputs append -2, -3, etc. after the complete basename (clip.mp4-2). Inspect actual names rather than guessing paths. Inputs stream with existing 64 MiB/file and 128 MiB/archive limits; input plus generated files share the final archive budget. Large jobs must plan for that bound, not silently truncate.

Write final deliverables under /workspace/output/, e.g. `mkdir -p output; ffmpeg -nostdin -n -i input.mp4 ... output/clip.mp4`. Paths under output/ are verified, published to the originating session, and returned as artifacts after a successful original Job. Other workspace entries remain in the full archive. Do not use result.txt or the legacy 1 MiB `output` import for binary media. Intermediate files can live under the job's /tmp; never delete user source files to make space.

The short tool defaults to 60 seconds. For longer work use the existing background Job (default 300 seconds, maximum 1800), observe by originalCallId / jobs.await, and collect the original result. Acceptance is not completion. Cancel and failed collection do not authorize replay or automatic quota renewal. A nonzero process exit does not publish produced output files as successful artifacts.

## Native CLI, not five presets

All argument combinations supported by this build may be used: multiple inputs, stream mapping, filter_complex, concat, segment, subtitles, drawtext, loudnorm, masks, GIF/WebP, audio conversion and stream copy. There is no shell eval inside the FFmpeg bridge; argv is forwarded unchanged. Shell syntax belongs in an explicit script; keep normal quoting for filenames and filter expressions. The direct argv tool has its existing argument-count limit, so an explicit shell script is appropriate for complex native commands.

H.264/HEVC software decoders and dav1d AV1 decoding are packaged. AV1, x264 and x265 software encoders are NOT packaged. H.264/HEVC MediaCodec encoding is a device-dependent path; the prior candidate used `-ndk_codec 1 -ndk_async 1 -flags -global_header -bsf:v extract_extradata`. Verify the exact device's output; never declare success from exit code zero or silently change codecs. Network protocols and capture devices are not compiled in. Use authorized download tools separately; Bash does not re-enable missing components.

## Validation and privacy

Probe input and output streams with ffprobe. Check expected codec, dimensions, duration/frame counts, audio/subtitle tracks, timestamps and actual file size; perform a bounded decode check where useful. Result-file integrity/registration does not prove semantic editing correctness. Frame-accurate cuts differ from keyframe-dependent stream-copy cuts. Target-size bitrate calculations are estimates until output bytes are measured.

Do not relabel HDR as SDR. This build has no accepted HDR tone-mapping workflow. Detect transfer/primaries/metadata and disclose unknown color handling. User/model chooses whether to preserve metadata, audio and subtitles; a blurred rectangle does not remove those other data channels. Solid redaction is preferable for sensitive content and needs coverage verification.

No fonts are bundled. Discover readable system fonts under /system/fonts or use an explicitly authorized font input. Do not assume a particular OEM font path. Extra subtitle/concat/font files are inputs and use normal file/execution permission. Do not copy or export system font files as artifacts.

Current host/package validation is distinct from executing this bridge on Android. Consult the HXA-240 evidence for the exact tested scope; an old standalone emulator result does not certify a new bridge build.
