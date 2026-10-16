# HXA-302：插件、工作导航、终端与图片体验

日期：2026-10-16。范围由 Owner 追加的界面优化与后续“已安装／市场／导入”命名决定；基线为 `193a1f87`，起始工作区干净。允许日常模拟器、真实模型及分阶段本地提交，不推送或发布，不改变上一轮冻结候选。任务与参考来源见 [HXA-302](../../development/tasks/HXA-302.md)。

## 实现与审查

- 插件入口统一中英文命名，三个并列标签分别管理已安装内容、浏览市场和导入外部包。导入结果仍需明确安装，安装不等于当前会话选用或授权。卡片的管理与会话选用直接可见；独立 Skill 导入和创建收起，避免默认铺开工作区路径等配置。插件与工作导航首先提交为 `cc6d1f3e`。
- 工作菜单只保留任务和成果，提供用途及空态说明；存储与诊断进入设置，既有路由和页面能力保留。导航单测及窄屏、大字体夹具验证归属与操作可达。
- Advanced 人工进入终端时查询并连接保留实例；首次且没有保留实例才启动。后台返回、旋转和启动失败不自动重复创建，UNKNOWN 与待结算保留显式处置；帮助、运行环境、刷新和停止归入菜单。仍使用现有 PRoot/ConnectBot、租期、容量和单写者边界，没有启用模型 PTY。参考 Termux 交互，未复制其 GPL 实现。
- 图片缩略图位于输入框内左上，可查看、关闭、缩放、平移和移除；消息历史也有图片入口。平移范围根据图像适配后的实际尺寸计算，避免横图被拖进上下留白。UI 只接收标识与字节，文件和 SAF 读取属于应用服务。草稿预览不建立持久会话、不发送模型；已暂存/历史图按规范化文件与原消息绑定哈希验证，移除或切换会话后不返回旧结果。
- 读取上限沿用 10 MiB，缩略图最长边 192 px，大图 2048 px；解码在后台执行并遵循相机 EXIF 方向，损坏、缺失、篡改或不匹配引用显示不可用，不伪造成功。授权、附件字节物化和工具调度不改。
- 图片元数据缺少宽高时不再显示 `0x0`；保留真实字节大小和绑定哈希。深色预览的系统栏图标在弹窗自己的 Window 内设置为浅色，关闭时恢复该窗口状态，不修改会话 Activity 的系统栏。

## 主要改动入口

| 范围 | 主要文件 |
| --- | --- |
| 插件与工作导航 | [ExtensionsScreen](../../../app/src/main/kotlin/com/helix/app/ui/ExtensionsScreen.kt)、[PluginManagementCard](../../../app/src/main/kotlin/com/helix/app/plugin/PluginManagementCard.kt)、[GroupedNavigation](../../../app/src/main/kotlin/com/helix/app/ui/GroupedNavigation.kt) |
| 终端进入及控制 | [ManualTerminalViewModel](../../../app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalViewModel.kt)、[ManualTerminalScreen](../../../app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalScreen.kt)、[TerminalToolbar](../../../app/src/developer/kotlin/com/helix/app/terminal/TerminalToolbar.kt) |
| 图片读取与事实描述 | [ChatImagePreview 应用服务](../../../app/src/main/kotlin/com/helix/app/chat/ChatImagePreview.kt)、[ChatService](../../../app/src/main/kotlin/com/helix/app/chat/ChatService.kt)、[AttachmentContext](../../../app/src/main/kotlin/com/helix/app/chat/AttachmentContext.kt) |
| 图片界面 | [ChatImagePreview 界面](../../../app/src/main/kotlin/com/helix/app/ui/ChatImagePreview.kt)、[PendingAttachmentStrip](../../../app/src/main/kotlin/com/helix/app/ui/PendingAttachmentStrip.kt) |

## 实际验证

本地原始输出位于忽略的 `build/hxa302/`；临时 runner 位于 `scripts/local/debug/2026-10-16/`。日常设备为 `emulator-5554 / Helix_API_36 / API36 / ARM64`，保留主应用及无关数据。设备、主机与 Provider 结果分别列出。

| 验证 | 当前结果 | 证据 |
| --- | --- | --- |
| 第一阶段编译、静态和导航单测 | 通过 | `stage1-compile-final.log`、`stage1-static-final.log`、`stage1-tests-build.log` |
| 第一阶段 Developer 插件/导航设备 | 19/19 通过 | `stage1-ui-1/` |
| 最终生产源码双渠道主 APK 与 AndroidTest APK | 编译及静态检查通过 | `preview-bars-build.log`；编译不代替运行 |
| Developer UI、Room 图片、终端 | 40 项实际通过；合并批次另有 1 项真实模型失败 | `developer-frozen/`，含实际 PRoot 输入/Python/旋转/重连/输出溢出；该合并批次整体为失败 |
| Consumer 同源界面与图片 | 35/35 通过 | `consumer-frozen/` |
| 最终图片描述/弹窗补丁 | Developer 7/7、Consumer 7/7 通过 | `developer-delivery/`、`consumer-delivery/`；此前 `image-dimension-consumer/` 6/6，Developer 6 项本地检查通过、1 项模型准确性失败 |
| 最终源码完整主机门禁 | 通过，exit 0；275 项 Python；App Consumer 1270、Developer 1391 项通过 | `check-all-delivery.log`；全仓 JVM、lint、R8 构建、依赖及渠道/Runtime 边界通过；通过项之外各渠道有 4 项既有外部输入条件跳过 |
| Qwen 真实图片 | **准确性失败**；发送、出站字节一致性和历史大图链路的断言已通过 | `image-dimension-final/` 最后回复 `402\|blue`；出站图为 `482` 和蓝色圆形，`wire-images/request-30.json` 与预览哈希一致；该方法整体仍是失败 |

完整主机入口为 `./scripts/check-all.sh --all`；两渠道 APK 与 AndroidTest 由对应 `:app:assemble*Debug` / `:app:assemble*DebugAndroidTest` 构建。设备的精确类和参数记录于各运行目录 `command.json`，真实模型只使用授权的 Qwen3.8-27B 和合成数字/色块图片，通过本机临时转发接入原服务，密钥不写入 APK、脚本或证据。

扩大回归后只补充了未知尺寸描述和弹窗系统栏修正，最后两渠道各 7 项覆盖图片界面、Room 绑定、会话隔离和插件/工作截图；前面的 40/35 项不冒充在最后两个补丁后再次整批执行。主机全门禁针对最终生产源码重新完成。

四项既有 JVM 条件跳过为 QwenWork/WorkBuddy 外部原始包、公开配置和公开 MCP 服务夹具；本轮 UI 验收不冒充这些外部输入的重新验收。真实图片设备测试只有在完全未给模型参数时按显式 opt-in 跳过，缺少一部分参数、非法端口或空模型名直接失败；本轮给齐参数后实际执行，不依赖跳过获得通过。

## 失败与修正记录

- 初次静态检查发现界面函数长度/复杂度问题，按职责提取标题、导航映射和组件；终端 ViewModel 的生命周期与操作留在同一串行 owner，记录局部函数数量豁免理由。没有降低测试或运行约束。
- 首次主机门禁发现新 HXA 缺少标准章节，补齐“范围与验收要求／验证入口／交付”后文档门禁通过。
- 第一轮第二阶段设备 16 项中 15 项通过；损坏图片占位被父级点击语义合并，测试未查合并前语义而超时。修正定位后，最终 40 项全部通过。
- 扩大回归运行列表误将 `ui.PluginManagementDeviceTest` 写成 `plugin.PluginManagementDeviceTest`；该轮 36 项实际方法通过、类加载失败 1 项，整体保留失败。最终使用正确类名重新运行。
- 真实图片首轮只完成基础连接验证，未完成独立视觉能力探测，发送前未进入附件确认。补上真实能力探测和视觉能力断言，保留发送门禁；首轮不算真实图片通过。
- 第二轮真实图片已识别数字和颜色、历史字节校验通过，但末步错误要求弹窗与背景同时暴露两个图片语义节点。改为验证大图窗口内的已加载图片，保留原失败批次。编译发现选择器成员函数的无效导入，删除导入后重新通过。
- 预览复核发现平移边界按整个窗口计算，会允许横图向留白区域移动；已改为按实际适配图像计算，并补充横图、竖图和未测量窗口的 JVM 回归。组件长度门禁要求提取大图标题栏，随后重新编译和验证。
- 最终源码的 Developer 合并批次实际 40 项 UI/Room/终端通过、1 项真实模型失败；Qwen 将图片中的 `482` 识别为 `452`，独立复测又识别为 `492`，两次颜色均为蓝色。没有放宽数字断言或把该批次记为全通过。后续诊断把历史字节/预览断言放在模型准确性断言前，让两种结果可独立记录，并比对出站合成图片与预览字节。
- 客户端转发处捕获的出站合成 PNG 与应用预览 SHA-256 一致，图片清楚包含 `482` 和蓝色圆形；这不是上游服务或模型解码后的哈希证明。测试失败截图显示重新打开会话后键盘弹起、列表跟随最新消息，缩略图位于屏幕外；补充收起键盘、实际浏览历史的触摸滚动和可见性断言，避免直接点击屏幕外坐标。
- 同一现场发现附件历史未保存宽高时，描述把未知尺寸写成 `0x0`。现在只在两维均有效时显示尺寸，否则保留真实文件大小和来源哈希；补充未知、缺失单维及无效尺寸回归。没有重写既有历史正文，也不声称这就是模型误识别的原因。
- 最后一次真实模型测试完成发送、历史字节一致、触摸滚动、可见缩略图和大图打开，但 Qwen 回复 `402|blue`，数字准确性断言失败。此前另有 `452`、`492` 误读，不把一次中间正确识别或视觉能力探测通过升级为稳定准确性。未篡改回复、泄露测试答案到提示词或放宽数字检查；不再为获得绿色结果重复调用。服务/模型侧的根因尚未确定。
- 生产 Activity 的大图截图显示状态栏图标对比度不足；改为在 Dialog Window 内设置图标样式。该补丁使正在构建的旧源 R8 批次失效，`check-all-dimension-label.log` 以 143 主动终止，不计完整门禁通过；最终重跑 `check-all-delivery.log`。

## 边界与清理

本轮日常设备为 Debug UI/运行验收，旧 R8 候选的通过不能替代新 UI 的设备结论。其他 API/OEM、实体设备、长稳及大批高分辨率图片的资源峰值未由本轮关闭。独立 Skill 与任意桌面插件包的执行支持保持原范围；安装不额外授予权限。真实 Qwen 数字识别是明确未通过项：客户端出站图片正确的证据不能代替上游转发/模型侧准确性验收，也不能据此宣称真实模型总体通过。

已移除本轮安装的两个测试 APK，确认仅保留 Standard/Advanced 主应用；测试会话、Provider、合成附件按夹具所有权清理，截图拉回本地后移除设备上的本轮截图目录。临时模型转发进程已结束，ADB 映射列表为空，凭据没有落盘。清理输出见 `cleanup-*-test.log`。

本地实现提交为 `cc6d1f3e`（插件/工作导航）、`6428a7a8`（终端入口）和 `654bcf84`（图片、相关界面及回归）；最终生产源码对应 `654bcf84`，后续收尾仅更新记录。未推送、发布或改变原冻结候选。Qwen 识别准确性仍为未通过项，后续若继续需要服务转发/推理侧证据或另一已授权视觉模型的对照，不能仅凭客户端日志确定根因。

## 图片误读追加诊断

Owner 要求优化上述误读，并进一步询问数字尺寸是否过小。本次起点 `dd068c7b`，工作区干净；沿用已授权接口、合成图片与日常 API36 Developer Debug。Owner 确认后端为 SGLang、Qwen3.8 27B；最初描述为 FP16，后续检查权重后纠正为 BF16，详见下方配置补充。以下是新增实测，前面的失败批次均保留，不把一次成功改写成稳定准确性或整个 Mobile Use 的失败/通过。

### 事实与对照

原图 640×360，数字字体 100 px，黑色字形约 73 px 高；客户端没有将其压成缩略图再发送。最简请求保留同一 PNG 和用户问题，绕过 Helix 的提示组装/编码/预览，直接调用同一 `/v1/chat/completions`。输出预算 4096，非流式，无温度或 seed 覆盖；每个变体只改变表中所述字段，基线在首尾各执行一次。

| 直接接口条件 | 真实回复中的数字/颜色 | 图片 token（服务报告） |
| --- | --- | --- |
| 基线，第 1 次 | `482 / BLUE` | 220 |
| `image_url.detail=high` | `482 / BLUE` | 220 |
| 图片 part 放到文字之前 | `402 / Blue` | 220 |
| `reasoning_effort=medium` | `402 / Blue` | 220 |
| `chat_template_kwargs.enable_thinking=false` | `492 / BLUE`，无 reasoning 输出 | 220 |
| 基线，第 2 次 | `482 / blue` | 220 |

此组用于排查参数，不是准确率评测；不能从单次变体结果判断参数好坏。`detail=high` 没有增加服务报告的图片 token，不假定 OpenAI 的 detail 语义在该 SGLang 部署中具有同样效果。最初诊断客户端使用默认 User-Agent 收到一次 HTTP 403；改为既有 Helix 验证客户端标识后六个请求均 HTTP 200。403 不计 OCR 失败，也没有绕过认证或验证码。

随后用未经修改的生产源码与原 AndroidTest 运行日常 API36 Developer Debug 的完整图片旅程：**1/1 通过**，回复 `482|Blue`，服务报告 220 图片 token。捕获本次完整合成请求和 SSE 返回，客户端出站图片仍与原图一致。实际请求为流式、16384 输出预算、一个合并 SYSTEM 和一个含图片 USER；本地 `ReasoningEffort.OFF` 按现行契约省略 effort，并不表示关闭服务默认思考。没有工具执行、真实用户图片或新的账号消费范围。

为检查尺寸，在本次捕获的应用请求上做第二组受控对照：两组都改为非流式，并移除生成的附件字节/哈希描述以免放大后引用旧元数据；保留相同系统提示、用户问题和其他参数，交错执行原图与宽高各放大两倍的 PNG 各 3 次。放大用本机 `sips -z 720 1280`，不重绘数字、补文字答案或改变题目。

| 尺寸 | 三次数字结果 | 正确次数 | 图片 token（服务报告） |
| --- | --- | --- | --- |
| 640×360 原图 | 482、482、452 | 2/3 | 220 |
| 1280×720 放大 | 482、482、482 | 3/3 | 880 |

这支持“视觉分辨率可能影响当前样本”的假设，但样本太少，采样、缓存和后端实现仍可能影响结果；不证明根因为字太小，也不支持所有图片自动放大。整图放大会令该样本视觉 token 变为 4 倍，尚未证明其他图片、模型或 Mobile Use 的收益。

### 本地改进与取舍

- `LiveImagePreviewDeviceTest` 方法改名以明确检查边界，收到模型回复后立即输出独立 `LIVE_IMAGE_MODEL_ACCURACY=PASS/FAIL`，后续预览失败不再遮住已取得的识别结果；整个方法仍要求准确性与 UI 两部分都通过。
- 增加显式 `imageScale=1|2`，默认保留原始 640×360 图片；2 倍模式只用于诊断，以 Android 位图过滤缩放同一原图。该缩放实现与主机 `sips` 对照区分记录，不假定两者字节相同。新增规范化后宽高校验及尺寸日志，避免误把客户端降采样当成模型质量。
- 部分模型参数或非法 scale 必须失败；只有完全没有配置时沿用显式 opt-in 跳过。没有修改期待数字、放宽断言、添加自动模型重试或把未知答案升级为事实。
- 修正本记录“服务收到”的过强措辞为客户端出站观察。生产编码器、图片归一化、权限及 Mobile Use 插件均未改变；没有默认启用放大、OCR 替答、关闭思考或服务专属参数。

参考 [Qwen 官方模型说明](https://huggingface.co/Qwen/Qwen3.8-27B) 的默认思考及显式关闭参数、[SGLang 模型部署说明](https://docs.sglang.io/cookbook/autoregressive/Qwen/Qwen3.8-27B) 的视觉路径，以及 [OpenAI 图片 detail 说明](https://developers.openai.com/api/docs/guides/images-vision#choose-an-image-detail-level)。采用单变量验证和明确协议差异，未复制外部实现。官方 SGLang 配方列出的原始 checkpoint 为 BF16，与 Owner 后续权重核验一致；不再将 FP16 权重或低比特权重量化列为本次误读线索，运行时、KV/SSM 与视觉计算精度仍不能仅凭权重类型外推。

原始证据位于 `build/hxa302/vision-followup/`（请求、返回、逐条件结果、尺寸对照）与 `vision-followup-device/`（实际设备命令、APK 哈希和 JUnit）。只保存合成测试内容，密钥仅驻留临时客户端/转发进程内。服务内视觉解码/权重加载、具体 SGLang 版本与启动配置仍未核验；后续应在相同固定输入下结合这些服务侧事实定位，不能靠持续重复当前测试凑通过。

### 追加测试与清理

双渠道 AndroidTest 编译、spotless 与 detekt 通过，见 `vision-followup/test-build-final.log`。首轮 detekt 发现测试方法复杂度 17 超过阈值 15，将 profile 读取/校验提取后重新通过，未增加豁免。

最终测试源码在日常 API36 Developer Debug 各运行一次：默认原图回复 `452|Blue`，识别 FAIL、预览 PASS，整个方法按原断言失败；显式 `imageScale=2` 回复 `482|BLUE`，识别及预览均 PASS，方法 1/1 通过。规范化尺寸分别为 640×360 和 1280×720；此轮不能记为全部通过，Consumer 只编译没有新增设备运行。见 `vision-scale-native/`、`vision-scale-double/` 与对应 `image-diagnostics.log`。新增尺寸入口只作用于测试夹具，不是面向用户的新图片设置。

已拉取本轮两张合成图片旅程截图，删除其设备端 `hxa302-ui` 目录及本轮 Developer 测试 APK，保留主应用和无关数据。临时转发主动结束，18766 映射移除并确认 reverse 列表为空，未更改 SGLang 部署；没有真机、R8 设备、其他模型或 Mobile Use 业务重跑。

最终 `./scripts/check-all.sh --all` exit 0：Python 主机测试、文档/ADR/i18n/密钥检查、全仓 Gradle 单测与静态检查、双渠道 Debug/Release 构建及依赖/制品/Runtime 边界均通过，见 `vision-followup/check-all.log`。未改的 Gradle 任务允许使用现有 up-to-date 结果；该主机通过不覆盖上述真实 OCR 失败。

### Owner 提供的 SGLang 启动配置

后续 Owner 提供实际启动命令：TP=2、上下文 262144、静态显存比例 0.90、最大并发 4，使用 `qwen3` reasoning parser 与 `qwen3_coder` tool parser；启用 EAGLE、steps=3、topk=1、draft tokens=4，draft 路径与主模型相同。命令没有显式 dtype、KV/SSM 精度或图片尺寸限制。Owner 随后检查 checkpoint 的 safetensors 张量为 **BF16**，并报告服务 `dtype=auto`、`quantization=null`；这是 Owner 提供的服务侧核验，不是本机远程读取结果。启动环境目录名不改变实际运行模块 `sglang.launch_server`；上述信息不等于已核验所有算子和缓存的实际精度。

[SGLang 官方 Qwen 配方](https://docs.sglang.io/cookbook/autoregressive/Qwen/Qwen3.8-27B) 明确支持内置 MTP，并列出 EAGLE 3/1/4；因此启用 EAGLE 或同一 checkpoint 作为 draft 本身不构成缺陷证据。下一项有信息量的对照是仅移除五项 `--speculative-*`，复用同一原图请求；这是排除变量，不是宣称推测解码必然损失准确性。`--enforce-disable-flashinfer-allreduce-fusion` 与 backend 选择并列时不据此认定融合正在使用，保持参数不动以免同时引入第二变量。

当时准备了本地 `build/hxa302/vision-followup/sglang-without-mtp.md`，包含保持其余参数的完整候选命令、只读版本查询和固定输入路径；没有在服务机器执行、重启或升级。SGLang/Transformers/Torch 版本、GPU、各计算/缓存精度、权重与 processor revision 未核验；本机只有已授权推理接口，没有该服务进程的执行连接。该服务侧对照当时 pending，后按下方 Owner 确认收口，不再作为本次待办，也没有改记执行通过。

### Owner 确认模型能力限制并收口

2026-10-16，Owner 明确告知已确认由当前线上模型视觉理解能力不足导致。本次按该确认收口：不再将 SGLang 版本、服务器连接和关闭 MTP 对照列为 Helix 本轮待办，不因该合成图误读要求客户端默认放大或修改协议。原因归属来源为 Owner 确认；本地独立证据仍是相同图在直接接口也会误读、预览/历史/客户端出站字节一致，以及放大后的有界小样本结果，不外推其他部署、模型或全部视觉任务。

原始失败样本、响应与双尺寸测试保留，模型准确性失败不改为通过；生产默认图片处理保持，后续替换模型或评估视觉能力可复用夹具。此次收口只更新文档，没有新增设备/模型调用、修改服务器、提交、推送或发布，也不覆盖既有主机、设备与发布验收边界。
