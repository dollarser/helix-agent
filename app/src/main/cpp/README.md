# Helix model runtime

llama.cpp is built as a JNI backend for the non-exported `:model_runtime` service.
No inference server is started. The application process never initializes `LlamaNative`.

Source: <https://github.com/ggml-org/llama.cpp/tree/7fe450e19305b828c199d602c23a8337aaa1f03b>
(release v0.5.0). CMake pins the commit archive and verifies SHA-256
`a6861d549427f814dc591c439e08206f67ffaba0248344d421589abf18199e67`.
The archive is fetched into ignored build output; it is not copied into source control.
MIT and bundled dependency notices are packaged under `assets/licenses/`.

The service serializes model load, generation and unload; cancellation uses a native atomic
abort flag. The supervisor must observe executor exit or process death before releasing
ownership. Prompt/history and results travel through bounded file descriptors (1 MiB each).
The model descriptor is read-only and its hash and size are checked again inside the service.

CPU only, arm64-v8a and x86_64; no GPU, OpenMP, runtime downloads or subprocess execution.
A single model/context is resident. Generation uses the embedded model chat template,
greedy sampling, bounded context/output and the upstream template parser. Results are
buffered until generation ends; this implementation does not claim token-level latency.
The downstream Harness alone validates and executes tools.

Building a shared library or APK does not verify model compatibility, memory consumption,
thermal behavior or device task quality. See the active HXA-222 acceptance record.
