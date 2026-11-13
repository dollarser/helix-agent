# 非重构收口与发行就绪条件

初稿：2026-09-28；归类：2026-09-29。本页保留内测、外部服务和发行的依赖与退出条件，不承担全项目“剩余工作”或第二份实时排期。当前进度看 [status](status.md)，功能/重构选择看[开发策略](feature-refactor-strategy.md)，候选范围看[待裁决索引](candidate-decisions.md)。

## 1. 当前范围

近期保持一个 Android 本机产品、一条执行管线。R1 已由 [HXA-231](../completion-records/HXA-231.md) 交付，后续所有者授权的实施范围由 [status](status.md) 和对应 HXA 维护；本页不扩展其授权。重构以外继续推进内测、真实设备和发行准备。[roadmap](roadmap.md) 仅作任务索引，不承担实时排期。

模型下载配置、Memory/Workspace 增量设备验收、首次成功旅程、恢复 UI、脱敏诊断、分类空间与发布残留管理已有交付证据，不再列为从零开发任务。这里的“已有交付”不代表所有 OEM、真实账号或发行版本通过。

2026-10-01 的[收尾快照](../evidence/development/phase-closeout-2026-10-01.md)记录当时回放/OAuth/R2-A/R3/基础 J1 候选的本地主机复验及未提交边界，不代表当前源码状态。后续 J1/AUTO、Project Memory 和插件交付以 [status](status.md) 及原 HXA 为准，不继续把当时缺口列为当前待开发项。任何历史源码/APK 指纹均不能代替拟验候选自身的冻结、回归、独立内测或签名发行验收。

正式发布前，每次发布页及中英文 README 必须明确：项目尚未正式发布；新版本不保证可以覆盖安装；即使覆盖安装成功，也不保证保留历史数据。一次候选的覆盖安装或数据保留验证，不构成后续版本的升级兼容承诺。

## 2. 就绪检查面

下列是所选版本需要满足的条件，不是一条自动串行的新任务链。是否执行及哪些范围已通过，由当前任务和证据决定。

预览版不要求旧库迁移或无损升级验收；不兼容数据库的重建须遵循 [ADR-PLATFORM-003 的预览升级确认](../adr/platform/003-storage-encoding.md#decision-history--2026-10-17-预览升级确认)与 [HXA-304 验收](../evidence/development/preview-upgrade-confirmation-2026-10-17.md)：容器和恢复启动前只读检查，前台说明删除范围，明确确认并复核原数据库身份后才重建。取消、重启、后台入口、普通读取错误或腐败不能触发自动清库；分发说明不能代替现场确认。签名、版本编号、安装成功和当前版本的会话/授权恢复仍须验证，数据库外文件及无关日常数据保留。正式发行的长期升级承诺另按 HXA-122 决定。

| 检查面 | 工作 | 可执行内容与退出条件 | 所需输入 |
| --- | --- | --- | --- |
| 本地收口 | 清理事实漂移与验证当前源码 | 文档指向同一状态；全部主机门禁及制品边界通过；已知问题有范围、复现入口和停止条件 | 不触碰并行 WIP，不因整理而再次开发已完成功能 |
| 内测准备与试用 | 验证真实用户独立完成任务 | 使用[内测验收清单](internal-pilot.md)，记录首次成功、人工介入、失败恢复和结果正确性；发现可复现阻碍先修 | 试用者、专用目录、设备和模型配置；实际设备/服务使用另按当前授权 |
| 条件性补验 | 真机资源及 Memory/模型效果 | 当前候选包的锁屏/后台、低内存、温升、取消、恢复和存储压力有证据；Memory off/on 有独立结果核验 | 指定真机/OEM；模型服务或账号授权；16 KiB 真机单列 |
| 候选版本回归 | 给内测或重构形成统一锚点 | 冻结源码/APK 后完整 P5；必要的 API/渠道矩阵按变更范围选择，专项与普通测试分开记账 | 干净且已提交源码；独占设备和本机服务授权 |
| 发行 | 120 → 122 → 121 → 123 | 选渠道、确定身份及数据承诺、签名候选验收、提交材料；审核结果如实记录 | 所有者渠道/身份/签名决策与外部提交授权 |

不要为了“清单全绿”无限运行模型测试或扩大权限。已有失败需记录；新证据不足时保留边界，恢复入口或诊断完成不等于原始问题根治。

### 每次候选必测的会话核心

按 Owner 2026-11-13 的验收优先级，会话核心不得被设置页、UI 导航、编译成功或历史候选的通过记录替代。冻结当前源码和 APK 后，发布前本地必跑以下两层；GitHub CI 仍只执行主机检查及测试 APK 编译，不执行设备测试。

- **Release/R8 生产旅程，两渠道**：`scripts/run-release-smoke.py --suite core` 通过公开 UI 配置仅本机 loopback 的合成 Provider，实际发送普通会话、执行只读工具并验证模型收到回填；进程停止重启后，确认完成回复仍显示且进入下一次请求的历史；请求中停止，确认连接结束、重启不重放、后续会话可继续。默认 `basic` 也包含这条必测旅程。runner 验证该方法实际执行，缺失、跳过、失败、清理不完整均阻断；不能只凭总测试数或截图判定通过。
- **Debug 真实持久化边界，两渠道**：`ProviderTurnJourneyDeviceTest`（模型选择、工具回填、压缩后继续）、`LocalProviderLoopDeviceTest`（本地模型同一生产链）、`TurnDiagnosticStoreDeviceTest`（诊断 producer/Room sink 与限额）、`ConversationStopConsistencyDeviceTest`（停止及旧停止身份）、`RoomTurnLifecycleStoreDeviceTest`（队列、终态及恢复归属）、`ToolSettlementRecoveryDeviceTest`（事务失败、UNKNOWN、重复恢复与结果持久化）、`SessionInputAdmissionFailureDeviceTest`（准入失败与暂停输入）、`PermissionAtomicityDeviceTest`（权限状态及审计原子性）、`ToolInspectionQueryDeviceTest`（完整工具详情查询及原始参数/结果/审计保真）。用现有独占模拟器 runner 按实际方法集合核对；需要重启阶段的其他专项按原 runner 运行，不把一次数据库重开写成主进程死亡。

真实 Room 核心批次复用现有 runner。成功构建同候选的普通 Debug 主包与双渠道 AndroidTest APK 后，在已授权的本机执行（输出目录每次新建，AVD 必须与 API 匹配）：

```bash
python3 scripts/with-host-slot.py -- python3 scripts/run-acceptance-matrix.py \
  --scope 206 --group core-conversation --api 36 --avd Helix_API_36_test \
  --clean-install --output build/release-core-room-NEW
```

默认运行双渠道，方法集合从当前源码提取并与实际执行核对；清装只针对新建独占模拟器中经 APK manifest 核对的两个测试包，不接触日常实例或源 AVD。Release/R8 则按 `run-release-smoke.py --help` 提供隔离包、测试 APK、确切 serial 和新输出目录，两渠道分别运行 `--suite core`。隔离签名与包名不证明正式签名发布，普通 Debug 包也不能代替 R8 行为。

主机 `check-all.sh --all` 继续负责 schema/参数、权限策略、预算、取消/恢复状态机、格式/静态分析及构建边界。设备必测的意义是验证这些模块在最终应用里的真实接线，不能由 host fake 替代。

复杂布局、主题、IME、手势细节和易变的辅助入口按本次影响范围选跑，不要求每次发布重跑全部体验专项。授权 UI、错误/未知结果提示、停止/恢复入口属于核心边界，不能因归类为 UI 而跳过。渠道专属 runtime、SAF、浏览器、JNI 等有变更时增加对应专项；真实模型、其他 OEM、长稳和资源压力单列范围，不把上述合成旅程外推成所有能力已验收。

这些是候选退出条件，不是本页宣称已经通过。具体命令、源码/APK 身份、已执行和未执行项仍归本次 evidence。任一核心项未执行即为 `pending`，不能以“只剩 UI”或“主机全绿”宣布可以发布。

## 3. 已知问题的关闭规则

| 项目 | 当前处置入口 | 何时继续、何时停止 |
| --- | --- | --- |
| SAF 来源 UI | [2026-09-29 修复与双渠道验收](../evidence/development/intermittent-closeout-2026-09-28.md) | 已修路径不再作为待实现项；后续若有新失败，记录 scope 是否存在、实时查询与 UI 状态，不能缓存旧授权放行 |
| 历史输出截断 | 同一记录的逐模型调用 usage 与 probe 计数 | 遇到新 length/OUTPUT_TOKEN_LIMIT，区分每次输出预算、推理、重复调用；保持失败终态，不执行截断 ToolCall。没有新样本不重复刷结果 |
| 能力探测偶发失败 | 阶段/错误码、fixture 和无正文计数 | 新失败时先分协议、能力与连接；连接成功不升级成能力通过；不按模型名猜能力 |
| 多余只读工具调用 | 固定 skill-003 与真实任务轨迹 | 按具体运行保留失败和成功；只有影响完成率、预算或时延才做同条件对照，不以一次 0 调用关闭所有重复调用问题 |
| JNI/Binder/FD、OEM 长稳 | [当前限制](status.md#仍开放与未启动) | 按真实进程/物理硬件维度验证；模拟器结果不能关闭系统或所有 OEM 问题 |

## 4. 模型、服务与评测边界

- 设备内模型继续聚焦 **Qwen3 4B Instruct 2507 Q4_K_M**，复用现有 ModelScope/Hugging Face 精选入口及高级导入。0.6B/1.7B 只留历史对照，不重新投入主动验收。
- 本地模型优化先测 load、prefill、decode、首响应、PSS/RSS 与 cancel-to-exit；模拟器固定任务不定义手机最低配置。KV/prefix、线程/batch、逐 token 输出及 GPU/NPU 仅在存在瓶颈证据后另行决定，不是本轮缺失功能。
- Harness 回归使用既定 SGLang 固定任务；规范、身份和统计口径见[系统基线](harness-system-baseline.md)。BFCL/AndroidWorld 是诊断，不是官方榜单结果或扩大权限的理由。
- Global/Project Memory 的生产接线及 HXA-246 本地主机、API36 双渠道 UI/Memory 和项目记忆进程恢复已交付，范围见[原任务](../completion-records/HXA-246.md)。其他设备、真实任务效果、过时信息、跨会话污染与长稳仍需独立验证，不能仅凭 prompt 中出现文本声称提升。
- 统一 PDF/DOCX 阅读由 [HXA-284](../completion-records/HXA-284.md)交付，已有 API36 及 OnePlus/API34 原生读取与真实模型切片，见[重构前收尾](../evidence/development/pre-refactor-closeout-2026-10-15.md)和[真机记录](../evidence/development/device-ui-model-validation-2026-10-15.md)。完整 R8 文档矩阵、大文档资源峰值、其他格式、编辑/生成、OCR 与完整 Office 排版不由这些切片推导通过。
- `helix-docs` 使用内置 Skill 与按需离线章节。2026-10-15 后续优化补齐八章中文资源，版本/编号/applicationId/渠道/可调试状态由安装宿主提供；未知事实为 null，不再硬编码旧版本或声称通用 Release 签名。构建 commit/dirty、签名证书、静态帮助 UI 和源文档自动生成未包含，验证边界见[本轮记录](../evidence/development/preview-polish-2026-10-15.md)。
- Connector/订阅按实际选定供应商验收，不承诺任意服务兼容；未选或暂缓供应商不形成无限开发义务。

## 5. 需要外部输入的正式任务

| 任务 | 剩余内容 | 最少输入 / 决策 |
| --- | --- | --- |
| [HXA-125](tasks/HXA-125.md) | Notion/Linear真实账号子范围已于2026-10-15按Owner决定结案，保留各自已通过结果及未执行边界；仅当另选Connector时按原任务定义新的范围 | 本轮不再需要Notion/Linear账号、授权、刷新或恢复输入；其他Connector需另行选定服务及授权 |
| [HXA-126](tasks/HXA-126.md) | Notion/Linear真实服务测试已结案，不再追加账号或OAuth复测；本地OAuth实现已交付，自有HTTPS/App Link和正式签名属独立发行条件 | 不等待Notion/Linear的重新授权、自然刷新或恢复；后续发行按HXA-120/122另行确定redirect、渠道和签名身份 |
| [HXA-190](tasks/HXA-190.md) | 所选订阅供应商目录、生成、刷新和失效恢复 | 供应商/账号/额度授权；暂缓 Claude/Grok 的决定不变 |
| [HXA-120](tasks/HXA-120.md) | 最终渠道权限/能力/SDK/Runtime/披露矩阵 | 首发渠道；执行时核验当日官方政策，不在本计划预判合规 |
| [HXA-122](tasks/HXA-122.md) | applicationId、签名、同 ID 升级及数据恢复承诺 | 发行身份、签名归属、何时停止开发期 Room v1 重建；现在仍仅承诺当前开发约定 |
| [HXA-121](tasks/HXA-121.md) | 同一签名候选包、真机/资源/供应链/材料验收 | 120/122 输出及指定设备；debug/release 构建不等于签名发行通过 |
| [HXA-123](tasks/HXA-123.md) | 隐私/权限说明、截图/演示、渠道材料及审核 | 候选包、主体/支持联系方式；准备、提交、审核、通过分别记账 |

上述任务不能用 fixture、当前主机全绿或旧设备结果关闭。签名、账号、真实用户材料不得通过普通文档收集。JSONL 诊断导出不是全量可恢复备份，release 产物也不自动获得发布授权。

## 6. 暂不扩展

具体候选的设计状态和进入条件只维护在[候选索引](candidate-decisions.md)。已交付的 Project Memory 和 PDF/DOCX 阅读不再列为未开发候选；其他格式、编辑/生成、视频、Subagent 与远程能力仍按各自范围决定。本页不复制功能 backlog，也不由发行条件自动启动工具/上下文/插件/Job 重构。

## 7. 发布附件与开发归档

已获推送/发布授权时，核对目标仓库/分支、完整 outgoing 提交与敏感信息、远端并发及最终 SHA；只检查 HEAD diff 不足以覆盖整段待推历史。成功与否以远端读回为准，相关实践见 [v0.0.6 发行记录](../evidence/development/v0.0.6-publication-2026-10-18.md)。本段是适用场景参考，不授予推送/发布权限。

按 Owner 2026-10-18 的发布要求，应用版本 Release 只上传可安装的可执行程序；Helix 对应源码使用 GitHub 按标签自动提供的 ZIP/TAR。更新说明、准确构建提交、对应 CI、APK SHA-256 和必要的第三方源码/许可链接写在正文，不再上传 ASSETS、BUILDINFO、校验清单或 mapping 等配套附件，不另建“资料 Release”。

CI 真正依赖的固定运行时二进制保留在既有资源 Release，保持已锁定下载地址及内容。分发这些二进制所需、与具体组件版本对应的第三方源码、配方、补丁和许可材料可随同归档；应用页必须直接链接到准确材料。不能用可变的上游首页代替对应源码。追加材料不覆盖原运行时附件，旧应用仍分发时不能删除其必要源码入口。

开发归档只保留实际有用且能关联具体 APK 的 mapping 和简要构建身份。重复制品、一次性附件说明和无后续诊断价值的流水材料不长期保存；下载校验优先流式读取，清理副本前先验证保留件。归档和附件整理不重建已验收 APK、不改版本或标签目标，也不把旧设备/模型结果记为新执行。

## 8. 状态维护

当前状态写 status，任务范围写 HXA，长期合同写主题 ADR，历史结果写 evidence/completion。新的验证只替代对应版本和范围，不重写旧失败。优先关闭明确的缺陷和证据缺口；账户、设备、发行决策和研究候选分别维护，不混成“继续收尾”一个无限任务。
