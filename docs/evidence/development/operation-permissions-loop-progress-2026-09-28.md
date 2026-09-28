# 操作分类权限与无进展保护：2026-09-28

## 范围与来源

所有者明确要求按前轮方案优化：取消 L0–L3 工具等级，以操作类型、效果范围与用户规则决定授权；长任务保持较大预算，同时识别无进展循环。本记录覆盖该增量，不替代历史系统基线或设备验收。

基于 `codex/tool-discovery-eval` / `8f26b704` 的未提交工作树，保留上一轮系统设置授权、暂停恢复、滑块及预算改动；未改写主 checkout 的插件工作，未提交、推送或合并。此处记录的是工作树验证，非干净提交的正式 P5。

## 实现

- 注册、Policy、Dispatcher、审批展示和新审计移除 `RiskLevel`、`baseRisk`、`dynamicRisk`。按 READ_ONLY / METADATA / LOCAL_MUTATION / NETWORK / EXTERNAL_ACTION / CODE_EXECUTION / PRIVILEGED 展示具体操作；网络上传/下载继续结合现有来源、目标与文件效果分类。
- 用户 ALLOW/ASK/DENY 与可信 effect footprint 仍是会话授权来源。工具声明、模型 intent、MCP/Skill 内容不产生权限；Capability、凭据、SSRF、Chat/Plan、精确 proof、执行前复检和 UNKNOWN/review 保留。未接入会话授权时，非只读/闭合元数据操作要求精确批准。
- 删除等级后 descriptor contractHash 改变，旧批准不能跨契约重用。旧审计缺少 operationClass 时不猜测分类，其余持久事实继续可读。无 Room 表结构修改。
- 最近 12 条持久调用/结果用于识别长度 1 或 2 的重复序列：连续 3 次给模型固定警告，6 次停止。签名包含工具身份/版本、规范业务参数 hash、结果状态、完整内容引用和摘要；忽略 call ID 与 modelIntent。
- 仅已结算失败/拒绝，或可信、幂等、已验证的稳定读取参与。参数/结果变化、成功修改、未结算或未验证结果打断序列。成功的 Accessibility、时间、Goal 状态及后台 Job 观察豁免；用户 steering 持久记录重置点。不是结果缓存，不把重复调用改写成成功。
- 警告作为固定 Harness 消息进入后续模型历史；不信任存储中的任意同名内容。停止保留真实结果，普通会话从保存结果继续；Goal 转 INPUT_REQUIRED，避免自动续跑同一循环；不确定副作用始终优先进入核实流程。
- 保留上一轮较大默认预算及已有用户配置。本轮不增加更小的通用调用上限。

## 主机验证

最终命令及日志（生成物均位于忽略的 build 目录）：

```sh
./gradlew spotlessCheck test detekt \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --continue
./scripts/check-all.sh --source
git diff --check
```

- Gradle 主机门禁通过：全工程 JVM/unit、两个通道 lint、debug APK、AndroidTest APK、spotlessCheck、detekt。最终日志 `build/operation-final-validation.log`。
- 新增三个专门测试类每通道 11 项：序列检测 4、持久记录重建/变化/不确定状态/观察豁免 5、Goal 结算 2；另补历史警告回填与恢复 UI 测试。既有权限、授权竞态、contractHash、审计、凭据和网络边界测试继续运行。
- `check-all.sh --source` 与 diff 检查通过，日志 `build/operation-source-final.log`。
- 中间发现并修复旧等级断言、审批卡重复分类输入不一致和格式问题；一次 detekt Kotlin parser 异常在后续完整运行未复现。未删除或跳过门禁来接受结果。

## 验收边界

- 本轮设备验证：**not requested**。AndroidTest APK 编译不代表设备运行；上一轮 API36 亮度成功不覆盖此轮权限/循环改动。
- 从复制的持久记录重建是 JVM 证据，尚无此版本的真实进程重开轨迹。没有调用真实外部账号或新增付费模型评测。
- 只识别固定长度 1/2 的稳定重复，未证明任意 AgentLoop 死循环都能检测。成功写入、长周期循环及生成不同但无用的参数仍主要受现有预算约束；一般文件读取轮询若长期完全不变可能触发，需要后续轨迹评估阈值。
- 1000 条豁免观察的单元输入不是千次真实模型/工具长程验收。后续可在 owner 明确授权后做 targeted 模拟器/模型轨迹，观察恢复入口、误停与真正循环，不扩大为完整设备基线。
