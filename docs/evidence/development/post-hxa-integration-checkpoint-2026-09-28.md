# Post-HXA Integration Checkpoint（2026-09-28）

本记录对应 HXA-210/HXA-227 已提交基点之后的本地整合收口。目标是保存 HXA-227 设备补验、Workspace 恢复增量、HXA-230 Global Memory、HXA-222 Local Model 及当前文档事实的可追溯源码基点；不是新的 HXA，不扩大账号、真机、发布或远端 CI 范围。

## 起点与归属

- 起点 HEAD：`c70c44403ff7f78e5347833159723f6d03f585a0`（`feat(workspace): complete HXA-210 and HXA-227 host eval baseline`）。
- 收口前工作树包含 125 个 tracked 变化路径与 83 个 untracked 文件；未发现 `.gguf`、APK、压缩包、日志、临时 build 目录或 `.so` 等意外未跟踪制品。
- 变更横跨 App chat/provider/UI、Room v1、Provider API、Memory、local model native/runtime、device/eval runner 与文档。`ChatService`、Session/Provider/storage 等共享文件同时承载 Workspace recovery、Memory 和 Local Model 集成，因此不为漂亮历史强拆成不能独立构建或不能对应现有证据的中间提交。
- 现有历史 completion/evidence 保留其生成时的“未启动/未提交”语义；当前索引只修正最新事实，不反写历史快照。

## 验证

在未修改功能代码的整合工作树上执行：

- `./scripts/check-all.sh --all`：exit 0，`BUILD SUCCESSFUL`；Gradle 最终输出为 1313 actionable tasks（26 executed / 1287 up-to-date）。
- dependency lock verification：passed（36 files）。
- consumer/developer debug 与 release APK 的 component/process/UID/payload/launcher boundary：passed。
- managed subscription / Runtime boundary / consumer exclusion / normal chat path checks：passed。
- untracked artifact audit：未发现 `.gguf`、`.apk`、`.aab`、`.so`、压缩包、日志或 build/tmp/out 目录进入待提交集合。
- `git diff --check` 在归属审计时通过；暂存阶段发现 `llama-httplib-MIT.txt` 末尾多余空行并在 checkpoint 前修正，随后重新执行 staged/source 检查。

## 边界

- 本记录不替代 HXA-222 真实模型设备证据、HXA-230 设备未验边界、HXA-227 专用 runner/条件跳过说明或 HXA-210 既有专项验收。
- 不执行 GitHub/device CI；不使用真实账号、物理真机，不 push/merge/release。
- Core Engine、Turn/Session owner、Dispatcher、permission/effect truth 保持冻结；本 checkpoint 不启动新的架构重构。
- checkpoint 后按 `docs/development/release-readiness.md` 进入 P1 Memory + Workspace 增量设备验收，再进入 P2 SAF bounded diagnosis。
