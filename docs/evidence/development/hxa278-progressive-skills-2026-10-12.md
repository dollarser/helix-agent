# HXA-278：渐进内容装载与固定模型对照

执行日期：本机2026-10-12，UTC2026-10-11。范围：宿主短规则、统一Skill目录/显式正文、按需原生参考资料和请求就绪摘要；设备为自有只读API36，真实模型仅既有本机无密钥Qwen。没有真机、真实账号或发布验收。

## 实现和身份边界

`PluginRegistry.instructions` 不再自动拼接所有 bundled Skill 正文。请求包含当前选择/资格下的元数据、宿主必需短操作规则，以及本次持久显式激活的准确身份正文/用户参数；重复快照及相同参数去重。取消、撤销、停用、目录替换后按原Repository事实重验，历史和已发送请求不改写。

Mobile Use/Browser 的短入口分别引用 `references/android-ui-reference.md` / `references/browser-workflow.md`。原生UTF-8参考资料复用文件快照的路径、长度framing和SHA-256，参考资料改变也改变SkillKey；普通`skills.read_resource`检查准确快照、来源选择、调用资格和512 KiB读上限。无新内容数据库、无原生正文专用执行接口、无旧策略开关。

请求就绪信息将重复组件事实按kind/state/requestState/saved/loaded汇总，保留诊断与省略组件数量，优先已选择插件，JSON限制8 KiB UTF-8，并报告被省略的插件/其中已选择数量。完整诊断、账号引用和配置目标仍在设置中；输出摘要不授予权限、不改变发现16项窗口/64工具hard cap或Dispatcher路径。

采用[Claude Code Skills](https://code.claude.com/docs/en/skills)按需正文与支持文件思路，保留Android短操作规则和准确身份边界；不采用命令/子Agent扩展，无参考代码复制。对应[技能ADR](../../adr/skills/001-authoring-and-installation.md)。

## 主机验证

- `./gradlew spotlessApply spotlessCheck test detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`：`build/hxa278-host-final-2.log` passed，1m14s，1077执行/增量任务总数。
- 随后必需规则文案及新设备fixture变更：双渠道lint/APK/AndroidTest、Mobile Use单元、格式和detekt，`build/hxa278-device-final-build.log` passed，1m，951任务；正式固定fixture编译/detekt `build/hxa278-fixture-final-build-2.log` passed，41s；原生内容设备fixture修正后双渠道AndroidTest/detekt `build/hxa278-reference-fixture-build.log` passed，42s。
- `./scripts/check-all.sh --source`：`build/hxa278-source-final.log` passed，206主机fixture、文档/ADR/i18n/secret检查。其中mockGUI FAIL_FUNCTIONAL为预期负例，不是设备失败或设备验收。
- 新回归覆盖准确激活/重复装载、停用与来源撤销、快照/目录变化、原生参考路径/字节读边界/不可变副本/文件hash一致性、必需规则及准确引用的压缩保留/容量失败、Unicode和大量组件的请求大小与省略事实。原有ToolCall配对、权限、取消、恢复测试保留。

## 冻结模型对照

三类任务各2轮，成功由独立成果核查，模型、任务、预算相同。纯流程读取chat-reply准确快照并生成指定合成回复；Mobile Use在真实Helix无障碍服务上填写、滚动、一次完成表单，通过fixture provider核对文本及完成计数；Browser在Activity-owned离线WebView上点击唯一按钮，独立DOM核对结果及一次点击计数。没有人手导航或模型假造成功的替代。

| 制品 | App SHA-256 | 同一固定test SHA-256 | 输出目录/结果 |
| --- | --- | --- | --- |
| 基线 | `00ee7bce01ab31fa747d7de620a6bd07c8dbd1ed83df6b75f424f6ff0993bea5` | `9b364e79433bec3fbf3012588701c9ca107f67bb48c1656fdd768ce0bd106fd7` | `build/hxa278-baseline-final-model-2/`，3 JUnit / 6任务，全部passed，82.373s |
| 候选 | `79770ca8cd05d9e5cd505d33191f75594119c9b0032ec9887f0146bb1464df7a` | 同上 | `build/hxa278-candidate-model/`，3 JUnit / 6任务，全部passed，89.174s |

Profile：`Qwen3.8-27B`，`127.0.0.1:30008/v1`（owned runner单独reverse），API36 Developer / `Helix_HXA271_API36`，各4096 MiB/2 cores，只读/no-snapshot。基线serial5640、候选5642，自有PID退出0；主机键盘与IME保留，本卡未另计中文输入验收。

下表为各任务两轮合计，inputTokens累加每次模型请求usage，包含历史被多次输入的实际消耗，不等于单次上下文占用。失败调用只依据FAILED/DENIED状态计数，未把所有失败归因为参数非法，也未推定副作用不存在。发现往返为tools.search/skills.list/skills.read/read_resource调用数。

| 任务 | 成功基线→候选 | 累计输入token | 未完成工具调用 | 发现往返 | 任务耗时ms |
| --- | --- | --- | --- | --- | --- |
| 纯流程草稿 | 2/2→2/2 | 72663→49772 | 0→0 | 6→4 | 12507→14144 |
| 真实Mobile Use表单 | 2/2→2/2 | 285930→292224 | 0→1 | 0→2 | 31181→34450 |
| Browser唯一按钮 | 2/2→2/2 | 134771→141990 | 4→2 | 3→3 | 24408→23022 |

混合结果：纯流程输入下降约31.5%，Mobile Use增加约2.2%、Browser增加约5.4%；合计493364→483986（约-1.9%），总耗时68096→71616ms（约+5.2%）。目录使纯流程某轮直接读取准确快照，但UI任务增加读取/观察往返；n=2不足以把差异归因为策略或证明普遍提速/成功率提升。交付内容正确性与测量结论，不声称所有任务都减少token。

## 双渠道内容与选择

Developer内容/显式选择6/6、0 skip、20.869s，`build/hxa278-developer-content-final/`，App如上，test `9f280dcf23a8c6b6e643675bff41e6afc846e5b69086df1191e16fda141fbc30`。Standard/consumer适用内容/选择5/5、0 skip、9.814s，`build/hxa278-consumer-content-applicable/`，App `10315ac85319fab5c6d2557be078e74178b27a227721919c19f060471f13aa47`，test `ae8f6c1bff0ee60c4f67d5bee9860befbf75b9a33aafd313590e9851fb53b3fc`。均为自有API36只读实例、关闭exit0；覆盖原生Browser短入口/准确参考读取、错误hash、本会话停用、来源撤销、导入纯Skill资源与显式激活参数。consumer没有现有原生chat-reply工厂，该Developer原生内容用例不纳入consumer分母；consumer导入纯Skill与Browser正常验收，没有把缺失工厂假报成功或跳过后称全量通过。

## 失败尝试与边界

固定fixture初版编译/格式行长、遗漏测试assertTrue导入、短入口迁移后的旧正文断言和测试必需规则fixture接线失败均保留；修复编译/文案分层/fixture接线，原边界断言保留。原Mobile Use断言改为入口加可读参考资料，并新增短入口/准确引用检查，未删掉等待、未知效果和权限规则。

`build/hxa278-baseline-model/`是指南读取/无独立按钮计数预跑，6任务passed，仅作预跑；正式分母不混入。`build/hxa278-baseline-final-model/`在模型任务前发生ADB连接写失败，UiAutomation owner died及DeadObjectException，没有非空JUnit结果；进程关闭0，完整失败日志保留。正式冻结制品使用4096 MiB分别跑基线与候选，没有生产策略双实现。

第一轮内容测试Developer的原生Skill停用fixture误调用仅接受安装包Skill的facade，CONNECTOR_UNKNOWN_SKILL失败；改用原SkillRepository的本会话停用契约，没有扩大全局facade能力。consumer runner包名误写`.consumer.test`导致找不到instrumentation，改为实际`com.helix.agent.test`；随后误选不存在原生chat-reply工厂的Developer用例（其余5项通过），保留`build/hxa278-consumer-content-final/`，按实际支持范围执行全部5项通过。原失败目录保留。

以上是有界合成验收，不覆盖OEM、Root/Shizuku、真实网站/账号、视觉推理或长稳。277的真实物理退出证据保持独立；本卡Mobile Use是真实后端模型任务，但不是全后端/OEM证明。日常模拟器仍为此前保留数据更新的275制品，后续最终更新需核对精确制品与数据库事实。
