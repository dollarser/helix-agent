# 设置导航与交互恢复

日期：2026-09-29。所有者在手机试用后报告返回跳过设置层级，并要求一并审查其他交互。改动基于 `4a1443df` 的 `codex/provider-settings`，与 Provider 表单工作共用独立工作树；主目录并行工作未覆盖。

## 改动与审查范围

- 顶栏返回交给统一返回分发器，遵循文件子页等已有页面内部返回逻辑。Provider 分类注册内部返回处理，回到三个来源入口后才离开模型页。
- 导航抽屉切换目的页不再清空来源页历史；抽屉打开时返回先关闭抽屉。明确返回会话根页的操作保留原有语义。
- 页面进入与返回使用方向相反的小幅位移和淡入淡出；RTL 使用反向位移。没有改动页面信息架构。
- 上下文设置读取失败禁止把默认值误保存，提供重试；检测失败但读取成功时允许编辑。重试检测保留未保存输入。保存失败留在对话框并提示重试，不显示原始异常；保存期间防止重复提交和切换目标模型。
- 检查了 Provider 表单、上下文设置、本地模型下载、文件返回、系统权限子页及侧栏/弹窗交互。已有文件选择/搜索/目录返回、下载取消与防重复提交机制保留。此范围不是全产品可用性验收，删除和权限确认没有移除。

## 主机验证

最终侧栏修复后的双渠道 app unit、debug lint、debug APK、AndroidTest APK、spotlessCheck、detekt 通过，876 个 Gradle tasks，日志 `build/interaction-accepted-gates.log`。此前 source gate 通过（619 Markdown、35 ADR、1815 三语言资源键），最终记录复核日志为 `build/interaction-accepted-source.log`；`git diff --check` 通过。首次新增编辑器封装的命名与长行检查失败，修复后重跑；不把失败轮计作通过。

新增 `HierarchicalNavigationDeviceTest` 四项返回/弹窗用例，以及 `ProviderContextRecoveryDeviceTest` 三项内存故障注入用例。后者不读写真实 Provider 或调用服务，只验证实际 Compose 编辑器的读取、检测、保存失败及重复提交控制。

## 设备验证

所有者延续当前手机安装测试授权，设备 PLC110 / Android 15 API 35 / developer debug。仅覆盖安装，不卸载、不清空应用数据，不调用真实模型或订阅服务。标准测试 runner 的临时语言选择已在结束后还原。

最终 **8/8 passed**：`HierarchicalNavigationDeviceTest` 4/4、`ProviderContextRecoveryDeviceTest` 3/3、`ProviderSettingsFormDeviceTest` 1/1。命令为指定这三个类的 `am instrument -w -r -e class … com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`；运行记录 `build/interaction-phone-r3.log`，原始结果 `build/interaction-phone/instrumentation.txt`。应用覆盖安装及重新冷启动成功，未请求模型、账号或网络能力探测。

前两轮均 7/8，侧栏返回失败。首轮还遇到 OEM 的测试组件启动确认，放行后第二轮仍复现；因此没有把产品缺陷归咎于设备弹窗。根因是 NavHost 内页比 shell 晚注册返回处理器，常驻外层处理器即使启用仍不能抢占。改为侧栏打开时注册，并覆盖 current/target 打开状态，原失败用例不变、第三轮 8/8。

真机范围仅为上述 developer UI 用例；consumer 设备、其他 OEM、窄屏/大字体矩阵、真实订阅账号仍未验。页面转场主观体验由所有者继续试用确认。

改动未提交、未合并 main、未推送。原有 Provider 表单验证见 [Provider 设置证据](provider-settings-2026-09-29.md)。
