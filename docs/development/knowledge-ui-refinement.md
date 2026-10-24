# 知识库阅读与编辑体验优化

Owner 于 2026-10-23 要求参考主流知识库/笔记应用优化实际界面。此次只调整原生 Compose 展示、导航和页面草稿，不迁移存储，不新增笔记引擎、模型协议或自动上传。

## 调研与取舍

| 官方资料 | 采用的启发 | Helix 的边界 |
| --- | --- | --- |
| [Obsidian 阅读与编辑](https://obsidian.md/help/edit-and-read) | 阅读视图与 Markdown 源码编辑可切换 | 已有正文默认阅读；空笔记直接编辑。预览不保存，不实现 Live Preview 编辑器 |
| [Joplin Markdown](https://joplinapp.org/help/apps/markdown/) / [富文本限制](https://joplinapp.org/help/apps/rich_text_editor/) | 同一 Markdown 正文可阅读、编辑；富文本来回转换存在格式限制 | 复用现有安全 Markdown 子集渲染，原文编辑，不做 HTML/富文本往返转换，不复制第三方代码 |
| [Notion 搜索](https://www.notion.com/help/search) | 查找入口稳定、与当前内容范围一致 | 资料/笔记分类和搜索固定在列表上方，分别走既有检索；不仿造数据库视图或云端工作区 |
| [Gemini Notebook 来源与笔记](https://support.google.com/gemininotebook/answer/16206563?hl=en) | 来源、问答和生成笔记的用途有区别 | 保留原件、提取文字、冻结引用、未核对草稿、已核对知识的区别；不复制其自动生成/云端能力 |

Markdown 是笔记正文格式，不等于当前产品把所有知识都保存为独立 `.md` 文件：现有 Room 笔记、FTS、来源和冻结引用继续由已有服务维护，原件保留在各自目录。界面不能把收集完成、预览或模型建议冒充已核对知识。

## 实现

- `KnowledgeLibrary` 为纯展示组件：固定分类/搜索、紧凑资料行、笔记标题/正文片段/核对状态、区分空库与无匹配结果。资料选择仍最多八份，复选框与正文同一行；所选操作自动换行，并提供取消选择。
- 导入、新建、隐私与 Agent 访问入口在紧凑工具区。完整隐私及只读访问开关移入可滚动对话框，概览始终说明本机保存、不自动上传。开关仍由既有 `agentAccess` 修改，默认权限不变。
- `KnowledgeNoteEditor` 对已有正文默认渲染，空笔记直接编辑；切换保留未保存文字，页面草稿/模式随保存状态恢复。退出仍经过未保存处理；草稿保存与核对保存保持独立。删除、导出与刷新引用集中到“笔记操作与引用”，原确认/版本核对不变。
- 问答正文复用 Markdown 渲染；原始资料展示明确标题，删除移至详情末尾，避免作为阅读首要操作。模型目标选择、外发确认、生成状态、固定证据及手动载入建议保持原流程。
- 不新增收藏、目录、标签、自动分类、图谱、同步或整库备份的空壳入口；现有服务不支持的组织能力不伪装成可用。

## 验证边界

新增 UI 回归覆盖阅读/编辑切换不隐式保存、未保存正文与模式重建恢复、搜索/分类恢复及原资料保留；现有导入、所选引用、多源提炼、手写保存与原件不变用例保留。最终 `scripts/check-all.sh --debug` 退出码 0：290 项主机脚本测试、文档/ADR/多语言/秘密检查、Spotless/Detekt、Debug Lint、JVM 测试、两渠道 Debug APK 与产物边界检查通过。最终源码的 `:app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin` 也成功；新增交互用例只是编译通过，未记为设备运行通过。原始输出位于 ignored `build/knowledge-ui-2026-10-23/host-debug-final.log` 与 `test-compile-final.log`。

首轮交付只做主机验收。随后 Owner 的既有模拟器授权经任务交接补充确认，继续以独立测试包及合成数据实际验证；不使用真机或真实模型。改动保留工作区供审查，不自动提交或推送；原有开发指南修改不属于本次。

## 追加模拟器验收

指定既有 API36 ARM64 模拟器，独立包 `com.helix.agent.developer.asrvalidation`，安装前确认不存在。日常应用不覆盖、不清数据。手机、窄屏大字体和平板的临时分辨率/密度/字体修改均保存原值并在 finally 恢复；无需新 OS 权限或账号。

首轮矩阵 24 + 11 + 11 项通过。截图复核指出独立 Compose 测试宿主没有复现正式 Shell 的安全区/900dp 内容宽度限制，不能将其状态栏重叠或过宽当作产品截图；已修正宿主，并新增真实 MainActivity 导航/阅读截图。窄屏 1.5 倍字体下标签改为“资料/笔记/问答”，新建按钮改短，笔记页使用简短本机保存提示；完整隐私与模型确认仍可见于既有入口。最终生产源码再次通过完整 Debug 主机门禁，未重复 Release/R8。最终设备矩阵如下；新增真实 Activity 重建补测另记。

最初一次本地脚本错误地要求 ADB 安装输出完全等于 Success，提前退出且未执行测试。实际临时包安装成功已通过包路径及候选身份核实，随后仅移除该包，修正脚本再继续；原始失败结果保留，不记作设备功能失败或测试通过。

| 最终独立 Advanced Debug 候选 | 配置 | 实际执行 |
| --- | --- | --- |
| 手机 | 1080×2400 / 420 dpi，字体 1.0 | 25 次通过：知识库 UI、真实 MainActivity 导航/阅读、笔记存储/引用边界和问答 UI |
| 窄屏大字体 | 900×2000 / 420 dpi，字体 1.5 | 12 次通过：同一 UI 与真实 Shell 场景 |
| 平板尺寸 | 1920×1200 / 240 dpi，字体 1.0 | 12 次通过：同一 UI 与真实 Shell 场景，正式宽屏侧栏生效 |

合计 49 次执行，并非 49 个不同方法。保存 27 张实际系统截图，已目视检查手机列表/阅读、窄屏大字体笔记/编辑/删除确认、平板来源/阅读等代表画面；主要入口可达，文字随字体换行，不以压小字号掩盖空间问题。独立组件截图用于细节，`*-shell-*.png` 才是正式 MainActivity/主题/导航的画面。

最终矩阵原始输出、JUnit、候选 APK、安装身份和配置/清理核对位于 ignored `build/knowledge-ui-2026-10-23/device-accepted/`；两渠道最终主机日志为 `host-device-accepted.log` 和 `test-compile-device-accepted.log`。原分辨率、密度、字体已逐项复核恢复，两个临时包均移除，原有三个日常 Helix 包保留。

本次没有执行真机、其他 API/OEM、全套 Markdown 语法、真实模型生成、实际系统导出到外部位置或完整 IME/拼音体验；删除 UI 核对确认说明与取消保留，实际删除/刷新引用的存储边界由笔记服务测试验证。不把入口可见当作外部送达，也不将本轮 Debug 结果称为 Release 验收。Standard 变体完成主机构建/测试编译，设备矩阵使用独立 Advanced 包，两者不混记。

### 真实 Activity 重建补测

原矩阵使用 StateRestorationTester 检查 Compose 保存状态，不能单独证明 Activity 生命周期。为此新增并实际执行 `KnowledgeShellDeviceTest.actualActivityRecreationKeepsSearchAndUnsavedMarkdown`：在真实 MainActivity 搜索后重建、编辑后再次重建，分别断言 Activity 实例确实更换，搜索词/笔记选择/编辑模式及未保存正文恢复，Room 中的原正文和 REVIEWED 状态保持不变。该一项补测通过，未运行模型或隐式保存。

此补测只追加测试源码；两渠道 AndroidTest 编译、两渠道 AndroidTest Lint、Spotless 与 Detekt 重新通过。应用使用与 49 次矩阵相同的候选 APK，生产源码摘要未变；增量测试 APK、JUnit、实际重建后截图及清理结果保存于 ignored `activity-recreation/`。总计 50 次设备执行，含 26 个不同方法。独立临时包再次卸载，最终保持日常应用与原设备配置。完整进程死亡/系统回收不同于此次 Activity 重建，不外推为通过。
