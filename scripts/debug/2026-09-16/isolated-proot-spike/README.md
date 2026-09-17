# Isolated UID PRoot feasibility probe

Evidence and decision: [review](../../../../docs/evidence/development/isolated-proot-feasibility-2026-09-16.md).

Requires the project's existing locked PRoot assets, Android SDK/NDK and API29/36
arm64 AVDs. No external accounts, Root, or external network requests are used.

1. `bash scripts/debug/2026-09-16/isolated-proot-spike/build.sh` stages unique debug
   sources and diagnostic native copies, builds the two developer APKs, then removes
   only those staged files in finally. Existing destination files cause refusal.
2. `POC_AVD=Helix_API_29 python3 scripts/debug/2026-09-16/isolated-proot-spike/run.py`
   runs on a new owned emulator. Repeat with Helix_API_36. Both APKs are diagnostic
   builds; this is not a production rollout.
3. Reports and logcat are in ignored build/isolated-proot-spike. The test asserts
   isolated identity, explicit FD input and network denial; it records other
   outcomes. `OK (1 test)` is NOT PRoot feasibility acceptance. Check prootJob exit
   and the matching successful host baseline. `OK exit=1` in raw reports means the
   observation returned normally, while the child command FAILED.
4. Rebuild ordinary APKs without this script before installing a normal build.

The probe reuses already-adopted native assets. Its DT_NEEDED filename adjustment
is applied only to an in-memory diagnostic copy; no production lock, asset or
license is changed. No native binaries are committed. Fixtures are not normal
application sources and expose no production execution entry point.
