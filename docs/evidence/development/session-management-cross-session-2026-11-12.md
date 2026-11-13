# HXA-295 B/C cross-session acceptance — 2026-11-12

## Scope

Controlled cross-session list/status/history/search/read and metadata management; explicit empty-session creation; explicit Queue and exact-Turn Steer. The existing Room input queue and Engine own execution. Source identity is persisted and rendered as MODEL_DELEGATION, never promoted to user Goal, Skill activation or approval. The owner selected execution under the target's normal current permissions rather than source/target intersection. Directed grants remain required to send; target history and metadata scopes remain separate. Existing explicit onward grants work, but persisted bounded ancestry rejects delegation cycles and no automatic forwarding is introduced.

## Host evidence

The final `./scripts/check-all.sh --debug` completed successfully, including source scripts, both app-flavor JVM suites, formatting, detekt, lint, builds, AndroidTest compilation and APK/runtime boundary checks. The final build logs and exact candidate hashes are retained under the ignored local `build/hxa-295-final2` evidence directory. Relevant JVM suites passed on both Consumer/Developer (80/80 each) and storage (33/33 selected). No commits, push, physical-device operations or live paid-provider quality evaluation were performed.

## Device evidence

A separately owned read-only `Helix_API_36_test` instance on emulator-5592 ran the frozen APK. Its fixture package was clean-installed only inside the owned test instance; the daily emulator was not cleared/uninstalled. Original Engine/Room/loopback-model acceptance demonstrates Queue using target FULL_ACCESS with source READ_ONLY and no automatic Goal, plus exact active-Turn Steer and rejection of new terminal-turn requests. Loopback deterministic scripts establish runtime contracts, not natural-language tool selection quality.

The first application run passed 33/33. Storage first run was 23/25 because two test fixtures manually inserted the same tool-call ordinal; fixtures now use the original repository allocator, with assertions unchanged. Final frozen rerun passed application33/33, Room25/25 and Compose access/provenance UI11/11. The owned instance closed successfully. App and app-test APK hashes were unchanged by the fixture fix.

## Data-preserving daily preview update

Device acceptance passed. Version/schema remain unpublished v1 per repository policy. The local update retains coherent original DB/WAL backups and verified transformation of 55 prior tables / 64,837 rows, including columns/values/rowids, indexes and AUTOINCREMENT high-water values. The extra retired connector_skill_ownership table (193 rows) remains intact; it is not loaded by a new app compatibility layer. The first preflight refused its unknown table rather than dropping it. The tested local transformer now narrowly preserves its validated historical shape. APK cover-install, database publication and launch checks succeeded. The installed APK SHA-256 is `e07d37805550086710e6cc9bcb2a80e0cc3b256cd013eb61a5ec483c3c69a188`, matching the tested candidate. The updated Room identity is `8b421309f7eab6265b6beec462b63b93`. Postlaunch coherent snapshot verification preserved all 18,426 message rows, 148 attachment rows, one reference snapshot, all 193 legacy ownership rows and all nine parked NEEDS_REVIEW tool records. No old data was deleted. An initial staged upload returned before remote completion; stable size/hash checks confirmed completion before publication, with no retransmission or reinstall during the final resume. Original device DB/WAL files and host archives remain available. It will not add a migration chain or resolve/replay existing NEEDS_REVIEW work.


## Evidence locations and limits

- Frozen source fingerprints, APK hashes, host gate, test build, device transcripts and owned-emulator closure: local `build/hxa-295-final2/`. The snapshot predates this documentation-only closeout; production and test source bytes remain unchanged.
- `device/instrumentation.txt`: 33 application tests; `device/storage-test.apk.instrumentation.txt`: 25 Room tests; `device/conversation-test.apk.instrumentation.txt`: 11 Compose tests.
- `daily-update/result.json`, `preservation.json`, `stage-verified.json` and host/device original backups document the actual daily update. Failed preflight/staging evidence was preserved separately, not overwritten or counted as passes.
- Original user-visible APK/application data were not cleared or uninstalled. Only the owned temporary test instance received a clean fixture install.
- No release/R8 gate or real-provider discovery/selection-quality evaluation is claimed by this Debug/runtime slice. No changes were committed or pushed. Later backlog topics are not included.
