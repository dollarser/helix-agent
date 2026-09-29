# P4 首次成功联合旅程（2026-09-28）

本记录对应 `docs/development/release-readiness.md` 的 P4。目标不是新增 onboarding/runtime，而是把已交付的 P3 local install、Session model binding、managed Workspace、Agent loop、Tool dispatch、artifact ownership 和 process reopen 串成一条可重复的 first-success 证据。P4 未修改 production Kotlin/Room/Engine；新增的是验收 fixture、driver 与 owned-emulator runner 的通用 clean-state/setup-arg 能力。

## 固定旅程

`FirstSuccessJourneyDeviceTest` 采用两阶段 instrumentation：

1. setup phase 从 `pm clear` 后的干净 app data 开始；
2. 通过 P3 相同的 local model install/probe orchestration 从 host 受控 HTTP fixture 安装 pinned GGUF；
3. 使用 `ChatService.createSession()` 建立唯一当前 Session，自动获得 managed Workspace；
4. Session 权限设为 FULL_ACCESS，只开放 `write` 与 `read`；ACT / reasoning OFF，bounded Turn budget；
5. 真实模型必须把固定字符串 `HELIX_FIRST_SUCCESS_V1` 写到当前 Workspace 的 `first-success.txt`，再 read 同一路径；
6. Harness/Room 必须落成 terminal `COMPLETED`，`write`/`read` 各恰好一次并为 `COMPLETED`；写入工具必须自动登记 turn-owned artifact，文件 SHA 与 artifact SHA 一致；
7. setup 保存 Session/model/Workspace/Turn/message/model-call/tool-call/artifact/PID 快照后主动 `Process.killProcess`；
8. verify phase 在新 PID 打开同一 Session，只读验证 model binding、Workspace binding/revision、conversation messages、tool timeline、artifact 与文件 bytes；再等待 2 秒，确认没有新增 Turn/model call/tool call/message 或重复文件副作用。

`run-owned-emulator.py` 为此新增两个通用 runner contract：`--clear-app-data` 在 instrumentation 前清除目标 app data；`--instrument-arg` 现在同样透传给 recovery setup。它仍拒绝借用既有 serial，并在 finally 中只关闭自己创建的 emulator process group。

## 0.6B bounded 对照：任务未成功

先使用 P3 默认的 `Qwen3-0.6B-Q4_K_M.gguf` 跑同一最小任务。安装与 real connection/capability probe 先通过，Session/Workspace 也正常建立，但 Turn 在第一 model call 结束：

- state：`FAILED`
- error：`LOCAL_OUTPUT_LIMIT`
- stepCount：1
- model usage：`inputTokens=1307, outputTokens=512`
- tool calls：0

这不是 Workspace、Tool executor 或 process recovery failure；模型在产生第一个 tool call 前就耗尽 output budget。P4 没有通过放宽 Harness truth 或修改 production 来“修复”它。该事实进入 P5 的模型质量/效率基线。

## 4B first-success：通过

成功轮输出目录：`build/p4-first-success/api36-developer-4b/`。

- 模型：Qwen3 4B Instruct 2507 Q4_K_M
- model SHA-256：`3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597`
- model bytes：2,497,281,120
- AVD：`Helix_HXA210_API36`，API 36，arm64-v8a，density 400；成功命令使用 8 GiB emulator memory / 4 cores。
- developer app APK SHA-256：`419dd9b2636a86ae340f80bc9ab2012cabbe60c9d388c0277e69ba8c9b91b6ec`
- developer AndroidTest APK SHA-256：`760d1749ea265396baaaa4c203e2b87577e513ac89382a34905de7f7d3d60ac6`
- HTTP fixture：单次 fresh `200` 下载；权重来自已存在、再次 size/SHA 校验的本地 pinned 文件，没有重新拉公网模型。
- setup PID：2771；setup 以 `Process crashed` 结束，符合 runner 的预期 process-kill boundary。
- verify PID：5560；与 setup PID 不同。
- Session：`3d382d911a5184488fcb6d7b1b118aae`
- Turn：`01c6121ff11e744c33c292cba5fe09d6`，terminal `COMPLETED`
- Workspace：`ws-ceb0447d-f299-4da8-854b-8cded5abe33a`，revision 1
- Tool calls：2；`write:COMPLETED`、`read:COMPLETED`
- Model calls：3
- Persisted messages：6
- Artifact：`art_8947bcdc342944b8a5833593528f7065`
- Artifact/file SHA-256：`40f35e0627f733e4472a1744c0a377c6e06caada86f21426d42c785b76394219`
- verify instrumentation：`OK (1 test)`
- 2 秒 duplicate guard：Turn 仍 1 条，tool/model/message 数不变，文件内容仍为固定字符串，`duplicateSideEffect=false`。
- runner：`closed.json` exit 0；最终 `adb devices` 为空。

## 工程门禁

P4 fixture/runner/docs 收口后：

- `./scripts/check-all.sh --source`：passed；592 Markdown files、35 current ADR、三语言 1782 resource keys parity、secret scan 均通过。
- `./scripts/check-all.sh --all`：`BUILD SUCCESSFUL`，1313 actionable tasks（30 executed / 1283 up-to-date）。
- dependency lock verification：36 files passed。
- consumer/developer debug 与 release APK component/process/UID/payload/launcher boundary：passed。
- variant boundary、managed subscription / Runtime boundary / consumer exclusion / normal chat path checks：passed。
- P4 没有修改 production Kotlin/Room/Engine；完整 gate 主要证明新增 AndroidTest 与 runner 增强没有破坏现有工程基线。

## 归因与边界

- P4 证明的是当前本地产品组合闭环，不是 UI 手工点击每一个控件的录屏，也不是 4B 的普遍 Agent 质量结论。安装/会话选择 UI 在 P3 已单独通过；P4 复用同一 production orchestration 与正式 `ChatService.createSession`/Tool pipeline。
- 没有 production 代码缺陷因 P4 被发现或修改。第一轮 draft helper 失败是测试绕过正常 materialization 状态机；改为仓库大量 eval/device journey 已使用的 `createSession()` production 入口后消失。
- 0.6B 的 `LOCAL_OUTPUT_LIMIT` 与 4B 的成功必须分开记账：runtime/probe success != task correctness。
- 未覆盖物理真机/OEM、热压、Doze、真实蜂窝/Wi-Fi CDN、长时间运行或广泛任务质量；这些进入 P5/P7，而不是反向扩张 P4。
