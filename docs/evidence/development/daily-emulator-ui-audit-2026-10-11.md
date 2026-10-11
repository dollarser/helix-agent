# 日常模拟器界面与配置调试

Owner本轮明确要求在日常模拟器调试、修复并优化界面与配置。范围为HXA-271展示交互的追加检查，不改变执行模式、权限或预算契约。

## 环境与保护

日常Helix_API_36，API36 arm64，Developer/debug。直接使用Owner指定的日常实例做页面检查，不重启AVD、不加载/保存快照，不将其称为自有runner实例。检查时持久Turn仅有COMPLETED记录，没有在途任务；安装前备份当前APK与数据库到ignored build目录。新旧APK内的Room identity与实际数据库一致，覆盖更新使用install -r，未清数据或卸载。工作树中的其他六个已修改路径及未跟踪扩展保持原状，构建包含这些在途内容，不声称为纯clean HEAD候选。

截图、UI层级、数据库副本与日志只保存在ignored `build/daily-ui-20261011/`，不提交用户会话、服务地址或凭据。临时只读检查辅助程序在ignored dated debug路径先保存后执行，不新增支持入口。

## 实际发现与修复

- 日常安装包未包含阶段4的过程展示；覆盖更新后真实既有会话出现灰色过程、按调用位置的默认收起工具行，最终正文完整保留。
- Agent默认值中，清空输出值→保存→输入合法值，错误仍显示。旧文案还要求输出不得超过累计总上限，与当前DSH预算契约不符。编辑时清除旧错误，字段使用错误状态与范围支持文字，仍仅显式保存写入；空值、零、超界、数字溢出和配置owner拒绝均不能显示成功。
- 保存按钮与次要动作层级接近，范围混在长标签中，字段默认键盘没有表达数字输入。改为全宽主按钮、短标签、数字键盘和紧邻字段的范围/错误反馈；未保存输入使用saveable展示状态，不自动写入。
- 默认设置中重复强调累计用量与Goal轮数。精简成必要说明，Goal当前值继续独立展示；不改变输出默认值、累计统计或续跑规则。
- Mobile Use不同工具统一显示“操作或查看界面”，无法分辨步骤。按真实工具类型给出具体动作；普通摘要继续不暴露输入正文、命令或不透明节点token，完整参数仍在详情中。未知工具保持原兜底，原模型intent保留。
- 输入框/会话设置展示原始模式枚举。改为本地化名称，不改变slash指令或持久模式。
- 通用设置披露标题有时不足48dp触摸高度，补足最小高度，继续整行标题展开、各区独立，仅改变展示。

实际查看会话、通用设置、Agent默认值、扩展、Mobile Use配置和模型列表；没有进行外部发送、连接测试、账号或模型调用。未对所有页面、全部字体/设备/OEM、后台执行或授权生命周期宣称全面验收。

## 参考与取舍

沿用HXA-271已核对的[DSH过程展示](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/packages/client/ui-chat/src/client/chat/TurnProcessNodeView.tsx)：披露只改变展示，执行事实与重要交互独立可见。Codex桌面闭源，只借鉴公开的过程/最终答复分层，不推断实现。Android配置字段另参考[Material 3字段指南](https://m3.material.io/components/text-fields/guidelines)的支持/错误文本位置与[按钮层级](https://m3.material.io/components/buttons/overview)。独立实现，保留本机权限与持久状态路径，不复制竞品代码或添加依赖。

## 验证

主机passed：`spotlessCheck detekt test`、双渠道lint/APK、Developer AndroidTest APK编译（包含当前spikes）；`check-all.sh --source`通过，脚本206项、docs/ADR/i18n/secret检查通过。后续仅测试改动重新执行spotless/detekt、双渠道lint与测试APK编译通过。原日志分别为host-final、host-r3、source-final，均exit 0；source日志中模拟FAIL_FUNCTIONAL来自门禁解析负向测试，不是本次设备结果。

日常API36 Developer设备passed：只运行以下五个合成Compose类，无resetDeterministicUiState、数据库夹具或真实模型配置读取：

| 类 | 实际结果 | 范围 |
| --- | --- | --- |
| MessagePresentationDeviceTest | 4/4 | 长过程披露、运行/最终正文、搜索、零消息动作副作用 |
| SettingsInteractionDeviceTest | 5/5 | 错误清除/显式保存、边界/溢出、owner拒绝、未保存输入恢复、独立披露/48dp |
| ToolTimelineLayoutDeviceTest | 9/9 | 工具标题、窄屏/大字、失败/拒绝可见、披露零副作用 |
| ToolTimelineScrollStateDeviceTest | 1/1 | 滚动后披露状态 |
| ApprovalLayoutDeviceTest | 3/3 | 待审批可见、中文长内容/窄屏操作可达 |

最终device-final日志 `OK (22 tests)`，无skip。使用既有HelixAndroidJUnitRunner，语言pin与日常简体中文配置相同；这次直接在Owner指定实例运行，不能标记为run-owned-emulator的进程归属验收。

保留失败：device-r1中3个配置测试的支持文字可见性断言失败。实际页面截图显示错误文字完整可见；TextField合并语义节点不适用于其支持文字，改为unmerged tree继续断言assertIsDisplayed，原功能/可见性断言未削弱，device-r2配置4/4通过。补加恢复/触摸高度测试后，host-r2因DpRect不存在height成员编译失败，改为bottom-top并完成host-r3和最终22项回归；不将重试前结果称为通过。

实际页面复验passed：空值保存失败、合法编辑立即清除旧错误、原输出值显式保存有反馈；EditorInfo确认数字输入类型0x2，不以此单独声称键盘候选/拼音验收。工具整行展开/收起，具体动作标签和中文模式可见；上下文弹窗仅上限/占用两行。Home→恢复Helix共3次页面可见，未出现黑屏；这是有界模拟器检查，不关闭历史真机/OEM ANR或长稳边界。安装APK拉回hash与最终构建一致。

全部测试和页面操作后，逐行比较sessions/messages/turns/tool_calls与安装前备份一致，Room identity未变；原草稿仍在，未创建或发送任务。日常模拟器保持运行并返回会话页，不删除测试APK或触碰其他设备。真实模型本轮not requested，未消耗用量；系统权限/浮窗执行/第三方账号与完整页面矩阵未验收。
