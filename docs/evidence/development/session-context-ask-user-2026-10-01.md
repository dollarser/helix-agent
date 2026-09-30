# 会话上下文与 Antigravity 候选回答修复

日期：2026-10-01。当前工作树定向验证，未提交；不是发布或全量 P5 证明。

## 原因与修复

- 手动压缩原先将无足够收益记为失败。现在正常结束，提示保留上下文，不提交摘要 checkpoint；真正失败仍保留错误。摘要正文目标按可移除历史收敛，推理输出余量不因此缩小。
- 圆环只读取当前会话、当前模型/连接的已确认用量。发送期间保留上次值；摘要请求的小输入、失败和未提交压缩不会替代正常会话用量。已提交压缩使用估计值，服务未报告窗口时明确标注窗口估算。
- `ask_user` 已成功执行，但 Antigravity 的私有回放存储以原始工具参数绑定，而 Harness 会剥离 `__helix_intent`。后续请求在本地编码阶段校验失败，甚至影响同一历史上的维护操作。修复为先校验原始私有记录的绑定与完整性，再严格比较业务参数；回填参数去掉展示字段，保留协议签名。账号、模型、调用身份及业务参数变化继续拒绝。

## 主机

- 两渠道 app unit、lint，Developer APK/AndroidTest APK 编译、格式和 detekt：通过，日志 `build/context-usage-final-host.log`。
- Runtime 全部 Debug unit、格式与 detekt：通过，日志 `build/ask-user-replay-tests-r2.log`。AntigravityProtocolTest 11/11，包含进程重开后 stripped 参数回放、业务参数篡改、跨账号/跨模型拒绝。
- `scripts/check-all.sh --source` 与 `git diff --check`：通过。源检查日志 `build/context-ask-source-gate.log`。

## API36 Developer

所有者明确授权当前可见模拟器及有界 Antigravity 真实服务测试。覆盖安装保留账号和用户历史，未清库。

- ContextCompactionDeviceTest、LongTurnCompactionDeviceTest、ContextWindowDeviceTest：22/22，含手动无收益正常结束、不写 checkpoint、不覆盖历史。
- SessionContextUsageDeviceTest：1/1，两个会话共用模型时用量互不覆盖，发送中和摘要失败后保持本会话已有值。
- 真实 Antigravity：旧历史恢复后短回复成功；新 `ask_user` 给出两项候选，实际点击候选并发送，续答成功，Turn 为 COMPLETED。
- 圆环维护入口真实压缩成功：审计 code=COMPLETED，输入估计 before=4276、after=1039；圆环从普通请求的 10439 tokens / 200000 fallback 窗口（≈5%）变为已提交估计 ≈<1%。这次下降来自成功提交，并非摘要调用用量污染。
- 修复前发送过程已观察圆环保留 ≈4%，没有变为问号。当前真实账号只覆盖所选 `gemini-3.8-flash-tiered`；不扩大为其他订阅或模型通过。

设备记录：`build/context-usage-device-r1.txt`、`build/context-session-isolation-device.txt`。用户历史与账号内容未收录。模拟器保留供用户继续测试。

## 后续修订（同日 files.list 诊断）

上述“严格比较业务参数”的阶段性实现仍会拒绝 Harness 合法的 Workspace 路径绑定。后续改为验证原始私有记录和调用身份后，回填 Harness canonical args；不再比较原始模型业务参数与规范化参数。此前测试与设备结果仅证明当时 ask_user 范围，最新 files.list/视觉验收见 `files-vision-readme-2026-10-01.md`。
