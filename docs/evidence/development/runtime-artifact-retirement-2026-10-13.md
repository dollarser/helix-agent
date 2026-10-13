# 历史独立 Runtime 产物退役

日期：2026-10-13。Owner明确要求删除历史独立 Runtime 产物和库测试宿主，防止再次误用。

## 当前入口

订阅 Runtime 是 Android library，生产服务与登录页合并到 Developer 主 APK 的 `:subscriptions` 私有进程。真实订阅验收使用 `app` 的 Developer APK、配套 AndroidTest APK 和现有 Provider；不安装旧独立包，也不导入宿主 CLI 凭据。

库 AndroidTest APK 是独立、自包含的组件测试宿主，不能代表 Helix 账号、私有进程或 Provider 验收。本次删除本机生成的宿主 APK；有效库测试源码、AAR、锁定元数据和许可证保留。明确运行库组件测试时 Gradle 可以重新生成宿主，不能作为产品 APK 使用。

旧脚本包含独立包安装、账号诊断、hosts 修改和旧 companion 内嵌校验，已不符合当前架构。旧下载门禁还对整个 APK 禁止 native 文件，与现有 PRoot 不符；删除过时入口，不删除锁定输入。现行 `check-all.sh --artifacts`、`verify-integrated-runtime-apks.py` 与 `check-cli-runtime-boundary.sh` 继续核验最终主 APK 的渠道/进程/凭据边界。声明的第三方制品下载哈希与法律审查仍需针对实际交付执行，主机门禁不冒充此类发行验收。

## 删除清单与来源

可恢复的 tracked 脚本来源提交：`80e3a107d9e28ae7a18670454b85e40408f048f1`。生成 APK 已删除，没有保留备份；下列哈希仅作来源识别，不能用于宣称可恢复。历史验收报告中的旧命令只作当时证据，不是当前入口。

| 路径 | 类型 | 删除前 SHA-256 |
| --- | --- | --- |
| `scripts/check-cli-runtime-lock.sh` | tracked | `e5cb4791f9080cae112ba6db6cc2133e5542640005ae358857dd2742807765f5` |
| `scripts/debug/2026-09-10/inspect-subscription-test-ui.py` | tracked | `06b82995e4159d014881108cb2a9a36fe6e1aa56e9d558eb78cf474c0d2ba04f` |
| `scripts/debug/2026-09-10/run-codex-account-diagnostic.py` | tracked | `7747ffcd5d889108c7f9b48d739f48ddd08db2c2aff5dc1e5b12db0ce06b3582` |
| `scripts/debug/2026-09-10/verify-bundled-runtimes.py` | tracked | `2b7195bcf4d01e28384a5bb1b90afd59f11214c0eb652e5e0edf1c2d1cb0f8bf` |
| `scripts/debug/2026-09-10/check-subscription-instructions-phone.py` | tracked | `0232831c8d829289f3dad038e5e84b3fb8e8c515474ec5d7485cbcd3dbb52ace` |
| `scripts/debug/2026-09-10/migrate-phone-dns-to-app.py` | tracked | `3e1fd3b46b5cd50c41cc614d006fb4e45e04a8b18a5c7769dde204b830afb8ef` |
| `scripts/debug/2026-09-10/inspect-subscription-pages.py` | tracked | `0b333faecdac079758e26450259410b256d7a745f99ef96cc724867025814867` |
| `scripts/debug/2026-09-10/verify-phone-installed-artifacts.py` | tracked | `f9a63b20a38e6137e84ac3f31e53661e04c5a560eea117f4136e85fc12f4c7ad` |
| `scripts/debug/2026-09-10/temporary-phone-hosts.py` | tracked | `5f132706d93bd29328840c83d19dec8c7e44b07fc42d0ef10335b7faa6b822fe` |
| `scripts/debug/2026-09-10/verify-phone-subscription-connection.py` | tracked | `ad568f4d672c0616bfda72647d883727680799ff2625b1afcb74632e67d79c1e` |
| `runtime/cli-app/build/outputs/apk/release/cli-app-release-unsigned.apk` | 本地产物/临时脚本 | `25d25a83df9903decd60598bff3c535e12166243c6afc4bc12ead13476113d40` |
| `runtime/cli-app/build/outputs/apk/release/output-metadata.json` | 本地产物/临时脚本 | `4f9aafbdfb1b3c036a15442a51a185ac986a9b9c9b4d5240947c2b9d4b96c54f` |
| `runtime/cli-app/build/outputs/apk/debug/output-metadata.json` | 本地产物/临时脚本 | `d158827fce560bae08f2e3804ee7788274f8788d61355b33fe060d1604ef0463` |
| `runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk` | 本地产物/临时脚本 | `7260291a870288ca9ef7fff3e9c7833ca33c5f9a368feeca36f6f9f4235aa359` |
| `runtime/cli-app/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物/临时脚本 | `b045e48c51a010d71d53c70ccc6abb4fe584c3f26fbd6d7860d4593b24a6ccf0` |
| `runtime/cli-app/build/outputs/apk/androidTest/debug/cli-app-debug-androidTest.apk` | 本地产物/临时脚本 | `4c6ee839de6df631d2a7bf43273aba0fa8a935b3fae5bb932e36cb3b8b531fb2` |
| `scripts/debug/2026-10-13/local/hxa282-pilot-models.py` | 本地产物/临时脚本 | `dd0b316c1a04ef69ded1c24f23f8e1f3da995bf7a3bd722c47eb433d0816ab67` |
| `scripts/debug/2026-10-13/local/hxa282-model-after.py` | 本地产物/临时脚本 | `5010bddcc6f0a3b1587ea7eca7015b4fd45ed224876f6f9156bb47dd0c858d80` |
| `scripts/debug/2026-10-13/local/codex-profile-readiness.py` | 本地产物/临时脚本 | `f831dc9547078914d1e6745aff56ae0917ef5f5cbde88bd5084b83a97cf70dd9` |

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

| 路径 | 类型 | 删除前 SHA-256 |
| --- | --- | --- |
| `scripts/accept-hxa-083-lifecycle.sh` | tracked | `f43253c48982c8ad01706b6618aa5daf98e34c6ccba4e4998319b4957fda55fc` |
| `scripts/accept-hxa-086-lifecycle.sh` | tracked | `ccdc224f1a6686af20ba23bb8b7dda87c8ed408a3a6a5e7ef478181e6a7bd9d8` |
| `scripts/accept-hxa-087-updates.sh` | tracked | `0b70306a927471ebb91beb2e192f93cdf8f82ba313c7e56aeb7d53d698e4fa90` |
| `scripts/accept-hxa-086-forced-idle.py` | tracked | `43cce38d818ac0572554b57a2920324416fa6cdb31245a8786f9a5884259c243` |
| `scripts/debug/2026-09-10/run-hxa183-runtime-device.sh` | tracked | `59354cd8a2e6f9023533fda7bbb07db048b78ce3dbb61a8e0d937acbd95a68f8` |
| `scripts/debug/2026-09-10/run-hxa183-proot.py` | tracked | `ae42ae5784c1e87ed4739ee7d8486abeb26312b2a09c7e8ce825b030268040ec` |
| `runtime/proot-client/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 | `c27a8082e257d76b0b638c139472c801b13d12685b48af47135e753f2e765c9c` |
| `runtime/proot-client/build/outputs/apk/androidTest/debug/proot-client-debug-androidTest.apk` | 本地产物 | `5bff85385ebe6a0415f0217c65b85c2c240fcdeb5229512205bbe0ad08cb9da2` |
| `runtime/proot-app/build/outputs/apk/release/proot-app-release-unsigned.apk` | 本地产物 | `c962a4e9bb0a46ed23e5667268ffdb0c8e03ca8d3dd17d53e06e9ec4fcef4bc2` |
| `runtime/proot-app/build/outputs/apk/release/output-metadata.json` | 本地产物 | `2cd42de95c0de95903a8125afee09706f15ae9cc0e620688993ca65eda88fabd` |
| `runtime/proot-app/build/outputs/apk/debug/output-metadata.json` | 本地产物 | `0d0b413e794e6562d89dbae0e7366adde54028b188498aa28456dc72b5b11fd0` |
| `runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk` | 本地产物 | `22903e0008e0db67d8bb4edc9d426024e338a419cd92ce626294b10d69cbba7e` |
| `runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk` | 本地产物 | `dfb0f530699c530b65a26ca4c2ec6bcc7a818412015fcb15a97054f5e86bc1ca` |
| `runtime/proot-app/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 | `2011c4b1dc2c7af3b4f70fc37b527ee62848d041c00adb882178058206427b8a` |
| `runtime/cli-client/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 | `18c84afc6358e2f27df47fef9a21260920a513d4231af88b8b58ea6b2e6f6cfb` |
| `runtime/cli-client/build/outputs/apk/androidTest/debug/cli-client-debug-androidTest.apk` | 本地产物 | `12fc4e151f15e56bcffb5d9795f6845f670aa756d1f9b573b11f1c92a3dc3955` |
| `runtime/quickjs/build/outputs/apk/androidTest/debug/quickjs-debug-androidTest.apk` | 本地产物 | `a903247b5355264537977e8c435b6552de49ac7b3feb77530c6e84aa0cd07d9d` |
| `runtime/quickjs/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 | `bfd1214b6084ec3c64e4be6fa536844456a5ef85249098ff5cd2d71ce36b664b` |
| `runtime/proot-ipc/build/outputs/apk/androidTest/debug/proot-ipc-debug-androidTest.apk` | 本地产物 | `b89f6d381fc0d2b89a68dbb8bf6e1bdcdfc95db829de58c1c08296daa0f86dd4` |
| `runtime/proot-ipc/build/outputs/apk/androidTest/debug/output-metadata.json` | 本地产物 | `ee04898105d9a9b8c39de8864dda22e687d270d790585ad7f58797ce3536de61` |
