# 内置 Subscriptions / PRoot 安装器

日期：2026-09-10。HXA-190 用户追加范围，决定见 [ADR-0047](../adr/0047-bundled-companion-installers.md)。

## 使用方式

当前 developer 主应用的设置页 → 内置组件 → 安装 / 更新 Subscriptions 或 PRoot。允许安装来源后，进入 Android 安装确认。安装完返回主应用；PRoot 使用原初始化/验证入口，Subscriptions 使用原登录入口。没有自动绑定、清除数据或 Root 静默安装。

同类型构建输出自动进入 `assets/companions/subscriptions.apk` 与 `proot.apk`。主应用校验固定包名与相同非空签名；系统负责实际安装和升级兼容性。缓存使用专用目录与独立 FileProvider，原文件共享 provider 不受影响。

Debug 当前可直接构建。Release 必须先配置主应用及两个组件的同一发行签名再打包；单独对最终主包补签无法修复其内部 unsigned APK。当前 consumer 沿用原能力边界，不携带两个包或安装权限；不是新的商店可上架承诺。

## 验证

主机脚本 `scripts/debug/2026-09-10/verify-bundled-runtimes.py`：目标构建、签名策略回归、双 flavor lint、主包嵌入字节与实际本次组件输出逐字节相同、consumer 无 companion assets。已通过：

- `./gradlew :app:testDeveloperDebugUnitTest --tests '*RuntimeApkPolicyTest'`：4 项通过，0 失败/跳过。
- `./gradlew :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:lintDeveloperDebug :app:lintConsumerDebug`：通过。
- 内置 Subscriptions 9094021 bytes、PRoot 59990755 bytes，与本次组件输出逐字节相同；`apksigner verify --print-certs` 确认三包签名有效且一致。
- consumer 无两个 APK 或安装权限；developer 的新安装 provider 与原文件共享 provider 同时保留。
- 国际化、ADR（47 条）、文档与 `git diff --check` 通过。developer 主 APK 约 111 MiB。

上述验证基于当时的工作区；未安装到设备。2026-09-11 将安装器及必要 UI 布局依赖单独提交，其他在途改动保留在工作区；未推送。

设备尚未操作，人工检查：首次安装来源拒绝/允许、取消安装、已装组件覆盖更新与数据保留、PRoot 初始化/验证、无网络时从主包安装。签名不一致不应进入系统安装；不得通过卸载清数据使升级通过。
