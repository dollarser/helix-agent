# Runtime 默认资产入口收尾（2026-09-18）

关联 [HXA-193](../../completion-records/HXA-193.md)。本轮不升级或更改 Runtime 二进制锁。

## 根因与修正

默认入口原先从滚动 Alpine 镜像安装所有锁定包。实际复验 `--rebuild-rootfs` 仍退出2：镜像当前只有 `xz-libs 5.8.4-r0` 与 `ca-certificates 20260909-r0`，不能满足旧锁 `5.8.3-r0` / `20260611-r0`。固定包版本与基础镜像 digest 不能让已撤下的仓库内容重新出现。

默认 `scripts/build-proot-assets.sh` 现在使用仓库已发布的 `runtime-assets-20260917` 固定 raw tar，继续校验 `runtime-lock.json` 的 SHA-256、大小及所有 ELF；本地文件和 HTTPS URL 可显式覆盖，覆盖也不绕过校验。CI 调用同一入口，未配置仓库变量也能选择固定归档。`--rebuild-rootfs` 保留显式 Docker 重建，`--generate-lock` 仍走重建；二者不接受归档覆盖，未知参数失败。没有自动切换新依赖、接受新 hash 或从下载失败降级为镜像重建。

这修复的是默认资产准备与可重复安装，不宣称旧包源码重建恢复。未来更新 RootFS 时须恢复包输入供应、更新来源/许可证/锁并重新验收；当前归档下载不构成源码重建证明。下载仍需网络，GitHub 不可用时可显式提供同 hash 的本地归档。

## 本轮实际验证

- 无 `HELIX_ROOTFS_ARCHIVE` / `HELIX_ROOTFS_ARCHIVE_URL` 的默认命令：从公开 Release 下载137287680字节，hash `674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`；360 ELF 全部通过 aarch64/16 KiB 门禁；exit 0。
- `bash scripts/debug/2026-09-18/hxa193/check-asset-inputs.sh`：未知选项、重建与归档冲突、损坏归档均拒绝；exit 0。
- `./scripts/build-proot-assets.sh --rebuild-rootfs`：exit 2，如上两项包冲突；保留失败日志，不作为通过项。
- 原始证据位于忽略的 `build/193-closeout/`。APK 覆盖升级见[两阶段安装证据](apk-replacement-upgrade-2026-09-18.md)，前次远端 CI 见[CI收尾](ci-runtime-assets-2026-09-17.md)；本次尚未推送工作流改动，不称为新一轮远端 CI 已通过。

## 设备回归的环境失败

首轮 API29 全部35项通过；并行启动的 API36 第35项 `IntegratedRuntimeUiDeviceTest` 失败，窗口取证是 Android 系统的 `System UI isn't responding` 对话框；logcat 明确记录 `ANR in com.android.systemui`，不是 Helix 登录页抛异常。该轮34通过/1失败保留在 `build/193-closeout/api36/`，不能计作通过。随后以同一APK、同一35项套件在全新独占 API36 进程单独重跑，最终结果另记，不关闭系统ANR的长期稳定性义务。

单独2核/2GiB重跑也在启动阶段发生系统 Keyguard/GMS/输入法 ANR：KeyguardService waited 20039ms，CPU pressure some avg10=85.68，memory pressure some avg10=13.39；随后系统对话框仍遮挡同一断言。没有产品生产代码变化，故不以延长页面断言或关掉ANR对话框解决。

owned-runner 新增显式 `--memory-mb` / `--cores`（默认仍2048/2）并记录 `emulator-config.json`。API36 用4096/4、新进程完整重跑：`build/193-closeout/api36-functional`，35/35通过，60.182s，closed exit0。API29 原轮35/35通过，201.435s。完成结论是功能矩阵；2GiB系统启动压力/长稳仍独立记录。主机 `check-all.sh --all`、显式AndroidTest编译、Debug/Release APK边界检查均通过。
