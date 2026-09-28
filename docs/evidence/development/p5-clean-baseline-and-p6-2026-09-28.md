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
