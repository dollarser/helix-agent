# Bug Fix: 当前会话入口、占用刷新与可见消息队列

Status: fixed
Date: 2026-10-04
Related HXA: HXA-216, HXA-234
Affected modules: core/agent, core/storage, app

## Problem

所有者要求当前会话单击直达、上下文占用在模型每次返回 Harness 后更新，以及工作期间新消息直接可见、可选立即发送，否则等待当前回复完成后依次自动处理。

## Impact

长任务的圆环直到最终回答才变化。已有 Queue/Steer 交付机制藏在折叠面板和输入选项中，用户不能从已排队消息直接选择加入当前任务。当前会话的独立标题和子项也显得像多层入口。

## Root cause

ChatContextProjection 在选择完成的 ModelCall 前先过滤 Turn 终态，排除了仍执行工具的任务。Core 在提交中间模型结果之后没有立即发布刷新。队列面板依赖界面 refreshKey 而没有订阅持久输入状态，正文和管理动作仅在弹层可见；队列没有针对原 revision 和指定 live Turn 的转换入口。

## Fix and invariants

- 抽屉当前会话合成一项，主标题“当前会话”、副标题为会话名，整行单击直接打开，不使用展开箭头或子栏目。
- 圆环读取每次已完成、匹配当前会话/模型/接入身份的 ModelCall 用量。在中间模型结果落库后、工具开始前刷新。流式输出尚未结束时保留上次确认值；摘要请求、失败或未提交压缩不污染普通输入用量，不把累计计费 tokens 当上下文占用。
- 输入框上方直接展示待发消息预览与附件数量；队列内容有独立限高滚动区。保留管理弹层中的完整正文、编辑、撤回和需处理输入的恢复操作。Room 投递变化驱动刷新，包含“已加入历史”和“已进入后续请求”的区别，不靠定时轮询。
- 单项“立即发送”重用原 inputId、正文、附件、配置和批准的数据传输身份，校验当前显示的会话/live Turn，再以 revision CAS 转为 STEER。安全衔接点消费；不拆开未结束的工具批、不额外启动 Turn、不打断正在执行的外部操作。
- 旧 revision、跨会话、已结束/正在取消的 Turn、配置不匹配或已消费输入不能转换；失败保留原队列，不复制重提。转换后重试原提交仍返回既有回执。
- 未点击立即发送时继续既有 FIFO successor 准入。手动 Stop/进程恢复停泊的输入不因新按钮自动释放；正常完成与自动恢复的已有授权边界保持。

## Alternatives considered

不按流式输出 token 数推算上下文占用，因为它不是请求输入用量。不撤回再重新发送排队消息，避免重复请求及附件/出网身份漂移。不取消正在运行的工具来模拟立即发送，避免把尚未结束的副作用误报为可安全重试。

## Regression verification

主机 JVM 测试 556 项通过（core:agent 297、core:storage 230、app 定向 29），无失败或跳过。Developer/Consumer debug APK、两个 app AndroidTest APK 与 storage AndroidTest APK 编译通过；双渠道 Lint、Spotless、Detekt、集成 APK 合同校验与 git diff --check 通过。当前设备验证为 not requested；未启动、使用或安装模拟器，未访问真实模型账号。

新增/扩展用例覆盖：提交模型结果后先刷新再执行工具、下一模型调用尚未完成时保持中间用量、真实 Room 中 RUNNING_TOOL 的占用投影、队列转换与重复提交去重、旧 Turn 拒绝、跨会话/终态/停泊拒绝、转换与撤回的 revision 竞争、可见预览和单击当前会话。AndroidTest 用例仅编译，不宣称已执行。

```bash
./gradlew :core:agent:test :core:storage:testDebugUnitTest \
  :app:testDeveloperDebugUnitTest --tests 'com.helix.app.agent.ChatContextUsageTest' \
  --tests 'com.helix.app.chat.SessionInput*Test' --tests 'com.helix.app.ui.ConversationDraft*Test' \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest \
  :core:storage:assembleDebugAndroidTest :app:lintDeveloperDebug :app:lintConsumerDebug \
  spotlessCheck detekt --console=plain
bash scripts/check-all.sh --source
python3 scripts/verify-integrated-runtime-apks.py
git diff --check
```

源码总门禁首次遇到既有 ADR-WORKSPACE-005 的全角标题冒号及缺失元数据/必备节；补齐 HXA 和结构说明，未修改项目决定或放宽校验。修复后源码总门禁通过，包括脚本回归、文档、ADR、国际化和密钥扫描。

主机日志位于 ignored `build/conversation-interaction/`。本次未提交、推送或发布。

## Residual risk

本轮未运行真实 Room 并发/网络流/Compose 设备用例，也未验证小屏键盘弹出后的实机布局；主机通过与 AndroidTest 编译不替代这些设备验收。立即发送表示等待合法衔接点加入，不保证远端模型在点击瞬间收到消息。Provider 不报告有效用量时保留未知或上次确认值。

## Related records

- [Turn 执行与输入交付](../adr/agent/001-turn-coordination.md)
- [上下文压缩与占用](../adr/agent/002-context-compaction.md)
- [当前实施状态](../development/status.md)
