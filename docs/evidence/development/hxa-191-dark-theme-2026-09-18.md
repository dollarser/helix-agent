# HXA-191 深色主题切片证据：统一跟随系统主题与四象限设备验收（2026-09-18）

本记录属于 HXA-191「深色主题」轮（接续「会话搜索」切片，worktree `Helix-theme-191`，分支 `codex/hxa-191-dark-theme`，基线 `5bfce200`）。本轮**只交付 HXA-191 的「深色主题」部分**，并对**已合并的会话搜索**做回归；与上一轮会话搜索（[切片证据](hxa-191-session-search-2026-09-18.md)）合起来，**HXA-191 两部分均交付并本地验收**，故本轮写完成记录 [`completion-records/HXA-191.md`](../../completion-records/HXA-191.md)。

## 结论

- **深色主题（统一跟随系统）已交付并四象限验收**：Compose 侧 M3 `MaterialTheme` 本就默认跟随系统 dark colorScheme（含对话框/主页面/各目的地）；本轮补齐的是此前**被硬编码为浅色**的 Android **窗口**侧——新增 `values-night` 平台主题（`app` + `runtime/cli-app` 各一份），使状态栏/导航栏/浅色状态栏标志/预 Compose 窗口背景在夜间随系统翻深，与浅色基线严格对应。
- 双 flavor × 双 API 独占模拟器**四象限主题矩阵全绿**（consumer/developer × API36/29；AVD `HelixApkUpgrade_API36/29`；端口 5700/5702/5704/5706）：每象限每模式 **setup（写 PID 标记 + `killProcess` 强杀）→ verify `OK (5 tests)`**；前景 `MainActivity` 窗口 `mFullConfiguration` **夜间带 `night`（`font_scale 1.3`）/ 日间无 `night`（`1.0`）**；`closed.json exit 0`（逐象限逐模式原始日志见各 `build/hxa-191-theme-<variant>-api<api>/`）。
- **已合并会话搜索回归四象限全绿**（`Helix191_API36/29`；端口 5642/5644/5646/5648）：每象限 app `SessionSearchDeviceTest` **`OK (8 tests)`** + storage connected `SessionSearchQueryDeviceTest` **`4/0/0`** + `closed.json exit 0`。
- **P1 全绿**：`./gradlew spotlessCheck detekt test` BUILD SUCCESSFUL；`check-all --source` 除**预期**的 completion-index 未重算（协调者职责，见[建议 status 更新](#建议-status-更新供协调者整合本执行者不直接改全局-statusroadmapindex)）外全过；`check-all --artifacts` 绿（developer 订阅/运行域边界未被主题改动波及）；`git diff --check` clean。

## 本轮交付（实现，非既有证据）

1. **`values-night` 平台主题（补齐窗口侧，Compose 侧不变）**
   - `app/src/main/res/values-night/themes.xml`：`Theme.Helix` 父 `android:Theme.Material.NoActionBar`（深色平台主题 → 深色窗口背景，避免 Compose 绘制前浅色闪现）；`statusBarColor`/`navigationBarColor` = `#FF1C1B1F`（M3 深色 surface）、`windowLightStatusBar=false`（深栏浅图标）。与浅色基线 `values/themes.xml`（`Theme.Material.Light.NoActionBar`、`#FFF9F9`、`windowLightStatusBar=true`）**逐项对应**；`windowLightNavigationBar` 沿用平台默认（false=浅图标），镜像浅色主题做法。
   - `runtime/cli-app/src/main/res/values-night/themes.xml`：developer 订阅/登录面 `Theme.Helix.Subscription` 同款深色（`colorAccent #D0BCFF`、`windowBackground`/`statusBarColor`/`navigationBarColor` = `#FF1C1B1F`、`windowLightStatusBar`/`windowLightNavigationBar=false`）。
2. **具名设备测试 `app/src/androidTest/kotlin/com/helix/app/ui/HelixThemeDeviceTest.kt`（5 面）**——全部读取**已解析**平台主题属性（`obtainStyledAttributes`），**不**读裸 `dumpsys statusBarColor`（API 36 edge-to-edge 会强制合成状态栏透明，裸读会误导）：
   - `systemBarsAndThemeFollowCurrentSystemMode`：状态栏/导航栏/窗口背景亮度带 + 浅色状态栏标志 + 窗口/正文 **WCAG AA 4.5 对比度**契约，随真实系统夜间模式翻转；
   - `themeSurvivesActivityRebuild`：portrait-locked AVD 上 `recreate`×2（旋转等价）后契约仍成立；
   - `themeRestoredAfterRealProcessRestart`：**真实进程重启**两阶段（`recoveryPhase=setup` 写 `no_backup/theme-recovery-pid` 并 `killProcess` → `recoveryPhase=verify` 断言新 `Process.myPid()`≠标记 PID，且主题由资源重推、不携带被杀进程主题）；
   - `shellDestinationsOperableInCurrentMode`：主页面 + 文件/任务/授权/浏览器/设置 5 目的地可导航、可交互，且契约仍成立；
   - `themeStableAcrossAppLanguages`：EN / ZH_CN / SYSTEM 三语言下契约稳定（主题与 locale 正交；`persistChoiceOnly` 记录选择、不做异步 `setApplicationLocales` 推送，避免 HXA-069 选择器中途 flake）。
   - 深色象限以 `font_scale=1.3` 覆盖「大字体」验收（`luminance`/`contrast` 为共享颜色助手）。

## 制品身份（本轮实际安装/检查对象，SHA-256）

| 制品 | SHA-256 |
| --- | --- |
| consumer debug app | `18e852152a94277ac549408bbf8168df6680e8efc0b2f0beb8129e82897ae4e0` |
| developer debug app | `d4ed9d270355a6a3ca9b84546cffda430967788e4507b15ed3ae6c6a855ae46b` |
| consumer debug androidTest | `e2220a091a859475330fe1858e15d34a06908d1197ec2c5fe832b437fa1c894f` |
| developer debug androidTest | `9ec02949ddd0653b071aa4667bfb8d671f6a92d41f5d1c301fab1ba4caf2ba0a` |

每象限 `artifacts.json` 记录当次安装的 app/test APK 哈希（consumer 象限 = `18e85215…`/`e2220a09…`，developer 象限 = `d4ed9d27…`/`9ec02949…`）。**测试 APK 由本轮交付源码重建**（APK mtime 21:10 新于 `HelixThemeDeviceTest.kt` 21:09），保证「已运行的 APK」=「已提交的源码」编译产物。

## 命令与结果（全部实际执行）

四象限主题矩阵（独占 AVD 自启自闭，`-read-only -no-snapshot` 全新启动，`wm size 1080x2400` + `wm density 420`，仅 teardown 自有进程组；每象限 = 每模式 `am instrument -e class HelixThemeDeviceTest -e recoveryPhase setup`（seed+强杀）→ `-e recoveryPhase verify`（5 例）→ 前景 `am start` MainActivity + `dumpsys window` → `force-stop`；四象限顺序经 `with-host-slot.py` 串行）：

| 象限 | 命令 | 结果 |
| --- | --- | --- |
| Q1 consumer×36 | `bash scripts/debug/2026-09-18/run-191-theme-matrix.sh consumer 36 5700` | 每模式 setup「process crashed」+ PID 标记（light=2627/dark=4580）→ verify **`OK (5 tests)`**；窗口配置 light=`… port finger`（无 night，1.0）/ dark=`… port night finger`（1.3）；`EDGE_TO_EDGE_ENFORCED`；`closed.json` exit 0 |
| Q2 consumer×29 | `bash scripts/debug/2026-09-18/run-191-theme-matrix.sh consumer 29 5702` | 每模式 setup「process crashed」+ PID 标记（light=2258/dark=3506）→ verify **`OK (5 tests)`**；dark 窗口字体 1.3 / light 1.0；API29 `mFullConfiguration` 未打印 `night` token（旧格式），夜间以具名测试已解析属性为准；`closed.json` exit 0 |
| Q3 developer×36 | `bash scripts/debug/2026-09-18/run-191-theme-matrix.sh developer 36 5704` | 每模式 setup「process crashed」+ PID 标记（light=2516/dark=4609）→ verify **`OK (5 tests)`**；窗口配置 light=无 night / dark=`night`（1.3）；`closed.json` exit 0 |
| Q4 developer×29 | `bash scripts/debug/2026-09-18/run-191-theme-matrix.sh developer 29 5706` | 每模式 setup「process crashed」+ PID 标记（light=2130/dark=3242）→ verify **`OK (5 tests)`**；dark 窗口字体 1.3 / light 1.0；API29 `mFullConfiguration` 未打印 `night` token，夜间以具名测试已解析属性为准；`closed.json` exit 0 |

> **权威判据是具名测试**（`OK (5 tests)` 直接断言**已解析**的 `windowLightStatusBar` 布尔 + 状态/导航栏/窗口背景亮度带 + WCAG AA 对比度 + 两阶段真进程重启 + 重建 + 5 目的地 + 3 语言）。**前景窗口 `mFullConfiguration` 的 `night` 有无**是补充的在设备证明（API 36 夜间带、日间无、字体 1.0/1.3 翻转；API 29 不打印 `night` token，故 API 29 象限的夜间在设备证明以字体翻转 + 具名测试已解析属性为准）。API 36 edge-to-edge 下 `dumpsys window windows` 的裸 `LIGHT_STATUS_BARS` 标志不出现在应用窗口块（`grep` 为空），故**不以裸 `statusBarColor`/裸标志作判据**——这正是具名测试改读已解析属性的原因。

会话搜索回归（已合并切片，`run-191-search-device.sh`；每象限 app `SessionSearchDeviceTest` 8 例 + after-script `SessionSearchQueryDeviceTest` 4 例 + 两阶段重启 + teardown，四象限顺序经 `with-host-slot.py` 串行）：

| 象限 | 命令 | 结果 |
| --- | --- | --- |
| S1 consumer×36 | `bash scripts/debug/2026-09-18/run-191-search-device.sh consumer 36 5642 build/hxa-191-search-consumer-api36` | app **`OK (8 tests)`**；storage `tests=4 failures=0 errors=0`；`closed.json` exit 0 |
| S2 consumer×29 | `bash scripts/debug/2026-09-18/run-191-search-device.sh consumer 29 5644 build/hxa-191-search-consumer-api29` | app **`OK (8 tests)`**；storage `4/0/0`；exit 0 |
| S3 developer×36 | `bash scripts/debug/2026-09-18/run-191-search-device.sh developer 36 5646 build/hxa-191-search-developer-api36` | app **`OK (8 tests)`**；storage `4/0/0`；exit 0 |
| S4 developer×29 | `bash scripts/debug/2026-09-18/run-191-search-device.sh developer 29 5648 build/hxa-191-search-developer-api29` | app **`OK (8 tests)`**；storage `4/0/0`；exit 0 |

门禁（本轮实际执行）：

| 命令 | 结果 |
| --- | --- |
| `./gradlew spotlessCheck detekt test` | BUILD SUCCESSFUL（宿主单测全过；`HelixThemeDeviceTest` 具名测试编译入 androidTest） |
| `./gradlew :app:assemble{Consumer,Developer}Debug{,AndroidTest}` | BUILD SUCCESSFUL；4 个 APK 哈希见[制品身份](#制品身份本轮实际安装检查对象sha-256) |
| `bash scripts/check-all.sh --artifacts` | exit 0（variant boundaries + developer 订阅/运行域边界 + consumer 排除，未被主题改动波及） |
| `bash scripts/check-all.sh --source` | 见[边界](#边界与外部事实)：新增完成记录触发 `Completion index is stale`（协调者重算），其余 check-docs 链接/ADR/i18n/secrets 全过 |
| `git diff --check` | clean |

## 模拟器纪律

独占既有 AVD 定义 `HelixApkUpgrade_API36/29_20260918`（主题矩阵）与 `Helix191_API36/29`（搜索回归），唯一端口 5700/5702/5704/5706 与 5642/5644/5646/5648；**每象限一个自有进程**（`owner.json` 记自有 pid + serial + AVD），跑前拒绝已占用串、探测两个控制台端口空闲；仅 teardown 自有进程组（`closed.json` exit 0）。四象限顺序执行（consumer36→consumer29→developer36→developer29），全部经 `with-host-slot.py` 串行（不并发重活）。未借用/未关闭任何其他执行者的模拟器，未向真机安装/卸载；每象限间与全部结束后 `adb devices` 无遗留、八端口均 free。

## 边界与外部事实

- **不新增手动主题选择器**：本版本以「跟随系统」为完整方案；新增手动选择器会把范围扩到共享设置 UI 与持久化（超本轮边界）。若日后引入用户主题选择器，须在此窗口侧 `values-night` 之上叠加显式覆盖并补持久化与设备回归。
- **不强制改写外部网页配色**：浏览器目的地只验证其在当前主题下**可操作**，不改写被加载网页的颜色（超范围）。
- **对话框覆盖方式**：对话框走 M3 `MaterialTheme`（默认跟随系统），随主页面/各目的地一起翻转；具名测试**未单独**对某对话框做在设备断言（M3 已覆盖，属可接受的次要缺口，非缺陷）。
- **裸 dumpsys 标志不作判据**：API 36 edge-to-edge 下应用窗口块不含可 grep 的 `LIGHT_STATUS_BARS`，故以「具名测试已解析属性断言」为权威、以「窗口 `mFullConfiguration` 夜间 `night`」为在设备补充证明（见[命令与结果](#命令与结果全部实际执行)）。
- **`check-all --source` 预期红**：新增 `docs/completion-records/HXA-191.md` 触发 `generate-completion-index.py --check` 的 `Completion index is stale`。按约束「公共 status/roadmap/index 由协调者最终整合」，本执行者**不重算 index、不改 status/roadmap**；仅在此登记（修复命令见下）。
- **具名测试与矩阵运行 APK 一致性**：本轮交付源码经 P1 修复（`@Suppress("LongMethod")` 编译期 lint 提示 + 删除未用助手）后重建 androidTest APK 再跑矩阵，避免「矩阵跑的 APK ≠ 提交的源码」；APK mtime 新于源码，哈希见[制品身份](#制品身份本轮实际安装检查对象sha-256)。
- 无真实付费账号、无真实用户会话数据（设备测试仅用自造 fixture）；API29/36 模拟器不代替真机长稳/OEM。

## 复现入口

```bash
# 一次性跑完整回归：四象限主题矩阵 + 四象限会话搜索回归（均经 with-host-slot.py 串行）
bash scripts/debug/2026-09-18/run-191-full-regression.sh
# 单独四象限主题矩阵 / 单象限
bash scripts/debug/2026-09-18/run-191-theme-matrix.sh                     # 四象限
bash scripts/debug/2026-09-18/run-191-theme-matrix.sh consumer 36 5700    # 单象限
# 门禁（--source 无需 host-slot，无 Gradle）
./gradlew spotlessCheck detekt test                 # 经 with-host-slot.py
bash scripts/check-all.sh --artifacts               # 经 with-host-slot.py
bash scripts/check-all.sh --source                  # 无构建，直接跑
git diff --check
```

## 建议 status 更新（供协调者整合，本执行者不直接改全局 status/roadmap/index）

- **HXA-191 建议改为「已完成 / 已交付」**：深色主题（本证据）+ 会话搜索（[切片证据](hxa-191-session-search-2026-09-18.md)）两部分均交付并本地验收；建议 `docs/development/status.md` 将 HXA-191 指向本完成记录与两份证据，并移除「待实现」标记。
- **重算完成索引**：`python3 scripts/generate-completion-index.py`（加入 `HXA-191`），解除 `Completion index is stale`。
- 本执行者**未**直接改 `docs/development/status.md` / `docs/development/roadmap.md` / `docs/completion-records/index.md`（公共文件，协调者职责）；`docs/development/tasks/HXA-191.md` 已附本轮深色主题交付记录并更新分类行为「均已交付（待协调者整合实施状态）」。
