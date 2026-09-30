# 重复压缩后的 UNKNOWN 回答修复与手动登录准备

## 范围与判断

所有者要求处理此前真实 SGLang 重复压缩后回答 `UNKNOWN|NO|PENDING` 的问题，并打开可见模拟器由本人测试 Antigravity 登录。此前摘要仍包含验证码，但没有保存失败轮的完整请求与摘要，不能据此断言唯一根因。

当前提示对“用户陈述”和“独立验证事实”的区别不足。本次在摘要生成与回填资源中明确来源：可以复述用户提供的标识和约束，不要求工具重新验证；验证码与验证成功是不同事实。仍保留不可信数据、不可授予权限、不可虚构执行成功的边界。不硬编码验证码答案，不放宽测试断言。

## 验证

- `build/continuity-host-r2.log`：Developer 单元测试 1084 项、0 failure/error、4 项既有 skip；APK/Test APK、lint、detekt 通过；Spotless 格式已修正。首次格式门禁失败保留在 `build/continuity-host.log`。
- `build/continuity-source.log`：源码门禁通过。
- `build/continuity-api36-r1`：独占 API36 arm64，4 GiB/4 核，真实 SGLang `Qwen3.8-27B`，262144 上下文；完整用例 1/1 PASS（69.097 秒），第二次独立会话 1/1 PASS（56.003 秒）。两轮均未出现 UNKNOWN，分别见 instrumentation.txt 与 repeat-instrumentation.txt。每次包含首次压缩、两轮再压缩和后续验证码/不删除/待验证三项问答。

## 手动账号验收

完成自动回归后另开带窗口模拟器，安装本轮 Developer APK。用户自行输入账号并授权；不读取账号令牌或替用户完成登录，不把页面打开记成登录通过。可见模拟器使用只读临时实例，关闭后本次登录状态不写回基础 AVD。

模型压缩依然具有概率性，有限回归通过不能证明任意历史都不会遗漏。
