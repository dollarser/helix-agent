# M9 rooted Android Emulator 工程实验

更新时间：2026-09-05

本文只记录 HXA-094/095 的 rooted Android Emulator 工程证据，不是完成记录，不替代 rooted
物理真机，也不代表 M9 或发布验收完成。`docs/development/status.md` 未在本分支修改。

## 判定口径

- `adb root` 只改变 adbd/shell 身份，不证明应用 UID 获得 Root。
- 合格实验必须由应用内 libsu 请求经过 Root manager policy，能区分 deny/grant/revoke，并启动
  非 daemon RootService。禁止用 `adb shell su 0` 或 Root policy 数据库写入注入结果。
- 本文的 AVD 结果只称为“模拟器 Root 工程路径”；rooted arm64/API 34+ 物理设备上的
  grant/deny/revoke/loss、RootService crash 与高阶工具仍是正式门禁。

## 可重复环境与供应链

| 项目 | 实测值 |
| --- | --- |
| AVD | `Helix_Verification_Root_API34`，每次 `-wipe-data -no-snapshot-load -no-snapshot-save` |
| SDK package | `system-images;android-34;default;arm64-v8a`；Android CLI catalog `4.0.0`，安装后的 `source.properties` 为 `Pkg.Revision=2`（元数据不一致，二者均保留） |
| 系统 | API 34、`arm64-v8a`、4096-byte page、`userdebug/test-keys` |
| fingerprint | `Android/sdk_phone64_arm64/emu64a:14/UE1A.230829.036.A1/11228894:userdebug/test-keys` |
| Root | 官方 Magisk `v30.7` / 30700，tag commit `e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac`，GPL-3.0 |
| Magisk APK SHA-256 | `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5` |
| 官方 setup | 固定 tag 的 `build.py emulator <APK>`；`build.py` SHA-256 `d509cb1a11e64b7e3e2d01230051bc434cfb1c5d9875a57974233c4853face6e`；`scripts/live_setup.sh` SHA-256 `b3dc430c96718cd4a08e30282c6c57f3da026bc4e75de09e0a426eaed64a1e49` |

Magisk v30.7 tag/commit 未签名；GitHub release API 的 asset digest只能固定已接受 bytes，不能证明
签名发布身份。源码和 APK 仅放临时目录，不提交。未使用已归档第三方 `rootAVD`、共享 patched
image、外来 `su` binary 或现有日常 AVD。官方 setup 是临时安装，AVD 重启后必须重新执行。

官方 AOSP 镜像自带 `/system/xbin/su`，且 `adb root` 后 `id` 为 UID 0；这两项均没有被当作
应用授权证据。Magisk setup 后执行 `adb unroot`，adbd 回到 UID 2000；随后 Helix 测试 APK 的
libsu 请求由 `com.topjohnwu.magisk/.ui.surequest.SuRequestActivity` 与 manager policy 处理。

## 结果矩阵

设备 serial：`emulator-5570`。原始 `am instrument -w -r` 输出同时检查 JUnit `OK (...)`、
`FAILURES!!!`、`INSTRUMENTATION_STATUS_CODE: -2/-4`，不采信 shell exit code。

| 边界 | 结果 | 证据类型与限制 |
| --- | --- | --- |
| unavailable/rootless | 既有 API 29/36 AVD 通过 | 模拟器；见 `m9-non-device-progress.md` |
| deny | PASS：manager prompt 10 秒 timeout 后拒绝，`OK (1 test)`，10.79/11.226 s 两次复现 | 模拟器；显式点击 Deny 尚未形成可自动解析控件的证据 |
| grant + Profile 不触发 `su` | PASS：manager Superuser UI 显示 granted；`OK (3 tests)` | 模拟器；其中 rootless-only immediate-disconnect 1 项为 assumption skip |
| RootService bind + crash/lost | PASS：真实 Binder 取得 root PID，`kill -9` 后收敛 `LOST` | 模拟器 |
| manager revoke/lost | **FAIL**：manager UI Revoke 后等待 30 s，仍为 `GRANTED/CONNECTED`；JUnit `FAILURES!!!` | 模拟器最小复现；不能写成门禁通过 |
| `root.file.read/package.info/process.list/log.read` | PASS：`OK (1 test)`，0.404 s | 模拟器 |
| scope 逃逸 + crash 后 session 失效 | PASS：同一 HXA-095 device test | 模拟器 |
| session timeout/时钟回拨/敏感路径/输出上限/Secret 隔离 | 既有 JVM tests；本轮未伪装成设备实测 | 结构性/JVM 证据 |
| consumer 无 Root；`root.exec` 不存在 | 既有 variant gate/APK scan；本轮未重新跑 | 结构性产物证据，不是发布证据 |

## 撤权缺口与建议修复面

最小复现 fixture 为 `hxa094ExpectedRoot=revoked`：先由 Root manager grant，使 libsu shell 与
RootService 连接；再在 manager UI Revoke；测试等待 `RootGrantState.LOST`。实测 manager 删除了
后续 policy，但现有 cached shell/RootService 未退出，`Shell.isAppGrantedRoot()` 仍为 true。

建议在独立生产修复任务中检查 `LibsuRootAccessDriver.cachedGrant()` 是否能代表活跃授权、manager
撤权是否需要 RootService 侧周期/逐事务校验或 manager-compatible loss signal，并保持“不自动重绑、
不盲目重放”。修复前不得将 manager revoke/loss 写成通过；物理设备必须重复同一最小复现。

后续结构性修复在 App 离开前台时主动销毁已建立的 libsu shell/RootService，并把 `GRANTED` 转为
`LOST`；`REQUESTING` 保持不变，避免切到 Root manager 完成首次授权时自我取消。对应 JVM 测试已通过，
但本轮尚未在重建后的 rooted AVD 重跑 manager revoke fixture，因此上表模拟器结果仍保持 FAIL，
不能用结构性证据覆盖设备证据。

## 专用入口

- `tools/root/scripts/run-hxa094-rooted-emulator-experiment.sh`
- `tools/root/scripts/run-hxa095-rooted-emulator-experiment.sh`

两脚本明确拒绝物理设备；原有 physical-only 脚本的 emulator 拒绝门禁未修改。

## 后续验证边界

API 34 Root 工程实验已建立。API 35、x86_64、16 KiB、PRoot 生命周期、M7 本地真实协议服务与
unsigned/signed release 的证据必须分别执行、分别记录；16 KiB 模拟器不能替代 16 KiB 真机，
本地 fixture 不能冒充第三方生产服务，debug/unsigned release 不能冒充发布证据。
