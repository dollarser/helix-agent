# 重构余项本地补验：2026-10-10

范围：[HXA-251](../../development/tasks/HXA-251.md)。本轮所有者明确授权 API29/API36 模拟器及现有 Qwen；没有真机、发布或其他账号验收。

日期使用所有者本地日历 2026-10-10；对应设备记录部分命令的 UTC 时间为 2026-10-09。命令原始日志保留各自时区，不以文档日期改写实际执行时间。

## 主机与设备

`build/refactor-closeout-20261010-host.log`：全仓 test/detekt/spotlessCheck、双渠道 debug lint、APK/AndroidTest 编译通过，990 tasks。CLI wire APK另由 `build/refactor-closeout-20261010-cli-build.log` 编译。新增真实 Room 取消更新回归后的编译/静态检查见 `build/refactor-closeout-20261010-plugin-build.log`。

最终源码的同一完整门禁再次通过：build/refactor-closeout-20261010-acceptance-host.log，57s，990 tasks；包含新增IME/插件/Memory夹具的detekt、spotless和双渠道编译。最终默认Provider五项各5/5（api29-provider-final、api36-provider-final），保留原key可见断言及新增save可见断言；不是只有窄屏路径通过。

最终文档/源码/安全门禁 `scripts/check-all.sh --source` 通过，日志 build/refactor-closeout-20261010-acceptance-source.log；git diff --check通过。仅本轮自有路径提交，不推送；其他历史未跟踪调试脚本保留。

隔离 owned AVD 的制品 SHA、API/ABI/fingerprint、启动/关闭身份分别在各目录 artifacts.json、device-properties.json、owner.json、closed.json。没有占用日常模拟器运行这些破坏自身 fixture 的恢复测试。

| 范围 | API29 | API36 | 日志目录（build/refactor-closeout-20261010/） |
| --- | --- | --- | --- |
| Developer 插件/回放/证据清理/输入/Provider/运行设置/Job | 26/26 | 26/26 | api29、api36 |
| Consumer 默认尺寸当前Provider输入/保存可见性 | 5/5 | 5/5 | api29-provider-final、api36-provider-final |
| Consumer Memory 真正进程死亡 + 项目隔离/提示信任 | 3/3 | 3/3 | api29-consumer-recovery-r2、api36-consumer-recovery-r2 |
| Consumer 320dp、font_scale=1.3 输入/表单，含当前保存可见性断言 | 9/9 | 9/9 | api29-pinyin-final、api36-pinyin-final/narrow-font.txt |
| CLI wire 回放生命周期 | 4/4 | 4/4 | api29-insets-pinyin、api36-insets-pinyin/cli-replay.txt |
| 插件生命周期含取消更新与正常重试 | 6/6 Developer | 6/6 Consumer | plugin-cancel-api29、api36-form-capture |
| Developer 原生插件修复保持会话选择、权限与全局范围 | 1/1 | 1/1（同轮通用插件6项亦通过） | api29-repair-current、api36-current-final/instrumentation.txt |
| Consumer 实际拼音按键、候选与中文草稿零发送 | 1/1 Google Pinyin | 1/1 Gboard | api29-pinyin-final、api36-pinyin-final/pinyin.txt及pinyin-preedit.png |

原生 Mobile Use 只在 Developer 存在，首次 Consumer 夹具误用已移至正确渠道。另两次夹具分别缺少显式自动化配置、误将新会话已有 APPROVAL_REQUIRED 配置期望为 null；改为只授权自己的测试包、保存/恢复原配置并比较修复前后的权限快照后通过，不启用无障碍/Root、不新增授权。

首次 Consumer 参数误用了不存在的 com.helix.agent.consumer.test；实际制品 applicationId=com.helix.agent.test，修正后重新运行，首次启动失败不能记作产品失败或设备通过。API36 大字体稳定失败发生于可编辑模型菜单与高级选项切换后 `provider-form-key` 可见性。几何在viewport内、截图却显示Dialog顶部越过状态栏且键盘仍在：只结束焦点未修复；手工根据IME高度改变body造成API36布局持续等待（300s超时），该方案撤回。最终使用Dialog自身decorFitsSystemWindows=false、safeDrawingPadding/imePadding及高级选项结束编辑，原断言在双API默认5项及窄屏9项通过；后续增加保存按钮屏内可见性断言，不削弱原检查。参考[Android官方Insets](https://developer.android.com/develop/ui/compose/system/insets-ui)，未引入依赖或新框架。失败截图与超时分别在api36-form-capture/form-failure.png、api36-insets-pinyin/follow-up.txt；最终布局方案不采用手工高度常量。

设备均开启硬件键盘及软键盘。最终双API实际节点按键nihao、候选“你好”、中文进入probe及真实ConversationComposer草稿、零发送断言各1/1；人工核验两份当前pinyin-preedit.png显示“ni hao”及中文候选。IME内部预编辑、候选事实、应用提交效果分别核对，不将UTF-8注入算拼音通过。API36的Latin包名实际包含中文拼音；API29初次只有英文布局，完整ime list -a发现已有Google Pinyin，在自有只读克隆启用/选择，并按观察到的“Pinyin keyboard”节点完成首次QWERTY选择，不修改日常输入法或安装新输入法。

保存可见性新增断言的当前双API Consumer窄屏九项再次通过。API29默认尺寸一次在IME/Insets动画中读到key的0×0临时几何，保留失败日志；当前夹具增加有界等待实际显示后仍执行原key和新增保存可见断言。中文夹具失败分别是无候选的英文IME、首次布局选择和错误地要求每个拼音字母先出现在应用TextFieldValue.composition或无障碍节点text；截图证明预编辑在IME自有窗口。最终使用实际候选与提交断言，截图人工核查预编辑，不跳过中文效果。辅助脚本一次误选原AVD上旧Developer runner导致class-not-found，改为显式目标包后重跑；该失败不记产品缺陷或通过。

## 六轮真实模型发现提示对照

日常 API36 Developer 上先运行既有只读 inventory，Room identity=c34065205d0987bb3b495a2f4a663c43，在途 Turn=0。使用既有 Qwen3.8-27B、OPENAI_CHAT_COMPLETIONS、正常 Provider/Keystore，不导出数据库或配置；未更新日常主 APK。日志 build/refactor-closeout-20261010-inventory.txt。

同一 `LiveJobAwaitDeviceTest#realModelWaitsAndCollectsOneOriginalExecution`：预算/模型/任务/编码器不变，只增加明确的组合检索及窗口替换规则提示；顺序 baseline、candidate、candidate、baseline、baseline、candidate。各轮创建新 synthetic 会话，marker内容随机但规则相同；断言原 start 一次、原句柄 await/collect、文件内容及结算完成，清理沿正常取消/收取入口。原始日志及耗时位于 build/refactor-closeout-20261010/discovery/。

| 轮次 | 组 | 严格成功 | 墙钟秒（含 instrumentation） | 已输出输入/输出 token |
| --- | --- | --- | --- | --- |
| 1 | baseline | 否：start 请求数 2，首个 UNKNOWN_TOOL 未执行 | 31.68 | 138,452 / 2,841 |
| 2 | candidate | 是 | 13.29 | 48,405 / 864 |
| 3 | candidate | 是 | 14.23 | 48,460 / 826 |
| 4 | baseline | 是 | 16.10 | 46,661 / 979 |
| 5 | baseline | 否：TOOL_LOOP_NO_PROGRESS | 53.60 | 126,686 / 4,741 |
| 6 | candidate | 是 | 25.03 | 48,410 / 729 |

baseline 严格验收1/3、candidate 3/3。成功 candidate 均5 model/5 tool；成功 baseline也是5/5。失败轮原夹具在最终统计前抛错，后由只读 `ValidationInventoryDeviceTest#reportSyntheticDiscoveryComparison` 按本轮开始时间/固定合成会话标题核查恰好六个Turn，补齐durable usage与派发审计：第一轮16 model/22 tool，第五轮15 model/17 tool；两个start请求分别为UNKNOWN_TOOL和SUCCESS，每轮实际成功start仅一次，不能写成脚本重复执行。第一轮Turn为COMPLETED但未到产物断言，第五轮为FAILED；严格fixture结果与Turn状态分别保留。日志build/refactor-closeout-20261010-comparison-audit.txt 1/1。两组总输入分别311,799和145,275，总输出8,561和2,419，只证明这个小样本；成功baseline单轮输入仍低于candidate。

两台其他模拟器并行运行，墙钟受宿主负载影响；没有测量context build time或资源峰值，不声称生产R2-B schema/摘要策略收益已验收。候选提示只在夹具中，组合查询与具体任务名混在同一提示，不能从六轮小样本直接升级为通用策略或改变替换窗口ADR。保留结构和现行权限/窗口，不降低原严格断言。日常主APK实际sha256仍为710694153ed29a362bb76858828201ee99f0f7cc4ca534fbf38c5014eeb93802，见build/refactor-closeout-20261010-installed-main-sha.txt；CLI测试APK摘要另见build/refactor-closeout-20261010-cli-sha.txt。

## 真实 Memory 读取与隔离

新增 `LiveMemoryRecallDeviceTest` 在独立临时 Room/Markdown 中建立两个显式 Project、各自摘要和 Global 摘要，使用生产 SystemPromptContext 与正常 Provider；不写日常实际记忆。两个允许读取的请求分别得到本项目及 Global 的唯一合成代码，两个无读取授权请求为 UNKNOWN，且均无另一项目代码。`build/refactor-closeout-20261010-live-memory-r3.txt` JUnit 1/1、四请求全部通过，输入/输出分别868/311、867/214、706/309、705/114。不是自动写入质量或长对话压缩保真率验收。

首次和诊断复测失败：夹具 maxOutputTokens=128，实际输出 token=128 而正文为空；请求前记忆可见性/隔离断言通过。提高到2048后同一事实/权限断言通过，不删除断言、不将截断当额度或接线故障。仅更新测试 APK，没有更新日常主 APK。编译中行长/格式/诊断复杂度失败与修正日志保留，最终门禁另记。

## 仍开放

完整插件在途引用/撤销/取消/进程死亡交叉矩阵、其他IME/OEM及完整切换恢复、真实Memory摘要质量、R2-B生产策略的完整收益/资源对照、长稳与真机卡顿黑屏没有由本轮有限通过关闭。API36本轮窄屏缺陷与双API指定拼音候选提交已经复验；不推导全OEM窗口/键盘问题已关闭。账号、16KiB硬件和发行签名输入仍按status的阻塞项处理；本轮没有覆盖安装日常主APK或推送/发布。
