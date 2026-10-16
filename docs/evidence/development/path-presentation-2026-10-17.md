# 路径表达与连接文案优化

日期：2026-10-17。范围：Owner 要求移除数据驻留断言，优化内部路径及显示；追加要求参考主流 Agent、明确工作区/项目目录边界，不兼容不合理旧设计。本轮属于 HXA-302 后续，不启动整体重构。源码起点 `10b92533`，开始时工作区干净。既有冻结候选保留；Owner 随后授权保留数据的日常模拟器、ARM64 双渠道与短真实模型文件验收，并独立本地提交，仍不推送或发布。

## 发现与裁决

模型工具入口已经接受相对路径，并在参数哈希、审批和调度前按请求目录固定目标；问题在于回放和工具结果仍反复携带完整 Workspace UUID。仅缩短 UI 标题不能改善后续模型调用，直接删掉持久 scope 又会让历史文件在切换目录后指向错误的同名文件。

采用同一套规范路径身份加请求时投影：当前目录内的 `scope:<workspace>:project/docs/报告.txt` 在当前目录为 `project` 时呈现为 `docs/报告.txt`；切换到其他目录或工作区后，仍从同一持久记录生成完整 scope 引用。目录根呈现 `.`，名为 `scope:…` 或驱动器形式的文件加 `./`，避免被重新解析成另一个资源。路径不可用仍按原执行策略报错，不改绑到替代目录。

| 对象 | 权威职责 | 本轮边界 |
| --- | --- | --- |
| Project | 长期组织、共享指令与显式成员；主目录是一个 Workspace 引用 | 不以项目名或目录字符串合并身份；成员变更不搬文件 |
| Session | 会话历史、授权与当前目录绑定 | 相对路径没有跨会话的全局含义 |
| Workspace | 实际 Path/SAF 资源的稳定身份、后端及可用性 | UUID 保留在存储/审计，显示名不代替身份 |
| 模型请求 / 执行 cwd | 请求捕获的 workspaceId、相对子目录与 bindingRevision；Runtime 自己的目录映射 | 旧响应、审批和运行中执行不随新目录重定向 |
| FileScopePath | scope + 规范相对路径；内容 hash 是另一个版本事实 | 授权、越界、符号链接、归属、产物与恢复按原身份验证 |
| 展示路径 | 面向用户的来源名、相对目录和文件名 | 可展开并显式复制完整引用；不是可回写的权限凭据或新路径协议 |

这是对现有职责边界的应用，不是为了兼容旧实现而保留双写或双执行。当前阶段只增加纯投影和 UI 位置呈现，不改数据库、不搬用户文件、不另建别名注册表，不引入云端 Workspace 或自动 worktree。后续工具/产物重组可将已知结构字段提升为类型化资源引用，但必须保持本轮的原始身份、请求目录和压缩源反例。

## 竞品依据

- [Claude Code 官方常见流程](https://code.claude.com/docs/en/common-workflows#reference-files-and-directories)：文件引用可用相对或绝对路径，目录引用与 MCP 资源引用明确分开。Helix 采纳易读的工作目录相对路径；Android/SAF 继续使用受控资源引用，不向模型暴露 Android 私有绝对路径。
- [Codex 官方 Worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)：区分 local checkout、worktree 与会话 handoff；持久 worktree 可承载多个会话，会话历史不等同于底层目录。Helix 采纳组织、会话与实际资源分离；本轮没有 Git worktree 生命周期需求，不照搬自动建目录或回收行为。
- [Claude Code Desktop 官方说明](https://code.claude.com/docs/en/desktop#work-in-parallel-with-sessions)：按项目组织会话，Git 仓库可选择 worktree 隔离。Helix 默认独立会话目录与用户选择的共享项目目录均保留；目录组织不代表执行沙箱或授权。

以上为 2026-10-17 实际读取的一手文档与 Helix 当前源码对照，采纳的是职责与交互取舍，没有复制参考实现。不是证明某个路径长度或接口数量能提高成功率；本轮未做真实模型对照实验。

## 实现与审查

- `FileScopePath.referenceRelativeTo` 提供同一目录内可往返的短引用；其他 scope、父级和相似前缀兄弟目录保持完整引用。
- `FileHistoryPaths` 只处理已知文件工具的结构化路径字段和搜索匹配项。`ChatHistoryBuilder` 在组装模型消息时应用，`ContextHistoryMapping` 使用同一捕获目录匹配压缩；摘要生成仍读取原始带 scope 内容，不持久化短路径副本。文件内容、错误文本、未知工具和截断预览没有文本替换。
- 工具标题只显示操作与文件名，不显示目录；仍有长度和行数限制，目录与完整路径放展开详情；文件操作即使有模型意图也采用该标题，原意图完整保留在详情，其他工具继续显示模型意图。会话目录、项目编辑与目录选择提供可复制的位置详情。文件工作区列表优先来源名，重名来源保留标识辅助辨认；完整参数仍在执行详情。
- Provider 中英连接标签移除“数据不出设备”“已按 host:port 授权”等不能从地址推断的结论，修正 `ProviderResidence` 说明。未新增提醒弹窗或会话常驻提示。
- 导航返回属于 Owner 另行咨询，仅只读核查并在对话反馈；本轮没有修改导航行为。

## 验证

`./scripts/check-all.sh --all` exit 0：275 项 Python、全仓 JVM、Spotless、Detekt、双渠道 Debug/Release lint 和 APK 构建、R8、release-smoke 测试 APK、依赖锁及渠道/运行时制品边界通过。日志中的恢复失败/损坏证据文本来自 Python 的失败夹具测试，不是本轮设备运行。

| 当前 JVM 范围 | 通过 | 跳过 | 失败 |
| --- | --- | --- | --- |
| App Standard / consumer | 1277 | 4 | 0 |
| App Advanced / developer | 1398 | 4 | 0 |
| Core Workspace | 112 | 0 | 0 |

App 测试在最终全门禁实际执行；Workspace 112 项在本轮首个完整门禁执行通过，最终同源检查为 up-to-date。每渠道 4 项跳过均为既有可选外部输入测试：QwenWork 包、WorkBuddy 包、公开配置输入及公共服务读取，未在此轮提供对应 opt-in 参数；不是本轮路径回归跳过。新增 9 项回归（App 6 项路径/压缩 + 1 项标题，Workspace 2 项规范引用），覆盖当前目录往返、跨根同名/父级/前缀兄弟目录、Unicode/保留前缀、目录切换、调用配对、摘要保留完整来源、错误与用户文本不改写、未知工具/截断结果及长文件名显示。

首次静态检查指出本轮新代码的格式、行长与复杂度问题；已按职责提取路径投影函数并仅格式化本轮文件，完整门禁复验通过，没有弱化测试或降低门禁。`./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` exit 0，仅证明编译/打包。最终文档/ADR 门禁及 `git diff --check` 复核通过。原始日志、JUnit 汇总与源码校验留在忽略的 `build/path-presentation-2026-10-17/`，保留初次失败。

首轮只执行主机验证；Owner 随后追加设备/真实模型验收，结果按下节单独记录。先前 `8ef710d3` 的 R8/真实 Qwen 证据仅覆盖原冻结包，不能证明本轮新源码设备通过。未推送、发布或改版本号。

## 追加模拟器验收

指定日常 `Helix_API_36` / API36 / ARM64，保留既有会话、文件、模型配置与输入法；不清数据，不使用真机。为避免测试夹具重置日常配置，白盒测试使用独立临时 applicationId，最终 R8 包另行覆盖升级日常双渠道并经公开 UI 验收；两类结果不互相替代。

双渠道首轮各 7/7 通过后，进一步对实际文字排版增加可见性断言，在 240dp、字体 1.8 倍时复现长目录挤掉末尾文件名：原语义节点包含名称并不证明屏幕显示名称。首轮修复将文件名置前，双渠道各 7/7、零跳过。Owner 随后明确标题只显示文件名，最终实现去掉标题中的目录，并增加目录/scope 不出现在标题、相对路径与 URL/查询互不混淆的断言；以下最终验收以该实现为准。保留初次失败日志，不把失败改写为通过。

实际 R8 操作又发现会话快捷菜单底部仍直接显示内部引用，已改接同一 `ScopedPathText`，补充真实会话设置回归。最终设备矩阵扩大到双渠道各 **20/20、零跳过**：完整路径展开及真实系统剪贴板复制/恢复、Room 关闭重开后的目录切换与压缩源、旧响应/待审批调用原绑定及拒绝规则、全部工具卡片布局、工作区列表、中文大字体目录布局和会话设置。模型意图中的长路径也不能覆盖文件名标题；展开后保留原意图与参数。

最终代码重新执行 `./scripts/check-all.sh --all` 与 `./scripts/build-release-apks.sh arm64-v8a`，均 exit 0；JVM 数量见上表。白盒运行先以 `scripts/release-smoke.init.gradle` 指定独立 applicationId，编译双渠道 Debug/AndroidTest APK，再运行 `adb -s emulator-5554 shell am instrument -w -r -e class … <独立测试包>/com.helix.app.HelixAndroidJUnitRunner`。具体类为 `ScopedPathPresentationDeviceTest`、`PathHistoryDeviceTest`、`WorkspaceRequestBindingDeviceTest`、`ToolTimelineLayoutDeviceTest`、`FilesHomePresentationDeviceTest`、`WorkspaceLayoutDeviceTest#chinese320LargeFont`、`SessionSettingsDeviceTest`。每条命令和 JUnit 位于本地 `consumer-menu-final` / `developer-menu-final`。

### 最终 R8 与真实模型

新建双渠道 ARM64 R8 候选，保持 0.0.6/code 1、既有开发证书和非 debuggable。构建时为上述起点加本轮未提交修改，`sourceDirty=true` 如实保留；生产输入哈希、APK 哈希、映射与构建信息在忽略的 `device/candidate-final/`，本地提交后另作源码对应证明，不伪称当时从干净提交构建。通过 `adb install -r` 覆盖日常两个包，实际安装哈希与候选一致。

| 最终包 / 真实模型范围 | Standard | Advanced |
| --- | --- | --- |
| Qwen3.8-27B / Owner 指定兼容 Chat 服务 | 3 个 Turn、7 次文件调用 | 3 个 Turn、9 次文件调用 |
| 两个目录的同名文件写入、读回与实际字节核对 | passed | passed |
| 从根目录切换到 left：相对读取左侧，完整引用读取右侧并单次批准 | passed | passed |
| 正常进程重启后继续相对读取、历史/目录保持 | passed | passed |

两渠道最终任务无失败、无任务重试，工具持久状态 COMPLETED，结果 SUCCEEDED 且 verified；实际文件内容独立读回核对。Standard 在前一份中间候选上另有 1 个 Turn / 6 次调用，只保留过程证据，不计入最终包数字。设备发送记录只提取目录与结构化路径：切换前为 `left/same.txt`、`right/same.txt`，切换后左侧为 `same.txt`、右侧仍带完整 scope；Room 中两者始终保留规范身份。文件标题实际显示 `读取内容 · same.txt`，完整位置在详情。

UI 脚本曾因入口名称、菜单层级和滚动上限假设失败，观察脚本也曾混淆 tool-call 的 COMPLETED 与结果的 SUCCEEDED；已按实际控件与分层状态修正，保留原失败，不弱化产品断言。Advanced 既有 80 个工作区，8 次滚动不足以到达新目录，补充有界公开 UI 滚动后完成；未清理旧目录。目录选择器的搜索及来源区分列入后续优化计划，不在本轮扩大实现。

已删除两渠道临时 API 配置，将两个验收会话恢复为“需要批准”；五个自有测试包、专用转发和仅在进程内持有凭据的测试代理均已清理。原有 Standard 8 个会话/8 条消息、Advanced 13 个会话/8 条消息及 5 个模型配置保留，旧 Provider 与会话权限记录哈希一致。新增验收会话与文件保留；主应用为本轮最终 R8，安装哈希和冻结生产输入逐项匹配。只验证旧记录保留及上述配置，不宣称对所有私人文件做过逐字节盘点。

本地证据：`host-menu-final.log` / `.exit`、`device/arm64-menu-final-build.log` / `.exit`、两渠道 `*-menu-final` JUnit、`*-final-observed.json`、脱敏结构化请求、`final-verification.json`、`final-preservation.json` 与 `cleanup.json`。初次标题失败、中间候选、UI 脚本失败及因 Owner 更新要求而中止的主机日志均保留，未计作最终通过。

### 验收边界

真实模型只覆盖上述文件旅程与一个兼容 Chat 服务；压缩来源/严格匹配通过 JVM 与真实 Room 测试，未做真实模型多次压缩长程对照。真机、其他 OEM/API/Provider、Mobile Use 全矩阵及 FFmpeg/PRoot 媒体端到端本次 not requested，不以此轮结果替代既有分项验收。两份旧候选未改；本轮不推送、运行远端 CI 或发布。导航、RootFS/原生目录对比属于只读咨询；后续目录架构研究和计划单独记录。
