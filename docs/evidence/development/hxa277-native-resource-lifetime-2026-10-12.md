# HXA-277 原生资源归属与在途释放验收

本机日期：2026-10-12（执行 UTC 2026-10-11）。Owner 授权 276→279、本地模拟器/既有 Qwen及阶段本地提交。本记录证明 HXA-277 有界范围，不代表完整 OEM、Root/Shizuku、长期无回调或第三方账号通过。日常模拟器未参与夹具、未在此阶段更新。

## 实现与原有 owner

Mobile Use 构造不再写全局 presentation 工厂；只有真实持有资源的 trusted native contribution 提供 registration。PluginRegistry 撤下工具新准入后退休句柄，NativeResource 保留原调用/物理回调引用至实际退出，最后引用后清理；ACTIVE、DRAINING、CLEANING、CLOSED、CLEANUP_FAILED 是资源事实，不是第二份 Turn/Job 状态。窗口移除在主线程执行，调用方有限等待确认；失败记录并传播，没有失败后报 CLOSED 或隐式重试。原服务连接/授权继续由宿主负责，不因单个引用关闭而断开共享后端。

真实截图存在“等待返回、Android 回调未结束、抑制先关闭”的原路径，改为物理 ticket 保留抑制及准确呈现实例直到回调。关闭旧 registration、迟到/重复回调均不能修改替代实例；通知停止携带原 conversation/Turn，旧通知不能停止新任务。正常 ToolDispatcher、scope、授权及 UNKNOWN 语义保持，取消不重放动作。

Browser 贡献不创建 Activity/WebView，也不制造空 resource；手动页面及实际 WebView 继续由 BrowserViewOwner 所有。修正后独立观察各持有自己的回调/超时注册，第二次观察不取消第一次的超时注册；销毁时原观察明确结算一次。Controller 校验原 owner、host 和 navigation，旧结果不更新新页面，已派发动作失去结果时仍按未知效果处理。观察等待结束不等于渲染器执行退出，真实 View 在其 Activity owner 生命周期内继续存在。

| 事实 | 既有 owner | 释放依据与本次验证 |
| --- | --- | --- |
| 整轮 live driver、持久终态 | TurnEngine / Room / TurnCoordinator | 原事务结算；新 Room fixture 验证 CANCELLED 不能释放仍运行 executor |
| 调度与 Job 观察槽位 | ToolScheduler / JobObservationService | 原观察结算释放；全仓既有等待/取消/原序回填回归保持，不新建等待注册表 |
| 实际 executor/Runtime 容量与原身份 | ExecutionOwnership / ToolExecutionPools / Runtime；Android gesture physical ticket | executor 或实际平台 callback 退出；原 ID/ generation 保留，Future.isDone 不当退出证明 |
| 原生呈现与共享 View 引用 | Native registration / NativeResource；Activity BrowserViewOwner | 最后实际使用引用退出才清理呈现，另一消费者仍在时不关闭；浏览器 Agent 停用不关闭手动标签 |

## 主机与设备验证

全仓 test/detekt、双渠道 Debug lint、APK与AndroidTest APK、Mobile Use和Browser library测试 APK编译通过，日志 `build/closeout-2026-10-12/hxa277-host-final-1.log`（1m14s）。新增 Room 跨层 fixture 后双渠道测试 APK及detekt复验通过（`hxa277-turn-build-2.log`，42s）。最终完整主机复验 `hxa277-host-final-2.log` 成功（1m6s）；最终 source 检查 `hxa277-source-final.log` 通过（206 项主机夹具、832 篇文档、36 份 ADR、i18n/密钥检查）。source 日志中的 mock GUI FAIL_FUNCTIONAL 是主机负例，不是设备执行结果。

NativeResource、注册与物理槽位测试覆盖两个消费者、取消等待而原回调仍运行、并发最后释放、重复退休、替代实例、部分初始化失败、清理失败和错误传播。原 ExecutionOwnership/JobObservation/Scheduler 回归复用原 fixture，未删除/跳过或弱化原断言。

API36均使用 owned runner 的自有只读 `Helix_HXA271_API36`，所有进程关闭 exit0；每轮启用主机键盘/保留 IME，未另计中文拼音验收。

| 范围 | 当前执行 | 输出目录 | App / test SHA-256 |
| --- | --- | --- | --- |
| 真实无障碍服务/浮窗 | 8/8，0 skip，54.358s | `build/hxa277-mobile-physical/` | 两者均 `8d9ccf149e93d614e6b10da81af0c8d7f03a67e0893581484e6c4c7836b6779b` |
| 共享 Browser 引擎/观察资源 | 32/32，0 skip，实际状态流完结 | `build/hxa277-browser-resources/` | 两者均 `18d5cee551d1c963b3b219df8efacbf3a5885a38a53e2a667d5bd0983ca548f5` |
| Developer 应用组合 | 20/20，0 skip，45.124s | `build/hxa277-developer-cross-owner-final/` | `17ceae328212c630714e0e89c5fe41a351e59c2161836b7198d0ee299141539a` / `7a68eb2311bc27f7aa7a633ffb86778af7b7aa442f7567d6c0c639b302cb2f2f` |
| Standard/consumer 应用组合 | 19/19，0 skip，44.396s | `build/hxa277-consumer-cross-owner-final/` | `9f069f3dcd6601c9fe7252cd4f1115b4a9076fc49132267caba177aa5ba11802` / `4892b4f1d17e1b90bc586f38073212bad6e45b11cff1288b8913eb51b5f7723c` |
| 最终 Developer 制品复核 | 20/20，0 skip，26.803s | `build/hxa277-developer-current-artifact/` | `00ee7bce01ab31fa747d7de620a6bd07c8dbd1ed83df6b75f424f6ff0993bea5` / `7a68eb2311bc27f7aa7a633ffb86778af7b7aa442f7567d6c0c639b302cb2f2f` |

最终全仓构建后的 Developer APK 与先前制品有一个 `classes8.dex` 条目不同，差异原因未确认；因此对最终精确制品重新执行同一20项组合，全部通过，自有模拟器关闭 exit0。Consumer 和测试 APK 哈希未变。后续内容策略基线冻结最终 Developer 制品，不以此前通过推定新字节制品通过。

Mobile Use 使用真正开启的 Helix 无障碍服务，包括实际节点/系统操作与授权撤销；新增 fixture 将真实 SDK 手势派发给 Android，停止等待/退休后 ticket 与呈现仍保留，真实完成或取消回调才释放。该 SDK fixture 没有调用模型或绕称完整模型任务通过；不是零工具 ChatReply 替代后端。Browser 32 项是共享实现验收，独立观察和原 owner 销毁均实际运行，不计作每渠道各32项。

双渠道应用组合覆盖 Browser 选择/撤销、Activity重建、实际截图像素披露、Room取消竞态和真实OkHttp本地受控SSE停止/旋转/断连；Developer另含原执行身份不阻塞独立工具。新增 Room fixture 强制 executor 忽略中断到原退出信号：Turn已持久CANCELLED、Future已结束时，同原ID不能重新准入、资源仍DRAINING；executor退出后另一消费者保持引用，最后释放才CLOSED。受控SSE不是既有Qwen服务，未声称真实模型推理通过。

## 失败尝试和验收边界

初版编译的缩进后行长、测试注释行长及泛型清理异常记录的静态检查失败均保留于 `hxa277-build-1`～`5`、`hxa277-turn-build`；重排代码/缩短注释并明确记录后原样传播异常，未删除测试或吞异常报成功。第一次双渠道组合命令误写不存在的 `ActivityLifecycleDeviceTest`，各有一个 ClassNotFound 初始化失败，保留 `hxa277-*-cross-owner/`；改为实际 `BrowserActivityLifecycleDeviceTest` 后同冻结制品完整复跑通过。

本次没有真实 Qwen 请求；三类内容策略的真实模型对照由 HXA-278 承接。未在真机、Root/Shizuku或其他OEM运行，也未以短跑关闭“平台永不回调”的长稳边界；缺少真实退出证据时保留物理占用及资源事实。旧DB/旧审批迁移、新hooks、子Agent引擎、LSP、每插件server均未加入。
