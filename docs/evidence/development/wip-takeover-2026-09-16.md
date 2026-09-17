> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# Harness WIP 接手与提交记录

日期：2026-09-16。工作树：`worktree-harness-2.0`，起点 `0422c78b`。

所有者明确授权接手此前保留的并行WIP并提交，包括Runtime测试语言修正。此次范围是审查、修正和提交工作树中已有的源码、测试、治理与方案；不是提前实现计划中的终端、后台Job或HXA-202～207。没有改动main、推送、合并或发布。

## 收口内容

1. HXA-192遗留主机门禁修复：API29读取兼容、资源/复数/Composable命名、局部职责提取、CLI移除旧上限后的测试契约、国际化检查及其回归。不改变审批、取消、事务和工具作用域。
2. HXA-193已有单APK实现：developer链接两个Runtime库，以`:subscriptions`/`:proot`私有进程共享UID；consumer排除；QuickJS保持isolated UID。保留按需冷绑定、验证、结果对账和停止语义，删除旧安装器路径。PRoot工具v2与设置明确共享权限边界。
3. Runtime页面测试语言修正：API33+按当前应用语言读取期望标题，不使用instrumentation启动时缓存的语言；API29沿用原配置。真实跨进程页面仍必须可见。该修正现在随Runtime测试一起纳入提交，不再留待其他所有方。
4. ADR-PERMISSIONS-002～0051、研究文档归档、终端/审批/导航任务方案及索引落盘。接受状态沿用所有者已有授权；计划未实现的条目保持planned。更新当前门禁事实与依赖升级授权，历史失败记录保留日期与解释。
5. 76个一次性修改/暂存脚本和原始日志保留到忽略目录`build/wip-takeover-20260916/historical-debug/`，未删除原始内容。来源、原相对路径与SHA-256见`scripts/debug/archive/2026-09-16-wip-inventory.json`。已跟踪的可复用runner保持原位，历史脚本不作为当前源码的执行入口；机器路径、原始日志和下载资产不进入提交。

## 实际验证

- `./scripts/check-all.sh --all`：通过，日志`build/wip-takeover-20260916/check-all.log`。包含源码/ADR/国际化/秘密扫描、Spotless、Detekt、全模块JVM、Debug/Release与双flavor lint、构建、35个依赖锁及最终APK边界。
- 主机结果沿用同一生产源码的XML：4399通过、8个既有条件跳过、0失败。完整API29/36双flavor设备证据见[基线修复](../../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)；这轮没有重复跑未改动的全套设备用例。
- 本轮补跑`IntegratedRuntimeDeviceTest`与`IntegratedRuntimeUiDeviceTest`：API29与API36各2通过、0跳过、0失败。覆盖真实RootFS安装、旧验证锚不激活、真实Binder服务的同UID不同PID，以及五个订阅页面。API29证据`build/pre-hxa-device-20260916-100154/`；API36证据`build/pre-hxa-device-20260916-100249/`。每次创建独占模拟器并finally关闭；其他已有设备不动。
- 档案整理后源码检查通过；最终格式与差异检查在提交前执行。前一轮发现的Runtime语言修正也已在API36完整套件中通过，本轮专项再次通过。

复现Runtime专项（配置JDK17与Android SDK后）：

```bash
python3 scripts/debug/2026-09-16/run-pre-hxa-regressions.py --api29 --developer --class com.helix.app.proot.IntegratedRuntimeDeviceTest,com.helix.app.proot.IntegratedRuntimeUiDeviceTest --expected-skips 0
python3 scripts/debug/2026-09-16/run-isolated-api36-baseline.py --developer --class com.helix.app.proot.IntegratedRuntimeDeviceTest,com.helix.app.proot.IntegratedRuntimeUiDeviceTest --expected-skips 0
```

## 不等于整个HXA或发行完成

- HXA-192仍缺Plan用户闭环与审阅不授予执行期权限的专项设备证明；工具级Plan/Room通过不能代替它们。main集成未授权。
- HXA-193的远端CI尚未执行。已有锁定RootFS归档可在本地构建，默认Docker重建的旧xz-libs来源失效是历史已知问题；需要发布匹配归档或完成依赖升级后的资产重验。此次未发布资产、配置远端变量或重写RootFS lock，不能宣称干净远端环境可完整重建。
- 真实订阅账号、真机后台/OEM、长稳、升级与发行签名继续独立验收。fixture成功不替代这些证据。
- 新增的终端/后台方案只是已授权开发计划，未将其记录为现有产品能力。

源码与Runtime已提交为`c96c277a`，包含Runtime语言修正、相关设备测试与门禁修复。档案与文档分别提交，hash见Git历史及交付回复。最终工作树包含所有已接手源码；不再以“并行所有方待提交”保留这些实现。可复现构建仍需记录中的锁定Runtime资产，干净远端重建的限制没有因此消失。
