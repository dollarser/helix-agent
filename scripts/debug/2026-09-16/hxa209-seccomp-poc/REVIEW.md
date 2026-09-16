# HXA-209 seccomp PoC correction — 2026-09-16

## Decision

CUSTOM Agent-tool network DENY needs enforcement for Shell, otherwise Shell bypasses
that setting. The three ordinary presets allow networking; do not install this
candidate for all jobs or for Provider inference. This review fixes the probe,
not HXA-209 production integration or the full execution-domain matrix.

## Root causes (not an NDK defect)

- Local `{u8 code,jt,jf; u16 k;}` has ordinary size 6, also on host clang.
  UAPI sock_filter is `{u16 code; u8 jt,jf; u32 k;}`, size 8.
- Use official linux/filter.h, linux/seccomp.h, sys/syscall.h. They exist in
  NDK 28.2.13676358. No compiler replacement or syscall override is warranted.
- arm64 seccomp/socket/connect are 277/198/203. 289 is pkey_alloc;
  41/42 are x86_64 socket/connect, not arm64.
- BPF_W=0, BPF_A=0x10, RET A=0x16. RET K works with full 32-bit k;
  truncating ALLOW/ERRNO destroyed the return action. RET A is not a workaround.
- seccomp_data is 64 bytes; socket family is args[0], offset 16, not 24.
- Old RET-A branch offsets skipped the IPv6 check. Shared canonical filter fixes it.
- _exit(300..500) truncates to eight bits. Old decoder could not report these
  statuses correctly; probes now use small codes and fail the parent on failure.
- SIGSYS alone does not distinguish pre-existing Android policy from the new
  filter. Old EINVAL/SIGSYS observations cannot establish platform infeasibility.

Primary references: [filter UAPI](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/linux/filter.h),
[generic syscall UAPI](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/asm-generic/unistd.h).
These headers are included from the NDK, not vendored third-party implementations.

## Verification performed

- `bash scripts/debug/2026-09-16/hxa209-seccomp-poc/build-poc-tools.sh`: all four ARM64 binaries rebuilt with the same NDK; host/device SHA-256 values matched.
- `bash scripts/debug/2026-09-16/hxa209-seccomp-poc/check-abi.sh`: ARM64 and x86_64 official ABI assertions compiled with -Werror; host original-layout size 6 confirmed.
- `bash scripts/debug/2026-09-16/hxa209-seccomp-poc/verify-owned.sh`: new exclusive API36 emulator on port 5580, shell UID. RET K allow, RET A allow, network filter, invalid/EINVAL control: four PASS. Guard exec into shell then netprobe: IPv4 UDP/TCP and IPv6 socket EPERM, UNIX socket creation OK. Owned process stopped by EXIT trap; existing emulator-5554 untouched.
- Detailed transcript: ignored `build/hxa209-poc/owned-verification.log`.
- No production App-UID/PRoot, API29, root, or complete Q1–Q5 acceptance claimed.

## Handoff: continue without inventing another toolchain issue

1. Rebuild before every device run and compare SHA-256. Use a new owned emulator;
   do not reuse this review's serial. Run diag/filterprobe before PRoot tests.
2. Audit the original run-seccomp-poc.sh before using it: netprobe lives in /tmp,
   but its PATH lacks /tmp; nested shell quoting can expand commands on the wrong
   side; printed outputs and unconditional netprobe exit 0 are not assertions.
   This review did not execute or certify that original runner.
3. Run baseline and guarded actual PRoot chain with explicit assertions for
   usable files/pipes, IPv4/IPv6, child/grandchild exec, cancellation and teardown.
   Then repeat from the real unprivileged App launch context. Shell UID success
   does not prove App SELinux/seccomp/ptrace compatibility.
4. Candidate blocks new INET sockets and all connect, including UNIX connect.
   It does not prove whole-job network isolation: cover inherited/received FDs,
   datagram send paths, io_uring, local proxies/Binder, other ABIs, same-UID process
   interaction and child escape. Prove no bypass or refuse unsupported restricted
   execution; do not weaken DENY into ASK. Keep normal permitted jobs functional.
5. Install only on the dedicated job launch chain, not a reusable app/service
   thread. Seccomp cannot be removed; verify stopped job groups and no cross-job
   or Provider contamination. Network filtering does not prove workspace/no-write.
6. Only then update the effect/constraint/unsupported matrix and HXA-209 A evidence.
   Do not close HXA-209 or label Q1–Q5 passed from this narrow review.

## Follow-up: broader validation and pending product choice

User requested full networking restrictions, then requested comparison before choosing
a runtime architecture. No production policy/Runtime activation has been changed.

- The original socket/connect filter allowed a guarded child to send a synthetic
  loopback UDP datagram over an inherited connected FD. The dedicated guard now
  closes nonstandard inherited FDs and requires regular-file/pipe standard streams.
  fdprobe asserts this specific bypass no longer works. This intentionally rejects
  socket/device stdio; it is not ready for future interactive PTY use.
- verify-proot.py launches only its own read-only emulator instance and tests real
  packaged PRoot/RootFS assets: baseline connectivity, guarded IPv4/IPv6 socket
  denial, file/pipe usability and descendant inheritance. It asserts output and
  exit status; it does not reuse the original unverified run-seccomp-poc.sh.
- Opt-in ProotNetworkProbeDeviceTest executes native probes in the developer App
  UID. Missing payloads fail after explicit opt-in; ordinary suites skip this
  feasibility probe. Four filter controls and inherited-FD regression are checked.
- A synthetic filtered child still modifies its unfiltered parent's flag via
  process_vm_writev, including in the API29 developer debug App UID. This does
  not access Helix state or user data; it disproves completeness of this filter,
  not feasibility of every possible same-UID design. Release/OEM coverage is absent.
- Blocking process_vm_writev alone is not proof against ptrace, proc memory,
  Binder/proxy, shared files, received FDs and io_uring delegation. PRoot itself
  uses tracing, so a blanket tracing denial requires compatibility evidence.
- No full network-DENY acceptance, real App-to-PRoot combined production pipeline
  acceptance, Provider isolation acceptance, or HXA-209 closure is claimed.

Options pending user choice: (1) keep researching same-UID filtering without
claiming a sandbox; (2) add a constrained execution domain, preferring a single-APK
isolated-process feasibility spike; (3) keep current Runtime and reject Shell
execution when network DENY cannot be guaranteed. An isolated UID is not automatically
a working PRoot: RootFS access, native loading, ptrace, file transfer, cancellation
and Android SELinux need a separate spike. A separate no-INTERNET Runtime APK is a
fallback design, not an implemented or accepted change.

Commands: build-poc-tools.sh, check-abi.sh, then
`POC_APP_PROBE=1 POC_AVD=Helix_API_29 python3 scripts/debug/2026-09-16/hxa209-seccomp-poc/verify-proot.py`
(and Helix_API_36). Artifacts are under ignored build/hxa209-poc and
build/hxa209-network-verification. Tests passing means the probes worked; the
recorded bypass remains an acceptance failure for complete isolation.

Final observed matrix for this follow-up: API29 and API36 each passed one opted-in
App-UID instrumentation probe (no skip), PRoot baseline/guarded file+pipe+descendant
checks and inherited-FD regression. Both App-UID probes reproduced the synthetic
process_vm_writev bypass. This is debug APK evidence, not release/OEM proof.
The two owned emulator runs exited via finally; existing emulator-5554 was not used.
Developer main/test APK builds, detekt, C ABI checks and git diff checks passed.
Spotless could not traverse the pre-existing ignored extracted RootFS broken symlink
`build/hxa209-poc/rootfs-x/usr/bin/top`; no full source-gate pass is claimed.
The original untracked run-seccomp-poc.sh remains untouched.
