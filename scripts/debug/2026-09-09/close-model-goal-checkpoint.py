"""Close HXA-178 only after all final owned-device evidence is green and closed."""
from pathlib import Path
import json
import re
base=Path('build/debug/2026-09-09')
runs={'hxa178-developer36-accepted':79,'hxa178-consumer29-accepted2':79,
      'hxa178-migration29':24,'hxa178-migration36':24,'hxa178-live36-final':3}
for name,count in runs.items():
 p=base/name
 assert re.search(r'^OK \('+str(count)+r' tests?\)',(p/'instrumentation.txt').read_text(),re.M),name
 assert json.loads((p/'closed.json').read_text())['exit']==0,name
for name in ['hxa178-accepted-host.log','hxa178-final-publication-test.log']:
 assert 'BUILD SUCCESSFUL' in Path('build/phone-polish',name).read_text(),name
record='''# HXA-178：模型判断 Goal 完成

Date: 2026-09-09
Status: completed

## 机制替换

决策记录：按所有者“按主流机制实现，完全抛弃旧方案”的授权，接受 [ADR-0040](../adr/0040-model-judged-goal-completion.md)，替代 ADR-0028 的全局强制绑定验证。模型通过内置 `goal.report(status, summary)` 报告 complete / in_progress / blocked；语义完成由模型判断，Harness 负责当前 Goal/Turn 归属、合法执行、持久化及正常结算。模型报告会误判，不把完成标记宣传为独立认证。

正常 Turn 结束时，仅消费当前轮最后一项工具调用中的成功、已验证报告；后续工具会使较早报告失效。取消、用户暂停、预算中止和未决副作用优先。无报告或 in_progress 保持 PAUSED，可继续；blocked 保留原因，修复后显式重新检查转 PAUSED。裸文本“完成”不是控制信号。Goal 指令从当前持久绑定重新组装，压缩后仍可用。

创建 Goal 只需要目标，补充要求可选。移除绑定编辑、规则/人工证据选择、独立 Goal verifier 及运行时 criterion evidence 字段；旧实现和专用测试仅保存为 scripts/debug 日期目录中的不可执行历史快照，替代契约有新的状态机、Dispatcher、UI 和设备回归。存储 codec 保留旧格式读取能力，不把历史绑定带入运行时。模型判断摘要在 Goal 面板可见。

数据库 v12：只将旧 BLOCKED(EVIDENCE_BINDING_REQUIRED) 迁移为 PAUSED，不启动模型、不重开已完成目标、不覆盖预算阻塞或历史运行/审计。保留既有显式 Continue、预算、后台结果回收和 Android 生命周期边界；未加入自动跨 Turn 驱动或生产子 Agent。

## 验证

```sh
./gradlew spotlessApply test :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest detekt lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug --continue --max-workers=1
./gradlew spotlessApply :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest detekt :app:lintConsumerDebug :app:lintDeveloperDebug --continue --max-workers=1
sh scripts/debug/2026-09-09/run-goal-report-regression.sh consumer 29 5570 build/debug/2026-09-09/hxa178-consumer29-accepted2
sh scripts/debug/2026-09-09/run-goal-report-regression.sh developer 36 5568 build/debug/2026-09-09/hxa178-developer36-accepted
python3 scripts/debug/2026-09-09/summarize-goal-report-verification.py
python3 scripts/debug/2026-09-09/test-owned-emulator.py
python3 scripts/debug/2026-09-09/verify-debug-archive.py
scripts/check-docs.sh
scripts/verify-adr.sh
scripts/check-i18n.sh
scripts/check-secrets.sh
git diff --check
```

主机日志 hxa178-accepted-host.log 与 hxa178-final-publication-test.log 均 BUILD SUCCESSFUL。根 test、lintDebug、双 app lint、双 flavor 构建、Spotless、Detekt 全通过。受影响 JVM XML：core/model 138、core/agent 166、core/storage 88、Consumer 337、Developer 360，共 1089 项、0 failure/error；8 个既有外部样本/opt-in skip，未以这些 skip 作为外部服务验收。

设备证据（均无 skip）：

| 运行 | 结果 | 范围 |
| --- | --- | --- |
| hxa178-developer36-accepted | 79/79 | 模型报告16、完整报告链路3、Goal 协调/绑定/预算/取消、后台回收、压缩、Goal UI |
| hxa178-consumer29-accepted2 | 79/79 | 同一套回归，包含任务列表发布同步修正 |
| hxa178-migration29 | 24/24 | 当前 schema、完整历史迁移链、旧绑定阻塞与预算阻塞的区别 |
| hxa178-migration36 | 24/24 | 同上 |
| hxa178-live36-final | 3/3 | SGLang Qwen3.8-27B 的仅目标完成1项，最终后台任务UI复核2项 |

合计 209 次通过的设备测试执行，包含最终复核。真实模型用公开的“2 + 2”合成任务验证实际 Provider → 模型工具调用 → Dispatcher → 持久报告 → Goal COMPLETED，不代表复杂开放任务的模型判断准确率。服务通过已有端口 30008 转发，无密钥或真实用户对话入库/提交。

每次使用自己的只读模拟器进程，冻结 APK SHA-256，保留 owner.json、artifacts.json、instrumentation.txt、test-logcat.txt、closed.json。上述五次运行均已关闭，exit 0。没有借用其他任务的模拟器或安装真机。日志位于忽略的 build/debug/2026-09-09 和 build/phone-polish；临时脚本按日期入仓。

## 失败、诊断与修正

- 新工具多行描述违反 ModelToolSchema 的控制字符约束，导致请求发送前 INTERNAL；根据真实异常栈规范为单行，并增加模型工具 schema 构造回归。此前失败保留，未归咎 Provider。
- 旧验证器移除产生的未使用资源/残留测试同步清理；旧“必须填写条件”断言按可选补充要求的新契约替换。
- 扩大设备回归发现后台列表在 Goal 删除中途分次读取 run，可能崩溃；任务和 Goal 摘要改为事务快照，增加并发读取/删除回归。
- 相同 startedAt 的多个 run 以前选中第一条；现在按 DAO 的 startedAt,rowid 顺序取最后一条，覆盖旧报告不跨轮与最新上下文阻塞优先。
- 旧 wake 时间预算测试仍期待 PAUSED，按 ADR-0039 的预算 BLOCKED 契约修正，同时保留 socket 关闭、用量和无重放断言。
- API29 的后台 UI 夹具只等待 Turn 落盘便点击结果，偶发早于任务列表发布；改为等待该任务终态出现在 UI 数据流，再执行点击。未放宽产品断言或使用固定延时。

## 交付边界

修改位于 phone-chat-polish 工作树，尚未提交、合入 main、推送或安装真机。系统 JNI/Binder 根因、多天长稳和外部账号验收保持原有独立状态，本记录不声明它们完成。
'''
Path('docs/completion-records/HXA-178.md').write_text(record)
p=Path('docs/development/status.md');s=p.read_text().replace('HXA-178 进行中：按所有者明确要求以模型报告替换强制 Goal 证据验证，见 ADR-0040。','无。HXA-178 已完成：模型报告替换强制 Goal 证据验证；主机门禁、双 API 回归、迁移与真实模型通过，见 [完成记录](../completion-records/HXA-178.md)。').replace('本批授权 HXA-173～177 已完成','本批授权 HXA-173～178 已完成').replace('HXA-161～177 尚未提交','HXA-161～178 尚未提交');p.write_text(s)
p=Path('docs/development/roadmap.md');s=p.read_text().replace('状态：in progress。允许 app、core/model、core/agent、core/storage、相关测试、docs、scripts/debug。完全替代 ADR-0028','状态：completed，见 [完成记录](../completion-records/HXA-178.md)。允许 app、core/model、core/agent、core/storage、相关测试、docs、scripts/debug。完全替代 ADR-0028');p.write_text(s)
p=Path('docs/development/verification-matrix.md');s=p.read_text().replace('| HXA-178 | 模型报告/状态归属/取消与预算/迁移/无绑定完成 | 进行中 |','| HXA-178 | 模型报告/状态归属/取消与预算/迁移/无绑定完成 | 主机门禁通过；双 API 各79、迁移各24、真实模型及最终复核3通过，见完成记录 |');p.write_text(s)
p=Path('docs/adr/0040-model-judged-goal-completion.md');s=p.read_text().replace('所有者明确授权作为接受依据，实施验收尚未完成。要求模型报告/跨会话/取消/预算/失败/旧绑定迁移、无绑定完成与 UI/真实模型测试；主机、双版本构建、独占模拟器与文档门禁。','所有者明确授权作为接受依据。实现与主机、双版本、独占 API29/36、旧库迁移、真实 SGLang 和文档验收已完成，见 [HXA-178](../completion-records/HXA-178.md)。模型报告/跨会话/取消/预算/失败/旧绑定迁移、无绑定完成和历史报告隔离均有回归证据。');p.write_text(s)
p=Path('docs/completion-records/HXA-177.md');s=p.read_text().replace('Status: completed','Status: completed\n\n后续变更：本记录中的 Goal 强制绑定完成机制已由 [HXA-178](HXA-178.md) / ADR-0040 替代，以下保留当时验收历史。',1);p.write_text(s)
print('HXA-178 completion recorded from passing evidence')
