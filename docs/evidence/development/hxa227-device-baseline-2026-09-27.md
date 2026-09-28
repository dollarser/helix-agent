# HXA-227 设备轨迹与当前类基线补充验收

状态：本次受控模拟器轨迹与普通全量基线已完成；保留专用 runner、条件跳过及历史 SAF 间歇问题边界。所有者于 2026-09-27 明确要求按模拟器路线继续。使用 API29/API36 × consumer/developer 的受控 fixture，在独占 API36 consumer 模拟器执行当前类清单；不使用物理设备、真实 Provider/账号或付费额度。此前 HXA-227 完成记录证明的 host 范围不改写为历史设备通过。

## 轨迹契约

`evals/trajectory/core-device.json` 的八类 case 对应十个精确 `class#method` selector。Queue、Steer、Cancel/UNKNOWN、Review、Room reopen recovery、Goal、Compaction 与工具失败换路分别报告。Room reopen 不冒充 OS SIGKILL；Workspace 的实际进程杀死证据属于 HXA-210，不合并为本次轨迹数。

使用 `scripts/run-owned-emulator.py --raw-results` 保存 Android 原始逐测试状态；`scripts/instrumentation_junit.py` 拒绝空结果、缺 start/terminal、重复 test、未知 code、缺最终 runner 结算。原始失败/错误/skip 保留，不能由总数推导每个方法 PASS。`summarize-hxa227-device.py` 校验十个 selector 精确相等，再输出 JUnit、context、envelopes 和 JSON/Markdown 汇总。未知 trajectory/cost 保留 null，不制造时长、token 或 first-pass 指标。

## 已发现的 fixture 漂移

- Goal 队列测试在会话创建后修改全局 Goal 默认预算，无法改变 HXA-228 已冻结的 per-Session 配置；改为等待 durable mode/turn budget，然后在 fixture 内设置该 Session 的 Goal budget。原预算继承/队列/取消断言不变。
- 工具恢复 fixture 用模型 call ID 查询内部主键结果，且把实际历史回填 `[TOOL_FAILED] ...` 当 JSON 解析。改用内部 id 并精确校验失败标记/目标名及 Room FAILED，再要求后续 time 工具成功、恰好三次请求、无失败工具重试。捕获脚本 verifier 异常后重新抛出，避免服务线程错误只表现为网络超时。
- 第一轮 consumer API29 8/10，第二轮 9/10；原始失败与诊断保存在 `build/hxa227/device/consumer-api29-r1`、`consumer-api29-r2`、`recovery-diagnostic-api29-r1`。失败记录不覆盖，不算通过。

## 全量清单

早期轨迹四组合已完成：`consumer-final-api29-r1`、`developer-final-api29-r1`、`consumer-final-api36-r1`、`developer-final-api36-r1` 均 10/10 方法、8/8 case 通过，0 skip。汇总为 `build/hxa227/device-trajectory-final.json`：40 methods / 32 cases，四个 owned emulator 已关闭，各 flavor 两 API 使用完全相同 app/test APK。每组在 `build/hxa227/device/` 下保留 source manifest、context、APK hash、raw status、JUnit 和报告。该轮早于后续启动恢复及 fixture 修正，不能替代最终制品验证。

当时新增 test helper 后重新编译并执行上述矩阵。`device-host-gate-r4.log` 为当时完整 `check-all.sh --all` exit 0；原始状态转换器 3 项、当时 baseline runner 分类/锁 5 项与 eval 18 项 Python 检查均通过。当时生产源码无 diff。两处 LongMethod 已拆 helper；一次运行中修改入口导致 shell EOF 的失败原样保留，该轮 gate 在冻结入口后重跑。此处为历史结果，当前源码须另跑最终 host gate。

当前源码发现 198 个 consumer 公共 androidTest 类；历史 183 类仅作为历史范围。清单为 `build/hxa227/current-consumer-manifest.json`，SHA-256 `cae86e52df5dfd8d6d6ac72894e2af58d3f3a9f05ce56fdf6ee38c0043736b4f`。

沿用 single-writer、逐类隔离、精确失败签名与 crash health probe。新增 WorkspaceProcessRecoveryDeviceTest/WorkspaceBackupRecoveryDeviceTest 必须分阶段执行，登记 PHASE_RUNNER_REQUIRED，不按普通无 seed 运行的断言失败归为产品回归，也不算 PASS。全量运行仅清理本次新建只读 emulator 中的 fixture 应用数据；wrapper 每个设备调用前检查 owned PID，不使用用户设备。

## 验收边界

第一轮全量扫描 `build/hxa227/full-consumer-api36-r1` 使用上述 consumer API36 冻结 APK。初步失败归因：历史会话入口测试仍在新会话页等待列表；Connector/Skill 安装 fixture 在异步 mode 更新后立即 dispatch；附件 Room reopen fixture 的第二个 service scope 未结束就关闭数据库，引发后台查询 closed connection。保留原始逐类日志，修复结果单独记录。

第一轮已扫描 198/198，缺失/重复 0，owned emulator 已关闭。原始分类：PASS 153、KNOWN_EXISTING_FAILURE 4、NEW_REGRESSION 14、NO_VERDICT / PROCESS_CRASH 2、PHASE_RUNNER_REQUIRED 9、SKIP / ASSUMPTION 16。该原始 PASS 包含下述未验证的 soak，不是 153 类功能通过；审计后普通 PASS 上限为 152，专用 verifier 待验至少 10。20 个失败/崩溃类全部纳入 `repair-api36-r1`，不以历史失败名单豁免。

第一批修复遵循当前契约：直接 dispatch 的安装测试明确传入 PLAN/ACT；真实 ChatService 测试等待 per-Session mode/budget 与打开状态；fork 验证独立 managed Workspace；文件旅程/Git fixture 位于真实绑定目录；草稿验证 canonical Workspace identity 和相同 submission receipt 幂等；分享/缺 Provider/权限恢复使用当前 UI 入口。旧“发送即抢占”Goal 用例改为明确 STEER，验证同一 Turn/GoalRun 和累计用量。附件恢复 scope 在关闭数据库前 cancel-and-join，原持久化/内容断言保留。

runner 补上 TimeoutExpired 部分 bytes 输出的解码并保留 NO_VERDICT，失败追加 logcat。Python baseline 分类/锁/超时测试 7/7 通过；两渠道 AndroidTest APK 与 detekt 编译通过，设备结果见下文。

`repair-api36-r1` 完成 20/20 类：17 PASS、3 FAIL、0 crash；owned emulator 已关闭。附件 32 方法可完整执行，剩余回填测试发现授权与 executor 混用两个 Workspace registry，后续改为 fixture registry + 真实 ReadTool；市场页独立 Compose 容器未留系统栏 inset（英语按钮 bounds bottom=2400），补 safeDrawingPadding；文件旅程主体通过，清理使用 awaited stopTurn 后再删除 fixture，避免异步 stop 读取已删除 Turn。第二轮 `repair-api36-r2` 专项复验这三类。初次图像 probe 的无逐方法结果崩溃仍保留为历史未定因记录；r1 四方法通过，不据此推断崩溃根因。

额外审计发现 `MainAppCombinedSoakDeviceTest` 的 JUnit 返回不代表任务成功，其源码契约要求专用 runner 读取 `soak-done.json` 等证据。普通全量 runner 的原始 PASS 不作为 soak 功能验收；后续汇总必须明确这个边界。

`repair-api36-r2` 已完成：附件 32/32、市场 UI 2/2、文件旅程 4/4，共 3/3 类、38/38 方法通过。`audited-summary.json` 从原始逐方法状态核验结果，确认 source manifest 未变、保存的 APK hash 一致、owned emulator 已关闭。随后启动 `full-consumer-api36-r2`，使用最新 test APK 从头扫描完整 198 类；修复专项不与最终全量结果累加。

前期修复限 androidTest、host evidence/parser/runner 与文档；后续发现并修复一处生产启动恢复竞态，见下文。未修改生产 Harness/权限/状态机。完整结论须等待四组合轨迹、当前清单完整覆盖、失败归因与对应修复复验、host gate 和最终源码/制品身份核对。

## 第二轮全量与顺序竞态

`full-consumer-api36-r2` 完整覆盖 198 类，但不是通过轮次：普通 PASS 166、失败 5、进程崩溃 1、专用 runner 10、条件跳过 16。原始结果审计拒绝缺少逐方法状态的图像 probe，不生成成功报告。后续 `repair-api36-r3` 保留失败，并加入其前序 WorkspaceSystemSafDeviceTest 复现执行顺序。

- SessionInputApproval fixture 强转首条回执为 Accepted；契约允许 Enqueued 后再创建 Turn。改为校验两种合法回执并等待唯一 durable Turn，等待 ACT 落盘；审批拒绝、零执行与 wire 顺序断言保留。
- GoalModelCancellation fixture 的 mode/budget 未等待落盘；补等待后暴露其 10,000 总 token 预算不足以容纳当前工具目录。socket 取消测试改用 100,000 总 token，原 2 秒 wake limit、停止/断网、恰好一次模型调用与结算断言不变，不冒充 token-limit 边界测试。
- UI reset 等待 Compose idle，并在同一 UI turn 内 close/new，避免 Conversation 的空会话 effect 插入两步之间。启动恢复测试在前台 Conversation 页面关闭会话会触发自动草稿，改为停至 CREATED 后关闭并重建 Activity。手动压缩的 durable 结果先于 UI 投影，等待相同投影断言而非立即读取。
- 图像 probe 在 `repair-api36-r3` 仍于任何方法开始前退出。系统日志明确记录 `Destroy timeout of remove-task`、`Killing ... remove task` 与 signal 9：上一轮 task 清理延迟杀死新 instrumentation，非图片解码异常。AOSP [ActivityTaskSupervisor](https://android.googlesource.com/platform/frameworks/base/+/be9dd94ecf18/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java) 定义该超时为 1000ms。逐类 package reset 后加入与原 recovery reset 相同的 2 秒隔离等待，不自动重试失败类；再次验证前序 SAF→图像顺序。

完整原始系统日志保存为各失败类 `.log.system-logcat`；精简 TestRunner/AndroidRuntime 日志仍保留。新增诊断没有把进程崩溃改标成 PASS 或环境豁免。

`repair-api36-r4` 七类、23/23 方法通过，0 skip/crash，审计确认源码未变且 owned emulator 已关闭。SAF 移除在共享初始化稳定并打印 dialog 语义树后通过；未据此宣称已定位生产 SAF 缺陷。`full-consumer-api36-r3` 使用相同 app/test APK 开始完整复验。

`full-consumer-api36-r3` 发现本次 helper 修改引入的前置条件错误：RecoveryJourney 允许初始 openSessionId 为空，不能在 reset 前强制等待非空。已主动中止该轮，保留部分清单/原始失败和 exit 1，不计完整通过；owned emulator 正常关闭。移除该前置条件，改用上述同一 UI turn 的重置顺序，补 RecoveryJourney 专项后再完整重跑。

`repair-api36-r5` 加入 RecoveryJourney 后，8/8 类、30/30 方法通过，0 skip/crash，原始状态审计通过。修正后的 consumer test APK SHA-256 为 `5fd1820bf7d69ecc1b1b465ac64ac993716d4aeed1cca6c2361658c62ec1d31c`；生产 app APK 仍为 `b658f430472066b1ea29481f1aa833b06bd0a08e5e4a5d9cc34a7ddea927c47e`。随后以该固定制品运行 `full-consumer-api36-r4`。

`full-consumer-api36-r4` 中 RecoveryJourney、Goal 取消、审批与压缩均通过，但启动恢复再次失败于“重建后恢复原 durable session”。该轮主动中止且不计完整通过。进一步源码核对确认生产竞态：MainActivity 的 `restoreConversationLaunchTarget()` 异步读取启动目标，ChatScreen 在临时 null screen 上直接 `newSessionDraft()`，会先把持久启动目标覆盖为 NewDraft。修复 ChatScreen 空状态使用同一个 `restoreConversationLaunchTarget()` 入口；显式新建按钮仍直接新建，不修改导航 IA 或持久化契约。原启动恢复断言保留，重建 production APK 后重新验证；此前生产 APK 的通过记录不替代新制品验收。

`repair-api36-r6` 在新 production APK 上 8/8 类、30/30 方法通过，审计确认无跳过/失败/崩溃。consumer app SHA-256 更新为 `61428682d5189db543b4edc7f7d5e82d80e10787a0438b33caedac1c794bcc88`，test SHA 保持 `5fd1820bf7d69ecc1b1b465ac64ac993716d4aeed1cca6c2361658c62ec1d31c`。以此制品启动 `full-consumer-api36-r5`。

`full-consumer-api36-r5` 完整 198 类：169 PASS、3 FAIL、10 专用 runner、16 条件跳过，0 crash/missing/duplicate；原始方法审计为 629 pass / 32 skip / 4 fail。生产启动恢复和 SAF→图像顺序通过。三类残余失败分别为协议 fixture 把 Enqueued 强转 Accepted、停止 UI 在新 mode 投影前断言文案、回执恢复 fixture 在旧页面未退出时重新打开会话。

新增测试 helper `awaitAdmittedTurn` 接受两种合法回执，但必须等待**相同 input ID** 的 durable consumedTurnId，并校验 Turn 所属 Session 和 Accepted 回执中的 ID；不把尚未消费的队列输入算为执行成功。Queue/Goal/Stop/去重/三 Provider fixture 中相同的同步强转一并改为该等待，保持真正待排队的 Enqueued 断言。既有持久消费后的 Composer recovery Accepted 断言保留。停止 UI 等待 durable config 与 projection；回执恢复先离开旧页面再重开，并等待旧会话控件消失，原回执清理、附件零重复、修订内容及 Turn 数断言保留。

`repair-api36-r7` 对 16 个受影响类复验：14 PASS、回执恢复与 SAF 移除各一类失败，0 crash。回执恢复改为实际切换到新草稿并确认旧附件控件消失，避免导航 back stack 保留已初始化 composer。`repair-api36-r8` 两类 8 个方法通过。SAF 失败时语义树明确为 `files-saf-empty`，排除“按钮被布局挤走”的猜测；新增失败时 registry/liveSources 诊断。`saf-diagnostic-r1` 三轮独立 package reset 后均未复现，仅是诊断结果，**不作为已修复 SAF 间歇问题的证明**。保留历史失败及未定根因边界，完整顺序继续在 `full-consumer-api36-r6` 验证。

`full-consumer-api36-r6` 完整 198 类审计为 171 PASS、1 FAIL、10 专用 runner、16 条件跳过，0 crash/missing/duplicate；逐方法 632 pass / 32 skip / 1 fail。SAF、回执恢复及图像 probe 本轮通过。唯一失败是 SessionDraft fixture 在前台关闭后等待 null，但 Conversation-first 会自动恢复草稿，临时 null 不是可稳定观察的 UI 状态。测试改为先将 Activity 移至 CREATED，检查关闭与发送前未落库，再恢复 RESUMED；原重开元数据、单次发送去重和 Turn 数断言保留。此前失败不覆盖，重新编译后专项复验并运行完整固定制品基线。

## 最终固定制品全量结果

`repair-api36-r9` 草稿/启动恢复两类、3/3 方法通过后，`full-consumer-api36-r7` 从头完成 198 类。原始方法审计确认：172 类 PASS、10 类 PHASE_RUNNER_REQUIRED、16 类 SKIP / ASSUMPTION；633 方法 pass、32 skip，0 fail、0 crash、0 missing、0 duplicate。专用 runner 类不生成虚构方法 PASS。SAF 移除在该轮通过，但历史间歇空来源列表仍未定根因。

`build/hxa227/full-consumer-api36-r7/audited-summary.json` 与 `baseline-methods.xml` 校验实际逐方法状态；source manifest 在运行期间未变、保存 APK hash 一致、owned emulator 已关闭。consumer app SHA-256 `61428682d5189db543b4edc7f7d5e82d80e10787a0438b33caedac1c794bcc88`，test SHA-256 `213e3c67594934c09c09c3b981a7c75b73dbc7c49757f2b2f819961440d2945c`。失败轮次和专项结果不与此轮累加。

随后使用相同最终源码执行 `--run-id r2`：API29/API36 × consumer/developer 四组均 10/10 方法、8/8 case，合计 40/40 方法、32/32 case，0 failure/skip。`build/hxa227/device-trajectory-r2.json` 保存四组报告；每组保留 raw instrumentation、JUnit、context、envelopes、source manifest 和 closed.json。consumer 使用上述与全量完全相同制品；developer app SHA-256 `074ac20414fcce8b5420502da51d85865e9baf381c4aeead732ff0644d394475`，test SHA-256 `5db6f032db0baaa53c450ae9a01c8af8bd6d91c6610a08bb4b4254bc7df1a107`。各 flavor 跨 API 制品相同，四台 owned emulator 均已关闭。

最终 diff ownership review：相对基点 `c70c44403ff7f78e5347833159723f6d03f585a0`，唯一生产源码修改是 ChatScreen 空状态改用启动恢复入口；未修改 Dispatcher、permission/effect owner、Harness 状态机、Room schema 或 Workspace 契约。其余 Kotlin 为 androidTest fixture/回归；脚本负责严格证据与运行隔离。本次补充变更尚未提交或推送，旧基点推送不代表本次变更已推送。

最终完整主机门禁 `./scripts/check-all.sh --all` exit 0，日志 `build/hxa227/final-host-gate.log`：source checks、全部 JVM/unit、spotlessCheck、detekt、双渠道 debug/release lint 与 APK、36 个 dependency lock、debug/release variant/Runtime 边界通过。两渠道 AndroidTest APK 在 `baseline-fixes-build-r11.log` 编译 exit 0，并用于上述实际设备执行。新增原始状态解析测试 3/3、baseline 分类/锁/超时测试 7/7、eval 测试 18/18 通过。Gradle 使用正常增量执行；未宣称每个未变化的 host task 强制重跑。

完成记录与 status 更新后，`./scripts/check-all.sh --source` exit 0（`build/hxa227/final-source-gate.log`），文档/ADR/i18n/secret scan 均通过；`git diff --check` exit 0。未新增或重开 HXA 任务，完成索引标题未变。

验收仅证明本次固定源码/制品与受控 fixture。10 类专用 runner 与16 类条件跳过仍未在普通基线验收；32 个方法跳过不计 pass。SAF 空来源列表历史间歇失败仍未定根因，后续若复现应读取已新增的 registry/liveSources 诊断，不能因最终轮次通过关闭其稳定性问题。未执行真实 Provider/账号、物理设备、长稳发行专项、远端 CI 或发布；Memory/本地模型继续暂缓。

### 2026-09-28 P2 有界 SAF 复现补充

后续剩余工作计划将该历史问题单独列为 P2。固定在 post-refactor / P1 基点 `0e5c5f80`，使用 API36 consumer 当前 APK/Test APK，先直接执行 `FilesImportExportUiTest` 1 次，再调用既有 `diagnose-saf-repetition.py` 做 3 次独立 package-reset；4 次均为 4/4 methods passed。该结果只说明当前限定窗口未复现，不覆盖本页 r7 的真实 `files-saf-empty` 失败，也不证明 defect 已修复。生产 `SafGrantStore → SafTreeScopeService.liveSources() → ContentResolverSafTreeCheck.query()` 仍保持每次实时验证、任一不可验证状态 fail closed；没有为了 UI 稳定而缓存上一次成功或放宽验证。完整当前证据见 [P2 bounded diagnosis](p2-saf-bounded-diagnosis-2026-09-28.md)。

## 复现入口与证据身份

所有运行位于独占、headless、read-only 的 arm64 AVD：`Helix_HXA210_API29` / `Helix_HXA210_API36`，1080×2400、density 400，2048 MiB / 2 cores。owned runner 拒绝借用已有 serial，结束后保留 `closed.json`。debug APK 不等于发行制品。

```sh
# 新的 run-id；已有目录会拒绝覆盖。依次执行四组合，不并行启动模拟器。
python3 scripts/debug/2026-09-27/run-hxa227-final-trajectories.py --run-id r2

# API36 consumer 全量；先按当前源码生成 current-consumer-classes.txt。
ANDROID_HOME="$HOME/Library/Android/sdk" python3 scripts/run-owned-emulator.py \
  --avd Helix_HXA210_API36 --port 5682 --density-dpi 400 \
  --apk app/build/outputs/apk/consumer/debug/app-consumer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk \
  --runner com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes com.helix.app.engine.TurnReviewResolutionDeviceTest#deterministicReviewClosesOldTurnAndGoalRunWithoutOpeningAnotherModelCall \
  --after-script scripts/debug/2026-09-27/verify-hxa227-full-baseline.py \
  --output build/hxa227/full-consumer-api36-r7 --timeout 20000
python3 scripts/debug/2026-09-27/audit-hxa227-baseline.py build/hxa227/full-consumer-api36-r7
```

wrapper 保存当前类清单、source manifest、runner 源码副本及 SHA；owned runner 保存原 APK 及 SHA。不能仅以 wrapper exit 0 判断通过，必须检查逐类汇总及原始逐方法审计。日志和 APK 位于忽略的 `build/hxa227/`，不提交机器路径、模型密钥或用户数据。
