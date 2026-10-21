# 工具命名与契约整理

Owner 授权优化工具命名、参数与描述，并明确独立工具可以不带命名空间。采用按能力成组、独立操作保留清楚短名的原则，不把所有操作套入同一前缀，也不改变来源归属或执行权限。

## 当前命名边界

| 能力 | 当前公开名称 | 本轮处理 |
| --- | --- | --- |
| Goal 生命周期 | `goal.create/get/update/report` | 创建、查询、更新统一；保留原持久身份、修订、结算与用户请求要求 |
| 设置查询 | `settings.inspect` | 无参数，只返回当前设置、可选模型及能力可用性 |
| 设置修改 | `settings.apply` | 走普通授权，只改变后续输入默认值，不改变当前 Turn |
| 工具发现与结果 | `tools.search`, `tools.read_result` | 结果读取与发现归组；读取不重执行原工具 |
| 用户澄清 | `ask_user` | 保留独立短名，立即返回待答；回答不替代审批 |
| 文件和命令短名 | `read/write/edit/bash/view_image` | 保留既有公开短名及同一作用域实现/权限链路 |
| 浏览器、会话、技能、记忆 | `browser.*`, `session.*`, `skills.*`, `memory.*` | 保持已有清晰分组；记忆契约按操作细化 |
| 本机、界面自动化与 Root | `device.*`, `ui.*`, `root.*` | 保持能力及来源边界，不混并为一个权限域 |
| 外部 MCP/A2A | 各自安装来源命名空间 | 不因内置工具整理改写外部工具名称或授予权限 |

旧 Goal 名、旧设置名及旧结果读取名从注册目录直接移除，不提供别名/调用转发。一次性移动原来源的用户 ENABLED/DISABLED 选择，冲突时禁用优先；不转移单次批准，不修改历史 ToolCall/审计，不删除已有 Goal、记忆、设置或会话。既有 `ask_user` 从始至终保持原公开名称，问题与回答持久身份不变。

## 设置职责

删除旧设置提案队列、对应弹窗和重复页面快捷入口，不再返回“等待设置确认”这种与实际应用状态分离的成功结果。导航统一由已有 `device.open_page` 请求，补齐 `permissions` 和 `session` 白名单目标，保留原有权限页/会话设置页能力。

`settings.inspect` 输入仅为空对象；action/page/模式等字段由 schema 拒绝，执行器也在访问应用前拒绝。取消和过期同样不能读取应用。它归 READ_ONLY，不授予操作权限。

`settings.apply` 保持 LOCAL_MUTATION 与 DEVICE_SYSTEM_MUTATION 效果分类。修改必须绑定当前 live 工具调用/Turn，校验取消、时限、模型可用性及排队输入；当前运行参数不变。不能修改权限、安全档位、原生 QuickJS 开关或凭据。失败不会假装设置已经生效。

## 参数与结果

- 记忆输入逐字段说明逻辑 path、来源、正文、唯一字面匹配及替换内容；逻辑路径不等于 Workspace/设备路径。`expectedHash` 是当前内容 SHA-256；`new` 只供 memory.write 创建，编辑/删除不接受此 sentinel。Global/可信当前 Project 与原冲突检查不变。
- 记忆按 list/search、read、write/edit、delete 分别声明输出字段。正文始终标为不可信数据，不能变成指令或授权。输出校验不改写底层内容或掩盖失败。
- 工具结果分页明确 offset/nextOffset 为 Unicode code point，不是 UTF-16 单元或字节；持续读取到 truncated=false。结构包含引用、偏移、总字符数、正文与截断标志，nextOffset 仅在需要续页时返回。
- 设置查询/修改与 ask_user 也声明实际输出结构。用户澄清返回 AWAITING_ANSWER，不是已收到答案；超时或推荐选项不表示同意。
- 保留不同资源的 ID、内容版本和控件 token 的语义，不为了外观统一改成同一种模糊引用。

工具发现补充设置、用户澄清、设备和结果读取的中文检索词。这些词仅供发现，不能影响来源、分类、授权或执行绑定。

## 验证范围

本轮新增测试覆盖新旧目录边界、空参数查询拒绝写入/导航参数、取消/过期前不访问应用、设置未来输入结果、记忆版本 sentinel、真实文件记忆输出的 schema，以及成组/独立工具中文发现；页面测试校验补齐的权限页/会话设置页目标与现有原生路由一致。既有 Unicode 无损分页测试同时验证新结果 schema 与返回的读取工具名。

完整主机门禁、主机测试及两渠道 AndroidTest APK 编译已通过。本轮没有设备或真实模型/账号验证授权，不安装、不启动设备、不发送真实服务请求；编译不等于 UI 或实际模型选用工具通过。源码不自动提交、推送或发布。


### 本轮最终主机结果

- `GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false' scripts/check-all.sh --all`：通过，含格式、detekt、Debug/Release lint、全量 JVM 测试、两渠道 Debug/Release APK、release-smoke 测试 APK 编译、依赖锁、渠道/运行时边界及文档/ADR/i18n/秘密扫描。
- `./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --max-workers=1 -Dorg.gradle.parallel=false`：通过，未运行设备 instrumentation。
- 新增 10 个测试方法，应用层方法分别在两渠道执行；Consumer Debug 1427 项、Developer Debug 1562 项，各有 5 项既有条件跳过，均零失败/错误。Android 工具模块 63 项、存储模块 243 项，均零失败/错误/跳过。
- 初次完整门禁在 lint 分析 DevicePageToolTest 时出现内部 FIR 错误；保留原失败日志，固定源码后重跑 `:app:lintConsumerDebug` 通过，随后完整门禁通过。没有禁用 lint、跳过测试或修改基线；根因尚未确定，不把重跑通过当成已定位原因。
- 本地忽略的 `build/tool-contracts-*` 保存日志、构建指纹与源码绑定；只在主机验收通过后记录最终候选。后续只补充文档，没有修改应用/测试源码。
- 未进行设备验证、真实模型选用工具验证、真实账号调用或安装；没有提交、推送或发布。并行 UI 原型目录未修改。
