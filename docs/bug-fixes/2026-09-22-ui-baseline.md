# Bug Fix: UI 重构前基线修复

Status: fixed
Date: 2026-09-22
Related HXA: HXA-218
Affected modules: app androidTest, local runtime assets

基线：`645fa680`，HXA-218独立工作树。此页记录重构前发现的问题；后续UI验收不能掩盖基线失败。

## Problem

新工作树首次主机检查缺少忽略资产；设备基线发现扩展入口及会话测试的定位/时序失败。

## Impact

基线无法作为UI重构前后对照，可能将旧测试失败误认为新版界面回归。

## Root cause

资产不随Git工作树复制；旧测试依赖默认扩展Tab及异步界面就绪的错误假设。模型测试的新建入口绕过真实UI事件，失败在草稿等待阶段。

## Fix and invariants

### 环境准备

新工作树不包含Git忽略的PRoot/RootFS资产，首次完整主机检查在 `verifyDeveloperRuntimeAssets` 失败。通过 `scripts/build-proot-assets.sh` verify模式，复用锁定归档并校验哈希及ELF，资产门禁通过。随后完整 `./scripts/check-all.sh --all` 通过；没有修改锁文件、校验或分发边界。

### 基线测试修复

- `ConversationTopBarDeviceTest.destinationHeadersAndRealExtensionsAreReachable`：市场集成后扩展首页默认是市场，旧测试直接寻找Skill管理按钮。先选择真实“管理”Tab，再保留原作者、安装和Connector入口断言；没有绕过功能或删除断言。
- `SessionForkFlowDeviceTest.corruptHistoryShowsFailureAndEarlierMessageCanStillFork`：只等待消息数量为2，不能确认当前界面已切到目标会话并完成渲染。失败可发生在第一次查找分支按钮之前，不能解释为分支服务错误。改为等待目标会话ID及精确消息ID列表，等待生产timeline语义节点，再在LazyColumn中滚动至稳定标记并点击。保留损坏历史拒绝、原会话不变、错误提示及更早消息成功分支的全部断言，并保留失败时语义树诊断。
- `SessionModelDeviceTest.selectionPersistsAndNextRequestUsesTheModelWhileActiveSelectionIsRejected`：fixed4在API36等待新草稿时超时，尚未执行模型选择。旧测试直接从instrumentation线程调用新建；改为点击生产新建按钮，让交互通过真实UI入口。没有延长超时、增加重试或改变服务逻辑；保留模型持久化、请求采用选定模型及运行中拒绝修改的全部断言。此为测试入口修复，不据此声称排除了所有服务并发风险。

## Alternatives considered

没有扩大超时或盲目重试、删断言、跳过测试；通过实际入口和精确UI身份同步。没有修改产品服务并发协议。

## Regression verification

忽略目录中的过程证据：

- `build/ui-refactor-baseline/host.log`：首次资产缺失。
- `build/ui-refactor-baseline/assets.log`：锁定资产准备及asset-gate通过。
- `build/ui-refactor-baseline-verified/host.log`：完整主机通过；其devices中的consumer/API29为31通过、2失败。
- `build/ui-refactor-baseline-fixed2/host.log`：完整主机再次通过；其设备consumer/API29为32通过、1失败。
- `build/ui-refactor-baseline-devices-fixed3/`：只有滚动定位改动仍32通过、1失败，未据此关闭问题。
- `build/ui-refactor-baseline-devices-fixed4/`：consumer/API29为33通过，consumer/API36为32通过、1失败；失败位于模型测试的新建草稿等待，后两组未执行。
- `build/ui-refactor-baseline-devices-fixed5/`：全部基线修复后的API29/36 × consumer/developer，每组33/33，合计132通过、0失败、0跳过。测试APK由同树重新构建；每组独占模拟器，严格方法集合及APK哈希均在证据目录内。

格式检查曾发现新增import顺序问题，修正后进入上述fixed2主机通过；一次错误的模块级Spotless命令未运行，正确入口在仓库根。所有原始失败日志保留，不作为最终通过结果。

## Residual risk

这132项是当前切片的基线，不是完整产品、真实账号或物理设备验收。模型测试通过不证明服务全部并发边界；相关协议按214～217独立验证。

## Related records

[HXA-218](../completion-records/HXA-218.md)、[UI方案](../research/ui-interaction-optimization.md)。
