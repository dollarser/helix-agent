# 内置模型账号登录接入

2026-10-21，Owner 要求将内置模型改为新账号登录，并参考国内主流平台的交互与界面。附件引用在读取工具中不可见；本机 Downloads 中同名 AccountAuth.java 已读，按其接口接入，没有将文件内容当作权限。没有复制测试账号或密码至本记录、代码或夹具；Owner随后明确授权的Git忽略本机测试配置另见历史验收记录。沿用同一 [Provider ADR](../adr/provider/001-models-and-connection.md)，不新增执行框架或修改主Agent/工具权限。

最新契约以以下“当前部署”与“账号额度和诊断”两节为准。[历史验收](../evidence/development/account-integration-through-2026-11-17.md)保留其当时范围，不作为本轮客户端或真实服务验收的替代证据。

## 当前部署（2026-11-18）

官方账号地址为 `https://accounts.hilex.me`，模型公网 base URL 为 `https://api.hilex.me/v1`，由 `OfficialServices` 统一派生。OpenAI 云模型不得通过本机免认证入口调用。账号管理请求使用 Bearer access token；模型服务请求另外携带有效 `X-Client-ID`；自配 API Key 使用自身 Bearer Key，不借用账号管理令牌，不在客户端存放 Worker 内部密钥或上游 OpenAI 服务密钥。

Owner 确认旧、新账号域使用通用令牌，持久记录不保存或判断签发域；有效会话不因换域强制重新登录。官方请求目标仅允许当前 accounts/api HTTPS 主机，WS 精确限定当前官方路径，不保留旧域回退或重定向。第三方 Provider 与自定义语音认证路径保持独立。

完整发现使用 `GET /v1/models?output_modalities=all`，根据 `architecture.output_modalities` 与可选 `endpoints` 区分对话、识别及合成，语音模型不进入聊天选择器。当前服务契约允许语音专用账号发现其有权限的模型，不要求额外 Qwen 权限；是否实际获准仍以服务响应为准。目录失败不等于直接语音调用必然失败，允许显式指定模型，且不从目录推断执行权限。

云端 `gpt-transcribe` 使用 HTTP `/v1/audio/transcriptions`，`gpt-realtime-2.1-mini` 使用 HTTP `/v1/audio/speech`；保留自建语音 `/ws/asr`、`/ws/tts`，但不能只改 model 名称便假设 WS 会转发至 OpenAI。具体音频约束与当前路由见[云端语音接入](cloud-speech-integration.md)。

## 账号额度和诊断（2026-11-18）

已登录账号详情提供按需展开的“额度与用量”。`AccountUsageClient` 只接受 `AccountSession`，通过其精确目标绑定的认证 wire 单次访问账号域名的 `/account/usage-snapshot`，在同一登录代际内读取完整 `luna`、`audio`、`usage` 三个对象；缺失或类型错误整体失败，不用三次请求拼接快照。响应上限 256 KiB，总超时 30 秒。以下是三个对象的展示字段：

- `luna`：`model/month/timezone` 和 `monthly_tokens/used_tokens/reserved_tokens/remaining_tokens`；月周期显示服务端 `YYYY-MM`。
- `audio`：`day/timezone`，`asr`、`tts` 各有 `model/unit/limit/used/reserved/remaining/requests`。ASR 的 `milliseconds` 除以 60000 显示分钟；TTS 的 `characters` 原样显示，不混用秒或 UTF-16 长度。
- `usage`：展示 `today`、按 service 汇总的请求数量及最多 20 条服务/状态记录。只映射展示白名单，不把原始 JSON、账号标识、IP 或设备标识放进 UI。

两类额度响应的可选 `resets_at` 解析为 `Instant`，界面按各自服务端 `timezone` 显示下次重置时间；ASR/TTS 使用音频响应顶层的同一重置时刻。缺失或非法值不推算、不用设备时区代替。

额度上限和剩余值均由服务端返回；不写死默认配额，不用 `limit-used-reserved` 重算余额。已计入包含预留/待核对量，不能再次扣除。日/月周期以服务端 `Asia/Shanghai` 为准，不使用客户端本地日期重算。仅配置 API Key 的 Provider 不调用这些账号接口、不伪造余额。额度读取绑定当前登录代际，切换/退出后的旧响应不能进入新账号；响应大小和总耗时有界。

账号错误先展示已解析的服务端 `detail`，无有效说明时用本地化分类；不展示异常堆栈或任意响应全文。保留 `X-Request-ID` 并提供复制入口。429 不无限重试，展示并遵守 `Retry-After`；401 只进行一次正规的刷新流程，403 保留服务端权限、封禁或首次改密提示。网络中断不无条件重交同份文本/音频，旋转刷新结果未知仍要求重新登录，不把请求诊断 ID 当作幂等保证。

请求诊断使用应用版本、随机安装实例 ID、应用 User-Agent 和每次逻辑请求随机 ID；安装 ID 不使用硬件序列号、MAC 或邮箱，也不参与认证。诊断信息不携带凭据，不把凭据或诊断字段塞进 WS URL 查询。常规日志不写识别结果、待合成文本或原始音频。

2026-11-15 的历史独立真实服务探测曾获得当时三项额度接口 HTTP 200，验证上述字段和单位；登录、目录、合成音频 HTTP TTS/ASR、退出也取得成功响应。只保留脱敏结构和状态，不保留身份/凭据或真实用量。该探测只证明当时三接口的服务器契约，不证明现行单请求快照已获同一批真实验收，不等于 Android 额度 UI、所有模型或发布候选全部通过。本轮客户端集成测试状态另随最终验收记录，不沿用历史记录的通过数。

## 已采用的契约与入口

- 设置、对话选模中的“Helix账号”均打开同一现有底部面板。登录使用邮箱/密码；注册为独立模式，确认密码后显式提交，注册成功不自动登录。
- `https://accounts.hilex.me/auth/login`：JSON `email/password`；`/auth/register` 为 `email/password/code` 与可选 `display_name`；`/auth/refresh` 和 `/auth/logout` 为 `refresh_token`。
- 响应读取 `access_token/refresh_token/expires_in/refresh_expires_in`。密码不保存；令牌组合为一条记录，经现有 Android Keystore AES-GCM SecretStore 原子保存，避免独立access/refresh写入撕裂。
- 内置模型使用固定目录服务地址和生成地址，模型条目由服务发现，经 AccountSession 注入 `Authorization: Bearer …` 与应用版本 User-Agent；同时发送固定公开 `X-Client-Id` 标识客户端，不复用自配Provider Key；单独ClientID及内置Key直调均废弃。None连接元数据表示无自配Key alias，不表示免登录。
- 到期前30秒单飞刷新；401最多在响应正文前刷新并重发一次，已经开始的流不重放。刷新失败或结果不确定清除本机会话，需重新登录，不重试旋转请求。退出在远端请求前清除本机访问权，即使远端失败也不恢复；新账号、退出后的迟到正文不能进入新会话。
- 固定白名单HTTPS目标，无重定向、隐式重试、明文/未知域令牌传输或调用方认证头覆盖。自配模型保持原凭据路径。账号变化后的目录以不同会话身份读取，旧目录和迟到缓存写入不能冒充当前账号。
- 语音设置的可选“使用Helix账号登录”只接受官方HTTPS域名；已有3/4行公开ID设置不再解析或复用；需重新显式配置。新记录使用account-v1标记。内网/自配无鉴权HTTP仍可显式使用，Bearer不发送到Mac loopback或10.0.2.2。

## UI与交互

当前官方部署基址统一于 `OfficialServices`：账号为 `https://accounts.hilex.me`，推理为 `https://api.hilex.me/v1`，语音 WS 为 `wss://api.hilex.me`。只有 accounts/api 两个 HTTPS 主机可接受账号令牌；WS 精确限定 `/ws/asr`、`/ws/tts`。官方语音地址由模式派生，设置不再提供独立地址编辑框；自定义 HTTP/HTTPS 地址与其无账号认证路径保留。此次不做旧语音域回退或重定向。详见[当前网关契约](cloud-speech-integration.md#当前统一网关owner-契约2026-10-28)。

实际查看[阿里云官方登录页](https://account.aliyun.com/login/login.htm)与[腾讯云官方邮箱登录](https://cloud.tencent.com/login)，参考其账密表单层级、突出主按钮、密码显隐及独立注册入口，使用Helix自有主题和图标。手机布局为欢迎标题、邮箱与密码、52dp圆角主按钮、注册切换和登录状态。提供邮箱格式反馈、Next/Done键盘行为、重复密码确认、请求中禁用重复提交/模式切换、退出确认和安全错误提示；密码状态不使用rememberSaveable，关闭表单即释放引用。短信、微信、QQ 未提供服务契约，不展示；邮箱密码找回已按 2026-11-17 契约接入，见历史验收记录；没有伪造协议链接、默认勾选或无效按钮。

## 文件与关键类型

| 路径 | 内容 |
| --- | --- |
| app/account/AccountTokens.kt | AccountTokens、AccountTokenStorage、AccountSecretStorage，安全令牌记录与既有Keystore适配 |
| app/account/AccountSession.kt | AccountSession、固定目标AccountServiceWire、单飞与版本/迟到正文边界 |
| app/account/BuiltInAccountWire.kt | 内置固定GET/POST及每次调用的独立身份绑定 |
| app/HelixApplication.kt、DefaultAppContainer.kt | 同一应用账号所有权、Provider与目录身份注入 |
| app/provider/ProviderFactory.kt、BuiltInModelCatalog.kt、BuiltInModelDiscovery.kt、ProviderTestStatusStore.kt | 账号注入、缓存身份与原身份发布 |
| app/ui/AccountSettings.kt、AccountLoginForm.kt、SettingsScreen.kt | 服务意图、登录/注册表单和现有入口 |
| app/voice/SpeechSettingsStore.kt、HttpSpeech.kt，app/ui/CloudSpeechSettings.kt | 当前账号语音设置、官方地址派生及自定义 HTTP 模式 |
| 三套strings.xml | 账号、登录错误及语音模式文案 |
| app/src/test/.../account/、provider/BuiltInModel*Test.kt、voice/HttpSpeechTest.kt | mock会话、身份、socket和配置回归 |
| app/src/androidTest/.../ui/AccountLoginFormDeviceTest.kt、voice/CloudSpeechDeviceTest.kt | 合成表单检查及旧真实服务profile明确拒绝 |
| README、Provider/语音同一ADR、status、HXA-307、接入说明 | 当前事实与历史证据边界 |

表中 `app/` 主源码缩写相对于 `app/src/main/kotlin/com/helix`，测试缩写为对应测试根；会话选模入口位于 `feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation/ComposerModelMenu.kt`。

## 验收证据与开放边界

[账号接入历史验收](../evidence/development/account-integration-through-2026-11-17.md)按原候选保留初版主机测试、双渠道真实账号、域名切换、错误分类、密码找回与通知收尾结果。普通/强制改密及验证码窄屏布局已实现；真实找回发码与独立收件核查有历史证据，实际重置及新密码登录在11-18由Owner手动完成并确认成功，见[最新补验](tasks/HXA-307.md#2026-11-18-账号与云语音补验)。这不是Agent读取验证码或合成proof的自动验收；原未知请求和失败保留。

最近候选的验证范围以[实施状态](status.md#最新验收及未通过项)为入口；文档整理没有重跑主机、设备或线上验收。

## 人机验证与发码

注册、邮箱绑定、找回密码共用 `AccountEmailCodeField`：先人机验证，验证通过后才启用发送验证码，验证回调不自动发送。内存 proof 在五分钟内单次消费；邮箱、用途、账号身份或表单可用性改变及离页都会使未发送 proof 失效。发送失败、取消或过期后需重新验证，不自动重放发码请求。服务端仍可能拒绝过期或无效 proof，展示其错误。

账号服务当前嵌入入口自动启动验证，加载、挑战和失败重试共用一张网页卡片，没有外围中间页。网页负责可视视口尺寸及取消回传；客户端以透明全窗口 WebView 承载，不再注入样式、复制取消按钮或改写页面。网页取消经同源、主框架、当前 URL 和用途校验退出原生窗口，系统返回同样退出；取消后不能接收迟到 proof。客户端只兜底网页无法处理的加载、TLS及渲染器失败，并提供取消；依赖受限消息能力，不支持时提示更新 WebView。验证 WebView 保持网页文字比例100%，保留手势缩放，原生表单仍跟随系统字体。历史检查点见 [HXA-307](tasks/HXA-307.md#服务端草案停止边界)。Owner 已明确确认新版人机验证到发码完整链路人工测试通过；该待验证项关闭，证据属于人工确认，不由合成布局测试替代，也不外推所有入口和异常组合。

## 邮箱身份与服务器用户名（2026-11-19）

依据账号服务 `docs/client-migration-email-identity.md` 及 `cloudflare/src/store.mjs` 的当前契约：邮箱是唯一登录凭据，`display_name` 是可重复的展示名，稳定 `id` 才是账号身份。客户端不再发送或解析旧 `username` 字段。改密后用账号邮箱重新认证，找回提示也使用邮箱。

注册用户名选填，空白不发送该字段，使用服务器默认“用户”；填写时按 NFC、1–80 Unicode 码点及拒绝 Unicode C 类字符校验。个人资料不再保存安装级用户名，头像仍在本机；用户名通过 `PATCH /account/profile` 只携带 `display_name` 更新；按 Owner 已发布的新接口，不再要求、缓存或发送当前密码。服务端执行每24小时一次及同值幂等；客户端不自动重放修改，不因改名重建认证或通知身份。成功响应按原账号 revision 和稳定 id 校验后保存，包含可选秒时间戳 `display_name_updated_at`。退出、切换账号后不能把旧响应写入新账号。

本轮只读服务器文档与源码，未修改服务器；当前验收及真实注册/改名未执行边界见 HXA-307。

## 账号交互收敛（2026-11-19）

Owner 已人工确认上一邮箱身份候选的真实登录、注册和改名成功。这属于人工结果，不冒充自动设备/服务测试；其后 Owner 发布免密码改名服务，客户端已重新核对迁移文档与 store/worker 的当前字段。

注册成功显示一次 Toast 并切换登录表单，保留邮箱、清空密码/确认/验证码和注册用户名，不自动登录；注册失败保留注册模式，切换模式清除上一模式的错误。找回成功同样返回登录并短提示，未知结果及需要重新登录的状态保留明确恢复信息。头像入口与原云端账号入口进入同一个“账号资料”页面，集中显示头像、邮箱、用户名及改密/退出入口，复用原窗口；用户名修改仅用登录态。头像选取后一次导入保存，取消或失败保留旧头像，保存不关闭账号页或丢失用户名草稿。成功改名、头像和改密改用一次性 Toast，不把成功文字常驻页面；错误仍在相应表单显示。验证范围见 HXA-307 的本轮记录。

Owner 随后明确确认新版免密码改名真实线上验证，以及人机验证到发码完整链路均已人工测试通过。两项待验证关闭，不重复发码或改名；未新增自动线上请求、Release或真机验收。
