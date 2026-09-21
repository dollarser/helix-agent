# 提交快检与 199/206 验收分支整合

日期：2026-09-21。所有者授权先优化本地/CI 分工，再合并验收分支、推送并验证远端。

## 优化取舍

- pre-commit 只做 index 中新增/修改 blob 的已知密钥模式和空白/冲突标记检查，不运行 Gradle、网络或完整仓库契约。常规 3 秒是反馈目标；超时不静默跳过，扫描器/读取错误不放行。部分暂存、二进制、符号链接和特殊路径都有回归。
- i18n/ADR/跨文件一致性继续由完整 source 门禁验证，不将它们称为不可逆失误；本地定向验证、HXA 完整主机/设备验收仍保留。
- CI 保留既有 source/debug/full 分层。补齐 full 的实际 Release APK 边界检查，不把“构建 Release”误认为“扫描过 Release”。本地 `--all` 同步包含该检查。
- 可选 `.githooks/pre-commit` 已入库，本轮 commit 通过 `git -c core.hooksPath=.githooks commit` 实际执行；未覆盖可能被其他 worktree 使用的共享 hooks 配置。远端不信任本地 hook 作为通过证明。

实现与用法见[CI 分层](../../development/ci.md)。本轮没有改变 Android runner 平台、删除 JVM 测试或放宽 lint/制品断言。

## 本地证据

- `python3 -m unittest discover -s scripts/tests -p test_staged_gate.py`：10/10 通过；`test_ci_*.py`：12/12 通过。首次 rename/delete fixture 在有暂存重命名时被 Git 正确拒绝删除，修正临时仓库的删除动作后重验通过，未放宽实际 checker。
- 本轮 10 个暂存文件：直接运行 `0.137 s`，实际 commit hook `0.087 s`。样本依赖当前机器和修改量，不保证任意提交低于 3 秒。
- 在共享 host slot 下执行 `/usr/bin/time -p ./scripts/check-all.sh --all`：exit `0`，暖缓存端到端 `55.25 s`，包含 source、全部主机门禁和 Debug/Release 实际 APK 边界。日志保留在验收工作树的 `build/acceptance-baseline/ci-tiering-final.log`。
- Git diff 空白检查与 secret 扫描通过。生产 Android 代码相对上一轮验收未再变化，不重复执行不受影响的 52 批产品设备矩阵。

## Git 与远端

main 从 `215b7d81` 快进到 `995c9baf`，包含验收提交 `b84d38d1` 与快检/CI 修正提交 `995c9baf`；已正常 push，`ls-remote` 确认远端 main 与本地相同。没有 force push，也未合并独立的 HXA-126 OAuth 工作。

远端 [full CI 35585669780](https://github.com/dollarser/helix-agent/actions/runs/35585669780) 绑定 `995c9baffd712489b0c6ec5e8de7282427052532`，已 completed/success：source、runtime-assets、android (analysis)、android (tests-build)、verify 五个 job 全通过。已下载实际 tests-build 诊断，`tests-build.tsv` 为 625 秒 / exit 0，`release-artifacts.tsv` 为 4 秒 / exit 0；日志明确 consumer/developer Release 的 components、process/UID、payloads、launcher 均通过。诊断保存在 main 工作树 `build/ci-integration-2026-09-21/`。

随后仅提交状态与复核文档；该文档提交由独立 source CI 验证，不冒充重复执行 full。HXA-126 候选的合并阻塞另见[复核记录](hxa-126-merge-review-2026-09-21.md)。

## 保留边界

206 本地核心产品验收已交付。199 的双 API/实际 30 分钟及两小时/升级/恢复已验，OEM/HOME/锁屏/Doze/热压/真实 16 KiB 物理专项仍未验；完整 CI 不关闭这些项目。真实账号、签名发行及商店提交也未随本次合并完成。原验收工作树保留原始 APK/日志，未作为“已合并垃圾”删除。
