# 本轮验证记录

日期：2026-10-21。范围只包含 `docs/design/helix-ui-prototype/` 的本地原型。不编译 Android、不使用模拟器/真机、不访问账号或真实后端、不提交/推送。

## 实际完成（第三轮复审）

- 当前为 **55 个设计视图 / 21 个专项场景**。相较第一版增加 7 场景；55 项源码映射存在，全部当前原型路由可渲染。
- `node --check` 检查 `app.js`、`catalog.js`、`details.js`、`assemble.cjs`、`verify.cjs`：通过。
- `node assemble.cjs` 重新生成单文件副本和覆盖矩阵：通过。
- 使用本机已有 linkedom，经 `DOM_MODULE` 运行 `node verify.cjs`：**80 项通过**。同一组测试对 `PROTOTYPE_BUNDLE=standalone` 再运行，**80 项通过**。
- 未下载或安装依赖。未为 Helix 引入 npm/Gradle 依赖。原型与单文件副本均无远程脚本/字体/图像、fetch/XHR/WebSocket、真实后端或终端执行。
- Markdown 本地链接、HTML 资源文件、路由和源码映射存在性检查：通过。
- 所有修改只在原型隔离目录；未改应用源码、Gradle、其他任务文档、旧原型。没有提交、推送、安装或设备操作。

第一版的 27 项检查是历史基线；本次是字段补齐及产品意图/竞品/手机差异复审后的新结果，不把第一版结果冒充当前验证。测试名字与断言保存在 `verify.cjs`；系统选择器、音频/时间/网络均模拟，不宣称真实效果。

## 80 项 DOM 检查清单

1. 55 registered routes render with valid local targets
2. all source mappings exist
3. all scenarios render
4. login return preserves search and draft, does not send
5. login cancel returns model picker with draft
6. guest can configure API subscription local without Helix login
7. attachments and references selected, cancel preserves draft
8. voice cancellation and acceptance do not auto-send
9. permission denial keeps original draft
10. mode command edits draft then commits on explicit send
11. stream cancel retains received content
12. model cannot switch during active generation
13. tool refusal and success keep separate facts
14. unknown effects do not replay, require factual review
15. file conflict retains edits and saves copy
16. back returns to originating page
17. branch keeps original messages
18. loading cancel and error retry preserve data
19. search, filters, deletion affect displayed rows
20. schedule scope confirmation required
21. reminder preview, note save, knowledge scope
22. all page normal/loading/empty/error views render
23. startup destructive confirmation only changes mock route
24. user question validates and cancel preserves draft
25. background stop waits for simulated exit
26. no external resources, unresolved local files, or unlabelled icon buttons
27. provider fields match three protocols and eighteen source templates
28. provider endpoint rejects credentials query fragment whitespace IDN invalid ports and addresses
29. provider headers validate pairing reserved credentials and template collision
30. provider name model display bounds fail without losing draft
31. provider discovery cancel and failure preserve fields and select explicit ID
32. source connection edits preserve model visibility and display settings
33. model empty selection explicit save and cancel rollback
34. context ratio window bounds per-model save and storage retry
35. context discovery failure preserves manual edit and reasoning OFF differs none
36. hosts syntax aliases IPv4 IPv6 IDN size and per-host cap
37. LAN uses exact private origin removes and rejects public CIDR paths
38. egress requires Advanced precise scope fixed TTL and supports revoke expiry
39. CUSTOM rules snapshot seven effects default separate tools remain disabled
40. cloud speech validates HTTPS loopback fixed WS account and cancellation
41. QuickJS enabling needs explicit confirmation and PRoot env rejects credential shapes
42. local model validates HTTPS hash size cancel and hash failure not loaded
43. multiple projects retain separate names directories and validation
44. nested files PDF pages image preview multi-selection moves and collision reject
45. system picker rejects file private URI oversized capture and supports mock PDF
46. history pagination and search navigate actual results
47. source failures preserve original and frozen citation survives version update
48. calendar month-end anchors DST gap advances and overlap uses earlier instant
49. reminder body exact notice and multiple saved rules round-trip
50. MCP malformed JSON endpoint credential fields reject preview import stays disabled
51. OAuth advanced config validates redirect document and device-code expiry
52. market filters signature failures review target and explicit confirmation
53. schedule date session limits and changed scope invalidate confirmation
54. storage cleaning changes only selected simulated category and diagnostic privacy defaults
55. branch tree stores prior messages and selected references are snapshots
56. model context nested cancel returns to model directory and local source max is explicit
57. mobile app scopes validate and skill structured fields export without execution
58. user single choice and maximum custom answer are enforced
59. browser tabs keep independent history and terminal sessions keep output drafts
60. new review scenarios preserve source drafts and block false resource success
61. build channel and safety profile remain distinct and Standard navigation gates developer tools
62. settings hub exposes real setting authorities and child nav preserves selected ancestor
63. new conversation resets authority and selected context to new-session defaults
64. project instructions and memory stay isolated by project ID and do not grant permission
65. project member transfer preserves directory permission and blocks active or queued input
66. project archive preserves tasks and deleting metadata preserves files memory sessions
67. project-created session uses current project directory and fresh permission defaults
68. uninstall preserves independently owned skills and question sample switch stays outside product canvas
69. plugin IA uses added discovery add flow and no global plugin toggle
70. plugin update preserves version on failed verification without duplicate approval
71. review state resets truthfully and running Goal does not create another attempt
72. conversation artifact side preview preserves draft model scroll route and closes without sending
73. knowledge question shows exact selected sources and saved answer cites only frozen scope
74. phone artifact reading is modal with backdrop inert background and explicit return
75. wide artifact reading stays nonmodal and adding reference returns editable composer
76. inline dictation streams into composer without editing dialog and requires explicit send
77. cancel inline dictation restores original draft and late recognition cannot overwrite it
78. user message exposes only edit copy and editing keeps replies without creating branch
79. editing cancellation keeps original user message and model reply retains branching
80. reset clears simulated records and modal

测试通过窄接口驱动操作，并通过 DOM input/change 事件验证字段与选择。单文件也以独立 DOM 和脚本环境执行同组断言。它不是真实浏览器鼠标点击端到端测试，不验证 CSS 计算布局、点击命中、真实音频或辅助技术。

## 未验证与限制

| 项目 | 结论 |
| --- | --- |
| 真实浏览器视觉/截图 | **未验证**。已知浏览器工具禁止 file:// 且禁止转发/换浏览器绕过；本轮未尝试、未建 HTTP/HTTPS 转发。没有截图文件。 |
| 响应式 | 已编写 390 px 画布、自适应宽屏、760 px 断点、列折叠、滚动、Sheet 规则；只检查源码与 DOM 结构，**未验证实际布局无溢出**。 |
| 浅/暗对比度 | 提供完整双主题色表与焦点/错误文字，未用浏览器/对比度工具测量，不宣称 WCAG 合规。 |
| 中文字体、200% 字号、键盘 | 系统字体回退、focus 样式、Tab/Esc 已实现；跨平台字形、软键盘避让和浏览器实际焦点待人工验证。 |
| Android/Compose | device validation **not requested**；没有执行设备命令。原型不证明当前 app 已有同样视觉。 |
| 真实服务/副作用 | 全部模拟，无实测；不使用真实账号、密钥、收费额度、剪贴板或系统分享。 |
| 完整业务细节 | 见 COVERAGE.md 的“未完整展开或未验收”，包括 Provider 全字段、系统选择器、完整分支树、真实恢复和多格式预览等。 |

用户可在自己的允许环境双击 `index.html` 或 `standalone.html`，按 README 推荐流程手动验证。截图验收建议矩阵：390×844、768×1024、1280×900；浅/暗各一组；聊天空/长文/键盘占位、抽屉、模型登录、录音、审批、文件冲突和设置长表单。逐项核对字体、图标、圆角、间距、底部输入可见性，不能只比较颜色。

语音结果直接写入 composer 的最新规则见 [VOICE_INPUT.md](VOICE_INPUT.md)。已替换旧的弹窗编辑断言，保留不自动发送、权限拒绝、取消和超时边界。
