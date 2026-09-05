# M9 非真机实现与验证进展

更新时间：2026-09-05

本文记录独立 worktree `codex/m9-accessibility-root` 中可在 JVM、构建和专用模拟器完成的 M9 工作，不是 HXA-094/HXA-095 的完成记录，也不替代 rooted 物理设备证据。M9 未读取或修改 M8 worktree。

| HXA | 当前进展 | 尚未满足的边界 |
| --- | --- | --- |
| 090～093 | 已有完成记录；Accessibility Service/session、snapshot/token、token-only action、攻击/恢复矩阵已在专用 API 29/36 AVD 验收 | OEM/物理设备覆盖不是既有完成记录所声称的范围 |
| 094 | libsu 6.0.0 `core`/`service`、JitPack exclusive content、checksum/notice/lock、accepted ADR-0019、rootless 双 AVD 与物理设备脚本已完成 | 专用 rooted 物理设备上的拒绝、授权、撤销/失权与 RootService crash 尚未执行；不得写完成记录 |
| 095 | 五个高层只读工具、短时 RootSession、developer-only App 接线、scope/失权/超时/Secret 隔离单测、rootless 双 AVD，以及 rooted 高阶工具物理验收脚本已实现 | 真实 `su` 授权后的 file/package/process/log、scope 和 RootService crash 矩阵尚未执行；不得写完成记录 |
| 096 | 产品必要性检查完成：当前没有任意 Root shell 的明确产品需要，因此保持不实现；APK 边界脚本禁止 `root.exec` 静默进入 developer Tool Registry | 若未来出现明确需求，必须重新评审并单独授权，不能沿用本检查点自动实现 |
| 097 | `android-ui-task` 内置 Skill 与九个 `ui.*` Tool 已接 developer App；只组合 HXA-091～093 的 session/snapshot/token/action，JVM、双 flavor 构建和专用 API 29/36 AVD 已验证 | Skill 不授予 Accessibility、不启动 session、不改变 allowlist；真实 OEM/物理设备不在本轮证据内 |

## 已固定的产品边界

- consumer flavor 的 Root/Accessibility 模块均为空实现；developer flavor 才注册对应工具与设置入口。
- Root 只有 `root.status`、`root.file.read`、`root.package.info`、`root.process.list`、`root.log.read`；执行目标为 `LOCAL_ROOT`，其中四个读取必须绑定当前短时 RootSession。没有通用命令字段、Secret 字段或 `root.exec` descriptor。
- RootSession 只能由用户在已授权且 RootService 已连接后启动；10 分钟空闲到期、60 分钟硬上限、时钟回拨、失权、Binder 丢失与用户停止均 fail closed。文件读取只接受用户选择的 opaque scope ID 加相对路径，并拒绝根目录和固定敏感路径。
- AutomationSession 只能由用户在系统 Accessibility 已显式启用、目标包进入 allowlist 后启动；`ui.snapshot/find/wait` 与 token-only action 复用既有 package/window/generation/fingerprint、敏感界面、预算、暂停和停止契约，schema 不含坐标。
- `android-ui-task` 每次动作前取得 snapshot，仅使用 opaque node token，动作后重新 snapshot；package/window 变化、过期 token、checkpoint、敏感界面、断连、失败和用户停止均立即暂停或结束。

## 本轮可重复验证

- JVM/构建质量：`spotlessCheck`、`detekt`、Root/Automation/Skill/Policy/Framework/App 双 flavor 单测、App 双 flavor assemble、PRoot/CLI Runtime assemble。
- 边界脚本：`check-docs.sh`、`check-i18n.sh`、`check-lockfiles.sh`、`check-secrets.sh`、`verify-adr.sh`、`verify-variant-boundaries.sh`；最后一项同时检查 consumer 无 libsu/Root/Accessibility 内容及 developer APK 无 `root.exec`。
- 专用模拟器：`Helix_M9_API_29` 与 `Helix_M9_API_36` 上的 Root rootless、Automation fixture 和 App developer 全量 instrumentation。
- rooted 物理设备入口：HXA-094 使用 `tools/root/scripts/run-hxa094-rooted-matrix.sh` 分别执行 deny/grant；HXA-095 使用 `tools/root/scripts/run-hxa095-rooted-tools.sh` 执行高阶读取、scope 逃逸拒绝与 RootService crash 后 session 失效。两脚本均拒绝 emulator、API &lt; 34、非 `arm64-v8a` 和缺失 Root manager/version 证据。

## 当前工作树验证结果

- 综合 JVM/构建/静态门禁命令（Spotless、detekt、Root/Automation/App lint、Root/Automation/Skill/Model/Policy/Framework/App 双 flavor 单测、App 双 flavor 与 PRoot/CLI APK assemble）：exit 0，`BUILD SUCCESSFUL`，681 actionable tasks。
- 文档、i18n、lockfile、Secret、ADR、variant boundary 六项脚本：全部 exit 0；i18n 为 base/en/zh-rCN 591 keys parity，variant gate 同时确认 consumer 不含 developer-only 模块且 developer APK 不含 `root.exec`。
- App developer 全量 instrumentation：`Helix_M9_API_29` 完成 136 tests、0 failed；`Helix_M9_API_36` 完成 135 tests、0 failed。skip 是既有外部服务/系统条件门控，不作为通过证据。
- 新增 Advanced 设置入口与 consumer 缺席断言：API 29 上 developer/consumer 各 1 test，均通过。
- `:tools:root:connectedDebugAndroidTest`：API 29/36 均 0 failed；HXA-095 rooted-only 用例在无显式 `hxa095ExpectedRoot=granted` 时按设计 skip，不能计作 rooted 通过。
- `:tools:automation:connectedDebugAndroidTest`：API 29/36 均 0 failed；既有需要宿主编排的 force-stop setup/recovery 用例保持显式 skip，其 HXA-093 权威结果仍见完成记录。

## 未完成声明

当前没有可用的专用 rooted 物理设备证据。因此 HXA-094 与 HXA-095 仍未通过，M9 不能宣布整体完成；依赖/编译/JVM/rootless 模拟器通过也不能替代 Root grant、loss、revoke、RootService crash 或高阶 Root 工具的真机验收。
