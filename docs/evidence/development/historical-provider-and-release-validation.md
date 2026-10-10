# 早期Provider、上下文与发布验证经验

整理：2026-10-10。承接2026-09-10～10-01的旧报告，保留失败机制、修订顺序、历史验证与边界。原报告已删除，Git来源见[整理记录](documentation-convergence-2026-09-29.md#2026-10-10-旧版报告按主题承接)。不是本轮设备或真实账号验收；正式当前契约查ADR，当前发布仍查[v0.0.5报告](v0.0.5-release-2026-10-06.md)。

## 账号连接与DNS

2026-09-10的订阅检查曾选认证目录首个slug生成固定文本，主应用检查曾用当前config.model；两者均不能独立说明账号认证。改为Codex请求真实认证目录，401仅刷新一次，仍失败原样报告，无POST生成请求；配置模型不在目录不影响账号连接检查，但不证明该模型可用。静态目录或没有认证目录的适配器不能假通过；普通API Provider仍按自己的协议检查，能力检测和实际生成独立。长期决定在[Provider ADR](../../adr/provider/001-models-and-connection.md)。

历史主机33项通过，含无生成、模型选择独立、认证拒绝、不支持不得假通过、401单次刷新及原能力探测；两渠道和订阅APK、lint/国际化/ADR/文档/diff通过。当轮设备not requested，设置和审计视觉验收待后续，详见[UI历史](historical-client-ui-validation.md#设置与审计窄屏布局)。

同日手动DNS预填曾为chatgpt.com及104.18.32.47/172.64.155.209、24小时；这只是当时解析结果，现已不是可推荐的有效地址。逐地址保留域名TLS检查收到403，说明未认证站点可达，不等于订阅对话通过。配置保存后生效，已有用户值优先，过期/删除回到系统DNS；不修改系统、关闭证书校验或影响其他App。该轮仅通过设置页保存，不运行模型任务。回归覆盖过期/删除/非法配置/缓存隔离，不把主机解析当真实账号验收。

旧Turn报告曾提高单次输出到16384并移除Assembler隐藏的4096二次截断，旧模板升级、自定义与主动回退不被重复迁移；这些是当时产品参数，不是模型规格。累计输入/输出/调用上限及Goal预算随后被DSH记账契约取代。当前边界见[模型数据/预算ADR](../../adr/agent/006-model-data-budget-boundaries.md)、[压缩ADR](../../adr/agent/002-context-compaction.md)，不从旧参数表恢复累计阻断。原研究参考Pi设置分离余量与历史保留，DSH在步骤边界维护工具配对；只采用机制，不复制代码。

## 压缩、用量与签名回放

2026-10-01：手动压缩无收益正常结束、不发布checkpoint、不改变原文；真正失败仍报告。摘要正文目标按可移除历史调整，不挤占推理余量。圆环按当前会话及Provider/model读取确认用量，发送中保留上次值；摘要小请求、失败或未提交压缩不替换会话用量，成功提交才显示估计值，缺窗口字段标明估算。

Antigravity `ask_user`已成功，但私有签名回放绑定原始args，Harness剥离`__helix_intent`后本地编码拒绝，连历史维护操作也受影响。最初修正为原始完整性/绑定检查后严格比较业务参数；后续files.list暴露合法Workspace路径规范化也会被拒绝，最终改为验证原始记录及账号/模型/调用身份后回填Harness canonical args。不能恢复旧“原始业务参数与canonical args严格相等”规则。签名和身份隔离仍必须验证；后续真实files.list/视觉结果在[原专项报告](files-vision-readme-2026-10-01.md)。

历史两渠道unit/lint、Developer APK/AndroidTest、Runtime unit、格式/detekt/source/diff通过；阶段性AntigravityProtocolTest 11/11含重开、参数篡改和跨账号/模型拒绝，后续13/13另见发布段。API36 Developer压缩三类22/22、会话用量隔离1/1；真实gemini-3.8-flash-tiered旧历史短回复及ask_user选择后续答COMPLETED。成功压缩审计COMPLETED，估计4276→1039；圆环10439/200000 fallback约5%→成功提交后的估计<1%，发送期间保留约4%。这些数字来自当时指定服务与合成验证，不证明其他订阅或模型质量。

## v004发布与回放依赖

2026-10-01开发预览v0.0.4：提交`05e910039e03095d98649d96a9e64a3a71bcb078`，versionCode=4，双渠道debug签名、prerelease。整合模型与订阅入口、继承、上下文、消息操作/分叉、终端指引、模式菜单和中英README。升级提示要求导出重要内容；开发库不兼容会清会话/配置记录，数据库外文件保留。这是当时升级契约，不代表本轮重新安装。

取消跨会话128条LRU：签名记录是仍被会话引用的依赖，新增129条并重开读取能稳定复现修复前`Signed response unavailable`，修复后全部读取通过；夹具使用真实wire name且保留隔离检查。单记录8MiB边界保留；当时引用感知清理未实现，旧版已经删除的记录不能恢复。后续生命周期实现应查[HXA-233](../../development/tasks/HXA-233.md)，不能把当时“未实现”当当前待办。

首轮设备oracle把隐藏消息操作当常驻控件、选user消息做安全分叉；改为实际点击气泡和assistant安全边界。短内容48dp触控区与0.5dp舍入容差用于必要inset，仍拒绝额外spacer，保留损坏历史拒绝、此前安全回答分叉及完整复制。

历史主机unit、双Debug lint/APK、Developer AndroidTest、格式/detekt/source/artifact边界通过；AntigravityProtocolTest 13/13含129条依赖保留、重开、canonical args和账号/模型/调用身份隔离。API36 arm64 Developer的ToolTimelineLayout、ApprovalLayout、MessageCopy、SessionForkFlow、ContextWindow、SessionContextUsage六类共21/21、34.218秒，覆盖320/360/412dp大字体及intent标题、操作、复制、分叉、隔离。安装保留账号和历史，无卸载/清库；前两轮失败不能替代最终结果。

发布验证轮真实账号调用为0；同日files.list→view_image及Qwen browser.screenshot→识图属于独立专项报告，不并入本轮。没有全P5/物理手机/商店/OEM/低内存/长稳或偶发故障根因关闭声明。旧APK与release004原始日志已于HXA-268删除，hash/路径见[清理记录](historical-version-retirement-2026-10-10.md)；这里只保留当时报告的事实，无法重新检查已删原始日志。
