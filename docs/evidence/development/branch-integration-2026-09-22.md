# 2026-09-22 分支整合与后续计划

## 整合范围

本次用户授权合并新开发分支到 main、收敛并规划后续；未发布，也未推送。

- 本地 main 从 c36d556b 快进至 6d9ce5f2，纳入 marketplace d5dc51ac 与 Connector 设计两次提交。
- 合并 session-fork dc560ac8 得到 8bd2d177，包含压缩 5c2a11df、fork 98b904b5 和后续交互需求。
- 三语 strings、完成索引和 roadmap 的追加冲突保留双方条目；运行代码无文本冲突。
- acceptance-199-206、hxa-126、small-model-preparation 已是 main 的祖先，不重复 cherry-pick。v0.0.1 为历史版本，不合回现代主线。
- 所有 worktree 检查均无未提交改动；保留分支及 worktree，不删除验收日志或产物。

## 本轮验证

整合后执行完整主机门禁、双 flavor 测试 APK 构建及 API29/36 × 双 flavor 的定向设备回归。最终串行命令 exit 0；完整主机门禁、双 flavor 测试 APK 构建及四组设备回归全部通过。每组 69/69，共 276 PASS、0 FAIL、0 SKIP，严格收集器均为 DEVICE_BATCH_PASS；已核对预期方法集合及 owned/closed 记录。原分支通过数字不替代此处。

入口：`scripts/debug/2026-09-22/accept-branch-integration.py`，复用 owned runner 和严格结果收集器，覆盖 Marketplace、Connector、fork、压缩及 Goal；只使用本轮独占模拟器。

最终日志：忽略目录 `build/branch-integration-20260922/final-verification.log` 与 `devices-3/`。文档终检为 `final-source.log`，`git diff --check` 通过。持有 `scripts/debug/2026-09-18/with-host-slot.py` 的共享 host slot，使用 JDK17，Gradle workers=2、parallel=false、daemon=false。

```sh
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
python3 scripts/debug/2026-09-22/accept-branch-integration.py --output build/branch-integration-20260922/devices-3
```


最终验收 APK SHA-256：

| Flavor | 主 APK | 测试 APK |
| --- | --- | --- |
| consumer | `366ab63468223ec426f3a603c520b3a615810b078ed514fd37fe250c5d286799` | `9ca306f436a02a8499c5c5dfacaf8dfc74c94498859c9c38e97a203f66c3c51b` |
| developer | `520ef2e6c8d40f68de1d348fa9c1992302d7d57ab84aa769f9aa24fbc149a354` | `d6f08f99ff0420617f4accc6d2eaa41fd521963e72f68e235d3649edf4764936` |

## 收敛中发现并修复的问题

首次设备入口配置了超出 runner 允许范围的端口，在启动前被拒绝；修正为 5740 起的合法偶数端口，保留 `devices/` 启动日志。

第二次 consumer/API29 执行 68 项，67 通过、1 失败：`marketplaceItemsAreInstallableAndDetectStatus` 安装后预期 INSTALLED_INACTIVE，实际 NOT_INSTALLED。根因是 `readJson` 将输入视为 MCP 配置而非 Connector manifest，包名成为 imported-connector；市场却按目录目标名称查找。此缺陷来自市场分支，不是压缩/fork 的文本冲突。

修复限定在 MarketplaceService：安装已解析包时绑定目录目标名称及 MARKETPLACE 来源，继续复用原 MCP 解析与 hash。补充全部内置远端条目的身份、查找、幂等安装及卸载回归；不改变通用导入协议，不声称解决 HXA-129 的独立稳定身份、历史错误记录迁移或原子替换。失败原始证据保留于 `devices-2/`；最终重新构建并验收的证据独立存于 `devices-3/`。

## 下一步及边界

按[工作计划](../../development/next-work-plan.md)执行：214 发送/草稿/停止 → 129 安全替换与会话启停（先接受 ADR）→ 215 编辑重发 → 216 排队/转向 → 217 请求清单。后三项也需对应设计接受。

129 已确认存在先卸载再安装的失败窗口，但本轮只整合已有代码和计划，不把待开发修复写成已完成。设备/账号条件项继续独立：199 物理专项、196 真机、125/126 真实服务、190 真实订阅和发行门禁。206 历史验收不覆盖本轮新增功能；本轮定向回归也不冒充全产品或真实服务验收。
