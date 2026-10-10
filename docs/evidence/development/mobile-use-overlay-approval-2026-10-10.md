# Mobile Use 微信等待诊断与悬浮审批

日期：2026-10-10。Owner要求分析原测试会话中打开微信后不继续的问题，并随后授权实现悬浮审批。未提交、推送、发布或发送微信消息。

## 现有失败的证据

只读检查唯一 OnePlus PLC110/API35 实体设备的 Developer 应用数据库投影，当前窗口为微信 LauncherUI。09:45:20 UTC 启动的 Owner 所指 Turn 处于 RUNNING_TOOL、stepCount=3、无 turn error：ui.launch 与 ui.snapshot 已完成且结果 SUCCEEDED/verified=1；下一条 ui.click 状态为 AWAITING_APPROVAL，尚无结果。因此当前任务在等用户审批，不是“打开微信没执行”或模型仍在思考。手动打开文件传输助手不产生原审批决定，也不会自动继续。前一轮的 launch/snapshot FAILED 另作历史失败，不以它覆盖当前成功/等待事实，也不推断其原因已定位。

当前实现后台任务投影按 Turn.state 去重刷新，而请求审批时 Turn 仍为 RUNNING_TOOL；悬浮窗依赖该缓存的 awaitingApproval，存在漏刷新。返回按钮原来总先接管停止再导航，无法作为保留任务的审批详情入口。本次实现修正这两处交互。

诊断期间无批准、拒绝、取消、消息发送、重新执行或更改系统权限；手机留在 Owner 当前状态。含用户数据的临时数据库副本在只读汇总后删除，仓库只保留上述必要状态与时间；不保留消息、联系人、账号或凭据。

## 实现及边界

可信审批 sink 发布原任务身份给已选择的原生插件；Mobile Use 在首次动作待审时也能绑定显示。原请求卡的来源、目标、范围、操作、参数、网络、代码及限制等完整事实投影为可展开详情。深色窄卡宽度随屏宽收敛，内容区域按屏高限制并滚动；本次批准/拒绝在滚动区域外，各48dp，接管和查看会话分开。普通运行保留小控制窗，状态窗仍透传触摸、不抢焦点；窗口身份排除、物理操作移除和锁屏隐藏不变。

原生提交再次核对持久 Turn 所属会话、RUNNING_TOOL、原 call 的 AWAITING_APPROVAL 和原待审 ID；投影 claim 只用于去重，不提供凭证，最终由既有 broker 处理过期、取消、一次性决定及 Dispatcher 消费。错误不返回成功。不同会话、旧卡、已取消/终态和窗口隐藏时拒绝操作；批准只处理显示的一条，不提供全部批准。查看会话在审批状态保留任务，即使同时有另一入口决定审批也不会取消。

## 首次主机验收（设备尚未请求）

主机验收通过：`./scripts/check-all.sh --source` 的206项主机夹具通过（输出中的 FAIL_FUNCTIONAL 是预期失败夹具，不是真机执行）；`ORG_GRADLE_PROJECT_includeSpikes=true ./gradlew test` 全仓JVM命令通过。Developer app XML记录1269项、4项既有skip、0失败（可选本机归档/外部账号缺输入）；Mobile Use 225项与Plugin 55项零失败。新增4项投影/并发/顺序、1项窗口状态身份/取消/隐藏以及1项首次审批插件选择/撤销回归均通过。既有审批broker的取消、过期和持久一消费测试继续保留。双渠道lint/静态及Debug主APK和Developer AndroidTest APK编译通过；最后追加拒绝/结算等待状态刷新后重新执行完整host组合，命令为`./gradlew spotlessCheck detekt test :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebugAndroidTest -PincludeSpikes=true`；其最终退出在下方记录。`git diff --check` 通过。

初始静态检查发现wildcard import、文案格式参数spread以及窗口show复杂度，已用明确导入、局部资源格式说明和拆分显示更新修正；首次审批通知接线编译发现推断的旧Unit返回，明确应用门面的返回类型后新鲜双渠道编译通过，未削弱测试或修改审批契约。证据汇总临时脚本变量复用曾误写ignored的Developer AndroidTest APK，最终构建检测输出变化并重新package，三个最终Debug主/测试APK均通过apksigner verify；主APK、手机和用户文件未受影响，汇总改为只写明确文档路径。此错误不伪装为设备失败或通过。

构建产物在ignored的`app/build/outputs/apk/{developer,consumer}/debug/`，未安装手机。新布局、首次待审显示及返回会话交互仍须设备实测，不能从主机通过推断微信消息发送成功。Owner手动改过目标页面，原pending点击引用的旧快照可能已失效；本轮未替Owner批准，旧token继续接受原执行验证，不静默重试。本轮没有运行新布局的设备 UI、instrumentation 或真实模型测试，状态 not requested；编译与 JVM 不替代窄屏/大字体和 OEM 悬浮窗实际验收。

设备后续步骤：使用合成 Settings 任务及 APPROVAL_REQUIRED，验证首次 launch 待审、后续单条待审、拒绝/批准、展开完整长参数、查看会话不取消、接管/锁屏隐藏、旧卡/切换任务及物理操作移除；窄屏和大字体检查滚动及48dp操作按钮。不得借验收自动批准真实第三方消息。

Developer Debug主APK SHA-256：`c4b450ac515c0b415fb9362c7543fd5f61e4ce04797ee3416feb3b8edabf1726`。

最终追加补丁的完整组合命令退出0（BUILD SUCCESSFUL，1002 actionable tasks）；Developer/Consumer lint均通过。文档模板检查曾拒绝非标准任务章节名，已改为要求的“范围与验收要求/验证入口”，最终source重跑退出0（文档/ADR/i18n/secret检查通过）；新增测试及完整JVM/静态/构建主机验收完成。设备新布局验收保持not requested，不宣称当前手机已更新或微信发送任务已成功。

## Owner追加真机更新与验收

Owner随后明确要求“帮我更新到手机测试”，并确认暂时不操作手机、继续自动测试。2026-10-10 10:29–10:46 UTC，唯一指定 OnePlus PLC110/API35 上安装 DeveloperDebug；沿用 Owner 已授权的现有 Qwen3.8-27B 配置，只新建合成 Settings 测试会话，不批准或重试原微信任务，不发送第三方消息。日常模拟器没有用于此验收。

安装前确认没有在途任务；`adb -s <designated-physical-device> install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk` 两次均 Success。安装前后 Room identity 相同，原 session/turn/message ID 全部保留，没有清数据、卸载或重建数据库。最终实际安装 base.apk 与主机构建 SHA-256 一致：`504445fa4f4828b086e131b4a4d6f7413b495c83b31e44b90ae419a597eeebb6`（仍为0.0.5/code1，不能只用版本号区分本次产物）。三个主/测试 APK 均通过 apksigner verify。

首次安装后的首次悬浮批准确实进入执行，但 launch/snapshot FAILED：系统此时已关闭 Helix 无障碍，绑定服务和 enabled service 为空，不能把此轮记作成功。按 Owner 已授权的相关系统权限操作恢复更新前的 HelixAccessibilityService 后，新的 launch/snapshot SUCCEEDED/verified=1。第二次最终 APK 更新后该授权仍为1。此 OEM 更新现象单独记录，不以恢复权限掩盖失败；产品后端就绪检查和用户授权仍适用。

初版实机暴露长详情展开高度过大、摘要含技术诊断、背景半透明遮挡。最终补丁将内容上限限定240dp，摘要显示本地化操作与原参数，技术诊断留在完整详情中，审批卡改为不透明。宿主执行开始回调作等价提取，解决 detekt LongMethod，不修改调用身份或时序，不抑制门禁或弱化测试。

| 最终安装版本上的检查 | 实际结果 |
| --- | --- |
| 首次 ui.launch 执行前等待 | RUNNING_TOOL，原 ui.launch AWAITING_APPROVAL，无结果；等待指示及原生卡可见 |
| 展开、滚动、收起 | 详情可滚动，展开不会占满屏幕；批准/拒绝保持可见，操作期间原调用仍待审 |
| 原生本次批准 | 点击原生按钮后 ui.launch COMPLETED/SUCCEEDED/verified=1；真实系统 Settings 打开，随后 snapshot 成功 |
| Settings 上查看会话 | 原生按钮返回 Helix；同一任务仍 RUNNING_TOOL，ui.back 仍 AWAITING_APPROVAL，没有取消 |
| 原生拒绝 | ui.back DENIED，result DENIED/verified=0；Turn COMPLETED，模型未重试拒绝动作 |
| 待审批时接管 | 新合成 ui.launch 尚未执行；接管后 Turn 与 call/result 均 CANCELLED，verified=0，悬浮审批消失，不打开设置 |
| 两次 Home→Helix | 两次页面均正常显示，无可见黑屏，应用 PID 1628 保持相同 |

最终版另有一轮 Settings launch/back 都成功且自动回到 Helix，但中间审批由其他入口点击，不能归因为自动测试点击；已停止坐标操作并与 Owner 确认后重新完成上表受控原生按钮回归。UI 辅助器在焦点意外变化时拒绝执行，失败的坐标操作没有算作通过。

本次日志时间范围内未检出 Helix ANR，设备全局 AndroidRuntime FATAL EXCEPTION 标题为0；只代表所取 logcat 范围，不能证明长期稳定性或其他 OEM/后端没有问题。原生 UI 使用截图观察和原始持久状态核对，不运行会抑制无障碍服务的 UiAutomator dump、不向真实用户数据库注入测试夹具。原始截图和数据库副本含用户信息，仅临时私有保存，汇总后删除；仓库不保存凭据、原消息或截图。

布局修正后的新鲜完整主机组合仍为 `./gradlew spotlessCheck detekt test :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebugAndroidTest -PincludeSpikes=true`，退出0、BUILD SUCCESSFUL，1045 actionable tasks。source/doc 门禁追加结果另记。设备限定路径 passed；锁屏/进程死亡重恢复、不同字体/旋转/窄屏、其他 OEM 和 Root/Shizuku 后端矩阵本轮 pending，已有主机取消/失效/隐藏边界测试继续保留。未验证微信消息发送成功。

追加文档后的 `./scripts/check-all.sh --source` 退出0，206项主机夹具及文档/ADR/i18n/secret检查通过；其中 FAIL_FUNCTIONAL 输出仍是预期失败夹具。`git diff --check` 通过。私有原始数据库与截图已删除，手机留在 Helix 已停止的合成测试会话，无在途测试任务、无残留日志采集进程。
