> 整合复审：本页保留分支交付陈述，不能作为 HXA-191 整体验收完成记录。API29 深色及订阅内容配色缺口仍由 HXA-191 跟踪。

# HXA-191：深色主题与会话搜索

日期：2026-09-18。本记录收口 HXA-191 两部分：**深色主题**（本轮，worktree `Helix-theme-191`，分支 `codex/hxa-191-dark-theme`）+ **会话搜索**（上一轮，见[切片证据](hxa-191-session-search-2026-09-18.md)）。两部分均交付并本地验收；不代表正式签名发行或真机长稳/OEM 通过。

决策记录：遵循任务范围（`docs/development/tasks/HXA-191.md`）——主题与搜索均为**应用能力**，不新增跨会话搜索 Tool、不把命中注入当前模型上下文、不因查询获得其他会话 Agent 权限，不发起模型请求。深色主题取「**统一跟随系统**」为第一版完整方案（**复审纠正**：material3 1.4 裸 `MaterialTheme {}` **并不**跟随系统、是固定浅色，故本轮**显式接线** `Theme.kt` 的 `HelixTheme`——两个组合根按 `isSystemInDarkTheme()` 选 dark/light——并补齐此前**硬编码为浅色**的 Android 窗口侧（`values-night` 平台主题）），**不新增手动主题选择器**（避免把范围扩到共享设置/持久化）、**不强制改写外部网页配色**。会话搜索沿用已合并的只读 `SessionSearchRepository`（**无第二会话状态源**、**无 schema 变更**）。无新增执行域/授权/凭据/二进制版本架构决定。

## 交付

- **深色主题（跟随系统）**：Compose 侧 `app/src/main/kotlin/com/helix/app/Theme.kt` 的 `HelixTheme`（新，`isSystemInDarkTheme()` 显式选 dark/light）+ `MainActivity` 两入口改用 `HelixTheme`；窗口侧新增 `app/src/main/res/values-night/themes.xml`（`Theme.Helix` 深色平台主题：status/nav 栏 `#FF1C1B1F`、`windowLightStatusBar=false`，与浅色基线 `values/themes.xml` 逐项对应）+ `runtime/cli-app/src/main/res/values-night/themes.xml`（developer 订阅/登录面 native-View 壳的**窗口/系统栏**同款深色）。覆盖主页面/对话框（树内 M3，随 `HelixTheme` 根翻转）、系统栏、**真实渲染 Compose 颜色**、活动重建（旋转等价）、真实进程重启、5 目的地、三语言、大字体（矩阵 `font_scale` 1.0→1.3 自设，深色真实生效 API36）、对比度（WCAG AA 4.5）。
- **会话搜索（上一轮，本轮回归）**：只读 `SessionSearchRepository` + 只读 `MessageDao.contentSearchCandidates(limit)`（JOIN + 排序 + `LIMIT`，无 schema 变更）+ `ChatService.searchSessions`（防抖 + workScope 扫描 + 范围/截断显式呈现，不在 Compose 主线程扫全量历史）。
- **具名设备测试**：`HelixThemeDeviceTest`（**6 面**：窗口侧读已解析平台主题属性、Compose 侧采真实渲染像素）+ 既有 `SessionSearchDeviceTest`（8 例）/ `SessionSearchQueryDeviceTest`（4 例）。复现脚本落 `scripts/debug/2026-09-18/`（`run-191-theme-matrix.sh` / `run-191-full-regression.sh` / `run-191-theme-device.py`）。

## 实际验证

| 命令 / 范围 | 结果 |
| --- | --- |
| `./gradlew spotlessCheck detekt test` | BUILD SUCCESSFUL；宿主单测全过 |
| `./gradlew :app:assemble{Consumer,Developer}Debug{,AndroidTest}` | BUILD SUCCESSFUL；4 APK 哈希见下 |
| 四象限主题矩阵 `bash scripts/debug/2026-09-18/run-191-theme-matrix.sh`（consumer/developer × API36/29，端口 5700/5702/5704/5706，独占 `HelixApkUpgrade_API*` AVD） | 每象限每模式 setup「process crashed」+ 独立 PID 标记 → verify **`OK (6 tests)`**；**深色真实生效 API36 两 flavor**（`cmd uimode night` no→yes、窗口带 `night` token + `apr=LIGHT_STATUS_BARS` 翻转）；**API29 两象限深色未在设备验证**（模拟器镜像无法强制夜间，实际覆盖浅色 + 非主题面，见[边界](#边界)）；`closed.json` exit 0 |
| 四象限会话搜索回归 `run-191-search-device.sh`（端口 5642/5644/5646/5648，独占 `Helix191_API*` AVD） | 每象限 app **`OK (8 tests)`** + storage connected **`4/0/0`** + `closed.json` exit 0 |
| `bash scripts/check-all.sh --artifacts` | exit 0（developer 订阅/运行域边界 + consumer 排除，未被主题改动波及） |
| `bash scripts/check-all.sh --source` | 除**预期** `Completion index is stale`（本记录触发，协调者重算 index）外全过；`git diff --check` clean |

本轮验收 APK SHA-256（consumer/developer 各 app + androidTest）：app `e9783c668e540864629bea58dc6b872c7c7b6f59c2ae3e16f36f8f4ce03e22ea` / `5687a451ac3a50e524b7e9dfa9fdd986106ed38ae2aa70192ac58dc45a23061d`；androidTest `1a3d70fed5a1346908277439ad07a5f81f1eded4b51e49498f4016d43dc5f4f7` / `377619b981f56dfb79bd6932198942ad47326bc849115b19d89fd0fe1bc63f0a`。原始日志在 `build/hxa-191-theme-<variant>-api<api>/` 与 `build/hxa-191-search-<variant>-api<api>/`（`build/` 被忽略，不入仓）。逐象限细节与在设备证明见[深色主题证据](hxa-191-dark-theme-2026-09-18.md)。

## 边界

- **深色主题以「跟随系统」为第一版**：未做手动主题选择器/持久化（超边界）；对话框由 `HelixTheme` 树内覆盖、未单独在设备断言（`composeSurfaceColorMatchesCurrentSystemMode` 的整壳 surface 断言已覆盖同一 Compose 根，属次要缺口非缺陷）；**developer 订阅/登录面（native-View 壳）的内容色未做 night-aware**（其窗口/系统栏已随 `values-night` 翻深，View 内容色为本轮残留边界）；不强制改写外部网页配色。
- **API29 深色未在设备验证（模拟器镜像限制）**：`HelixApkUpgrade_API29_20260918` 无法被强制夜间（`cmd uimode night yes` 等 5 种标准方式均 no-op、`settings …/ui_night_mode` 三命名空间皆 `null`），两 API29 象限「dark 模式」实际停留在浅色，故其 `OK (6 tests)` 覆盖浅色主题 + 两阶段真进程重启 + 5 目的地 + 3 语言 + 大字体（`font_scale` 1.0→1.3 为矩阵自设值），深色渲染未在 API29 验证；同一 API-无关代码路径已在 API36 两 flavor 真实深色下验证（非代码缺口，需可强制夜间的 API29 镜像方可补验）。
- **判据选择**：具名测试以**已解析属性**断言 + 采样真实渲染像素为权威（API 36 edge-to-edge 下窗口 `statusBarColor` 由系统合成、裸读不具判别意义）；`mFullConfiguration` 的 `night` token 与 appearance flag（`apr=LIGHT_STATUS_BARS`，API36 light 有 / dark 无）为**可 grep、可翻转**的在设备补充证明（旧稿「应用窗口块无裸 `LIGHT_STATUS_BARS` 可 grep」不成立，已更正）。
- **无 schema 变更**（已验证的缺失）：会话搜索 `contentSearchCandidates` 为新增只读查询，本轮不改任何 `@Database` 版本/迁移/索引；主题改动仅 `res/values-night`，不触及存储。
- **公共文档未直接改**：`docs/development/status.md` / `roadmap.md` / `completion-records/index.md` 由协调者整合（本记录触发 index 待重算，建议命令见[证据](hxa-191-dark-theme-2026-09-18.md#建议-status-更新供协调者整合本执行者不直接改全局-statusroadmapindex)）。
- API29/36 模拟器不代替真机长稳、OEM、真实付费账号；本轮未 push/合并/发布。
