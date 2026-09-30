# Antigravity 登录失败定位

用户在可见 API36 模拟器手动登录，脱敏记录为 `login_exchange DNS`。同机 shell 能解析 token/project 域名，应用 INTERNET 已授予；失败时段系统将 Helix UID 记为 `LAST`，后台网络规则受限，而浏览器仍在前台。不能据此判定账号无资格或凭据错误。

修复：浏览器回调的授权码只在进程内暂存，登录 Activity 恢复前台后才启动令牌交换。一次性取出，取消/销毁清除，保持原 state/PKCE 校验；不自动重放已发出的请求，不关闭系统网络保护。主机测试覆盖后台等待、前台立即处理、重复 resume 只执行一次、取消后不执行。补充诊断仅记录允许列表中的错误分类和 HTTP 状态，不打印响应、URL 或令牌；登出标签修正为 Antigravity。

主机证据：`build/antigravity-foreground-host.log`。用户随后在可见 API36 模拟器确认登录成功；生命周期单元测试与该人工真实账号结果分别记录。

## 登录后模型不可选

用户确认登录成功。现场来源仍为 Untested；执行只读账号目录连接检查后 Connected，但已选 `gemini-3.8-flash-tiered` 有 PROTOCOL 生成失败证据，仍被禁用。经用户单独授权的一次短生成测试仍失败，不清除失败记录冒充可用。

代码核对发现流式 endpoint 遗漏 `?alt=sse`，与 SSE 解码器不匹配；本地参考适配器 `dsh-plugin-subscriptions` 的 endpoint 明确带该参数。修正查询参数，新增真实 Request 查询断言，主机结果见 `build/antigravity-sse-host.log`。这解释了一个具体协议缺陷，实际账号修复效果仍待另一次授权复验；不绕过失败状态或账号权限。

补充：所有者另授权一次复验，修正 alt 后仍为 PROTOCOL，故不能把参数修复等同于完整链路修复。当时两次账号调用均已用完；增加固定枚举阶段诊断后，所有者进一步授权按需自主模型测试。

## 真实生成根因与修复

在同一 API36 arm64 可见模拟器、Developer 包、已登录账号和精确模型 `gemini-3.8-flash-tiered` 上继续诊断。第一轮阶段诊断确认 START → ENCODED → HTTP_OK，无解析错误；第二轮新增终止诊断明确为 TOKEN_LIMIT_EMPTY。服务返回合法 MAX_TOKENS，16-token 基础测试在产生内容前已耗尽，应用将空结果误分为 PROTOCOL。该证据不是账号封控。

将单次基础生成预算提高至 2048 tokens，仍只发一次无工具合成短请求，不自动追加付费重试。空 length 结束独立记录 OUTPUT_TOKEN_LIMIT；空 stop、畸形流、缺少终止及真正模型错误仍失败。固定枚举诊断不记录响应正文、请求体、账号身份或凭据。

修复后第三次真实测试返回 HTTP_OK → COMPLETED，界面显示 Basic generation: verified。返回会话模型选择器，该模型 enabled=true，点击后出现选中标记及推理选项；未绕过失败证据或直接修改数据库。能力检测仍未执行，不将基础生成通过外推为工具、视觉或所有模型通过。

主机检查：`build/antigravity-generation-fix-host.log`，core:model、Runtime 与 Developer 单元测试、Developer APK、格式化和 detekt 通过。新增回归覆盖空额度终止的分类、一次调用及实际预算；保留推理流、空结果、错误与终止顺序边界测试。`build/antigravity-generation-final-host.log` 的 Runtime/Consumer 单元测试、Consumer/Developer lint、spotlessCheck、detekt 通过。

第四次请求为真实会话合成短消息，模型回复 `OK.`，HTTP_OK → COMPLETED；无工具调用。强停并重开应用后该请求和回复仍可见。本次自主授权下共四次生成：两次修复前诊断、一次修复后基础验证、一次短聊天。保留可见模拟器和登录状态供用户继续测试；未执行全量能力探测、其他模型、真实手机或发布验收。

源码 gate：`build/antigravity-generation-source.log`，`check-all.sh --source` 通过（文档、ADR、国际化、secret scan 等）；`git diff --check` 通过。本轮修改尚未提交或推送。

## 冷启动模型标识

重开时消息保留，但输入区曾显示“选择模型”。根因是会话投影先于 Provider rows 完成，ChatService 只监听 contextRevision，没有在 rows 就绪时重投影。现在合并监听两者，不改持久绑定和准入规则。`build/antigravity-badge-host.log` 的 Developer 单元测试、应用/AndroidTest APK、格式化及 detekt 通过；安装后冷启动恢复同一模型标签和已生成消息，无额外账号请求。补充设备回归：修改来源显示名称后，已打开会话无需重开即刷新，同时保留原模型 ID。

设备命令：`adb -s <owned-api36> shell am instrument -w -r -e class com.helix.app.chat.ChatSessionLifecycleDeviceTest#providerRefreshUpdatesOpenConversationWithoutReopeningSession com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`。`build/antigravity-provider-refresh-device.txt`：OK (1 test)，1.181s。测试 runner 产生的语言偏好已恢复为测试前的无覆盖状态，未清理账号数据。

最终刷新修复后的 Consumer 单元测试、双渠道 lint、spotlessCheck、detekt 通过（`build/antigravity-badge-final.log`）；源码 gate 复验通过（`build/antigravity-badge-source.log`）。
