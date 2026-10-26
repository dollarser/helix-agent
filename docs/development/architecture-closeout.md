# 架构主线有界收尾

日期：2026-10-26。承接已交付 286／287／293-A 与有界实验，限过时计划校正、PRoot 展示职责和关联旧路径核查；不启动新增网页／MCP／跨会话能力，不推送或发布。

## 裁决与收益

- 总计划 §23.2、23.7 仍将 Engine→Room、AppAgentRuntime→宽 ChatService 回调写成“当前缺口”，已更正为历史动机及现行 Core／Application／Infrastructure 落点。保留原决策原因和当时验收，不以文案修正重新声称所有平台验收通过。HXA-286／287／293／294 与 status 的主线已完成结论保持；295 B/C 是未实现新能力，289 已交付有界 glob／字面量 grep。旧测试数据兼容描述也按当前 AGENTS 收敛。
- 实际局部耦合：PRoot 模块直接返回 `R.string` 与已翻译错误文本。现在返回 `ProotInstallationStatus` 安装事实和明确的管理页打开成功／失败；Settings 与 Capability Center 共用 UI 本地化映射。安装事实不等于进程可用或执行许可；被动查询仍只调用原 supervisor.installation，不 cold-bind／安装／执行。维护仅由明确点击触发。
- 删除 `ProotEntryNote`、`openEnvironmentNote` 和 `installationStatusResource`，迁移全部当前消费者与两变体入口，不保留兼容壳。共享的状态文案由 UI 主资源持有，三套本地化原文未改；Standard 仍不暴露 Linux 入口，明确返回 NOT_AVAILABLE，不假装支持执行。未改包名、签名、数据库或内部版本。
- Dispatcher 的两种 internal 具体执行器是可信观察／等待资格、原 call ID 校验和容量生命周期的闭合实现，当前未发现真实故障或新消费者收益，不为“消灭类型判断”增加可自报角色接口。截图名分支还承担本地模型屏幕披露，不能无等价受信来源信息改成外部 JSON 自报用途。FileHistoryPaths 是 request-only 投影，不是资源身份写入者，glob／grep 已同源接入；这些路径保留。
- AppAgentRuntime 仍有真实提交／取消适配及流结束后持久终态补读职责，ChatService 仍承担应用会话与展示协调；不是只为旧调用保留的空壳。Core 无 App／Android／Room 引用、旧 App Engine／启动取消桥／混合结算路径无当前实现，按本轮关联范围核查，不宣称全仓永久无废码。

## 验证与边界

最终生产源码 `GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Xmx6g' ./scripts/check-all.sh --debug` 完整退出 0：源检查、文档／ADR／本地化／秘密、格式／Detekt／两渠道 Lint、全 JVM 与双渠道 Debug、依赖锁和变体／CLI 边界通过。App Standard 1475、Advanced 1637 项零失败，各保留 5 项既有跳过；新增一项穷举安装状态的 UI 映射 JVM 回归。没有重建引擎或改变 Core／工具框架／底层 Runtime 代码。

独立 API36 ARM64 Standard 与 Advanced 最终各 4/4，通过被动 UI 不打开维护、实际适配三次查询不启动 :proot 进程、入口失败显示／仅显式点击重试清除错误，以及 Standard 模式无 Linux 管理入口。实际维护 Activity 的启动／安装／修复未执行，原 E2E 用例只更新当前 API 调用并编译，不据此宣称真实维修通过。既有 RuntimeSetup 测试误用包名尾缀判断渠道，本轮改用实际 AVAILABLE 能力事实，覆盖独立验证包而不削弱断言。

已查看浅色与暗色／1.3 字体组件截图，文案、错误、按钮可见；这是实际 Compose 组件的合成夹具，不是 Standard 新增 Linux 页面或全应用视觉矩阵。首轮夹具漏安全区导致标题靠近系统栏，已修正并重跑；之后 Lint 指出测试预期文案需配置感知，改 stringResource 后再次通过。原编译、源链接及 Lint 失败均保留，不跳过门禁。原始证据位于忽略的 `build/architecture-closeout-2026-10-26/`：`accepted-debug-gate.log`、最终 `consumer-accepted/`／`developer-accepted/`（实际 APK 哈希、JUnit、组件截图及进程退出）、`test-counts.json`。两实例均关闭，未发送真实模型请求。早期共享资源映射编译失败已保留，原因是四项状态文案此前仅在 Developer，已按共享 UI 所有权移入 main；不借此扩大 Standard 的运行能力。纯 JVM／实际 Compose／真实 Runtime 启动／模型效果分开报告。日常包未更新，本轮设备仅独立模拟器；不清日常数据、使用真机或发送真实模型请求。


## 最终候选与交付范围

已准备普通 Standard／Advanced Debug APK，包名仍为 `com.helix.agent`／`com.helix.agent.developer`，均 0.0.7／versionCode 1。签名与上轮已验证的日常安装记录匹配，两库 schema 1 未变，没有新增迁移／清除／兼容分支或应用身份变更。精确候选哈希及签名在忽略的 `daily-candidates.json`；本轮未访问日常数据或安装，后续若要求更新可先核实当时状态再保留数据覆盖，不沿用旧清除确认。原无关实施指南修改保留并排除提交。

Release/R8、真机／其他 OEM、真实维护／进程故障组合、系统栏及完整横屏视觉矩阵未由本轮覆盖。删除项仅普通 Kotlin 展示包装和静态资源接口，没有删除 JNI、反射／序列化入口；本轮不为此重复发行构建。HXA-288 的有条件候选与新增能力仍按真实收益启动，不新增架构欠账或自动开始新功能。
