# P5 clean 基线与 P6 提示候选

## 固定基线 A

2026-09-28，在 clean `b436247f`、API36 developer arm64、4 GiB/4 cores、4 KiB page 的独占模拟器执行一次完整 P5，Provider smoke 1/1；**14 PASS / 1 FAIL / 0 fixture ERROR**。runner 非零退出，关闭自己启动的模拟器；不重跑直至通过。

Files 4/4、JavaScript 4/4、Skills 3/4、Goal 3/3。原始记录 `build/p5-clean-baseline-20260928/`。source manifest `277539e78fc80cdadadab46aa2c240aeb0c07e98bf2f051cfa011e0ef0725d82`；app APK `fa089fefd7d269d0d7a085cdcab1c77dc58c349edbf4907abc0a99d2d4eaf88d`；test APK `bd0ea1993945118610a7ef223561164bef8f0c844b9c00d49ec59324a831ff3d`。

性能覆盖有 elapsed 的 12 项，mean 11,300.8 ms、median 4,929 ms、p95/max 50,552 ms。三个 Goal 没有同口径 elapsed，不混入平均，也不把这个数字称为模型推理速度。

`skill-003`：host `archiveRefused=true`，Turn COMPLETED，但模型仍调用 files.list、skills.list、code.linux.run；最后一项被 DENIED。拒绝和隔离权限有效，失败的是“已有导入拒绝事实时仅报告，不额外执行”的任务质量要求。oracle 仍要求所有实际调用均为 READ_ONLY，不因 DENIED 而豁免。

`goal-001` 原 4,096 输出限制不变，本次正常产出 checkpoint，get_goal/update_goal 均完成，Goal COMPLETED 与“生成计划”这一目标一致，不表示审计已执行。历史 OUTPUT_TOKEN_LIMIT 保留，尚无直接证据证明具体成因。Goal 总预算变更不能解释此单响应截断。

## P6 候选 B（执行前约定）

只修改 packaged base prompt：已有证据足够时直接回答；报告完成/拒绝结果时，不为报告而重做或更换执行方法；显式修复请求仍允许在权限内解决可恢复问题。不增加硬性工具次数限制，不改变权限、工具 schema、Goal/Turn budget、fixture、oracle 或数据集。

使用同一 SGLang Qwen3.8-27B、OpenAI Chat override、设备配置和全部 15 项执行一次候选对照。保留所有结果；若发生其他质量回退不得宣称整体通过。一次对照只证明该样本表现，不足以给出统计稳定性或因果提速结论。

## 候选 B 实测结果

clean `99b7bee7` 的完整对照 **15 PASS / 0 FAIL / 0 fixture ERROR**，Provider smoke 1/1；runner 正常退出并关闭自己的模拟器。原始记录在 `build/p6-prompt-candidate-20260928/`，摘要见 [candidate summary](p6-prompt-candidate-2026-09-28/summary.json)，A 见 [baseline summary](p5-clean-baseline-2026-09-28/summary.json)。test APK 完全相同；app APK `a401ef07994bf7fbb08c36bd78e07cb8b1aaf434265102ac7fc92e38c5c40202`，source manifest `4f84cff302c3deb5431e42f3c994524d274ee63badf2c8920bae597e1675bcc3`。

| 同口径 12 项 elapsed | A | B |
| --- | ---: | ---: |
| mean | 11,300.8 ms | 7,779.9 ms |
| median | 4,929 ms | 4,652 ms |
| p95 / max | 50,552 ms | 22,165 ms |

不把单次同向变化当统计提速结论。skill-001 的 11 次必要调用未减少；skill-002 从 2 次调用变为 3 次。skill-003 从 Linux DENIED 改为仅只读调用，oracle 通过，但仍有 files.list / skills.list / files.search / files.list 四次调用，末次 FAILED；不宣称工具漫游已经消除。提示候选保留，后续需要多次独立样本才能声称稳定性收益，不为扩大分数再修改 oracle。

Goal 三项通过：goal-001 PAUSED、无工具调用；goal-002 累计真实调用 1→2 后 BLOCKED；goal-003 write AWAITING_APPROVAL、无写入。历史 goal-001 截断未再现但根因仍未确定，不删除旧失败记录。P5 基线与这一轮 P6 有界对照完成，不代表全产品稳定性验收。

最终提示装配测试、双渠道 app 单测/lint/debug APK/test APK、spotlessCheck、detekt、source gate 通过；日志 `build/p6-prompt-host.log`、`build/p6-final-host.log`、`build/p6-prompt-source.log`。没有修改 Dispatcher、权限/effect owner、MAX_TOOLS、业务 schema 或固定评测预算。未推送、未运行真机或真实账号验收。
