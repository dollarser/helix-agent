# 外发规则权限页应用接口验证（2026-10-10）

HXA-255，起点 `9c5ee3ab`。所有者要求继续完成架构审查，并延续原任务逐项提交/模拟器验证范围。仅应用接口、UI归属和回归，未调整实际授权范围、渠道、TTL或持久存储schema；未操作真实手机或调用模型。

## 改动与依据

EgressRuleService 在 IO 校验 Provider/MCP 精确ID、规范端点、APP scope和既有固定TTL，再调用原仓储；展示仅输出 ID/目标/源/category/scope/到期时间。MainActivity/Settings不再传递规则仓储，页面移到 `permissions/ui`。PolicyEngine仍读取原Room仓储，页面模型不构成授权来源。

取消与致命错误传播，普通错误返回失败；异常没有message时仍明确显示错误，可重试读取。写入完成后读取失败如实显示失败，不回滚已提交事实，不自动重放写入。创建期间用户的新表单内容不因旧写入完成而清空。

参考[Android分层/UDF/生命周期建议](https://developer.android.com/topic/architecture/recommendations)与[审查中的Agent参考](../../research/topics/architecture-and-test-maintenance-audit-2026-10-10.md)，复用现有手工容器、Result、协程和Policy值类型，不新建权限注册表/框架、未复制参考代码。沿用ADR-PERMISSIONS-003应用服务规则。

## 主机

`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` exit=0，日志 `build/maintenance-followup-20261010/egress-host-r2.log`。默认构建全仓JVM、静态检查、两渠道Debug lint/App/AndroidTest通过，993 tasks/53 executed，1m38s；不将此称为发行或opt-in spike全验收。

新增应用接口5项每渠道passed、skip=0：创建/读投影/撤销、非法target/端点无写入、读/写/撤销失败、已提交后重读失败不重放、全部动作取消/致命错误传播。既有可选真实Connector输入每渠道4项仍skip，不计外部验收通过。

新增表单并发回归后再编译、detekt/spotless并执行Developer lint（`egress-fixture-final.log`、`egress-fixture-lint.log`）；最后修正字段语义断言后再编译/静态/lint（`egress-ui-assertion-host.log`）通过。首次新断言错误地省略规范origin的`:443`，修正为原契约而非改变生产规范化。没有删除原测试或放宽权限。

## 设备尝试与边界

自有API36 arm64-v8a `Helix_Main_Verify_API36`，read-only/no-snapshot，4GiB；Developer5602首次、5606最终，Consumer5604。日常5554未借用/安装/清数据，自有runner负责PID/序列核验、制品SHA256、原始JUnit和关闭。

首次Developer **3/4通过、1失败**：新增草稿断言把OutlinedTextField的标签Text与EditableText一起比较，实际EditableText已经是`next-draft`、原提交行是`provider:submitted`。改为严格比对EditableText属性，保留原提交目标、仅一笔写入及新草稿精确值，未删除断言。失败日志 `egress-api36-developer.log`，截图/窗口与closed记录保留；这次不能计整体设备passed。

最终Developer **4/4 passed**：原真实Room创建/撤销/Activity重建/Standard隐藏1项，失败读取重试与新草稿边界2项，实际拼音1项；日志 `egress-api36-developer-r2.log`。最终Consumer **3/3 passed**：规则不可达、固定Standard、实际拼音；日志 `egress-api36-consumer.log`。两个runner exit=0、closed exit=0；硬件键盘/软键盘保留，实际n/i/h/a/o候选及“你好”进入草稿、零发送，未使用UTF-8注入代替IME。

最终SHA256，来源两个runner的`artifacts.json`：

| 渠道/制品 | SHA256 |
| --- | --- |
| Developer App | `1d52bfd7e8e46d9e9e3e1bd5bae95a44bcc94076e5b53c130b743f3c88b305d9` |
| Developer AndroidTest | `df9608ca211c659ecb98798f6634309f4c959659ebf56915aefb59bbf1277c7b` |
| Consumer App | `942dc7dfe7b83c3f33bcd2f5042678c0da344df08aadc9b34be29e9ca96e1056` |
| Consumer AndroidTest | `621a0cbbf17c28fb859951e36235b18e9d797fbe7c210943a32e65748d9957d8` |

稳定入口 `scripts/run-owned-emulator.py --avd Helix_Main_Verify_API36 --port <空闲且当轮授权端口> --apk <对应DebugApp> --test-apk <对应AndroidTest> --timeout 600 --memory-mb 4096 --output <新的build目录>`；Developer classes=`com.helix.app.ui.EgressRuleUiTest,com.helix.app.ui.EgressRuleFailureUiTest,com.helix.app.ui.PinyinComposerDeviceTest`、runner=`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`；Consumer classes=`com.helix.app.ui.EgressRuleConsumerAbsenceTest,com.helix.app.ui.ProfileConsumerFixedTest,com.helix.app.ui.PinyinComposerDeviceTest`、runner=`com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`。语言未配置须先在自有实例准备，不能默认为pass。

未运行真实模型（推理路径不变）、API29、真机/OEM、实际进程死亡或长稳；Activity重建不冒充进程死亡。此项不关闭卡顿/黑屏或性能问题。
