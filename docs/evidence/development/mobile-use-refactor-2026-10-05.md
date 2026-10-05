# Mobile Use 五项优化：授权共享与插件自治

日期：2026-10-05。所有者要求先提交当前代码，再完成五项优化，并明确授权模拟器与当前真实模型测试；过程中进一步确定系统权限归 Helix、自动化能力归 Mobile Use。未推送，未使用真机。

## 基线与范围

基线提交 `73be3752` 保存此前累计代码、文档与测试，不包含临时调试脚本。本轮后续变更尚未提交。

| 项目 | 实现 |
| --- | --- |
| 收拢自动化实现 | 引擎、Root/Shizuku 自动化适配器、工具与测试归 extensions/mobile-use；删除 tools/automation 模块 |
| 宿主与插件边界 | tools/device-access 管理系统权限和隔离连接；停用插件不撤销 Helix 系统授权；原 Dispatcher/图片/任务停止接口不变 |
| 统一能力选择 | 后端声明操作集合；选择器按实际操作、授权/连接状态选择；不支持的 Root 不遮蔽支持该操作的 Shizuku |
| 工具与上下文 | 移除 backend 输入；动作输出 observationRequired 与 nextObservation，内置 skill 明确全部旧 token 失效 |
| 回归 | 主机单测/静态检查、API36 插件 UI 与真实 Qwen、API34 Root、独立服务与停止接管；具体结果见下方收敛记录 |

## 设备与数据边界

日常 `Helix_API_36` / emulator-5554 / API36 / developer debug；独立 `Helix_MobileUse_Root_API34_20261004` / emulator-5572 / API34 / developer debug。Root AVD 使用已有官方 Magisk 30.7 临时 emulator setup，没有修改共享 SDK system image。设备配置、原会话和第三方应用数据保留；测试创建的对话用于追踪真实模型，临时插件全局范围在 finally 恢复。

原始日志置于忽略的 `build/mobile-refactor-2026-10-05`，不提交含会话数据的数据库副本。

## 当前验证记录

- 首轮真实模型 Qwen3.8-27B 在合成页面完成中文输入、滚动、点击与结果核查，8 次 ui 工具调用，无 bash；其中旧 token 滚动被拒绝一次，模型重新观察后恢复。宿主仅创建测试会话并展示合成页，没有代操作。随后增强动作反馈，最终复测结果见下方。
- API36 插件注册/停用、插件工具与 skill 展开、全局配置、权限入口 5 项通过；首轮详情节点查询错误已按实际 Compose 合并语义修正，并保留可见性断言。
- Root 范围测试原先假设会话范围可覆盖全局配置，已修正为修改真实全局范围并恢复；旧 APK 的中间失败不计入最终通过。
- 模块迁移遇到 annotation 1.3.0 获取失败，改为与现有依赖图一致的 1.7.0 并生成锁文件；没有添加仓库或动态版本。

## 收敛结果

实现边界为：Helix 管无障碍/Shizuku/Root 的系统授权入口与状态、使用方连接；Mobile Use 管自动化引擎、特权自动化协议、观察/动作工具、内置 skill、停止与接管交互。系统已授权不等于插件已启用或某个会话已授权。Root 断开只释放该使用方连接，不再关闭进程共享的 libsu Shell。旧 `tools/automation` 模块、工具级 backend 参数及旧的整组能力判断已删除，没有兼容桥。

最终主机命令：

```sh
./gradlew :extensions:mobile-use:testDebugUnitTest :tools:root:testDebugUnitTest :core:policy:test :extensions:plugin:test :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :extensions:mobile-use:assembleDebugAndroidTest :app:lintDeveloperDebug :app:lintConsumerDebug detekt spotlessCheck --offline --console=plain
python3 -m unittest scripts.tests.test_mobile_use_contract scripts.tests.test_emulator_input
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/verify-variant-boundaries.sh
bash scripts/check-docs.sh
git diff --check
```

最终联合 Gradle 命令通过（57 秒），双渠道 APK 边界/运行时检查通过，文档检查通过（739 Markdown / 230 HXA），`git diff --check` 无错误。

单测报告：Mobile Use 184、Root 23、policy 200、plugin 52；app developer 1219 个条目，其中 4 个既有条件跳过，其余通过。Python 14/14。中间一次 lint 在源码收敛期间发生 PSI 分析器异常，冻结源码后重新运行；不跳过规则。

### 当前设备验证

| 设备/范围 | 结果与原始日志 |
| --- | --- |
| API36 developer 插件注册、停用保留系统权限、内容展开、全局设置与 Shizuku 入口 | 5/5；`emulator-5554-ShizukuSettingsDeviceTest-1791144089.txt` |
| API34 developer Root 优先、范围/只读拒绝、断连后新观察回退 | 1/1；`emulator-5572-RootMobileUseDeviceTest-1791143341.txt` |
| API34 standalone Mobile Use 覆盖窗、接管/返回、生命周期/撤权/恢复 | 4/4；`plugin-device-suite.txt` |
| API36 当前 Qwen3.8-27B 输入/滚动/点击 | 最终 1/1，19.613 秒；`emulator-5554-MobileUseCurrentModelJourneyDeviceTest-1791143953.txt` |
| API36 当前 Qwen3.8-27B 系统安装流程 | 1/1，69.259 秒；`emulator-5554-MobileUseCurrentModelJourneyDeviceTest-1791143829.txt` |

设备测试 runner 为 app 的 `com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner` 和插件的 `com.helix.extensions.mobileuse.test/androidx.test.runner.AndroidJUnitRunner`；运行的类为 `MobileUsePluginRegistrationDeviceTest`、`PluginContentsDeviceTest`、`AutomationSettingsAuthorizationDeviceTest`、`ShizukuSettingsDeviceTest`、`RootMobileUseDeviceTest`、`MobileUseOverlayDeviceTest`、`AutomationServiceDeviceTest`、`MobileUseCurrentModelJourneyDeviceTest`。真实模型测试必须显式传 `helixRealModel=true` 与 `helixSourceSession`，安装分支另传 `helixInstallFixture=true`；参数本身不取代所有者授权。

最后输入任务使用 7 次 ui 调用：snapshot → set_text → snapshot → scroll → find → click → find，全部成功，实际页面为 `TEST_DONE:你好 Helix`，回合 COMPLETED。相比首轮，未再使用旧 token；两次样本不代表统计成功率。

安装任务使用 12 次 ui 调用：snapshot → click → snapshot → click → snapshot → device → screenshot → gesture → wait → snapshot → click → apps。系统确认、视觉手势和安装核查由 Helix 模型自主决定；宿主只提供自有测试 APK、起始页面与测试会话，没有代点。一次 wait 因预期文字“应用已安装”与实际“已安装应用。”不同而返回超时，模型重新观察后确认安装，并用应用列表核查；系统包 `com.helix.validation.installfixture` 存在且回合 COMPLETED。没有 bash、第三方账号登录或重新安装抖音/酷安。

### 环境问题与处理

- 日常模拟器在一次中间运行异常退出，恢复原 AVD 数据后重启 Shizuku；该次不计通过。两台 AVD 均启用硬件键盘和显示输入法。API34 安装 Gboard 并选择简体拼音，实际键入 `nihao`、空格选词后字段为“你好”，见 `root-pinyin-commit.txt`。
- 重启后发现本轮合成测试引用的一份内容寻址文件为零字节，与预期 hash 不符。核对其引用仅属于本轮测试后，保存主机副本并移到设备 `files/helix-test-quarantine`，没有删除或静默接受坏内容，也没有重放 UNKNOWN。这不是通用内容损坏自愈实现或断电持久性验收。
- 安装测试夹具先因独立测试 APK 进程缺少 AndroidX/Kotlin 运行时而崩溃；改为仅依赖 Android framework 的 Java ContentProvider，限定唯一只读 APK 路径后通过。生产应用没有增加安装权限。
- 测试最后恢复原会话选择、原全局 Mobile Use 范围、安全配置和原无障碍开关。只创建自有测试会话与自有 fixture，不清除既有用户应用或会话数据。

以上覆盖本机 API34/36、developer 和当前 Qwen 配置；consumer 仅主机与制品检查。真机、OEM、第二个真实插件同时使用 Root、其他模型与发布验收未覆盖。后续代码仍为本地未提交工作树，未推送。

最终 developer APK 已覆盖安装到日常 API36 模拟器并打开 Helix，原数据保留；安装验证完成后卸载本轮自有 `com.helix.validation.installfixture` 并恢复测试来源安装 app-op。API34 临时验证模拟器已关闭，日常模拟器保持运行。
