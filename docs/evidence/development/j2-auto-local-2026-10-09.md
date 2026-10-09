# J2 同执行等待：2026-10-09 本地证据

范围：[HXA-248](../../development/tasks/HXA-248.md)。所有者明确授权模拟器、现有真实模型测试和逐任务提交；未推送、发布或更新日常模拟器主 APK。隔离 AVD 为 API36 arm64 Developer，运行器记录键盘设置、制品 SHA、设备属性和每项 instrumentation 结果；此轮不证明真机/OEM 或中文输入法完整验收。

## 实现与身份

`code.linux.job.start` v2 增加 BACKGROUND（兼容默认）与 AUTO。AUTO 最多观察 15 秒，快速完成经原控制准入/输出授权收取，慢任务返回同一 originalCallId。Dispatcher 先执行原 schema、权限、审批、deadline/物理资源路径，随后逻辑等待释放 Scheduler worker；观察结果经唯一验证/审计发布，不获得纯观察工具的权限。收取进入同宿主 control runner，防止越过原执行 admission；取消、权限/Binding 变化与失败保留已启动副作用事实。原 Runtime lease 不续期，UNKNOWN 不重放。

用户后台释放只停止原启动等待，不取消进程或其他等待。资格检查结合 Room 原调用与当前观察注册；UI 不授予权限，USER 服务再次验证原 Binding。按钮隐藏、重复操作和终态竞争不得假装重启或延长执行。

参考与取舍在 HXA-248 / Runtime ADR 中：固定 Codex `2351d9e1` unified_exec 及 Claude Code 后台任务文档；采用稳定执行身份与回复等待分离，独立实现，保留 Android lease、持久状态与容量边界。

## 当前执行

- `build/hxa248-20261009/api36-initial/`：4 通过、2 失败。异步会话 UI 夹具未等待更新；快速收取最初缺少 control admission。分别补同步点及共享同宿主 deadline runner，没有删除断言。
- `build/hxa248-20261009/api36-r2/`：6/6，通过 Connector 两项、原 J1 两项、AUTO 快/慢两项。
- `build/hxa248-20261009/lease-boundary/`：5/5。原 J1 两项及 AUTO 快任务、观察超时、原执行租期到期；15 秒等待未延长 2 秒 lease，结果 TIMED_OUT、无产物、resultAvailable=false、原身份结算。主 APK SHA `5ff2d67847cb9fc710e93cda84a896f37062cf9575153b8a82e6f212d00581c7`。
- `build/hxa248-20261009/background-project-ui/`：J2 的 Runtime 六项与按钮两项实际通过 8/8，包含 USER 释放同一原等待、重复释放 NOT_WAITING 后仍可等待/收取原 Job。整套另有一个 Project 测试初始化失败，不能称整套 9/9；该失败不属于 J2，通过范围按每项 instrumentation 状态核对。
- JVM 补充默认模式、取消不收取、收取失败副作用事实、唯一启动/审计、等待释放 worker、未知提交不观察/重放、最终 Binding 变化、同宿主控制与过期准入。早期夹具非法组合 `sideEffectFree=true/requiresReview=true` 被构造器拒绝，改为合法失败契约并保留断言。
- `build/hxa248-project-final-host-r2.log`：最终全仓 test/detekt、双渠道 Debug lint、APK 与 AndroidTest 编译通过（990 tasks，59 executed）；包含后台入口和项目旅程。此前新测试编译/静态规则失败均保留原日志，未跳过测试。`build/hxa248-final-source-r2.log`：源码、文档 765/任务 231、ADR36、i18n、密钥扫描通过；最终文档与资源更新仍需刷新源码门禁。`build/hxa248-final-format.log` spotlessCheck 通过；追加测试签名后再次检查。

## 真实模型失败边界

日常 `emulator-5554` 现有 Developer 主 APK SHA `5eabd1f3ddbabc1424963570f2894da29f9dcfa971c3c2f849d7b850ffd8516b`；测试 APK 单独更新，不替换主应用或导出数据库/密钥。现有 Qwen3.8-27B / OPENAI_CHAT_COMPLETIONS，经正常 Provider 路径，新建合成会话和唯一输出标记。

第一次 J1 原执行旅程 FAILED/PROTOCOL：三次成功搜索分别为 start、time.now、await/collect；start 与 await 成功，time.now 为 UNKNOWN_TOOL，未 collect。前三次请求用量输入 23,704 / 输出 969，第四次无用量回执。原副作用没有重放。

只增加明确搜索窗口替换说明的单变量试验仍 FAILED/PROTOCOL：一次组合搜索 `linux job time await` limit8；start、time.now、await 均成功，第四次失败，未 collect。前三次输入 26,494 / 输出 510，第四次无用量；测试清理经原 USER cancel/query/collect 完成，不把清理当模型成功。消除 UNKNOWN_TOOL 没有消除 PROTOCOL，不能宣称窗口说明修复了模型链路，不能将错误归因于累计预算或推导费用收益。

当前 PROTOCOL 分类不能区分非 2xx 拒绝与 SSE 形状违约，尚无该失败的 HTTP 状态/解码原因证据。两个真实模型失败不算 AUTO 实测（日常主 APK 为此前版本），也不由 scripted Runtime 通过覆盖。拒绝过一次过宽数据库导出后，改用应用内只读、仅合成夹具的状态/用量/搜索参数检查；未执行数据库导出。
