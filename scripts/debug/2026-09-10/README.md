# 2026-09-10 调试与测试脚本

- `remove-subscription-response-caps.py`：长回复修复的一次性编辑记录，移除应用追加的响应累计限制与计时；不要重放到已修改源码。

- `install-subscription-bugfix.py`：只覆盖升级已有 developer/订阅组件并启动，保留数据；不执行模型请求或测试。必须明确物理 serial，保存 APK 哈希到忽略目录。
- `observe-subscription-freeze.py`：有界、只读采样两个 Helix 进程及前台状态，不改系统冻结策略。

## HXA-186 真机回归

- `run-physical-library-regression.py`、`run-physical-app-regression.py`：仅在用户明确授权的真机 serial 上安装独立测试包；拒绝已有目标包，结束后清理新增包。结果和原始日志保存在忽略目录。
- `close-physical-regression.py`：本轮一次性结果归档，保留失败，不可重复执行。
- `install-physical-developer.py`：用户授权后覆盖升级已有 developer，保留数据，检查启动；拒绝模拟器，不卸载、不清除数据。先运行 `:app:assembleDeveloperDebug`。

## HXA-187 QuickJS 真机冻结与绑定清理

- `patch-quickjs-visible-test-host.py`、`fix-quickjs-bind-cleanup.py`：一次性编辑来源，不要在最终源码上重放。
- `close-quickjs-freezer-fix.py`：核对最终77项、清理和升级证据后归档，不可重复执行。
- 物理库runner的 `--test 类名#方法` 只允许单模块；`--headless` 仅用于QuickJS无界面冻结对照，正常回归不使用。`--host-timeout` 取20～300秒，属于宿主停止上限，不修改产品10秒预算；超时前采集本测试包的线程wait-channel，再终止和清理。
- QuickJS可见测试宿主在每个用例的 `@Before` 启动、`@After` 关闭。不得通过关闭系统冻结、电池策略或删除超时断言让测试通过；后台/锁屏验收需另行设计实际生产生命周期。

HXA-182 跨日继续。所有权约束和制品冻结复用前一日的 `run-owned-emulator.py`。

- `run-responsibility-recovery-regression.sh`：双 API/双 flavor 的文件、聊天职责及跨进程恢复组合；发布阶段在测试 backend 内杀死测试 App，检查持久 PID 后启动新进程通过原生 UI 恢复。预期死亡只允许在 setup；正式用例必须非空且全通过。
- `run-transfer-recovery-device.sh`：仅运行上述跨进程恢复专项。
- `extract-file-trash.py`、`add-process-recovery-runner.py`、`refine-unresolved-publication.py`、`use-real-process-death.py`：本轮一次性编辑的来源记录，不作为可重复修复器。

输出保持在忽略的 build 目录；仅测试合成文件，不连接用户手机，不借用其他模拟器。

## HXA-183 职责拆分

- `run-hxa183-device.sh variant api port output`：独占 API29/36 上运行聊天、草稿、审批、Provider、文件与 Goal/压缩/后台回归；随后在同一独占实例执行 PRoot 验收，最后关闭实例。
- `run-hxa183-proot.py serial output`：只由上述运行器调用；验证 owner，复用 `accept-hxa-086-lifecycle.sh` 的真实跨 APK 生命周期阶段，再运行 companion 和主 App 的归档/ACK/Tool 回归；冻结 APK SHA 并检查未漂移。脚本必须显式接收 serial，不自动选择 adb 中的设备。
- `run-hxa183-runtime-device.sh api port output`：app 回归已通过后，仅在新建独占实例补跑 PRoot。
- `restore-locked-proot-assets.py source-checkout`：只读复用相同 lock 的被忽略构建资产，验证 RootFS 锁定哈希与 loader 一致性；不修改源工作树、lock 或版本。
- `verify-hxa183-evidence.py app29 app36 runtime29 runtime36`：分别核对成功阶段、当前 APK 哈希和关闭记录；`close-hxa183.py` 通过这些检查后才生成完成记录与状态。
- `split-responsibilities.py`、`split-runtime-and-chat.py`、`refine-extracted-owners.py`、`wire-draft-owner.py`、`refine-split-file-layout.py`、`clean-extracted-imports.py`：本轮一次性编辑来源记录，只适用于编辑当时的源码，不要在最终树重复执行。
- `inventory-large-production-files.py`、`write-large-class-audit.py`：拆分前审查的清单与文档生成来源；人工分类不能随清单重跑自动视为当前结论。

`run-owned-emulator.py` 的 `--after-script` 在主 suite 成功后、释放独占实例前运行后续验收；后续异常同样进入关闭流程，不能把前半段通过当成整轮通过。

## HXA-184 大类 B/C 整理

- `split-hxa184-declarations.py`、`extract-hxa184-support.py`、`extract-hxa184-execution.py`、`extract-hxa184-boundaries.py`、`refine-hxa184-owners.py`：一次性职责提取脚本，基于执行当时的精确声明边界；不要在已经拆分的源码上再次执行。
- `refine-hxa184-layout.py`：仅清理机器清单内的拆分文件导入；机器清单位于忽略的 build 目录。
- `run-hxa184-device.sh <variant> <api> <port> <output>`：调用独占模拟器生命周期 runner，运行 app 集成后执行下述库级验证；拒绝现有 serial，finally 关闭自建实例。
- `run-hxa184-modules.py <serial> <output>`：仅供独占 runner 回调；逐项校验父进程所有权，冻结模块 APK SHA，记录非空 JUnit 与 assumption 数量，包括独立 Accessibility force-stop/setup/recovery。
- `fix-hxa184-races.py`：记录设备回归触发的审批注册/通知竞态和 Goal 删除时提醒关联读取竞态的初次修复；后续编译修正以当前生产源码为准，脚本不用于重放修复。

- `verify-hxa184-evidence.py`：核对主机结果、双 API 原始测试计数、冻结制品与关闭记录；`close-hxa184.py` 仅在验证成功后一次性生成收口文档。
- `recount-hxa184-module-results.py`：根据保留的原始日志修正 assumption 统计（AndroidJUnitRunner 使用 -4；-3 是 ignored），不改原始证据，不重跑设备。

## HXA-185 待 Claude 验证

- `prepare-hxa185.py`、`patch-hxa185-autofill.py`、`refine-hxa185-goal.py`、`finalize-hxa185-source.py`：本轮一次性源码编辑记录，不是可重跑修复器。
- `soak_evidence.py`：纯 WebView 当前包/版本解析；`test_soak_evidence.py` 为未执行的主机回归。
- `analyze-fd-phases.py`：离线阶段统计，拒绝混合进程/轮次；输出不判定泄漏归属、不改变资源门限。
- 所有编译、测试与模拟器操作交给 Claude；详见仓库 `docs/development/hxa185-claude-test-handoff.md`。


## HXA-188 真机浏览器、后台/锁屏与 Root

- `isolated-browser-build.gradle`：`./gradlew -I scripts/debug/2026-09-10/isolated-browser-build.gradle :feature:browser:assembleDebugAndroidTest` 将 browser 输出隔离到忽略目录，不替换正在长稳的冻结 APK。
- `run-physical-library-regression.py` 新增 `--apk-metadata` + `--apk-sha256` 明确选择隔离制品；Root 使用 `--modules tools/root --instrument-arg hxa094ExpectedRoot granted --instrument-arg hxa095ExpectedRoot granted`，仅安装自己的临时测试包。Root 管理器按该测试 UID 授权，不更改全局自动响应或其他应用授权。
- `run-physical-recovery.py`：必须明确 serial/output，只使用全新 consumer 沙箱，冻结当前双 APK。`PhysicalBackgroundRecoveryDeviceTest` 使用真实 HOME/熄屏，短窗口验证 FGS、后台完成、无重复请求与显式 Goal 继续；安全锁屏需所有者正常解锁，不自动关闭密码或电池策略。不是 Doze/长稳验收。
- `prepare-physical-browser-host.py`、`prepare-physical-root-host.py`、`prepare-physical-recovery-runner.py` 是本轮一次性编辑来源，禁止在最终源码重放。资源宿主仅用于短回归，原显式长稳/control 生命周期保持不变。
- `inspect-disposable-recovery-db.py` 是失败的诊断尝试：仅针对新建 consumer 沙箱；SQLCipher 数据库不能用 sqlite3 直读，不读取或导出密钥，不用于验收。

- `fix-root-callback-reentrancy.py`、`close-physical-browser-root.py`：一次性修复/证据校验归档来源，不重放。
- `run-physical-root-policy.py`：按 setup → granted → 管理器对临时包的正常撤权 → denied → cleanup 执行；ownership.json 核对安装 UID，拒绝已有包或所有权变化，不编辑 Magisk 数据库。
- 最终后台夹具使用普通 instrumentation，不使用 Compose/ActivityScenario 跨锁屏清理；回收由独占手机 runner 的 finally 完成。

## HXA-189 审查复核

- `prepare-review-fixes.py` 为一次性初始修复记录，不重放；后续修订以最终源码及复核文档为准。
- 主机日志位于忽略目录 `build/debug/2026-09-10/review-*.log`；设备用例交接独占测试者，不操作既有长稳模拟器。

## HXA-190 真机覆盖更新

- `run-codex-account-diagnostic.py` 与 `run-physical-codex-chat.py` 只运行显式真实账号合成请求；保留个人 App/Runtime 数据，结束卸载自己新增的测试包。
- `run-physical-permission-ui.py` 仅在全新 consumer 沙箱验证权限/导航/系统栏，拒绝占用已安装的 consumer，结束卸载。
- `verify-phone-installed-artifacts.py` 只读核对手机 APK 与当前构建 SHA、唯一 Runtime 启动入口及测试包清理状态。
- `implement-codex-image-envelope.py` 是一次性源代码修改记录，禁止在已修改源码重放。
- 结果和边界见 `docs/development/hxa190-physical-update-2026-09-10.md`；本轮未操作 Claude 模拟器。
- `inspect-subscription-pages.py` 只打开订阅首页和四个登录页留存界面证据，不点击登录/注销或读取 vault。
- `rename-subscription-presentation.py`、`format-subscription-presentation.py`、`extract-subscription-header.py` 为一次性显示层修改记录，不重放；格式脚本初次因既有长行未自动修复而失败，后续修改以源码为准。
- `refine-subscription-name.py` 为显示名简化为 Helix Subscriptions 的一次性修改来源；`find-subscription-launcher-page.py` 在显式真机桌面有界翻页，仅保存含 Helix 的实际图标证据，不修改桌面数据。桌面刷新使用已核实的系统 launcher 进程重启，没有卸载应用。
