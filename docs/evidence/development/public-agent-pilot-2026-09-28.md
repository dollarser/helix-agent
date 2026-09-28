# BFCL 诊断与 AndroidWorld 小规模试跑

日期：2026-09-28。生产代码基于 `023c59fd`；本轮增加 opt-in 测试适配；试跑发现工具曝光缺陷后，按所有者后续授权优化默认工具面与按需发现。没有增加生产工具能力或执行授权。模型为已有本机 SGLang `Qwen3.8-27B`，未使用付费账号、物理真机或提交榜单。

## BFCL：模型直连诊断

引用 [Gorilla/BFCL 官方仓库](https://github.com/ShishirPatil/gorilla/tree/main/berkeley-function-call-leaderboard)，固定 revision `6ea57973c7a6097fd7c5915698c54c17c5b1b6c8`，Apache-2.0。参考源码在 ignored `build/public-eval/gorilla`，未复制进产品代码。

调用前固定数据集前缀：`simple_python_0..9`、`multiple_0..4`、`irrelevance_0..4`，各重复三次。使用原始问题、官方 `convert_to_tool` / AST checker；只新增模型名字归一化配置。irrelevance 按无 native tool call 判定。temperature=0、max_tokens=4096、每请求 timeout=120s，不执行工具；截断计 FAIL，传输/解析异常独立记 ERROR。

| 类别 | 不同题数 | 调用数 | PASS | FAIL / ERROR |
| --- | --- | --- | --- | --- |
| simple_python | 10 | 30 | 30 | 0 / 0 |
| multiple | 5 | 15 | 15 | 0 / 0 |
| irrelevance | 5 | 15 | 15 | 0 / 0 |
| 合计 | 20 | 60 | 60 | 0 / 0 |

端到端请求 median 2.170s、mean 2.323s、max 3.299s；45 次 `tool_calls`、15 次 `stop`，无 `length`。不是 decode tok/s，不包含 Android / Helix Harness。

这是顺序前缀小样本，不是随机代表性抽样；temperature=0 的三次重复不视为独立样本。未覆盖 parallel、多轮、执行结果、权限、恢复等；直接 native-call 协议也不是完整官方 runner。因此 **60/60 不称为 BFCL V4 总分或 Helix Agent 成绩**。

复现脚本：`scripts/debug/2026-09-28/run-bfcl-diagnostic.py`，在安装官方 BFCL 包及 `soundfile==0.13.1` 的 Python3.11 环境执行，传入新的输出目录。数据/答案 SHA、选题、配置在运行前写入 manifest。依赖快照 `build/public-eval/bfcl-packages.txt`。

原始证据：`build/public-eval/bfcl-pilot-20260928/`。

- manifest SHA256：`b4201e88f22242c972dee481b00fb3fcc9c1e7658f8964674ddf6d3b9ac168bd`。
- results JSONL SHA256：`8a27ef7edaac980fd3ba939b86d01dd652d4b9131c9248bce005efdf848cff73`。

## AndroidWorld：适配试跑

引用 [AndroidWorld 官方仓库](https://github.com/google-research/android_world)，固定 revision `e3fea3ccc69787570e282c99573298f1c3019a34`，Apache-2.0。通过官方 requirements 建 Python3.11 环境并生成 protobuf；依赖快照 `build/public-eval/android-packages.txt`。导入原版 `SystemBrightnessMin` / `SystemBrightnessMax` 初始化及 oracle，host 只负责夹具与判分。

本轮使用独占 API36 arm64-v8a AVD（4 GiB、4 cores、400 dpi），不同于官方推荐 Pixel6/API33 环境，且执行动作是 Helix Accessibility token 工具。不能对标官方榜单。没有应用快照时 upstream 初始化自身会跳过恢复；初始亮度和初始失败判分另作强断言。

Agent 路径为正式 Provider → ChatService → AgentLoop → Dispatcher/Policy → Accessibility。原始任务文本不加入答案或操作步骤。前七轮为32 model calls、16 tool steps、单次输出4096 tokens、累计131072 tokens、240s任务观察窗；第九轮将tool steps增至32、累计tokens增至产品已有上界1000000，其余保持。这是试跑边界，不改产品默认预算。仅允许本次授权的 Settings/SystemUI/Launcher/Helix UI 范围，test driver 可批准 UI 操作，其他待审批行为拒绝，生产敏感UI/token/包检查仍生效。ADB/root 仅用于独占设备初始化和判分，模型不通过 host 执行任务。

首次试跑 `build/public-eval/androidworld-pilot-20260928/` 为两个 fixture ERROR：新 Provider 未做连接测试，Session 创建被正式入口拒绝，均没有 Turn。夹具补齐连接测试后重跑，保留初次记录，不归因模型失败。

第二轮 `build/public-eval/androidworld-pilot-r2-20260928/`：两项均为 FAIL，Agent Turn 均为 `COMPLETED` 且无 Harness error，独立 oracle 均为0。min 保持255（44.554s，9次工具请求，其中2次 Linux 被拒绝）；max 保持1（16.373s，2次工具请求，其中1次 Linux 被拒绝）。准备入口 UI smoke 1/1，通过不代表任务成功。

第三轮 `androidworld-pilot-r3-20260928` 仅增加注册/可见清单取证，仍0/2：两个任务注册9个UI工具，但64个模型可见工具仅有 `ui.back`、`ui.click`，缺失获取token必需的 `ui.snapshot`。旧 `tools.search` 只搜MCP，无法找回内置工具。min/max elapsed 为20.051s/29.646s。

第四轮为未收敛的优先级候选（`androidworld-pilot-r4-20260928`），9个UI工具均可见，但默认仍64；0/2，出现 `TURN_TOTAL_TOKEN_LIMIT`。这轮开发期间源码继续收敛，因此其 APK SHA 是运行身份，follow-up 时的 host source hash 不代表APK源码快照。它不是最终验收。

所有者随后明确要求优化工具数量。最终方案保留64上限，默认19个基础工具，有效自动化会话加9个UI工具，其余内置/MCP/A2A通过原有搜索窗口按需发现；小MCP目录保留直接曝光。版本替换、禁用、模式/功能过滤与执行授权继续独立。新增拥挤回归先在旧代码失败；两渠道单元测试覆盖窗口替换、会话隔离、重启、禁用、模式变化和最终数量上界。

第五轮使用最终精简工具面（`androidworld-pilot-r5-20260928`）：准备UI/生产MCP发现回归2/2通过，但两个模型任务均在连接测试阶段失败、无Turn。主机30008也超时；只读SSH探测远端30008成功，随后本地转发恢复。该轮单列为连接 fixture ERROR，不能记为模型或Harness任务失败。第六轮首次启动因前一端口尚未释放被runner拒绝，未启动设备；换用空闲端口继续。

第六轮 `androidworld-pilot-r6-20260928`（连接恢复后）准备2/2通过，两个任务均实际执行，oracle仍0/2。每个初始请求28个工具，9个UI工具齐全；min/max elapsed 26.450s/25.366s，均以 `TURN_TOTAL_TOKEN_LIMIT` 结束，亮度未变。min轨迹触及 `SENSITIVE_UI`，不绕过保护；max反复尝试未公开取值的scroll方向。

进一步定位到既有 `ui.scroll` schema 只描述string，没有公开executor仅支持的 `forward/backward`。将这两个合法值写入enum与说明、scroll契约升为v2；未增加坐标、手势或系统写能力。新增回归先在旧schema失败，再验证两个合法方向与观察到的六种非法方向；原安全测试保持。第七轮 `androidworld-pilot-r7-20260928` 准备2/2通过、任务0/2；min/max 24.616s/21.052s，仍被累计token上限终止。min已发出合法backward/forward，其中一次scroll实际完成。不能将这轮预算终止直接归为能力缺失，因此第八轮单独增加试跑总预算，不与前轮声称严格性能A/B。

第八轮 `androidworld-pilot-r8-20260928` 为夹具 ERROR：测试把总预算误写为1048576，超过产品上界1000000；异步校验异常使min进程退出，随后max未连接Accessibility。该轮不计模型任务失败。隔离分支修正为引用 `TurnBudgetBounds.MAX_TOTAL_TOKENS` 并在调用异步入口前同步校验，后续保留独立重跑证据。

主目录出现另一端插件改动后，所有者确认该端继续负责。复用空闲 `codex/tool-discovery-eval` 分支独立验收；移除复制时带入的 AutomationTools provenance 参数及 PluginOrigin 测试，只保留本轮scroll契约修正。主目录保持原状。

初始 manifest 的预算字段按位置参数误标为16 model calls/32 tool calls；前七轮实际 Kotlin `TurnBudgets` 是 `maxSteps=16,maxModelCalls=32,maxInputTokens=131072,maxOutputTokens=4096,maxTotalTokens=131072`。此处以源码真实配置为准，后续 runner 字段已纠正，不修改历史原始 manifest。

## 隔离候选最终试跑

第九轮 `build/public-eval/androidworld-pilot-r9-20260928/` 使用隔离分支构建、修正后的32步/32模型调用/1000000累计token预算。准备 UI/工具发现 **2/2**；两项 instrumentation 都正常完成，官方 oracle **0 PASS / 2 FAIL / 0 ERROR**。初始曝光各28个，9个UI契约齐全，`ui.scroll` v2；执行后独占模拟器已关闭。

| 任务 | 初始→最终亮度 | Turn | elapsedMs | 工具请求 |
| --- | --- | --- | --- | --- |
| SystemBrightnessMin | 255→255 | COMPLETED，无errorCode | 132853 | 33 |
| SystemBrightnessMax | 1→1 | FAILED，MODEL_CALL_LIMIT | 206469 | 33 |

两条轨迹都实际打开 Settings，随后得到 `SENSITIVE_UI`。源码 `SensitiveAutomationTargetPolicy` 明确拒绝 Settings/SystemUI；allowlist 并不覆盖这一保护，本轮未放宽。min 最终报告无法完成，max 在重复等待后耗尽模型调用预算。`COMPLETED` 不代表原始目标成功，亮度未变的oracle决定FAIL。

此选题与现有系统界面保护不兼容，只作为负向边界诊断，不代表一般 Android 应用任务成功率。后续正向样本应先核对允许的应用/动作范围，不能通过解除保护获取分数。仍观察到模型使用过长AND搜索词、反复等待敏感界面，以及min建议关闭保护但产品没有对应解锁入口；这些是未解决的模型交互边界。没有增加坐标/手势/系统写能力，也不继续重复同一试跑寻找通过。

## 验证边界

测试适配开发期间修正了 Gradle task 名称、Provider enum、静态格式及深层嵌套检查。最终精简方案双渠道 app unit/lint/debug APK/AndroidTest APK通过（`build/public-eval-discovery-gates.log`）；曝光选择6/6、发现14/14每渠道通过。scroll修正后双渠道完整增量gate再次通过（`build/public-eval-final-gates-r2.log`），AutomationTools 6/6；`spotlessCheck detekt`（`build/public-eval-final-static.log`）和 `check-all.sh --source`（`build/public-eval-discovery-source.log`）通过。隔离分支去除另一端插件改动后，双渠道 unit/lint/APK/AndroidTest APK 与 automation unit 再次通过（`build/public-eval-isolated-gates-r2.log`）；`spotlessCheck detekt` 与 source gate 通过（`build/public-eval-isolated-static.log`、`build/public-eval-isolated-source.log`）。AndroidWorld benchmark 分数与准备 UI smoke 的 JUnit 成功分别报告。

P7 的发布副本清理与双渠道 4/4（各32轮压力注入）见 [发布残留证据](p7-publication-residue-2026-09-28.md)。实际发布中硬杀、真机满盘/长稳、历史输出截断及偶发多余调用仍开放；公开小样本不替代这些验收。

## 正式 P5 与失败取证

clean `d8ee8418` 在独占 API36 developer 完整执行15项，结果 **13 PASS / 2 FAIL / 0 fixture ERROR**：Files 3/4、JavaScript 4/4、Skills 4/4、Goal 2/3。准备真实Provider smoke 1/1；runner如实返回失败并关闭设备。原始目录 `build/p5-tool-discovery-20260928/`。生产缺省面为19项；skill-004通过搜索找到非默认 `skills.disable` 并完成原有授权链路。

- `file-003`：一次 `write` 请求未满足夹具精确path/content条件，夹具拒绝后模型如实报告未写入；原记录没有args，不能确定是路径还是内容不匹配。核心write已可见，因此不是原64截断问题。
- `goal-001`：模型产出三个计划checkpoint，但发出 `get_goal → files.list → update_goal`，用完固定4次模型调用；Goal落为 `BLOCKED / BUDGET_EXHAUSTED(maxModelCalls)`。当前oracle允许正确的MODEL_COMPLETED或RUN_FINISHED结算，但禁止业务工具；该次既有预算阻塞，也有多余目录查询，不能记PASS。goal-002 continuation、goal-003 approval阻塞均PASS，不扩大fixture预算。
- JS timeout这次executor有终止结果，tool为FAILED；取消case为NEEDS_REVIEW。都由现有oracle验证，不概括为所有timeout必须NEEDS_REVIEW。

同源码manifest `9dade91df2aa1dd597469b10a021e5d706ba8ec3169bc8056eeb17ec6fb715da`；app APK `d2cfbb479af257561af11e668c504501e95dfcad729ae094a6710ca4da596005`；test APK `ecd36fb1c6e8f3ef037a18a5c86dec74b1ec284a892513428f039cfa9443bcd3`。12项可测case elapsed median 4861.5ms、mean7063.4ms；含失败case，不能用其宣称任务成功率改善或因果提速。

发现两处取证不足后，仅增加File的canonical args/审批决定/预期path与content、Goal的工具args；修正四个P5 suite将数据集protocol误作实际protocol的问题，另存datasetProtocol。原正式运行的config/命令明确为OPENAI_CHAT_COMPLETIONS override，历史原始record不回写。保持生产代码、prompt、输入、预算、审批和成功oracle不变；下一次只做file-003/goal-001一次有界诊断，不把子集结果替换13/15正式成绩。
