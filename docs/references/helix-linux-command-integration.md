# Helix Linux 命令能力与集成

> **本文档定位**：Helix 侧——现在能跑什么代码、为什么这么隔离、如何在「**不开放给 agent 工具面**」的前提下集成一个跑 Linux 命令的能力，以及 PRoot 在代码里的实现细节。
>
> **前置阅读**：Android 机制本身（SELinux 双权限 `execute` / `execute_no_trans`、linker64 跳板、toybox/busybox、PRoot、Termux、爆炸半径）见 [android-native-execution-mechanisms.md](./android-native-execution-mechanisms.md)。权威架构见 [local-code-execution.md](../architecture/local-code-execution.md)（尤其 §6 PRoot 方案、§6.2 Termux 对比、§6.3 运行时组成）。
>
> **性质**：参考性说明，**不是** ADR；与权威文档 / 代码冲突时以后者为准。

---

## 1. 能力矩阵（Helix 现在能跑什么）

| 语言 / 命令 | 工具 | 执行底座 | 变体 | 风险级 |
| --- | --- | --- | --- | --- |
| JavaScript | `code.javascript.run` | QuickJS（Zipline，进程内） | consumer + developer 都有 | L2 `CODE_EXECUTION` |
| Python / bash / shell / 任意 Linux 命令 | `code.linux.run` | PRoot + Alpine rootfs | **仅 developer** | L2 `CODE_EXECUTION` |
| Root | `RootModule` 的高层只读操作 | libsuv / rootd | **仅 developer** | 受限（当前只读，非通用 root shell） |

关键机制：**模型只能看到注册进 `ToolRegistry` 的工具**（`AppContainer` 里那串 `register`）。这是「把某个能力挡在 agent 外」的架构基础——不注册 = 模型看不到、调不到。

- `code.javascript.run`：`CodeJavascriptRunTool.kt:72`（HXA-053），两种 flavor 都注册（`AppContainer.kt:454`）。
- `code.linux.run`：`LinuxRunTool.kt:96`（HXA-085），仅 developer flavor（`AppContainer.kt:461` → `ProotToolModule`）；consumer 的 `ProotToolModule` 是 no-op（`AVAILABLE = false`）。工具早期叫 `bash`，规范名现为 `code.linux.run`，仓库若干处仍 `in setOf("bash", "code.linux.run")` 做兼容。
- Root：`RootModule.kt`（developer），`AppContainer.kt:410` 注册；当前是受限的高层只读，不是可任意命令的 root shell。

---

## 2. PRoot 在 Helix 的形态

- **独立伴生**：PRoot、rootfs、job 目录都在独立 `com.helix.runtime.proot` APK/UID；**不运行在主 app UID**。Runtime APK **不声明 `INTERNET`**（基线离线），不共享主 app 的 API Key / Keystore / Room / 通知 / 联系人 / Workspace。
- **有界 IPC**：每个 job 通过 Binder/PFD 接收**经审批的输入快照**，输出也以快照返回。
- **仍过 Policy**：Linux 命令每次都经 Policy Engine，属 L2。
- **rootfs**：固定 Alpine **3.22.5** minirootfs（aarch64），**53 个 pinned 包**（musl、busybox 1.37、bash、git、python3 3.12、nodejs 22、ripgrep、libcurl、openssl、sqlite 等）；SHA-256 锁定，唯一版本真相是 `runtime-lock.json`。基线必装包见 `RuntimeBaseline.kt:18`。`apk` 数据库仅离线审计，**不是安装通道**。
- 详细定位、Termux 对比与集成结论、运行时布局见 [local-code-execution.md §6](../architecture/local-code-execution.md)；许可证与包清单见 [ALPINE-README.md](../../runtime/proot-app/src/main/assets/runtime/licenses/ALPINE-README.md)。

---

## 3. 为什么要隔离（设计动机）

- **模型 = 高价值单设备目标上的不可信执行者**。
- 主 app **有 `INTERNET`**（`app/src/main/AndroidManifest.xml`）且**持有 provider 密钥**（`storage.secrets` / keystore）。模型生成的命令若以主 app UID 直接跑，就同时具备「读敏感数据 + 联网外发」= 外泄 / RCE 面。这是隔离存在的核心原因。
- 两层隔离：
  - **物理隔离**：独立签名 companion APK/UID + 无 `INTERNET` + pinned sha256 rootfs + 有界 I/O（Binder/PFD 快照 IPC）。
  - **逻辑隔离**：capability/policy engine + 风险分级 L0–L4 + 逐调用审批 + 一次性消费 + 审计。
- [ADR-0012](../adr/0012-capability-first-advanced-grants.md) 的**不可变安全内核**：无模型自授权、无全局自动批准、无全局 Full Access、无 L2/L3 长期放行。

---

## 4. 最大化信任 / 权限（ADR-0012 允许的最大范围）

- 两个运行时配置：`STANDARD`（所有安装默认）/ `ADVANCED`（**仅 developer** 变体、用户显式开启）。
- **Trusted Workspace**：自动执行仅限 **L0 + 动态风险 ≤ L1**；覆盖/删除/代码执行/跨 scope 外发或风险达 L2/L3 时不匹配该规则。
- **有界长期工具规则**：固定 tool ID/version/contract hash、capability、scope、execution target、origin/数据类别与有效期；任何绑定字段或动态风险变化即失效。
- **精确批量批准**：一次操作批准界面已完整披露的有限 ToolCall 列表，每个调用仍生成独立、一次性、精确绑定的 `APPROVED` proof；批准后追加/变更不在批次内。
- **Full Workspace Access**：表示「workspace 文件 scope 较宽」，须同时显示根目录 / 可用操作 / 撤销入口；**不是**全局 `Full Access`。
- **有界高敏出网规则**：精确绑定 Provider/MCP、规范 origin、数据类别、scope、固定期限（1h/24h/7d/30d，默认 24h，不可滑动续期，时钟回拨 fail closed）。
- **明确禁止**：模型自授权 / 全局自动批准 wildcard / 全局 Full Access / L2/L3 长期放行。

---

## 5. 不开放给 agent、纯 app 内部跑 Linux 命令

**结论：能，而且这是更干净、更安全的设计。**（Android 侧三种底座各自怎么跑、要不要跳板，机制见 [android-native-execution-mechanisms.md](./android-native-execution-mechanisms.md)。）

- **关键点**：「不给 agent 调」= 命令来源从「模型输出」变成「你自己的受信 Kotlin 代码」→ **prompt-injection 面消失**。这是它比「薄封装给模型调」更安全的根本原因。
- **怎么挡在 agent 外**：agent 只看到注册进 `ToolRegistry` 的 `ToolDescriptor`。你的命令执行器若只是自己代码里的一个 `ProcessBuilder` / `Runtime.exec` 调用、**不注册成工具**，模型就看不到、也调不到。架构上天然解耦。

**三种底座，按重量排：**

| 底座 | 怎么跑 | 跳板 / rootfs | 能力 | 身份 |
| --- | --- | --- | --- | --- |
| **原生 `/system/bin/sh`**（mksh + toybox） | `ProcessBuilder("/system/bin/sh","-c", cmd)` | **都不要**（sh/toybox 是 /system 二进制） | 有限（mksh/toybox；无 bash/python/node/git） | 你 app 的 UID |
| **单个自打包二进制 + linker64 跳板** | `setsid + linker64 + <你的二进制>` | 只要跳板，**不要 rootfs** | 你想要的那一个工具（静态 Go/Rust 二进制、你的 python 等） | 你 app 的 UID |
| **完整用户态（proot + Alpine，Termux 式）** | 上面那条完整链 | 跳板 + rootfs | 全套（bash/python/node/git/ripgrep）+ 隔离 | 你 app 的 UID（Helix 里是独立伴生 UID） |

- **第一行的关键点**：如果只需要「标准 shell + 标准 Unix 命令」，`ProcessBuilder("/system/bin/sh","-c",...)` **直接就能跑，零跳板、零 rootfs**——因为 exec 的是 `/system` 里的东西，不碰那道 SELinux 限制。跳板只在你要 exec **自己打包的二进制**时才需要。

**两条必须记住的边界：**

1. **命令串必须受信、内部来源**（你的代码从类型化状态拼出来）。**绝不用模型输出 / 网页 / 文件内容当命令串**——一旦命令串来自外部，「internal」就名存实亡，等于又造了个 RCE。这是「不给 agent 调」唯一真正的前提。
2. **爆炸半径 = 你 app 的权限**：以你 app 的 UID 跑，能读你 filesDir（DB、任何 key），有 `INTERNET` 就能联网。对你自己的受信逻辑通常没问题；如果连内部都想硬隔离（限 FS / 无网），那就走独立 UID / proot 那条。

---

## 6. ProotJobRunner 实现细节（对应 Android 机制那份的 §2 / §3）

- **启动链**（`ProotJobRunner.kt:297`，device-verified，注释 `284–295` 明确点名 termux-exec 的 `system_linker_exec`）：

  ```
  /system/bin/setsid  /system/bin/linker64  <installDir>/bin/proot  \
    -r <rootfs> -b /dev -b /proc -b <tmp>:/tmp -b <workspace>:/workspace -w /workspace  \
    <commandArgs...>
  ```

  - `setsid`：让 job 拿到自己的 session（pgid == pid），后面能精确地按进程组 kill。
  - `linker64`：SELinux 跳板（proot 二进制在 `app_data_file`，直接 exec 会被拒，见机制文档 §3）。
  - `commandArgs`：`Argv` 直接用；`Script` 是**单个 argv 元素** `["/bin/sh","-c",script]`，不拼进未转义的命令串。

- **第二层（比通用讲解更精确）——`PROOT_LOADER` 走 `apk_data_file`**：PRoot 会改写 tracee 的 in-flight execve 指向 `$PROOT_LOADER`（LD_PRELOAD 钩子对这种改写无效，tracee 不会再调一次 `execve()`）。这个 loader **必须放在 app domain 能直接 exec 的标签** = `apk_data_file`（该标签**同时**授 `execute` + `execute_no_trans`）；以 jniLib 形式随 APK 安装时解压，且**每次 job 都对锁定的 runtime loader 做 hash 校验**（`ProotJobRunner.kt:302–324`）。
  - 也就是说 Helix 同时利用了**两个标签差异**：起 proot 走 `app_data_file`（需跳板），而 guest 内部 exec 的 loader 走 `apk_data_file`（可直接 exec）。

- **固定环境项**（device-verified）：`LD_LIBRARY_PATH=installDir/bin/lib`、`PROOT_LOADER`、`PROOT_TMP_DIR`；`builder.environment()` 先 `clear()` 再只放 `spec.environment`（环境白名单；这些是固定非 secret 路径，但对 guest 进程可见，代码里明确「记录而非隐藏」）。

---

## 7. 集成要点 / 待决策

若要把它做成「能跑 Linux 命令、不进 ToolRegistry、不给 agent 调」的能力，需先定几点（不同选择实现量差别很大，原生 sh 十几行，完整 proot 一整个模块）：

1. **底座**：原生 `/system/bin/sh` / 内置 busybox / 完整 proot？
2. **跑哪些命令、输入从哪来**（必须是受信 / 内部来源——需确认）？
3. **在哪个代码路径触发**（哪个 service / 场景）？
4. **要不要隔离**：app UID 就行，还是要 bounded FS / 无网？

**安全铁律重申**：命令串受信、内部来源，绝不用模型 / 外部输出当命令。

---

## 参考

- [android-native-execution-mechanisms.md](./android-native-execution-mechanisms.md)（Android 机制：SELinux 双权限、linker64 跳板、toybox/busybox、PRoot、Termux、爆炸半径）
- [docs/architecture/local-code-execution.md](../architecture/local-code-execution.md)（权威；§6 PRoot 方案、§6.2 Termux 对比、§6.3 运行时组成）
- [docs/adr/0012-capability-first-advanced-grants.md](../adr/0012-capability-first-advanced-grants.md)（能力优先的授权边界与不可变安全内核）
- `runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt`（启动链 + SELinux 注释）
- `app/src/developer/kotlin/com/helix/app/proot/LinuxRunTool.kt`、`runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/tool/CodeJavascriptRunTool.kt`、`app/src/developer/kotlin/com/helix/app/root/RootModule.kt`
- `runtime/proot-core/src/main/kotlin/com/helix/runtime/proot/core/RuntimeBaseline.kt`（基线包）
- `runtime/proot-app/src/main/assets/runtime/licenses/ALPINE-README.md`（rootfs 许可证与包清单）
