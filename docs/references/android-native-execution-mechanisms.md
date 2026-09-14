# Android 原生执行与 SELinux 机制

> **本文档定位**：讲解 Android 上「运行本地程序」的底层机制——为什么 app 不能直接 exec 自己打包的二进制、linker64 跳板为什么能绕过去、toybox/busybox、PRoot、Termux 是怎么回事、app 起外部命令的爆炸半径。
>
> **目的**：**了解 Android 平台机制本身**。与是否在某款 app（如 Helix）里开放某项能力**无关**——本文只讲机制。
>
> **与 Helix 的关系**：Helix 只是这些机制的一个真实落地（独立 UID + PRoot + linker 跳板）。Helix 侧的实现与决策见 [helix-linux-command-integration.md](./helix-linux-command-integration.md) 与权威架构文档 [local-code-execution.md](../architecture/local-code-execution.md)；本文把它们当「在哪里能看到」的指路，不作为论述主线。

---

## 1. 背景：Android 内核就是 Linux，缺的是默认「用户态」

- Android 的内核就是 **Linux 内核**（带 Google / 厂商补丁）。每个 app 进程本身就是内核里的一个**原生 Linux 进程**，有独立 UID。
- Android 缺的不是内核，是**默认的用户态**：
  - 默认 `/system/bin/sh` 是 **mksh**（不是 bash）；
  - 标准 Unix 命令大部分由 **toybox**（单文件多 applet）提供，不是完整 GNU coreutils；
  - **没有** bash / python / node / git；
  - 原生代码链接的是 **bionic**（Android 的 libc），不是 glibc / musl。
- 所以「在 Android 上跑 Linux 命令」要么用系统自带的 mksh + toybox（能力有限），要么**自带一套用户态**——而「自带并运行你自己的二进制」正好撞上 SELinux 那道墙（下一节）。

---

## 2. SELinux 双权限 + 三种文件标签：app 为什么不能直接 exec 自己的文件

Android 用 SELinux 做强制访问控制。对「执行一个文件」，它区分**两个不同的权限**，判定对象是**文件的 SELinux 标签（type）**：

| 权限 | 什么时候触发 | 含义 |
| --- | --- | --- |
| **`execute`**（普通） | `mmap(..., PROT_EXEC)` 把文件映射成可执行内存 | 允许「把文件内容映射成可执行内存」 |
| **`execute_no_trans`** | 内核 `execve()` 该文件、且不发生域转移（domain transition） | 允许「直接 exec 这个文件并留在当前域」 |

对普通 app 的 SELinux 域 `untrusted_app`，策略大致是：

| 文件标签 | 典型位置 | `execute`（mmap-exec） | `execute_no_trans`（直接 exec） |
| --- | --- | --- | --- |
| `system_file` 等 | `/system`、`/vendor` 里的系统二进制 | 授 | **授** |
| `apk_data_file` | app 安装目录（APK 解出的 native 库等） | 授 | **授** |
| `app_data_file` | app 私有存储（filesDir、缓存、你从 assets 解出来的东西） | 授 | **拒** |

由此：

- `execve("/system/bin/sh")` → 查 `system_file:execute_no_trans` → **允许**。所以 `ProcessBuilder("/system/bin/sh")` 在 Android app 里随处可用。
- `execve("/data/data/<pkg>/files/mybin")` → 查 `app_data_file:execute_no_trans` → **拒绝** → `avc: denied { execute_no_trans }`。

**为什么这么设计**：

- 拒 `app_data_file` 的 `execute_no_trans`，是为了**禁止 app 把「自己写到磁盘上的东西」当代码直接 exec**（挡 RCE / 数据当代码执行 / 从可写存储跑代码）。
- 但 `execute`（mmap）必须授，否则 app 连自己的 `.so` 原生库都无法映射成可执行内存、加载不了。
- 于是「跑自己的原生代码」的**官方认可路径**就是：让系统动态链接器去**映射**它，而不是直接 exec 它（见 §3）。
- 附带：**app 改不了自己文件的 SELinux 类型**。app 能写的地方（filesDir、app 专属外部目录）都是受限标签，所以「换个存放路径绕开限制」走不通。

---

## 3. linker64 跳板（system_linker_exec）

### 3.1 linker64 是什么

`/system/bin/linker64`（32 位叫 `linker`）是 **Bionic 的动态链接器**，也叫 ELF 解释器 / 程序加载器。它是 Android 上**每个动态链接可执行文件启动时必经的程序**：

- 动态链接 ELF 里有个 `PT_INTERP` 头，内容就是 `/system/bin/linker64` 这个路径；
- 你 `execve(某动态二进制)` 时，内核的 ELF 加载器读 `PT_INTERP`，**真正执行的是 linker64**，把原二进制路径当参数传进去；
- linker64 再把原二进制 `mmap` 进内存、解析符号依赖（libc 等）、跑初始化，最后跳到它的 `entry`/`main`。

所以它**不是外挂 trick**，而是 Android 程序**本来就有的启动路径**——跳板只是**显式点名**它，让它去加载你自己打包的那个二进制。

### 3.2 为什么能过 SELinux：把「一次被拒的 exec」拆成「两次都被授的操作」

直接跑会失败的那一步：

```
execve("<app私有目录>/mybin")   →  查 app_data_file:execute_no_trans  →  拒
```

跳板把它拆成两步，每步单独都合法：

1. `execve("/system/bin/linker64", "<app私有目录>/mybin", args)`
   - exec 的对象是 `/system/bin/linker64`，标签 = 系统文件 → 查 `system_file:execute_no_trans` → **授**。
   - 进程变成 linker，而且**不换域**（`no_trans` = 不 transition），仍是 `untrusted_app`。
2. linker `mmap` 你的 mybin
   - 查 `app_data_file:execute` → **授**（app 能 mmap-exec 自己的文件）。
   - 解析依赖 → 跑 init → 跳 entry。你的程序跑起来了。

关键点：**全程没对 app 私有文件做过 `execve`**（那才需要被拒的 `execute_no_trans`），只做了 (a) 对系统文件 execve（授）+ (b) 对自己的文件 mmap-exec（授）。「跳板」就是指从「允许 exec 的 linker」蹦进「你那个 app 文件」。

### 3.3 统一理解

其实**每个动态链接的 Android 程序，正常启动就是「内核先跑 linker、linker 再 mmap 加载它」**（`PT_INTERP` 干的）。SELinux 策略本就按「linker 会去 mmap 你的 app 代码」这个模型写的——所以 `execute`（mmap）授、`execute_no_trans`（直接 exec）拒。跳板只是把你的启动**显式对齐到策略预期的那条路径**上；对**静态二进制**（没有 `PT_INTERP`、内核不会自动拉起 linker）尤其必要。静态 / 动态都不影响 SELinux 这层——真正被查的只有「对哪个标签的文件、做的是 execve 还是 mmap」。

---

## 4. busybox vs toybox

- **Android 自带的是 toybox，不是 busybox**（AOSP 用 toybox 替代了 busybox）。toybox 是单文件多 applet，提供大量标准 Unix 命令（`ls`/`cp`/`mv`/`cat`/`sort`/…），但 applet 集合与 flag 跟 busybox 有差异，且随 Android 版本 / OEM 变化。
- **调系统 toybox 不用跳板**（它是 /system 文件）：`ProcessBuilder("/system/bin/toybox", <applet>, ...)`、或 `/system/bin/sh -c`、或单 applet 包装 `/system/bin/<cmd>`。
- **内置自己的 busybox 仍然要跳板**：因为 SELinux 那个拒绝是**按文件标签（`app_data_file`）判**的，跟二进制「是不是静态链接」「需不需要 libc」**无关**。静态链接只省「运行时找 libc」，省不掉「app 私有目录文件不许直接 execve」（见 §2 / §3）。
- **无 root**，所以 `iptables`/`mount`/`route` 这类特权命令 toybox / busybox 都跑不了——那是内核权限问题，不是工具问题。
- 查某台设备到底有哪些 applet：`/system/bin/toybox --list` 或 `ls /system/bin`。

---

## 5. PRoot

- **PRoot** 是基于 `ptrace` 的用户态 chroot：无 root、无 suid，通过**拦截并转换系统调用**，模拟路径、身份（伪 UID/GID）和部分 syscall 行为。
- **它不是安全边界**：不提供 namespace / cgroup / seccomp / 真实 mount，不能与 VM 等价隔离；伪 root（UID/GID 0）只是模拟值。
- **网络**由宿主进程 UID 的 `INTERNET` 权限决定，PRoot 本身**不剥离**网络。
- **性能**：PRoot 拦截每个 syscall，文件系统密集型任务、包管理、编译会明显慢于原生；CPU 密集且 syscall 少的工作负载损耗较小。
- **能跑哪些命令**：取决于你放进 rootfs 的 Linux 用户态（Alpine / Debian / Ubuntu 等）里装了哪些包。

---

## 6. Termux

- **Termux 的基础是原生的**：NDK 构建、直接在 host 内核上跑、链接 bionic，**不是** PRoot。
- **为什么有网络**：它就是普通 app、有 `INTERNET` 权限。
- **为什么支持 Linux 命令**：自带一套适配 bionic + 固定安装前缀（`$PREFIX`）的 GNU 工具用户态。
- **PRoot 在 Termux 里只是 `proot-distro` 插件**（用来跑标准 Linux / OCI rootfs），不是 Termux 的基础。
- **Termux vs PRoot**：二者不是同一层替代品（Termux 里也能装 PRoot）。原生更快、生态是 Termux 自有包；PRoot 兼容性更标准（Alpine/Debian/Ubuntu 目录布局）但更慢。

---

## 7. app 起外部命令的「爆炸半径」

- 你用 `ProcessBuilder` / `Runtime.exec` 起的外部进程，**以你 app 的 UID 运行**，继承你的能力：
  - 能读写你 filesDir / 你能访问的文件；
  - 若你 app 声明了 `INTERNET`，它就能联网；
  - 能触及你进程可访问的敏感数据（DB、key 所在位置）。
- 因此「**给谁执行、命令来源是否受信**」是安全核心：命令来源若不可信（模型输出 / 网页 / 文件内容），等于给了它一个**以你 app 权限运行的 RCE**。
- 想要更强的隔离（独立 UID、无网、有界 FS）需要额外机制（独立 app / UID、PRoot 等）——那是**产品 / 架构决策**，不是 Android 机制本身。
