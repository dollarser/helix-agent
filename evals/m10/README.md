# M10 fixed evaluation set

`fixed-evals.tsv` is the immutable HXA-100 scenario set. Run cases in ID order. The model receives the exact UTF-8 `prompt` cell; its SHA-256 is recorded per case. Fixture-provider runs test deterministic Agent Loop behavior; real-provider runs measure tool selection and task completion and must not be substituted for tool correctness tests.

For every result, copy `run-metadata.template.json` to an evidence directory and replace every `REQUIRED_*`, protocol alternative, date, and result placeholder. Record the dataset SHA-256, exact provider/model/reported version (or `UNAVAILABLE`), temperature, prompt SHA-256, the versions of every exposed tool, full Git commit, and real device model/API/ABI/page size. Evidence paths are repository-relative; secrets and prompt responses containing user data are forbidden.

Reproduce hashes with `shasum -a 256 evals/m10/fixed-evals.tsv` and `printf %s "$PROMPT" | shasum -a 256`. A run with missing metadata, a changed dataset digest, a skipped case, or a non-real device field is incomplete rather than passing.
