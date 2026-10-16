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
- 出站诊断证明服务收到的合成 PNG 与应用预览 SHA-256 一致，图片清楚包含 `482` 和蓝色圆形。测试失败截图显示重新打开会话后键盘弹起、列表跟随最新消息，缩略图位于屏幕外；补充收起键盘、实际浏览历史的触摸滚动和可见性断言，避免直接点击屏幕外坐标。
- 同一现场发现附件历史未保存宽高时，描述把未知尺寸写成 `0x0`。现在只在两维均有效时显示尺寸，否则保留真实文件大小和来源哈希；补充未知、缺失单维及无效尺寸回归。没有重写既有历史正文，也不声称这就是模型误识别的原因。
- 最后一次真实模型测试完成发送、历史字节一致、触摸滚动、可见缩略图和大图打开，但 Qwen 回复 `402|blue`，数字准确性断言失败。此前另有 `452`、`492` 误读，不把一次中间正确识别或视觉能力探测通过升级为稳定准确性。未篡改回复、泄露测试答案到提示词或放宽数字检查；不再为获得绿色结果重复调用。服务/模型侧的根因尚未确定。
- 生产 Activity 的大图截图显示状态栏图标对比度不足；改为在 Dialog Window 内设置图标样式。该补丁使正在构建的旧源 R8 批次失效，`check-all-dimension-label.log` 以 143 主动终止，不计完整门禁通过；最终重跑 `check-all-delivery.log`。

## 边界与清理

本轮日常设备为 Debug UI/运行验收，旧 R8 候选的通过不能替代新 UI 的设备结论。其他 API/OEM、实体设备、长稳及大批高分辨率图片的资源峰值未由本轮关闭。独立 Skill 与任意桌面插件包的执行支持保持原范围；安装不额外授予权限。真实 Qwen 数字识别是明确未通过项：客户端出站图片正确的证据不能代替上游转发/模型侧准确性验收，也不能据此宣称真实模型总体通过。

已移除本轮安装的两个测试 APK，确认仅保留 Standard/Advanced 主应用；测试会话、Provider、合成附件按夹具所有权清理，截图拉回本地后移除设备上的本轮截图目录。临时模型转发进程已结束，ADB 映射列表为空，凭据没有落盘。清理输出见 `cleanup-*-test.log`。

本地实现提交为 `cc6d1f3e`（插件/工作导航）、`6428a7a8`（终端入口）和 `654bcf84`（图片、相关界面及回归）；最终生产源码对应 `654bcf84`，后续收尾仅更新记录。未推送、发布或改变原冻结候选。Qwen 识别准确性仍为未通过项，后续若继续需要服务转发/推理侧证据或另一已授权视觉模型的对照，不能仅凭客户端日志确定根因。
