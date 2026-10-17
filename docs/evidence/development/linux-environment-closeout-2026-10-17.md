# 内置 Linux 环境：独立模拟器与本地候选收尾

日期：2026-10-17；任务：[HXA-305](../../development/tasks/HXA-305.md)。当前记录实施与验证过程，未完成项目不记通过。

## 范围与来源

从 `main` / `8431718f` 接续已完成的 [HXA-305 主机阶段](bundled-linux-environment-2026-10-17.md)，保留全部 Runtime 修改和支持审查者已修改的 7 份工程指导文档。Owner 追加授权独立 ARM64 模拟器、指定 Qwen、最终 R8 验收、分阶段本地提交及候选冻结；不推送发布，不操作日常模拟器或实体手机数据，不运行 x86 或长稳，不实施新 diff、远程平台或整体重构。

设备仅新建 `Helix_HXA305_API36` / API36 / ARM64、自有 `emulator-5634`，runner 使用只读磁盘与新输出目录，结束后关闭自有进程。首次启动因宿主剩余空间不足失败，未执行测试；仅把新 AVD 的数据分区从 10 GiB 调到 4 GiB 后重新启动。主机键盘及硬件键盘下的 IME 按现有 runner 配置；本任务不包含拼音输入验收。

## 追加修复与审查

- 新增真实首次使用夹具：从缺失环境开始，Job/手动终端双向复用相同安装；两个并发 Job 只生成一份环境。破坏性起点要求显式 `hxa305OwnedEnvironment=true`，仅用于自有合成安装。
- 删除 UI 夹具新增环境外合成标记及目录链接，验证取消保留安装、确认删除不跟随链接清理标记。
- 首轮 13 项中 12 项通过，返回设置状态测试失败。单项复现确认 `state=PREPARED`，页面预期“已准备”，应用 Context 却返回 `Prepared`。改为状态资源交由当前页面的语言上下文解析，设置概览和能力页一起修正；不延长等待或删除刷新断言。
- 终端初始提示和内置 helix-docs 仍要求先修复、再验证，已统一中英文为点击启动即按需准备，异常时再进入维护；连接错误说明改为当前内置版本匹配。
- 7 份指导文档审查合并：AGENTS、导航、实施、调试、工程手册、验收矩阵、发布就绪。统一同一持续任务的授权接续、成功构建与实际候选关联、结果持久化与模型消费的区别、旧契约测试替换、符号链接清理和临时脚本目录。文档本身不授予额外操作权限。
- 竞品补充沿原研究/ADR/任务记录：持续工作区是交互目标；Helix 已有 JGit staged/unstaged diff，逐调用有界变更记录属于后续 286/288。dots/Grok Bot/Muse 的控制界面、推理、执行与持久状态分层是设计参考，不复制其部署或账号共享范围。具体一手链接见[目录与差异研究](../../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#115-同日收尾持续工作区与差异审阅)、[个人 Agent 部署研究](../../research/modules/01-architecture-and-execution-engine.md#106-2026-10-17-个人-agent-产品的部署边界复核)。

## 验证状态

原始输出与候选位于忽略目录 `build/hxa305/closeout/`。初次 Debug 首用/维护结果、语言失败复现和后续复验分别保留，不覆盖失败日志。

- `device-debug-final`：Advanced 四个测试类共 **13/13**，覆盖真实首用与并发、维护/删除、离线许可/布局、损坏/版本及返回刷新；Standard `RuntimeSetupDeviceTest` **1/1**。零失败/跳过，自有实例正常关闭。Advanced 的逐方法 TestRunner 日志和 JUnit 汇总保留，Standard 另有 raw instrumentation/XML；不伪造缺失的 raw 状态包。
- `./scripts/check-all.sh --all` 完整通过，见 `all-gates-final.log`：Python **275** 通过；JVM **6,089**，零失败/错误，**8** 项 opt-in 跳过；格式、detekt、双渠道 lint、Debug/Release/R8 构建、独立测试驱动编译、42 份锁及渠道/运行时制品边界通过。跳过项仍为两渠道的外部包/配置/公开 MCP 输入，不冒领 HXA-303 的历史补验。
- 首次完整门禁被新增设备测试的 LongMethod 阻断，已提取链接标记准备帮助方法，保留所有断言；最终全仓门禁重新通过。调试中的无效 Gradle 任务名和格式错误保留日志，不计成功构建。
- 源码本地提交 `e311f016` 后，干净工作树执行 `./scripts/build-release-apks.sh arm64-v8a` 并成功冻结双渠道 R8 候选。实际应用 ID、ARM64 only、non-debuggable、0.0.6 / code 1 和现有本机开发签名均核验；候选目录保留 BUILDINFO、SHA256SUMS、签名报告、mapping、独立 UI 驱动来源及发布说明草稿。
- 最初把自宿主的独立 UI 驱动误作主应用 instrumentation，启动器在设备启动前拒绝。改用已有 Debug 启动夹具，再安装 R8 和独立 UI 驱动；后续业务仅由公共 UI 驱动最终包，并分别核对实际已安装 APK。没有修改启动器的目标校验或以 Debug 结果代替 R8。
- 首轮安装读回哈希比较失败，未进入模型任务，当时没有保存设备端对照，根因未确认。后续保留设备端哈希、实际读回字节哈希和长度，两者与冻结候选逐项一致；不回写首轮为通过，也不据此前一次失败宣称产品安装缺陷。
- `r8-hash-check` 的 Standard 独立边界、真实 Qwen 写入/读取通过：原 Turn COMPLETED，write/read 均 SUCCEEDED，另从实际文件核对 `HXA305_STANDARD`。Advanced 首次 Job 也仅提交一次，status/collect 绑定同一 originalCallId，ZIP stdout、工具回填及随后真实模型请求均为 `HXA305_PROOT`。该轮后续终端导航失败，因此只采用上述已经完成的子项。
- 两次终端夹具假设分别是未展开“工具”分组、以及进入已自动运行的终端后再点不存在的“启动会话”。按当前 UI 修正为展开分组、进入并等待 RUNNING；保留失败截图/原始日志，不修改产品来适配旧脚本。第二轮已额外读取并核验内容哈希，确认模型最终回答包含实际 stdout。
- `r8-maintenance-final` 已通过真实模型原 Job、终端实际输入/独立文件读回、共用原安装、停止结算、检查和被动损坏状态；修复确认遇到英文原生对话框 `CONTINUE` 与夹具 `Continue` 大小写不符，尚未执行修复。同步发现历史检查成功回执与当前损坏状态并列，新增“上次维护结果”标签，明确其为过去操作，当前安装事实仍单独读取。修复/删除及新措辞候选复验继续进行，不继承旧包通过。

真实手机、其他 API/OEM、x86 设备、长稳、其他 Provider/账号及 FFmpeg/PRoot 媒体端到端均不属于本轮验收范围。
