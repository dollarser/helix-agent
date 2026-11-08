# 网页、Resources 与有界客户端验收

2026-11-08。以下原轮记录对应基线 `4a451f4e` 上当时未提交的工作区；各批次源码输入、候选与安装身份以 ignored `build/validation/continue-delivery/` 对应清单为准，HEAD 不替代工作区身份。原轮未提交、推送、发布或修改版本；后续独立 Debug 收尾见文末，不将其结果追溯归入旧候选。

## 主机与候选

- `full-host-3` 的 `scripts/check-all.sh --all` 退出 0，门禁期间输入未变，双渠道 Debug/Release 制品已保留。App XML 为 Consumer 1,639 项、Developer 1,837 项，各 0 failure/error、5 项既有 skip；跳过不记为通过。
- MCP XML 为 17 组共 105 项，tools/android 为 8 组共 104 项，均 0 failure/error/skipped。Fetch/HTTP 五组 52 项已包含在 104 项中；105 是整个 MCP 模块结果，不是 105 项新增 Resources 测试。`final-focused.log` 静态门禁通过，两个模块测试任务为 UP-TO-DATE，不描述为重新执行全部测试。
- `full-host-1` 取消、`full-host-2` heap 失败保留。第三轮之后的验收断言、辅助识别与文档更新不倒记为该轮已验输入；最终 `full-host-4` 的 `--all` 退出 0，门禁期间输入未变，四份 APK 哈希与第三轮完全相同。随后仅补录本记录及相关文档事实，不改变已验生产/测试源码。

## 网页与 MCP 文本 Resources

- `web_fetch` 保留 `http.fetch` 原始响应语义；生产 transport/extractor 对 example.com、IANA reserved domains、RFC 9110 的手动抽查均实际 HTTP 200。example 首次误将 head 标题要求为正文，原失败保留，后以实时 HTML 正文逐字/hash 核对确认；IANA 有布局空白，RFC 保留 BOM 并如实标记 60,000 字符截断。见 `web-fetch-public/summary.md`，不外推通用提取质量或浏览器呈现。
- `natural-live-6` 及最终 `natural-live-7` 的资源消费各 1 项通过，每轮 5 次合成请求，使用真实 dispatch、持久化和请求编码路径，核验网页/MCP 正文的下一请求消费、来源 URI、内容 hash 与不可信标识。Provider transport 为进程内脚本夹具，不是实际模型，也不是外部 Resources 服务；既有账号 admission 前置未绕过。两轮不相加为覆盖数量。
- 前一资源夹具已看到正确正文投影，但最终嵌套持久化 JSON 字符串断言失败；改用结构化检查后独立复验，不把旧失败改写为通过。
- Search 服务接入、登录/JS 页面完整正文、真实模型自主发现/引用、真实外部 Resources 服务、二进制物化均不由上述结果证明。MCP 二进制仍明确拒绝，不冒充已保存或发布。

## API36 客户端与 SAF

- `isolated-3` 成功构建，输入未变；`isolated-device-3` 25 项通过：后台预览 UI 13、私有 SAF Provider 12（快照容量 2、平台取消 3、目录可靠性 7）。该组没有系统 SAF 目录授权，不用私有 Provider 冒充真实系统验证。
- `system-saf-2` 1 项通过：实际 DocumentsUI/ExternalStorageProvider 的搜索、双向同步、冲突暂停及服务重开后的恢复/显式解决。仅授权本轮新建空目录；该授权和所有自有内容、目录、私有夹具已清理，日常包不变。服务实例重开不是 Android 进程强杀恢复；不外推其他 Provider、OEM 或真机。
- `release-smoke-2` 双渠道隔离测试签名 R8 候选成功构建且输入未变。Consumer 首轮 3 项因首用提示旧标签识别失败；第三轮两渠道各 3 项因漏适配可跳过的第二页引导失败，原结果保留。最终 helper 仅核对所属 App 和已知引导标题后点击 Continue，不接受授权。
- `release-smoke-4` 重建测试包并复用第二轮应用 APK，生产输入未变；API36 Consumer 3/3、Developer 3/3 通过，零失败。测试包身份见该轮 receipt；应用构建后的差异仅自然任务测试、Release UI helper 与文档。未卸载或清数据，测试包保留；这是隔离测试签名的基础入口 smoke，不是正式签名或完整 Release 产品验收。
- `daily-install-1` 已在 `Helix_API_36` / API36 覆盖安装第四轮 Developer Debug 到 `com.helix.agent.developer`；安装字节核验与 firstInstallTime 保留均通过，没有卸载或清数据。完整 APK 哈希和安装核验保留在该轮 receipt，不把安装成功外推为额外功能验收。

## 有限自然模型与历史失败

- `natural-live-5` 自然诊断通过；修复样本 180 秒超时，诊断遗留的 SESSION/session 禁用项污染后续工具可用性。虽已消费 stderr，文件未改，不记通过，也不归因于“有可用工具仍拒绝修复”。
- `natural-live-6` 自然诊断 1 项通过。修复样本已核验实际文件修正、exact keep/hash/readback、回答、exitCode=7 及原失败命令未重跑，但最终 JUnit 因合法路径投影与旧原始参数身份断言冲突而失败；该轮整项仍记未通过。
- 后续测试核验持久本地调用→wire 参数、assistant function、后续 tool wire 及解析后的 owned Delivery callRef，并补路径投影/错误 callRef 反例。最终 `natural-live-7` 离线证据 5 项通过；修复样本 JUnit 与实际任务均通过，10 次 HTTP 200；不是仅以请求成功或回答自述判断效果。
- 同轮自然诊断真实行为失败：2 次 HTTP 200，仅调用 `tools.read_result`，未补读 stderr，却把 270 字符摘要当作完整日志。保留失败，不继续修改夹具或重复直到通过。整轮 `tasksPassed=false`，自然诊断可靠性仍开放；先前两轮诊断成功不能把本轮改记全绿。
- 最终资源消费通过与诊断失败分别记账。账号退出、加密 session absent、日常包不变均为 true，cleanupErrors 为空；没有清除日常或隔离包数据。
- 历史[非真机客户端回归](nondevice-client-acceptance.md)中未读取 stderr 的样本保留。本轮有限成功不抹去历史失败，不从 Turn COMPLETED、HTTP 200 或有限样本推导一般可靠性。

## 原轮交付边界

最终主机门禁、双渠道 Release 基础入口复验和日常覆盖安装均有独立成功回执；自然诊断失败仍开放。本轮没有真机操作、正式发布、额外外部账号授权或服务部署。各候选分开绑定，不继承历史 Release、OEM 或全部产品通过结论。

## 2026-11-08 独立 Debug 收尾

本节证据位于 ignored `build/validation/closeout-2026-11-08/`，与上文原候选分别绑定；以下文档回填不改变已验生产/测试源码。代码已按日志 `fa2e574e`、网页 `bdf0b9a4`、MCP `9b4b06ca`、缓存 `dd8d6181`、客户端 `5baca271` 分批本地提交，未推送或发布。

- **日志语义与实际效果：** `tools.read_result` 明确分页仅覆盖结果正文，区分日志流的 inline preview、正文缺失和未知；正文读完或 `logTruncated=false` 不代表 stderr 已读。模型按原 logRef 补读独立日志，不重执行原命令。`natural-live-1` 的真实诊断 5 次、修复 8 次模型请求均获 JUnit 和任务效果通过：stderr 被实际读取并进入后续请求，原执行一次、绑定不变、未重跑，修复文件的保留内容和 readback 精确核验。账号/会话及测试清理通过；历史 `natural-live-7` 失败保持失败，不据有限样本保证一般可靠性。
- **主机及客户端：** 最终 `debug-host-7` 完整 Debug 门禁通过且输入未变；App XML Consumer 1,649、Developer 1,847，各零 failure/error、5 项既有 skip。生产 APK 与 `debug-host-2` 相同，未把复用结果说成全部重新执行。`isolated-device-1` API36 预览 17 项和私有 SAF 12 项通过；预览新增取消、焦点、可用高度和横屏关闭热区四项边界。此轮不重跑或继承旧系统 SAF、Release 与真机验收。
- **真实资源任务：** `live-resources-5-trace` 的 Qwen3.8-27B 四轮 ChatService、六次 HTTP 200 整轮通过。网页严格检查正文、HTTP 200/status、持久结果、下一请求消费及答案；MCP 另核验 URI、不可信标识、`hashFields` 内容 hash 及 source-only，不将 MCP hash 专项当作网页新增 hash 验证。MCP 为本机 HTTP 协议服务；隔离包临时精确 App 级 `127.0.0.1:19092` LAN scope 在 finally 撤销复验，账号与测试清理通过。该授权不称会话级，不代表第三方生产 MCP 服务或二进制能力。
- **验收器与历史失败：** 首轮初始化 EOF；夹具改 HTTP/1.1 并 flush、经连接自测后不再复现，不将协议版本认定为唯一根因；次轮缺 LAN scope 被正确拒绝。`live-resources-3` 实际四轮/六请求内容成功，但 JSON 斜线转义和随后发现的裸文本 hash 假设使验收不成立，原 JUnit 失败保留；最终改为结构化 URI、完整文本及 `hashFields` 长度封装 SHA 契约。`resource-evidence-offline-3` 一项方法含错误 URI/hash/untrusted 等反例通过，对旧持久结果的离线重分析不改旧轮状态。
- **自主完成边界：** `live-resources-4` 两次 HTTP 200 后，模型读 MCP、仅表示将获取网页便结束，未调用 web_fetch、未完成四轮，保持真实失败。该轮没有完整 SSE，不能归因为 Provider 或模型中的某一方。唯一新增脱敏 SSE/Turn 留痕的 `live-resources-5-trace` 成功轮保留六份已交付 SSE，独立 `trace-analysis.json` 核验原始及 decoder 终止原因一致为两次 tool_calls、四次 stop，各响应只有一个位于 finish 之后的 `[DONE]`；capture 记录为 consumer stop，不据此声称观察到网络 EOF；六份 chunk/body hash、decoder replay 和持久工具顺序一致，四个最终答案原始 text SHA 与 Room 一致。`sse-offline-1` 三项通过，未发现确定的生产解码或结算缺陷；新成功仍不证明旧早停根因已修复，自主完成可靠性保持开放。
- **构建与安装边界：** `debug-host-4` 虽完成构建/测试，但产物检查与并行隔离构建竞争共享输出，整轮失败；`live-build-5` 测试构造编译失败也保留。最终序列化门禁及相应独立测试候选分别验收。`daily-install-1` 将 gate 7 的 Developer Debug 保数据覆盖至 `Helix_API_36`，安装字节及 firstInstallTime 核验通过，无卸载或清数据。本轮无真机、推送或发布，Debug 不代表新 Release 验收。

Search、登录/JS 页面完整正文、通用提取质量、第三方生产 Resources 服务、二进制和其他模型/OEM仍不由本轮证明。四轮任务的实际缓存复用与非因果边界见[缓存接续](long-task-cache-2026-11-08.md)。
