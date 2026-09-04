# ADR-0019: 采用 libsu 6.0.0 与受限 JitPack 作为 RootService 底座

Status: accepted
Date: 2026-09-05
HXA: HXA-094
Deciders: Project owner（M9 启动说明固定 libsu 6.0.0、JitPack exclusive content 与依赖 ADR）
Supersedes: none
Superseded by: none

## Context

Helix 的 developer/Advanced Root 能力需要在用户主动请求后取得 Root shell，并通过 Binder
承载高层、结构化 Root 操作；状态查询、Profile 切换和普通 Registry 刷新不得触发 Root
管理器弹窗。RootService 是更高权限执行域而不是安全沙箱，后续工具必须把 scope、Policy、
Approval、输出预算和 Secret 隔离留在 Helix 控制面。

HXA-094 对上游与已解析 artifact 的核验得到以下事实：

- `topjohnwu/libsu` 的 `6.0.0` 是轻量 Git tag，指向 commit
  `8c3e80ffe466b89ff875471e542d77e6a1b480c5`；上游 README 声明 `core` 提供 Root shell，
  `service` 提供 Binder RootService。
- JitPack 解析出的坐标是 `com.github.topjohnwu.libsu:core:6.0.0` 与
  `com.github.topjohnwu.libsu:service:6.0.0`；`service` 只传递依赖同版本 `core`。
- 两个 AAR 均不含 native library。`service` AAR 包含 3,656-byte `assets/main.jar`，其
  SHA-256 为 `ae7fdb8b70bd2c1cc2c27043e985f8dc5cfff2cb9142a1576dcdbc889763ef7b`；该 bootstrap
  会进入 Root 执行域，因此必须与整个 AAR 一起校验，不能在构建时静默替换。
- 上游许可证为 Apache-2.0，tag 根目录有 `LICENSE`、没有独立 `NOTICE`；Helix 在
  `THIRD_PARTY_NOTICES.md` 记录组件、来源、归属、许可证和已解析 AAR checksum。
- 两台 rootless M9 AVD 上，真实 libsu 6.0.0 验证了状态/Profile 观察不创建 shell，只有
  显式请求才创建 shell；无 Root 授权时不绑定 RootService。rooted 专用实机尚不可用，
  因而 grant、真实撤权和 RootService crash 仍是 HXA-094 未通过的设备门禁。

JitPack 是按 Git ref 构建并托管 artifact 的额外供应链节点，且 6.0.0 tag 本身没有签名。
仅固定版本字符串不足以抵御远端 artifact 漂移，必须同时固定解析 group、lock 和 checksum。

## Decision

采用 libsu `6.0.0` 的 `core` + `service` 作为 Helix Root shell/RootService 底座，并遵守
以下边界：

1. 依赖只由 developer-only 的 `:tools:root` 解析；consumer runtime classpath 不含 libsu。
2. `settings.gradle.kts` 中的 JitPack repository 使用 `exclusiveContent`，唯一允许 group 为
   `com.github.topjohnwu.libsu`。其他 JitPack group、HTTP Maven 与 `flatDir` 仍不允许。
3. 版本固定为 `6.0.0`，提交 `tools/root/gradle.lockfile`、受影响的 developer app lock 和
   `gradle/verification-metadata.xml`。Gradle 校验 AAR 和 module metadata 的 SHA-256；任何
   checksum 变化都必须作为供应链事件人工审查，不能自动接受。
4. Root shell 只能由显式用户“请求 Root”动作创建。状态查询、构造 adapter、Profile 切换、
   App 启动和 Tool Registry 刷新不得调用 `Shell.getShell()` 或绑定 RootService。
5. 使用非 daemon RootService；客户端 Binder 丢失、RootService crash、grant 实时失效或用户
   断开时不自动重绑、不盲目重放。HXA-094 Spike 只交换进程 ID 以验证 IPC 生命周期，不提供
   任意命令或文件操作；业务工具由 HXA-095 另行实现并验收。
6. 本决定接受依赖与底座方向，不代表 Root 产品验收通过。HXA-094 在专用 rooted 实机完成
   grant/deny/revoke/crash 之前保持进行中，HXA-095 不得提前开始。

当前接受的是 JitPack 路径。若该路径不再可接受，候选替代是仓库内维护经审查的 6.0.0
源码镜像及 notice；启用替代前必须由新的 ADR 获得授权，不能临时更换 Root 库。

## Alternatives considered

1. **自行直接调用 `su` 并维护 RootService bootstrap。** 可移除 JitPack，但需要自行处理不同
   Root 环境、shell 生命周期、Binder bootstrap、多用户与进程回收，且容易让命令字符串散落
   到业务层；不选。
2. **选择另一个 Root 库。** 没有证据证明低维护度替代项能提供同等的 shell 与 Binder
   RootService 生命周期，也会背离已经明确固定的 M9 技术路线；不选。
3. **把 libsu 6.0.0 源码镜像进仓库。** 可减少 JitPack 可用性与 artifact 漂移风险，但 Helix
   将承担源码审查、补丁、构建复现和长期同步成本。只有 JitPack 被拒绝、停止服务、artifact
   无法稳定复现或供应链政策禁止时才重新选择。
4. **直接开放 libsu Shell 给 Agent 或实现 `root.exec`。** Spike 最快，但会绕过高层参数化工具
   与 L3 精确审批边界；HXA-094 不实现，HXA-096 也只有明确产品必要性时才评估。

## Consequences

- 收益：使用经过公开维护的 Root shell 与 Binder RootService 生命周期，不自行适配 Root
  管理器协议；后续 HXA-095 可以把高层只读操作放在独立 Root 进程。
- 收益：JitPack 的可见范围缩至一个 group，版本、lock 和 artifact checksum 三层固定；
  consumer 产物不携带 libsu。
- 代价：构建依赖 JitPack 的可用性与其基于 tag 的构建产物；上游 lightweight tag 未签名，
  Gradle verification 只能检测与已接受 bytes 的差异，不能证明上游发布身份。
- 代价：`service` AAR 的 `assets/main.jar` 将以 Root 权限 bootstrap，升级或 checksum 漂移必须
  视为高敏供应链变更并重新完成源码/artifact/设备审计。
- 约束：libsu 探测到可执行 `su` 仍不等于授权；Helix 只有在显式请求返回 Root shell 且
  RootService Binder 建立后才报告 `GRANTED`。非 Root 结果报告拒绝，不伪装为授权。
- 约束：当前 rootless 设备证据不能替代 rooted 实机上的 Root 管理器 grant/deny/revoke、
  Binder death、RootService crash 和多 OEM 行为。

## Verification

已执行：

- `git ls-remote https://github.com/topjohnwu/libsu.git 'refs/tags/*6.0.0*'`：tag 指向
  `8c3e80ffe466b89ff875471e542d77e6a1b480c5`；浅克隆后 `git cat-file -t 6.0.0` 返回
  `commit`，确认是 lightweight tag。
- `./gradlew --write-verification-metadata sha256 :tools:root:dependencies --write-locks`：解析
  `core:6.0.0` 与 `service:6.0.0`，并写入 lock/verification metadata。
- `--refresh-dependencies ... dependencyInsight --configuration debugRuntimeClasspath --info`：
  Gradle 报告两个模块的 POM/module metadata 均来自 `https://jitpack.io/com/github/topjohnwu/libsu/`。
- 已解析 SHA-256：`core-6.0.0.aar` =
  `a1ca5a8adb9ab11c42b71fc2d2a61b5a95cb4cdd06df0eb8c204c06813c2bb5b`；
  `service-6.0.0.aar` =
  `528bbcc3f057e8b5ea6f69a2deea1e0ff536880602e99e98763d7b74cf1d0659`；对应 module metadata =
  `0d87c4308c16150fb709adb03224532541b2c9e3faa487917aa989c0c0421ffa` 与
  `03e47387558cacf7ccbfd6908d17833a099ea1e8d9f17db8d68be85484ce9466`。
- `:app:dependencies`：`consumerDebugRuntimeClasspath` 无 `:tools:root`/libsu；
  `developerDebugRuntimeClasspath` 只经 `:tools:root` 解析固定的两个模块。
- `./scripts/verify-variant-boundaries.sh`：在 consumer/developer、PRoot/CLI APK 均构建后通过；
  consumer 不含 Root marker/project，developer 必须包含。
- `./gradlew :tools:root:testDebugUnitTest :tools:root:lintDebug`：状态机 7/7，lint 通过。
- `ANDROID_SERIAL=emulator-5558 ./gradlew :tools:root:connectedDebugAndroidTest` 与 API 36 的
  `emulator-5560`：每台 3/3、0 failure，覆盖 Profile 不触发 Root、显式请求后的 rootless
  拒绝、RootService 不绑定，以及请求完成竞态中的立即断开不遗留 shell。

HXA-094 完成前仍 required：在专用 rooted arm64/API 34+ 实机上分别执行用户 grant、deny、
grant 后撤权，以及杀死真实 RootService 进程；确认状态为 `GRANTED`/`DENIED`/`LOST`、无自动
重绑或调用重放，并记录 Root 管理器、版本、设备/API 与完整测试结果。

## Reconsider when

- JitPack 停止服务、改变构建/坐标语义，或相同 6.0.0 坐标返回不同 checksum。
- libsu 6.0.0 出现无法在本地封装层规避的安全、Binder 生命周期或新 Android 兼容缺陷。
- Rooted 设备 Spike 无法稳定完成 grant、撤权或 RootService crash 恢复，或发现 daemon-free
  生命周期不能满足短时 RootSession。
- 组织供应链政策要求签名 release/source provenance，当前 lightweight tag + checksum 模型
  不再足够。
- 项目所有者明确拒绝 JitPack 并授权审查过的源码镜像方案。

## References

- [Android 平台能力](../architecture/android-platform-capabilities.md)
- [开发环境与供应链约束](../development/environment.md)
- [M9 路线](../development/roadmap.md)
- [Capability-first Advanced 与持久授权](0012-capability-first-advanced-grants.md)
- [libsu 6.0.0](https://github.com/topjohnwu/libsu/tree/6.0.0)
- [JitPack libsu](https://jitpack.io/#topjohnwu/libsu/6.0.0)
