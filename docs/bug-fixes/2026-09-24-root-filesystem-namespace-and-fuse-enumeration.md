# Bug Fix: Root 文件管理沙盒命名空间隔离与 FUSE 挂载点枚举失败

Status: fixed
Date: 2026-09-24
Related HXA: HXA-094, HXA-095, HXA-182
Affected modules: tools/root, app/files, app/ui

## Problem

在授予 Root 权限后，用户通过 Helix 文件管理器浏览系统目录时发现：
1. 访问 `/data/data` 仅能看到 `com.helix.agent.developer` 本身目录，无法查看其他已安装应用的私有数据；
2. 访问 `/storage/emulated` 目录内容为空，导致无法通过常规路径进入 `/storage/emulated/0` 及 `/storage/emulated/0/Android/data`。

## Impact

用户在已获取系统 Root 权限的情况下，手动文件管理器的浏览能力被局限在普通应用沙盒与外部存储可见范围内，破坏了“获取 Root 权限后能访问整个文件系统”的用户预期和系统级调试/备份价值。

## Root cause

1. **Android 14 进程挂载命名空间隔离（Mount Namespace Isolation）**：Zygote 在 fork 应用进程时为应用创建了独立的 mount namespace，并在 `/data/data` 上覆盖了仅包含自身包名的隔离挂载视图。`libsu` 默认继承调用进程的 mount namespace，导致即使用户具有 Root 权限，执行的普通 Shell 也处于该受限挂载空间中。
2. **Android MediaProvider FUSE 虚拟文件系统限制**：`/storage/emulated` 为 Android FUSE 守护进程的挂载点。FUSE daemon 针对 `/storage/emulated` 自身的根目录枚举（`opendir`/`readdir`）直接返回 `EACCES` / `Permission denied`（即便 UID 为 0 亦然），只处理 `/storage/emulated/<userId>`（如 `/storage/emulated/0`）的子节点访问。原有的单一 `find $path -maxdepth 1` 命令因此直接返回失败（exit code 1）。
3. **符号链接未解引用**：`find` 默认不对命令行中指定的软链接（如 `/sdcard`、`/etc`）进行目录解引用，导致软链接目录枚举为空。

## Fix and invariants

1. **启用 Global Mount Namespace**：在 `RootFileAccessor` 中通过 `Shell.setDefaultBuilder` 配置 `Shell.FLAG_MOUNT_MASTER`（在执行 su 时附带 `--mount-master` / `-M`），使 Root Shell 运行于系统的全局 mount namespace。
2. **底层持久路径重定向**：在 `RootFileAccessor` 的路径解析层 `resolvePath` 中，将 `/data/data` 智能映射至全版本 Android 共通的底层真实持久数据路径 `/data/user/0`（各 Android 版本的该路径均不受沙盒遮罩影响）。
3. **挂载点探测与软链接解引用**：
   - 针对 `/storage/emulated`，增加智能子节点探测（通过 `/data/media/*` 及 `/storage/emulated/0` 探测存在的用户卷），返回 `0` 等有效用户目录；
   - 在 `find` 中加入 `-H` 参数，自动解引用命令行参数中的符号链接，使 `/sdcard` 和 `/etc` 等能够顺畅直接展开。
4. **变体隔离不变式**：所有 Root Shell 调用与 `libsu` 依赖严格限定于 `:tools:root` 及 developer flavor，consumer 构建中保持 `NoOpRootFileOperations`，不引入任何 `libsu` 符号。操作能力仅限于手动文件管理器，不对 Agent 工具分发系统暴露。

## Alternatives considered

- **强依赖 nsenter**：曾考虑在 shell 命令前统一包裹 `nsenter -t 1 -m`，但不同定制 ROM 和精简环境下的 busybox/toybox 不一定包含 `nsenter` 工具，兼容性不如 libsu 原生支持的 `FLAG_MOUNT_MASTER`。
- **仅引导用户使用 /sdcard**：曾考虑仅让用户从 `/sdcard` 访问内部存储，但破坏了层级导航的一致性，且无法解决用户直觉性从根目录 `/` 下钻 `/storage` 的使用习惯。

## Regression verification

1. **主机验证**：
   - `./gradlew spotlessApply spotlessCheck detekt` 通过；
   - `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`（824 项 developer 单元测试全部通过，含 `FileManagerServiceRootTest` 5 项测试）；
   - `./scripts/verify-variant-boundaries.sh` 与 `./scripts/check-all.sh --source` 100% 通过。
2. **真机验证（OnePlus 6T / Android 14 / APatch）**：
   - 在设备 `561e3b15` 上安装 `app-developer-debug.apk`；
   - 导航至 `/data/data`，成功突破沙盒列出全量 271 个应用的私有目录；
   - 导航至 `/storage/emulated`，成功列出 `0`；进入 `0` -> `Android` -> `data` 完整列出各应用包名目录并支持子文件预览与属性详情查看。

## Residual risk

多用户隔离场景下，非主用户（如双开或工作空间 user 10+）的私有目录需通过其对应的 user id 访问。当前设计已通过自动探测 `/data/media/*` 支持该机制。

## Related records

[ADR-RUNTIME-004 Root Service](../../docs/adr/runtime/004-root-service.md)、[ADR-WORKSPACE-002 独立文件管理与传输恢复](../../docs/adr/workspace/002-manual-files-and-recovery.md)、[HXA-094 完成记录](../../docs/completion-records/HXA-094.md)、[真机验收记录](../../docs/evidence/development/physical-oneplus-acceptance-2026-09-24.md)。
