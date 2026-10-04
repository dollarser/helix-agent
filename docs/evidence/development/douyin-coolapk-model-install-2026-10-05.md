# Helix 自主操作安装抖音与酷安

Status: installations and final host / bounded device checks passed
Date: 2026-10-05
Related HXA: HXA-244
Affected modules: app, tools/automation

## 授权与设备

所有者明确要求安装最新版到模拟器，使用 Helix 安装抖音，遇到问题修复并继续，成功后再让 Helix 安装酷安。这次授权包含真实模型持续测试。使用 emulator-5554，API36 ARM64，Developer，当前配置 Qwen3.8-27B；没有使用真实设备或登录应用账号。

开始时两个目标包均不存在。保留已有微信、官方下载 APK 和其他用户文件。主机只部署 Helix/测试 APK、读取日志/包状态并维护测试所需无障碍绑定；没有通过 ADB/pm 安装抖音或酷安，没有人工点击其安装按钮。中间一次调试打开 Helix 前台影响了进行中的观察，记录为调试干预，不冒称所有尝试均无干预。

## 结果与过程边界

| 应用 | 系统包事实 | 路径 |
| --- | --- | --- |
| 抖音 | com.ss.android.ugc.aweme，40.7.0，versionCode 400700 | Helix 复用此前官方下载 APK，开启 Download Manager 来源许可，截图定位并点击 Install，看到 App installed，点击 Done，ui.apps 查到包 |
| 酷安 | com.coolapk.market，16.6.4，versionCode 2609291 | 新会话从官网开始，浏览器下载 APK，通过系统安装确认，截图显示安装成功，随后进入系统应用列表核查 |

设备时钟的 firstInstallTime 分别为 2026-10-04 11:15:38、11:21:19；本记录采用客户端日期 2026-10-05，不混用两种时间。最终包查询提供独立安装事实。

抖音不是一次请求直接完成：早期尝试因缺少默认启动工具而绕行，后续在 PRoot 查路径和最终回复过早结束。追加同一会话消息，明确已核实官方 APK 可复用，要求继续；测试驱动最多追加四轮明确的继续消息，不伪造工具返回、不代替 UI 操作，也不改变生产 Harness 的终止语义。成功 fixture 中第一轮完成实际安装操作、第二轮继续等待并核查，140.17 秒，1 项通过。此前失败尝试保留。

酷安新会话第一轮完成下载与实际安装，240.13 秒，1 项通过。模型最后仍以进入应用列表的下一步计划结束；测试按真实包存在判定安装完成，不将模型的叙述作为包事实。原始任务记录保存在 Helix 会话；私人转录、数据库与截图仅位于忽略的 build 目录。

追加一次仅只读的结果核查消息后，Helix 调用 ui.apps 查到 CoolApk / com.coolapk.market，完成 todo 并明确回复安装完成；`coolapk-final-verification.log` 1 项通过，9.904 秒。其记录的官网为 www.coolapk.com，下载包 CoolApk-16.6.4-2609291-coolapk-arm64-sign.apk，页面链接来源 dl-t2.coolapkmarket.com，约 117 MB。没有重新安装或启动目标应用。

收尾撤销测试会话授权、恢复原 Profile/会话，删除临时无障碍服务设置并恢复 accessibility_enabled=0；Download Manager 来源安装权限恢复原 deny，Chrome 原 allow 保持不变。两个已安装应用和官方下载文件保留。没有登录、接受应用内协议或操作微信/抖音数据。

## 本轮修复

- 活跃 Mobile Use 默认提供 ui.launch；用它替换默认集合中已有替代方式的 ui.ime_enter，保持工具预算和可搜索能力，不增加权限。
- 显式 Root/Shizuku 精确点击缺少字段时，在副作用前返回明确参数错误；不再只报 Required value was null。
- Linux 工具说明明确 PRoot 不是 Android ADB/root shell，路径取决于运行时挂载，Android 应用/存储界面应使用 Mobile Use。
- 酷安动态网页暴露全树版本对节点动作过于严格的问题。宿主保存并传递目标节点指纹；远端核对目标语义、位置及窗口，再执行现有 fresh-root 校验。无关页面变化可接受，目标改变仍拒绝。截图的目标变化改为明确 TARGET_CHANGED；坐标手势校验不放宽。

最后一项修复发生在两项安装已经完成后。最终 Helix APK 覆盖安装成功；关闭普通无障碍后，正式 Dispatcher 的 Shizuku 观察、查找及节点点击通过，目标为已观察到的系统设置 Network & internet，未修改系统设置值。该项验证证明新节点协议能够执行，不冒称此前安装使用了这项最终增量，也不冒称已对动态网页做新一轮真实模型回归。

设备 fixture 的前两次选择标签与系统设置实际页不一致，另一次首次连接尚未就绪；保留失败日志。改为先等待有界的只读观察就绪，且每次调用使用独立身份；最终 `final-semantic-ready-device.log` 1 项通过。无关树版本变化可接受、目标文字/状态/位置/窗口变化拒绝、缺失指纹拒绝由主机回归覆盖。

最终主机 `final-host-3.log` 通过：automation 145 项、mobile-use 10 项、app 单测、双渠道应用与设备测试 APK 编译、双渠道 Lint、Detekt；设备 fixture 只读就绪补充的编译和 Detekt 另见 `final-fixture-readiness.log`。Python Mobile Use 合同 10 项与双渠道 APK 合同检查通过。设备执行范围为 API36 Shizuku，没有本轮 Root/OEM/真机验收。

## 验证文件

忽略目录 `build/install-regression-2026-10-05/`：`douyin-1.log`、`douyin-2.log`、`douyin-continue.log` 保留失败；`douyin-continue-2.log` 与 `coolapk-1.log` 为成功安装 fixture。当前工作树含大量先前未提交工作，本记录仅覆盖上述增量，不代表提交、推送或发布。
