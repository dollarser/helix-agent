# Goal 默认预算与合并后 P5 定向验证

所有者授权继续 P5、增大真实使用的 Goal 默认预算，并允许验证后本地提交、继续正式基线；不推送。当前主工作目录为 `refactor/clean-slate-engine`。

## 产品变更

未自定义的新 Goal：128→512 次模型调用，256→2,048 次工具调用，4M→32M 累计 token，2→8 小时累计执行时间，30 分钟→2 小时单次运行时间。自动重试仍为 0。已保存默认预算和现有 Goal 不自动扩额，权限、审批、UNKNOWN、Turn/模型单次限制不变。新增持久化回归确认旧的显式预算重开后不被放大。

## 主机验证

双渠道 app 单测、lint、debug APK、AndroidTest APK、spotlessCheck、detekt 和 `scripts/check-all.sh --source` 通过。日志：`build/goal-budget-host.log`、`build/goal-budget-full-host.log`、`build/goal-budget-source.log`，developer APK/test APK 构建另见定向 runner 日志。测试范围不代表完整全模块单测或发行验收。

## API36 developer 定向验证

本轮所有者继续既定路线，使用独占 API36 arm64 模拟器、4 GiB RAM、4 KiB page，SGLang Qwen3.8-27B / OpenAI Chat。Provider smoke 1/1，定向固定 case 5/5 通过；runner 正常退出并关闭自己启动的模拟器。原始记录在 `build/p5-merged-targeted-20260928/`。

- `js-002`：Tool FAILED，audit TIMEOUT、isolated=true，Turn COMPLETED；不把模型的解释当副作用证明。
- `js-004`：Tool/Turn NEEDS_REVIEW，audit CANCELLED_AFTER_START；未确认退出时继续保留核查。
- `goal-001`：Turn COMPLETED，Goal PAUSED，1 次模型调用、0 工具调用，只给 checkpoint 计划。原 4,096 单次输出上限未修改。
- `goal-002`：累计模型调用 1→2，之后 GOAL_BUDGET_LIMIT；剩余一次调用实际执行，未退款或重置累计用量。
- `goal-003`：get_goal COMPLETED、write AWAITING_APPROVAL，文件未写入；Goal RUNNING 不冒充持久 PAUSED。

身份：base `2dc8f5b2` 加本轮预算变更，dirty candidate；source manifest `277539e78fc80cdadadab46aa2c240aeb0c07e98bf2f051cfa011e0ef0725d82`，app APK `fa089fefd7d269d0d7a085cdcab1c77dc58c349edbf4907abc0a99d2d4eaf88d`，test APK `bd0ea1993945118610a7ef223561164bef8f0c844b9c00d49ec59324a831ff3d`。仅子集，不是正式完整 P5。

历史 goal-001 OUTPUT_TOKEN_LIMIT 本次未复现，不宣称已定位或修复，也不改变 oracle/fixture 上限来获得通过。产品 Goal 总预算调整与该次单响应截断是不同维度；下一步在 clean 提交上执行一次完整 15-case，保留失败与随机性边界。真机、真实外部账号未使用。
