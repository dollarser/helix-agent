# HXA-242：HTTP 仅提示、交互反馈与主机验收（2026-10-02）

## 结论与范围

本轮所有者要求完成用户配置的 HTTP Provider 仅提示行为，并完成 HXA-242 现有 UI / Mobile Use 改动的主机验收。基线为 main / `0fc836db898bbe0b63df3c64def01e829e4f058d`。实现、根测试、全模块 Debug 静态分析、两渠道 App / AndroidTest APK、锁文件、源码和实际 APK 检查已通过。未提交、未推送；本轮没有启动模拟器、真机或真实外部账号。

## HTTP 最终行为

用户填写并选择 HTTP API / 自建服务时，可保存、发现模型、运行连接/能力/上下文检测、发送消息、执行已授权 Plan 和投递已接受队列；HTTP 本身不再是拒绝理由。服务端错误、不存在的模型、资源不足和工具权限仍如实反馈，不把传输风险提示当作另一份权限。

| 层次 | 当前处理 |
| --- | --- |
| 表单 | 移除额外明文确认框、确认字段和保存/发现门槛；保留 `provider-cleartext-warning` |
| Provider | `CleartextWarning` 只描述 host / port；删除 `CleartextAuthorization`、`CleartextBindingStore` 及 `isCleartextPermitted()` |
| 发送 / Plan / Queue | 移除旧明文拒绝分支；保留原目标、内容、模型绑定和工具权限校验 |
| 当前会话 | `chat-cleartext-warning` 提醒消息、附件和 API Key 未加密，建议 HTTPS，不增加确认弹窗 |
| Android 两渠道 | 共用 `app/src/main/res/xml/network_security_config.xml`，允许用户选择 HTTP；不再只有 developer 放行 |
| TLS | 保留默认系统 CA / 主机名验证；Provider 默认传输禁用 HTTP/HTTPS 间自动重定向，不把 HTTPS 失败改成 HTTP 重试 |
| 旧偏好 | 旧 `cleartext_bindings` 不再读取或约束请求，不删除用户的 Provider 或其他设置 |

HTTP/HTTPS 协议间的自动重定向均由 `.followSslRedirects(false)` 关闭，遇此类响应应使用服务端正确地址；同协议重定向行为未改变。模型下载、订阅登录和工具网络授权不由本次改动扩权。动态用户地址无法通过静态 XML 域名表枚举，因此 Android 传输配置属于应用级支持，并非声称它在系统层只对 Provider 生效。当前决定见 [ADR-PROVIDER-001](../../adr/provider/001-models-and-connection.md)。

## 现有交互与 Mobile Use 收口

共享滚条覆盖普通纵向表单、LazyColumn 管理列表和横向溢出内容，包括 Provider 模型管理、模型选择、本地模型、设置、插件/文件/产物与长内容弹窗。复用原滚动状态、列表 key、焦点和滚动语义；只有溢出才绘制，主机测试覆盖短列表、边界、超高单项和窗口变化。AndroidTest 保留滚动与 BringIntoView 检查，但本轮只编译，不宣称设备截图验收。

Composer 显示恢复、模式切换、权限保存和提交等不可发送原因。附件恢复不再锁住纯文本编辑；失败时保留未确认的附件身份，显式移除后才发送，不悄悄丢弃用户附件。缓存保存失败不成为发送前置条件。附件改为有界横向条；紧凑输入布局使用实际 `LocalWindowInfo.containerSize` 和键盘状态，而非旧 Configuration 屏幕高度。旧会话的模式切换回执不能改写新会话输入。

Mobile Use 对目标包名整体验证后才修改选择，处理空目标、非法包名、服务未连接和启动失败，并展示剩余许可时间/动作数。进入平台动作调用后的异常返回 `ACTION_OUTCOME_UNKNOWN`；动作失败或不确定结果不再标为确定无副作用，不能触发基于该标记的盲目技术重试。执行前拒绝与已发出动作的结果仍分开。

## 验收期间发现并修复的问题

除原任务外，全模块 lint 发现既有 FFmpeg 包装脚本使用全员可读/可执行权限。PRoot Runtime 与应用共享 UID，无需其他用户访问；改为 POSIX `rwx------`，并新增从宽权限收紧到仅 owner 的实际文件测试。未更改 FFmpeg 候选或增加另一个执行通道；本轮不含 PRoot / MediaCodec 的设备执行验收。

格式与静态检查还定位了滚条复合条件、旧测试 helper 的无用参数、紧凑布局使用旧屏幕高度和 AndroidTest 遗留的 Provider 更新参数，均修复后重跑。HTTP 测试的资源关闭接口和规范 URL 端口断言也修正。既有任务/权限断言没有为通过测试而删除；已废弃的 HTTP 许可断言迁移为 warning-only 行为回归。

APK 校验首次失败是工具输出形态差异：编译 Manifest 返回 `@ref/0x...`，不是源码符号名。现在通过该 APK 的资源表解析精确 ID，再检查实际 XML；缺失、错误或重复映射仍失败，并添加相应反例，不只是接受任意 `@ref`。

## 最终主机证据

最终联合入口：`bash scripts/debug/2026-10-02/validate-hxa242-host.sh`。

| 检查 | 结果与证据 |
| --- | --- |
| 根 `test`、detekt、Spotless、全模块 Debug lint、两渠道 App lint、Debug App/AndroidTest APK | `BUILD SUCCESSFUL`；1156 tasks：51 executed、1105 up-to-date |
| 依赖锁 | 38 个锁文件通过，无新增依赖 |
| 重点 JVM 回归 | 14 个类、89 个不同方法；跨渠道 136 次结果均通过，0 失败、0 跳过 |
| HTTP 主机/制品规则 | 6 项 Python 回归通过，含优化模式执行与资源 ID 反例 |
| 源码门禁 | 文档、ADR、国际化、秘密扫描及既有 Python 门禁通过 |
| 实际 APK 门禁 | 两渠道 Manifest / 网络资源 / DEX、Runtime/订阅/FFmpeg 渠道边界通过 |

89 个方法包含既有和新增/修订测试，不是“新增 89 项”；136 是跨渠道执行结果，不是独立方法数量。最终构建是增量验证，不能称所有用例强制重跑。重点包括真实主机 loopback HTTP 凭据/请求体传送、HTTPS 握手失败不降级、目录发现、废弃偏好无影响、草稿/滚条反馈、自动化动作结果和 FFmpeg 脚本权限。

最终 Gradle / 锁文件日志目录：`build/hxa242/host-20261002-202224-30973/`。

其他记录：`build/hxa242/http-policy-final.log`、`build/hxa242/artifacts-verified.log`；文档回填后的源码与差异复核使用 `build/hxa242/source-closeout.log` 和 `build/hxa242/diff-closeout.log`。早期失败日志保留，不覆盖成通过。核心 host.log SHA-256 为 `b81eecde30d001716ab45d4ae40ea5390b45b50ecd4b6d193693bd651694d98f`。

机器汇总由 `scripts/debug/2026-10-02/summarize-hxa242-host.py` 生成到 `build/hxa242/host-acceptance.json`，记录日志、重点 XML、变化代码与四 APK 的哈希；不是设备报告。新增检查已接入 `scripts/check-all.sh --source` 和既有 APK 检查链路。

### 四份最终 Debug 制品

| 制品 | bytes | SHA-256 |
| --- | ---: | --- |
| consumer App | 86,991,503 | `6af33317a2da605822a5ad4602457d09578a995852b6ad06417e965845ee0beb` |
| consumer AndroidTest | 7,800,088 | `2891625b6765adfbd42cca546cd2470279d36c0214313b1839d4947b7b0d32fe` |
| developer App | 148,636,569 | `f62a381c9879043681aac051b352cdb44f41a3e115f593b7d832c4f717277ae6` |
| developer AndroidTest | 10,156,951 | `c1bce5a616bace818b5dfa72469e76c0fb221ef0ec25b07a2c9fd2c54357f8ed` |

## 剩余边界与交接

本轮设备状态 **not requested**。没有沿用 HXA-241 的模拟器通过记录；真实键盘/分屏/大字体布局、当前 HTTP 在 Android 上的实际收发和 Mobile Use 设备交互仍需单独授权验收。没有执行 Release 打包、商店发布或真实账号兼容测试，不承诺远端服务一定可连。

自动化许可上限、敏感动作政策、屏幕截图和坐标操作不在此次实现范围。本轮 HTTP 需求和既有 HXA-242 主机范围已完成，不代表所有产品流程无限制或全仓没有缺陷。修改留在 main 工作区，未提交、未推送；原始构建产物/日志留在 ignored `build/`，不自动纳入 Git。
