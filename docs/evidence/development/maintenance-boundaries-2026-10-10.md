# 历史入口与复杂边界复核

日期：2026-10-10。范围：[HXA-260](../../completion-records/HXA-260.md)，承接系统/测试维护审查；仅主机维护与既有证据复核，没有设备、真实模型或账号操作。

## 路径级清理

仅删除scripts/debug/2026-09-09/extract-chat-responsibilities.py。它按旧ChatService字符串位置改写源码；当前源码没有原完整片段，不能重跑。清理前限定.github/scripts/docs搜索只有审查表中的来源提及，没有当前运行入口。最后修改提交193de8e9，SHA256 `65aedd190b6f3628fffdfc3451aef6c9f25eb85d253185989305cf139891de2c`；删除前与Git原文逐字节核对，并保留同哈希local副本。没有执行旧编辑器。

需要溯源时从Git读取，输出到ignored local，不能恢复成当前迁移入口：

```sh
git show 193de8e9:scripts/debug/2026-09-09/extract-chat-responsibilities.py > scripts/debug/2026-10-10/local/extract-chat-responsibilities-retired.py.txt
```

保留2026-09-09/run-owned-emulator.py兼容shim：实现委托稳定scripts/run-owned-emulator.py，仍有历史命令与host导入测试引用，不是第二套设备引擎。debug README的会话/文件回归入口改为实际保留的日期路径；没有虚构根目录入口。composer/revision协议保留真实PID死亡与恢复，不因测试曾失败而删除。AttachmentE2e的附件、协议、Goal、安全断言并不因文件大成为重复测试，保留；未跟踪其他脚本与唯一失败输出未动，不按日期清目录。

## 构建与复杂类：有依据的保留

CodeGraph有界context定位后核对当前源码；图候选不能证明所有动态调用。

| 范围 | 当前实证与处理 |
| --- | --- |
| root build.gradle.kts | 已用androidLibraries/jvmLibraries集中通用配置，模块特例对应Android/native/运行域与公开API依赖；版本目录与相关注释一致。没有证明需新增convention框架或升级依赖，保留现有接线及锁/校验门禁 |
| ToolDispatcher | 保留schema、capability/policy、会话授权、审批、limits及审计的单一链；已有ToolExecutionPools/activity等协作者。仅修正timeout注释：best-effort interrupt不释放真实容量，直到executor退出。无代码语义更改、无全局写锁 |
| ProotJobRunner | 已有capacity、processOwner及durable store；finally先awaitExit，再释放owners/capacity。输出pump、lease、真实子进程退出需要同一生命周期，不按步骤拆成多个权威owner |
| WorkspaceArtifactStore | scope/root、symlink/quota、document backend与ArtifactSink有现成边界；先文件后登记，登记失败文件仍保留。不能用Room回滚假设文件回滚，保留一致的文件操作facade |
| ProviderService | 探测/状态存储、ProviderConnectionProbe、模型/上下文设置及managed accountSync已分离；剩余服务承担配置/可选性/渠道仲裁，未证明再按每个方法增加use case有收益 |
| ChatService | HXA-257/258已移出完整状态owner与元数据事务；剩余输入身份、动作门禁、模型设置重验、Goal应用仲裁及页面投影有共享原身份。继续因真实重复/瓶颈收敛，不用“最终空壳”或行数作为验收条件 |

参考选择沿用[HXA-259](../../completion-records/HXA-259.md)中的OpenCode职责与Android分层；没有复制参考实现、引入框架或修改运行契约。

## 历史OUTPUT_TOKEN_LIMIT：不同证据不能合并归因

| 记录 | 能证明 | 尚不能证明 |
| --- | --- | --- |
| [近期元数据回归](session-metadata-transactions-2026-10-10.md) | 两个普通回答夹具持久会话额度1；旧APK同样复现；明确自身默认额度后通过 | 全部历史真实模型同样根因 |
| [旧SGLang重复压缩](public-client-sglang-revalidation-2026-09-30.md) | r1实际4287 input/4096 output截断；后续输出余量与摘要质量分别验证，r3输出5062 | 输出扩大就普遍保证摘要质量；r2仍有UNKNOWN失败 |
| [旧Goal/探测诊断](intermittent-closeout-2026-09-28.md) | 原Goal失败仅累计20872，无法拆分逐请求输出/推理；后续固定4096额度三轮通过；探测256是独立小额能力夹具 | 历史截断唯一原因、默认预算缺陷或已根治 |

生产默认现为16,384 token/次；当前错误既可能来自本地有效输出超请求上限，也可能来自provider finishReason=length。输入占上下文，累计用量不再阻止普通Turn。未为获得绿色提高生产默认、自动重试或接受截断工具参数。后续真实复现应保留同一请求身份、实际max_tokens、finishReason、独立input/output/reasoning计数及来源；缺失旧计数无法事后由累计量还原。没有读取凭据、记录模型正文或用历史后续pass掩盖原失败。

## 本轮验证

`python3 scripts/with-host-slot.py -- ./gradlew :tools:framework:test detekt spotlessCheck`退出0，15任务（8执行、7up-to-date），工具框架test实际执行；记录maintenance-boundaries-host.log。两个保留回归入口bash -n退出0；source门禁原153项及文档/ADR/国际化/秘密检查通过，完成状态后另跑closeout记录。日志在ignored build/maintenance-followup-20261010/。设备与真实模型not requested；主机评估不关闭性能、完整取消恢复组合、其他服务/OEM或真机卡顿黑屏。
