# ADR-0049: developer 单 APK 内置订阅与 PRoot

Status: accepted
Date: 2026-09-14
HXA: HXA-193
Deciders: Project owner（在说明共享 UID 的权限与凭据边界变化后明确要求确认并实施此重大重构）
Supersedes: [ADR-0047](0047-bundled-companion-installers.md)
Superseded by: none

## Context

用户要求 consumer 不包含 Subscriptions 和 PRoot，developer 包含完整组件，且只安装一个应用。旧实现把两个 APK 嵌入 developer assets，再调用系统安装器；仍有三套安装、更新与生命周期。

Android 普通私有进程共享应用 UID 和权限。`isolatedProcess` 使用特殊 UID，不能直接读写现有应用私有 RootFS、持久任务目录及 Keystore。当前 PRoot 依赖这些路径及原生 exec/ptrace；仅更改 manifest 不能保留功能。这里的结论限于现有实现，不声称 Android 永远无法实现带文件系统代理的隔离 PRoot。

## Decision

1. developer 的一个 APK 链接两个 Android library 模块；现有 `runtime:cli-app`、`runtime:proot-app` 模块路径保留以避免无关历史引用迁移，但不再产出生产应用 APK。consumer 编译依赖、manifest、dex、assets 和 native libs 均排除两者。
2. 订阅 Service 和账户/网络设置 Activity 在 `:subscriptions`，PRoot Service、停止 Receiver 和修复/许可 Activity 在 `:proot`；全部 non-exported。生成代码不在主进程运行。QuickJS 继续使用独立 UID 的 isolated service。
3. 两个 Runtime 共享 developer 应用 UID。PRoot 是用户明确批准的可信开发者命令环境，具有应用层网络和私有数据访问能力；RootFS 映射、输入快照、环境变量筛查不构成恶意代码沙箱。取消旧“强制离线、不能读取主 App/订阅数据”的保证。此信任变化必须进入工具描述和用户可见设置说明；`code.linux.run` 升级工具版本，旧版本精确审批不可复用。
4. 订阅 token 的代码所有权仍在订阅模块、通过自己的 OAuth 登录和 Keystore 保存，正常 Binder/API 不返回 token；这是模块契约而非 UID 隔离保证。同 UID 的受信任代码具备越界访问能力。禁止导入其他 App/CLI/browser 的凭据；非官方标识和服务商发行限制不变。
5. 保留 Binder/PFD、任务 ID、输入/输出哈希、持久结算、取消、按 ID 查询和不盲目重放。绑定自身 package 的固定私有 Service，并验证该组件存在；不回退旧 companion。后台和 FGS 限制保持 ADR-0007。Runtime 进程启动不执行主应用 Room 恢复或初始化 AppContainer。
6. 升级保留主数据库、历史和产物。旧 companion 的 RootFS、凭据和未结算远端 journal 不复制、不删除、不自动重放。新组件需要重新登录/初始化，旧 PRoot 验证锚不激活新执行环境；旧任务未知结果停泊供用户核查。旧应用可由用户在核查后卸载。
7. 本决定完全取代 ADR-0047 的内嵌 APK 安装方式，部分取代 ADR-0007 的独立 APK/UID、ADR-0021 的 UID token 隔离和 ADR-0012 的离线 PRoot 描述；保留其余授权、生命周期、账号来源及发行约束。consumer 与 Standard/Advanced 的概念仍不同，不因本次打包选择宣称所有商店禁止这些能力。

## Alternatives considered

- 继续三个 APK：保留更强 UID 隔离与独立更新，但不满足本轮产品目标。
- 单 APK 的 isolated PRoot：需要另行设计 RootFS/文件描述符访问、网络和持久化代理；现有执行底座不能直接迁移。未来有设备证据后可重新评估。
- 全部在主进程：崩溃、阻塞及取消会影响 UI，不采用。

## Consequences

授予此 UID 的其他系统权限及 Root 等外部授权也不能靠普通进程隔离；这属于可信开发者执行环境的边界，不得在 UI 或文档中暗示另有权限沙箱。

安装、签名和更新归为一个应用；功能仍按模块和进程分工。force-stop/清除数据/卸载作用于整个 developer 应用。脚本信任要求提高，不能再把订阅凭据隔离或离线执行作为此变体的产品承诺。对同 UID 恶意脚本没有安全保证，也不通过额外提示虚构这种保证。

## Verification

决策授权来自本次用户请求；本地实现及专项验证结果另记 [HXA-193 记录](../development/integrated-developer-runtimes.md)。验证覆盖双 flavor 依赖与最终 APK、真实跨进程冷绑定、订阅无凭据 fixture 请求/取消、PRoot 安装/执行、进程死亡与旧状态恢复，以及主进程恢复不在 Runtime 进程重复执行。全量门禁、远端 CI 制品来源与真实账号/发行验收仍须按记录闭合；不能以旧 companion 测试结论代替新形态验证。

## Reconsider when

需要运行不可信第三方脚本且仍承诺 token/主数据隔离，出现可验证的 Android 隔离底座，或包体/更新成本超过发行要求时重新决策。

## References

- [被取代的安装方案](0047-bundled-companion-installers.md)
- [保留的生命周期契约](0007-companion-runtime-lifecycle.md)
- [订阅适配器](0021-third-party-subscription-protocol-adapter.md)
- [能力与授权](0012-capability-first-advanced-grants.md)
- [渠道分发](0013-standard-store-capability-preserving-distribution.md)
- [Android Service 进程定义](https://developer.android.com/guide/topics/manifest/service-element)
- [Android UID 沙箱](https://source.android.com/docs/security/app-sandbox)
