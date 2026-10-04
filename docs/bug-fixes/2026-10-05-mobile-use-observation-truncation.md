# Bug Fix: Mobile Use 深层界面采集与紧凑观察

Date: 2026-10-05
Status: fixed
Related HXA: HXA-244

## Problem

复核[两轮自主安装证据](../evidence/development/mobile-use-privileged-semantics-2026-10-05.md)：首轮 124 次工具调用中包含 30 次结果分页，最后已打开 APK，并进入“允许来自此来源”设置页，随后被测试 fixture 的 600 秒限时取消；不是在最终安装按钮处再次证明识别失败。第二轮约 30 秒结束，最后观察只有文件行外框，没有文件名，且采集自身 truncated=true。模型说要截图，但没有提交截图调用。最后一次请求仍包含截图工具，不能归因于工具未曝光或额度不足。

## Impact

深层界面的标签遗漏、结果过长造成模型反复读取或缺少下一步依据。

## Root cause

源码显示采集深度上限 16，节点上限 200，文字另有预算。旧结果没有记录具体截断原因，不能由旧日志唯一归因于深度。

## Fix and invariants

修复深层布局的确定性缺陷，并补充诊断：

- 深度上限改为 64，增加 24 层布局内文件名读取测试；上限之外仍截断并回收已持有节点。
- 保留节点和文字预算，记录四种具体截断原因，沿 Root/Shizuku 私有协议传回宿主；升级服务协议版本使旧服务不复用。
- 默认快照压缩空字段、默认 false 和结构信息；可点击父节点、文字、边界、敏感字段遮蔽和未选中复选状态保留。精确查找继续返回详细字段。
- snapshot/find/wait 共享采集截断说明：分页不能找回未采集节点，应选择已授权的截图观察并继续工具调用。普通分页仍使用原有结果读取路径。

模型提前结束的事实与执行完成区分：现有 AgentLoop 在没有工具调用时接受最终回复。本次没有加入关键词判定、隐式追加模型调用或安装专用流程；观察结果提示能否改善当前模型，仍需真实模型验证。

## Alternatives considered

仅延长测试超时不能解决无效观察；取消所有预算会扩大 IPC 和遍历成本；按最终回复关键词强制续行会改变模型的结束语义。本次保留有界观察和模型决定流程。

## Regression verification

主机验证日志位于忽略目录 `build/douyin-diagnosis/optimize-final.log`。新增深层文件名、截断原因、未选中控件保留、查找遗漏提示测试，运行 automation/mobile-use/app 单测、双渠道构建、Lint、格式及 Detekt；另执行文档和制品合同检查。

## Residual risk

本轮设备验证 **not requested**，未启动或操作模拟器，未安装新版，也未调用真实模型。前轮自主安装失败仍是当前设备事实，主机通过不能替代安装回归。

## Related records

- [权限决定](../adr/permissions/001-session-authorization.md)
- [当前状态](../development/status.md)
