# P2 SAF 间歇空来源 bounded diagnosis（2026-09-28）

本记录对应 `docs/development/release-readiness.md` 的 P2。目标是对 HXA-227 期间 `FilesImportExportUiTest.removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions` 的间歇 `files-saf-empty` 失败做有限复现与链路归因；不是通过无限 rerun 寻找绿色，也不因无法复现而宣称问题已修复。

## 历史事实

HXA-227 `repair-api36-r7` 的失败是真实保留证据：SAF panel 已正常打开，但语义树出现 `files-saf-empty` / “暂无已授权 SAF 来源”，等待 `files-saf-remove-<scope>` 30 秒后超时。该语义树排除了“按钮存在但被布局挤出”的解释。随后 r8、三次历史 `saf-diagnostic-r1` 和最终全量轮次均通过，但完成记录一直保留“根因未定位”。

当前测试在相同超时处会额外打印 `knownScopeIds()` 与 `liveSources()`；历史 r7 发生在该诊断加入之前，所以无法从旧日志还原“registry 已知但 live re-check 为空”还是“registry 本身为空”。

## 当前生产链路审查

当前实现没有第二个 SAF registry：

1. `AppFileServices` 建立单一 `SafGrantStore(filesDir/workspaces/saf-grants.json)`；FileManager 与 import/export 共用它。
2. `SafGrantStore.grant()` 以 tree URI 派生 opaque scope ID，更新内存 registry 并原子持久化。
3. `SafTreeScopeService.liveSources()` 对 `store.list()` 的每个 grant 调用 `SafTreeScopeResolver.liveScope()`。
4. resolver 每次重新校验 scope derivation、provider/root identity、root liveness 和 READ mode；任何 `ScopeNotAvailable` 都被映射为“不在 live source list”。
5. `ContentResolverSafTreeCheck` 使用 `ContentResolver.query()` 检查 root document；异常或 null cursor 都 fail closed。成功空目录仍视为 live。
6. `FilesScopeEffects` 在 SAF dialog 打开时只执行一次 `liveSources()` 投影。若 re-check 返回空而不抛异常，UI 合法地显示 `files-saf-empty`；直到 reload/panel reopen 才会再次取值。

因此“某次瞬时 ContentResolver re-check 失败 → 一次性 UI 投影为空”与历史症状相容，但当前没有证据证明它就是根因。尤其不能据此把上一次成功结果缓存成 authority：那会破坏 revoked/provider-gone 时 fail-closed 的安全契约。

## 有界复现

固定源码：`0e5c5f80a06036789e6d85eff907b9c27f3113f1`。

固定制品：

- consumer app APK SHA-256 `d4885c750b844455b9101e94d1c488f66b6f99b4b4bd4d33524b20b5a85564aa`
- consumer AndroidTest APK SHA-256 `58669f82490bb01ac83a1b60e585f28b147c0b5b1a32cb71f7dffcb1ab1ffe38`
- AVD `Helix_HXA210_API36` / API 36 / arm64-v8a / density 400

执行窗口严格限定为四次当前 `FilesImportExportUiTest`：

1. owned-emulator 主 instrumentation：4/4 passed；
2. `diagnose-saf-repetition.py` attempt 1，独立 package reset：4/4 passed；
3. attempt 2：4/4 passed；
4. attempt 3：4/4 passed。

诊断脚本在三次通过后原样输出：`No reproduction in three attempts; this is not evidence that the defect was fixed`。没有继续扩大次数或运行完整 198-class baseline。输出目录：`build/p2-saf-bounded/current-api36-consumer/`；`closed.json` exit=0，结束后 `adb devices` 为空。

## 结论

- **当前限定条件下无法稳定复现。** 不满足修改 production 的证据门槛。
- 不改 `SafGrantStore`、`liveSources()`、ContentResolver liveness 判定或 UI 缓存；尤其不以缓存旧成功来源换取表面稳定。
- 历史问题继续作为 known limitation。若再次复现，当前测试会保留语义树，并在 timeout catch 读取 `knownScopeIds` + `liveSources`；应优先区分：registry empty、registry known/live empty、registry known/live recovered 三种情况，再决定是否需要 provider/query 时序诊断或最小修复。
- P2 到此停止，下一主线进入 P3 本地模型安装最小闭环。

## 边界

本轮未使用真实系统 DocumentsProvider picker、云文档 Provider、真实账号、物理设备/OEM；没有证明所有 SAF Provider 稳定性。没有修改 production，也没有重跑全量普通类或专用 runner。历史失败没有被当前 PASS 覆盖或删除。
