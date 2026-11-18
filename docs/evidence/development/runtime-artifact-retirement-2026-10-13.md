# 历史独立 Runtime 产物退役

日期：2026-10-13。Owner明确要求删除历史独立 Runtime 产物和库测试宿主，防止再次误用。

## 当前入口

订阅 Runtime 是 Android library，生产服务与登录页合并到 Developer 主 APK 的 `:subscriptions` 私有进程。真实订阅验收使用 `app` 的 Developer APK、配套 AndroidTest APK 和现有 Provider；不安装旧独立包，也不导入宿主 CLI 凭据。

库 AndroidTest APK 是独立、自包含的组件测试宿主，不能代表 Helix 账号、私有进程或 Provider 验收。本次删除本机生成的宿主 APK；有效库测试源码、AAR、锁定元数据和许可证保留。明确运行库组件测试时 Gradle 可以重新生成宿主，不能作为产品 APK 使用。

旧脚本包含独立包安装、账号诊断、hosts 修改和旧 companion 内嵌校验，已不符合当前架构。旧下载门禁还对整个 APK 禁止 native 文件，与现有 PRoot 不符；删除过时入口，不删除锁定输入。现行 `check-all.sh --artifacts`、`verify-integrated-runtime-apks.py` 与 `check-cli-runtime-boundary.sh` 继续核验最终主 APK 的渠道/进程/凭据边界。声明的第三方制品下载哈希与法律审查仍需针对实际交付执行，主机门禁不冒充此类发行验收。

## 删除清单与来源

可恢复的 tracked 脚本来源提交：`80e3a107d9e28ae7a18670454b85e40408f048f1`。生成 APK 已删除，没有保留备份；下列路径用于识别退役对象，不能用于宣称产物可恢复。历史验收报告中的旧命令只作当时证据，不是当前入口。

| 路径 | 类型 |
| --- | --- |
| `scripts/check-cli-runtime-lock.sh` | tracked |
| `scripts/debug/2026-09-10/inspect-subscription-test-ui.py` | tracked |
| `scripts/debug/2026-09-10/run-codex-account-diagnostic.py` | tracked |
| `scripts/debug/2026-09-10/verify-bundled-runtimes.py` | tracked |
| `scripts/debug/2026-09-10/check-subscription-instructions-phone.py` | tracked |
| `scripts/debug/2026-09-10/migrate-phone-dns-to-app.py` | tracked |
| `scripts/debug/2026-09-10/inspect-subscription-pages.py` | tracked |
| `scripts/debug/2026-09-10/verify-phone-installed-artifacts.py` | tracked |
| `scripts/debug/2026-09-10/temporary-phone-hosts.py` | tracked |
| `scripts/debug/2026-09-10/verify-phone-subscription-connection.py` | tracked |
| `runtime/cli-app/build/outputs/apk/release/cli-app-release-unsigned.apk` | 本地产物/临时脚本 |
| `runtime/cli-app/build/outputs/apk/release/output-metadata.json` | 本地产物/临时脚本 |
| `runtime/cli-app/build/outputs/apk/debug/output-metadata.json` | 本地产物/临时脚本 |
| `runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk` | 本地产物/临时脚本 |
| `runtime/cli-app/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物/临时脚本 |
| `runtime/cli-app/build/outputs/apk/androidTest/debug/cli-app-debug-androidTest.apk` | 本地产物/临时脚本 |
| `scripts/debug/2026-10-13/local/hxa282-pilot-models.py` | 本地产物/临时脚本 |
| `scripts/debug/2026-10-13/local/hxa282-model-after.py` | 本地产物/临时脚本 |
| `scripts/debug/2026-10-13/local/codex-profile-readiness.py` | 本地产物/临时脚本 |

## 防误用与验证

自有模拟器 runner 在启动前读取冻结 APK 的实际 manifest，拒绝旧独立 CLI/PRoot 包及其库测试宿主充当产品目标，核对 runner 包名/类名及 instrumentation targetPackage 与实际 App 一致。其他库的正常自测试入口保留。本次清理不安装、卸载或改动设备，设备/真实模型验证为 not requested。

本次共退役 39 个文件：16 个 tracked 旧脚本、4 个旧独立 APK、6 个生成库测试宿主 APK、10 个配套元数据和 3 个本轮失效临时脚本。历史报告保留当时结论与身份，当前源码中的调用和文档入口已更新。

| 当前主机检查 | 结果 |
| --- | --- |
| `check-all.sh --source` | passed：225 项脚本测试、文档、ADR、i18n、Secret |
| 当前 Consumer/Developer 冻结 APK manifest 目标检查 | passed；真实不匹配渠道输入被拒绝 |
| `verify-integrated-runtime-apks.py` | passed：两渠道 Debug 最终 APK 边界 |
| `spotlessCheck :runtime:proot-app:compileDebugAndroidTestKotlin` | passed；初次误用不存在的模块级 Spotless task 失败后，改用现有根任务通过 |
| 保留 dated App runner 的 `sh -n` 和 `git diff --check` | passed |
| 删除后 `runtime/*/build/outputs/apk` 残留与活动脚本独立 APK 引用 | 无残留 |

主机日志在忽略目录 `build/hxa282/runtime-retirement/`；这里的结果只证明本次清理与输入保护，不关闭 HXA-282 的模型对照或连续长稳验收。

## PRoot 与其他 Runtime 库补查

PRoot 同样已合入 Developer 的 `:proot` 私有进程。本次补删旧独立 PRoot APK、各 Runtime 模块生成的库测试宿主 APK，以及依赖旧跨 APK 安装/卸载生命周期的脚本。保留所有有效测试源码、AAR、RootFS 与许可证；库宿主可在明确组件自测试时重建。旧强制 idle 脚本依赖已退役的独立安装流程，不再作为当前入口；forced idle 不等于自然 Doze，验收仍须当前 fixture、明确授权和恢复原设置。

| 路径 | 类型 |
| --- | --- |
| `scripts/accept-hxa-083-lifecycle.sh` | tracked |
| `scripts/accept-hxa-086-lifecycle.sh` | tracked |
| `scripts/accept-hxa-087-updates.sh` | tracked |
| `scripts/accept-hxa-086-forced-idle.py` | tracked |
| `scripts/debug/2026-09-10/run-hxa183-runtime-device.sh` | tracked |
| `scripts/debug/2026-09-10/run-hxa183-proot.py` | tracked |
| `runtime/proot-client/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 |
| `runtime/proot-client/build/outputs/apk/androidTest/debug/proot-client-debug-androidTest.apk` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/release/proot-app-release-unsigned.apk` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/release/output-metadata.json` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/debug/output-metadata.json` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk` | 本地产物 |
| `runtime/proot-app/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 |
| `runtime/cli-client/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 |
| `runtime/cli-client/build/outputs/apk/androidTest/debug/cli-client-debug-androidTest.apk` | 本地产物 |
| `runtime/quickjs/build/outputs/apk/androidTest/debug/quickjs-debug-androidTest.apk` | 本地产物 |
| `runtime/quickjs/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 |
| `runtime/proot-ipc/build/outputs/apk/androidTest/debug/proot-ipc-debug-androidTest.apk` | 本地产物 |
| `runtime/proot-ipc/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 |
