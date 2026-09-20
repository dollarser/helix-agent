# HXA-197 手动终端页面接线与验收

日期：2026-09-20。任务仍进行中，范围见[任务规格](../../completion-records/HXA-197.md)；本记录不关闭进程死亡、压力或 OEM/长稳验收。

## 本次实现

- developer Workspace 目录入口 → 非导出主进程 `ManualTerminalActivity` → 应用 `ManualTerminal` → 已有私有 Runtime；没有新增模型工具或自动输入入口。consumer 不打包页面、渲染组件、native 库及许可证资产。
- 固定 ConnectBot termlib 0.2.1：sources JAR SHA-256 `fe728b29a2c0f4612de88af2126ae61fc793a3203ca7564fafcd842754a0ab03`，AAR `01a7dcb57aebdb5492abc9c505963de9d638eee8065ab074bd67b9882f7690d5`。构建从前者生成源码并应用已验证的显式 close 补丁，从后者取 native/资源/混淆规则，不重复打包原 classes.jar。许可证和归属可在页面读取。
- compileSdk 37、Compose BOM 2026.09.00，targetSdk 36/minSdk 29 不变；版本、36 份锁文件、校验 metadata、CI SDK 和开发环境同步。既有 OkHttp JVM 选择保持不变。
- 单写连接跨 Activity 重建；页面退出 detach，停止与结算是独立用户动作。输入四个 8 KiB 块，拒绝/未知结果不重发；resize 合并；输出单读者按有界页解析，缺口换新解析器。移除视图并停止生产者后在 callback looper 释放组件。
- 应用内语言与 Helix 主题；键盘、控制键、起始目录、连接/执行阶段和结算入口。连接时单个只读观察循环更新阶段，避免首次 STARTING 快照一直显示。

## 主机与制品门禁

所有重型命令均由 `python3 scripts/debug/2026-09-18/with-host-slot.py -- ...` 独占执行。

| 检查 | 命令与证据 | 结果 |
| --- | --- | --- |
| 完整主机 | `./scripts/check-all.sh --all`；`build/hxa197-ui-all-v4.log` | exit 0；源检查、Spotless、Detekt、双 flavor debug/release lint/单测/构建、36 锁及 debug APK 边界通过 |
| 测试 APK | `./gradlew :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest`；设备脚本日志 | 两个测试 APK 构建成功；不把主机 --all 当作 instrumentation 编译证明 |
| release 边界 | `python3 scripts/verify-integrated-runtime-apks.py --build-type release`；`build/hxa197-ui-device-final-v3.log` | consumer/developer 通过；包含新 Activity 的非导出/主进程、组件 dex/native/许可证排除 |
| 模块 lint | `runtime/terminal-renderer/build/reports/lint-results-debug.txt` | 严格 warnings-as-errors 下 No issues found |

最终生产 developer debug APK SHA-256：`fd2353a42df0106b9cea37b369f9e581a1d2392c145074c906512203899ddd5a`。

## 产品设备旅程

复现入口：

```bash
python3 scripts/debug/2026-09-18/with-host-slot.py -- python3 scripts/debug/2026-09-20/run-terminal-page-gates.py <新证据前缀> --runtime-only
```

脚本构建测试 APK，使用全新独占 API29/API36 模拟器，关闭只针对本次进程；拒绝借用既有 serial。默认不带 `--runtime-only` 时也运行主题四象限。`--ui-only` 用于只重跑实际页面与截图，不宣称整套 Runtime 被重跑。

最终 Runtime/页面批次：`build/hxa197-ui-final-v3-api29`、`build/hxa197-ui-final-v3-api36`，各 **47/47**，分别 45.579 s / 60.815 s，owned emulator exit 0；两个 API 的 app/test 哈希一致。测试 APK SHA-256：`87bf2781b54dfc3706b3f1a18e5279cf5390a2817ef713ba883b5291c8587316`。

新增的是每 API **一项** `ProotTerminalUiDeviceTest`，其余 46 项为已有 Runtime/PTY/产品会话回归。新旅程通过生产页面的 InputConnection 输入中文，核对真实 Workspace 文件；进入真实 Python REPL，跨次输入得到 42；重建 Activity 后核对原 shell 环境保留；检测绿色终端字形像素；实际打开软键盘；页面自动更新 RUNNING/STOPPED，最后经页面结算。API29 为中文界面，API36 为英文界面。

后续仅将截图采集改成连续三帧稳定后保存；该测试辅助修改不改变生产 APK。补验与最终截图采用 `build/hxa197-ui-capture-v4-api29`、`build/hxa197-ui-capture-v4-api36`，与完整批次分开记账：各 1/1，7.589 s / 12.906 s，owned exit 0；app SHA 与完整批次一致，test SHA 为 `9b7d0b02034b948878d400a886d42a0fd4b914e27376760ad13a3e8e66885d3d`。已人工核对两种语言的稳定键盘截图。截图文件为 `terminal-shell.png`、`terminal-keyboard.png`、`terminal-rotated.png`，由拥有模拟器的 runner 在 teardown 前采集。

## SDK/Compose 升级回归

`build/hxa197-ui-final-v1-theme-*`：developer API29/36 明暗模式合计 **32 项**，consumer 合计 **28 项**，共 **60 项**，真实系统夜间模式、较大字体、主题进程恢复和 owned teardown 均经 runner 验证。

这一主题批次发生在最后的终端状态观察/相对目录显示修正之前；SDK/Compose、公共页面、主题实现和 consumer 代码没有随后变化。其 developer APK SHA 为 `c40378ef4aab31cd5e046cbaa99ab17ad999622966e2a1480643923dd1776526`，不是上述最终生产 APK。该证据覆盖原有主题页面，不声称已逐一验收新增终端页面的所有深色/大字体组合。

## 失败与修正

- AGP 不接受 SourceSet Provider 接线，改用 Variant generated-source API，未关闭检查。
- consumer lint 指出公共资源中未使用的终端文案，将专用文案移到 developer，不 suppress UnusedResources。
- 状态快照曾一直停在 STARTING；截图揭示后增加有界状态观察，并加入页面自动更新断言。
- 新模块依赖目录警告改用 catalog；恢复原 AAR 混淆规则；新页面补应用语言上下文。
- 中间格式化运行长期占用 CPU，线程快照落在 ktlint；终止本次调用后进行单文件及全量复验，最终全量通过，未确定为某一表达式的确定根因。
- 测试新增状态匹配误导入了成员函数 `onAllNodes`，instrumentation 编译失败；移除多余导入后重建与设备复验通过。
- 软键盘截图曾捕获动画过渡帧，截图辅助逻辑增加稳定帧检查；不把旧过渡帧当作最终界面证明。

## 剩余边界

产品主进程/Runtime 实际死亡及原身份对账、完整关闭页面后重连、长输出缺口与长粘贴压力、明确运行长命令后的租期终止仍需专项。已有组件释放/OSC/PTY 探针与服务租期测试按各自原范围保留，不冒充这些产品旅程完成。断开三十分钟 idle、OEM HOME/锁屏/Doze/热压/长稳和真实 16 KiB 设备仍独立记账；静态 ELF/ZIP 对齐不等于设备运行。197 不关闭，不进入下一 HXA。
