# Runtime、Provider 与端侧模型

> 研究基线：2026-09-25。后续 HXA-222 已交付端侧模型首版，当前引擎与验收范围见[Provider ADR](../../adr/provider/001-models-and-connection.md)和[完成记录](../../completion-records/HXA-222.md)。下文保留当时比较，不作为当前未立项或候选状态的依据。

2026-09-30 的模型管理增量参考 VS Code/Copilot、Cherry Studio、LM Studio 与 OpenCode 官方文档，统一目录/候选/默认/逐模型验证而不合并认证或执行域。参考依据与实现验收集中在[模型管理记录](../../evidence/development/provider-model-management-2026-09-30.md)，本页不复制变动中的测试结论。

## 1. Runtime 应按执行边界分层

Helix 当前合理的执行域是：

- app/core：类型化业务与轻量工具；
- QuickJS isolated process：离线生成代码，no privileged host bridge；
- PRoot/CLI private process：开发者/Linux 工具、长命令、PTY；
- subscription/provider private process：订阅客户端或特定 provider transport；
- Android system capabilities：通过显式 bridge + permission。

不要为了“统一运行时”把这些执行域压成一个万能 shell，也不要把 shared UID 当 credential isolation。

## 2. 桌面 Harness vs Android Runtime

桌面 Agent 可以假设稳定 shell/process/worktree；Android 必须处理：

- process/background kill；
- app sandbox/UID；
- foreground service；
- SAF/permission；
- ABI/native dependency；
- battery/memory；
- OEM 差异。

因此 Helix 应复用桌面的 Agent loop/permission/session 思想，但不能照搬“宿主 shell 就是一切”的 runtime 模型。

## 3. Provider

当前 Provider abstraction 应继续保证：

- protocol/stream normalization；
- capability probe；
- explicit provider/model identity；
- connection validation；
- residence/data-destination classification；
- credential owner 与 Runtime 分离。

Session/Turn 必须记录实际使用的 provider/model identity；UI 当前选择不是历史事实。

## 4. 本地模型综合判断

本地模型 Provider 是 Helix 的**一等 `ModelProvider`**，不是只服务摘要/分类的辅助模型。用户可以把本地模型选为当前 Session/Turn 的主 Provider，并让它直接驱动完整 Agent loop，包括工具调用、上下文压缩后的多轮执行和 Goal/Task 工作流。

架构不因为“模型在手机上运行”而禁止 `toolCalls=true`。是否适合完整 Agent loop 由**能力探测 + 模型元数据 + 真实任务 eval + 设备资源**决定，而不是由 `ON_DEVICE_LOCAL` residence 直接决定。

### 一等 Provider 必须满足的契约

- 走与远端模型相同的 `ModelProvider` / `ModelRequest` / `ModelEvent` 抽象；
- 显式标记本地 residence，不伪造 `http://127.0.0.1` endpoint；
- capability probe 能表达 context window、tool calls、parallel tool calls、vision、reasoning、structured/json-schema output 等真实能力；
- 工具调用最终仍进入同一 Dispatcher / Policy / Approval / Audit 管线，本地模型不因“可信设备内运行”获得额外权限；
- capability 不满足当前 Agent mode 时 fail closed 或做**用户可见**降级，不能悄悄把 ToolCall 任务当普通聊天；
- 模型切换、历史快照、request receipt、context compaction 与远端 Provider 采用同一身份语义。

### 完整 Agent loop 是正式目标，不只是实验

摘要、标题、分类、隐私过滤和 `toolCalls=false` 聊天仍然是低风险、低算力的优先验证场景，但它们只用于更快拿到质量/性能基线。它们**不是**本地 Provider 的产品上限。

完整 Agent loop 的验收应直接测：

- 多轮工具选择与调用成功率；
- schema/参数合法率；
- 长上下文下的状态保持；
- tool result 解释与错误恢复；
- Queue/Steer/Goal 下的连续任务；
- token/s、首 token、峰值内存、热降频、电量和后台存活；
- 同一任务与云端 Provider 的完成率/人工干预/成本对比。

### Runtime / engine 候选

llama.cpp + GGUF 仍是高价值候选，原因包括 ARM64 生态、量化、广泛模型兼容和 grammar-constrained decoding。MNN/其他移动推理框架也可作为后续候选；不应把某个推理引擎写死成 Provider 契约。

Grammar/JSON 约束可以提高结构合法性，但不能证明模型具备规划、工具选择和结果判断能力，因此完整 Agent loop 仍必须用真实任务 eval 决定支持级别。

## 5. Loopback vs in-process/private process

consumer cleartext/loopback 暴露、端口授权、恶意本机 App 访问和生命周期使“起 localhost 服务”不适合作为正式本地 Provider 形态。

推荐演进：

```text
S0 developer 外部/PRoot server 验证质量
S1 app 内 ModelProvider + JNI/本地 runtime
S2 若内存/OOM/稳定性证据要求，再移 private :llm process
S3 有真实收益后再做 NPU/SoC 加速
```

本地推理的 isolation 选择应由真实内存/崩溃测量驱动，不预先复制 PRoot/Subscription 的所有 IPC 复杂度。

## 6. 模型分发

真正的新工作面往往不是 JNI，而是：

- GB 级下载；
- resumable transfer；
- SHA-256/签名；
- storage quota；
- model metadata/licence；
- 删除/升级；
- ABI/native library size；
- 用户可见的数据去向。

模型资产应与用户 Workspace 文件分开管理，除非产品明确允许用户导入/导出模型。

## 7. Subscription / proxy / local provider

不同 provider transport 不应进入 Tool Dispatcher；模型调用仍是 Provider 路径。订阅客户端、API provider、本地模型都实现同一 ModelProvider surface，但 credential、network/isolation 和 capability probe 可以不同。

## 8. 冲突裁决

- “本地优先 = 本地模型优先”：否；本地执行/数据与本地推理分开。
- “所有 Runtime 一个 PRoot”：否；生成代码、Linux CLI、模型推理、订阅 transport 风险不同。
- “先 private process 最安全”：不必；先拿真实 OOM/稳定性证据，再付 IPC 复杂度。
- “本地模型只能做摘要/聊天”：不采用。本地 Provider 是一等完整 Agent Provider；实际支持级别由 capability/eval 和设备资源决定。
- “有 grammar 就一定能做 Agent”：不成立；grammar 只解决结构合法性，规划/工具选择/结果判断仍靠模型能力与 eval。

## 9. 重新评审条件

- 本地模型在完整 Helix Agent eval 上出现新的成功率/资源证据，需调整默认模型级别、能力门槛或 Runtime；
- Android 设备普遍提供稳定标准化 NPU LLM API；
- 本地 Provider 使用比例/设备资源数据证明需要改变默认启用策略、模型下载策略或进程隔离；
- consumer cleartext/loopback 或 platform local inference policy 发生根本变化。

## 2026-11-01：原位 PATH、SAF 与 CLI 路径投影研究

本节是基于 `b50f9211` 的研究与候选验证路线，不是已接受的新文件系统契约，也不表示桥接已实现。
优先级保持：已有权限的真实 PATH 直接执行；App 文件工具通过 SAF 操作原件；不把目录选择变为默认全量复制或双向同步。

### 当前实现与缺口

- `app/.../proot/LinuxLiveWorkspace.kt` 冻结所选引用、重新解析真实目录、检查可访问根和目录身份；
  `runtime/proot-app/.../ProotPathAccess.kt` 再检查真实 OS 访问能力。当前 `/workspace` 是 PRoot bind 的原目录。
- `app/.../files/SafManualFileBackend.kt` 通过 ContentResolver/DocumentsContract 读写原件；创建后核对实际名字/身份，
  但“先查不存在，再创建”不是跨应用原子排他创建。UI 的能力声明不是 POSIX 语义证明。
- `app/src/androidTestDeveloper/.../proot/SafRpcPrototype.kt` 只是固定范围、nonce/UID/修订/撤权检查的只读 RPC。
  已有原型测试证明 guest Python 可以访问原 Provider 的目录和字节，未证明普通 Git syscall 会进入它。
- `runtime/proot-app/src/main/assets/runtime/runtime-lock.json` 锁定 Termux PRoot `5.1.107.92`、
  源提交 `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`。`scripts/build-proot-assets.sh` 校验/打包既有二进制，
  不是 SAF 扩展编译流水线。现有 CMake 构建页尺寸、PTY 与状态文件 JNI，不编译 PRoot 本体；NDK 为 `28.2.13676358`。
- PTY native 启动在 fork 前完成 JVM 交互，exec 前关闭 3 以上 FD。不能直接假设 Java 打开的文档 FD 会传给 guest。
  既有 Binder PFD 通道承载 manifest/result，不是 tracee 文件描述符注入实现。

### 一手证据与成熟组件的适用边界

1. 锁定提交的 [PRoot extension.h](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/extension/extension.h)
   提供路径转换、syscall enter/exit、fork/clone 继承、chained syscall 等事件；
   [chain.h](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/syscall/chain.h)
   提供 syscall 注入基础。这证明可扩展，不证明已有通用 SAF 文件系统或稳定可直接加载的插件 ABI。
   原型若继续，应评估在既有 tracer 中构建小型扩展，不新增另一套执行引擎；修改 GPL 组件须先明确采用及分发义务，不能直接复制参考代码。
2. [Android ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,java.lang.String))
   说明 `r`/`w` 可返回 pipe/socket，`rw` 支持 seeking，而具体 mode 行为仍因 Provider 不同。
   [proxy FD](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler))
   是单文件 I/O 接口，不提供目录命名空间或普通目录挂载；可 seek 不等于可 mmap、ftruncate 或锁定。
3. [AOSP FileSystemProvider](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/com/android/internal/content/FileSystemProvider.java)
   的 createDocument/renameDocument 都使用 buildUniqueFile；即使来源是本机，也不能把 SAF 名称操作等同
   `O_CREAT|O_EXCL` 或原子覆盖 rename。create/rename 标志只说明存在相应 API。
4. [RSAF](https://github.com/chenxiaolong/RSAF#limitations) 是 rclone 后端向 SAF 暴露文件，方向相反。
   官方说明代理 FD 的 ftruncate 限制、异步 close/上传、同名创建竞争；不能用其成熟度证明 Git 写入安全。
   rclone 为 MIT，RSAF 为 GPL-3.0-only；这里只作为设计证据，不引入代码。
5. [Termux SAF 讨论](https://github.com/termux/termux-packages/discussions/22155)也区分 SAF 命令访问与 PRoot 扩展。
   名称带“SAF bridge”的反向 bind 脚本、QEMU `-fsdev local,path=...` 示例依赖已有普通路径，
   不能证明任意 URI 可被挂载。此次未确认可直接嵌入 Helix、完整处理 URI→POSIX 的成熟成品。

补充组件筛选：
- [android-SafFile](https://github.com/YuriSizuku/android-SafFile) 使用 xhook 包装 App JNI 的 fopen/mkdir/remove，
  不是 Linux 子进程的通用 syscall 层；根目录未见许可证文件，不能直接采用其实现。
- [RCX](https://github.com/x0b/rcx) 确有 safdav 模块和 WebDAV 服务能力（GPL-3.0），不是只有反向 Provider。
  该模块详细目录本轮被浏览工具限制，未继续换入口读取，不声称完成其源码或锁/原子性审计。
- [prim-ftpd](https://github.com/wolpi/prim-ftpd) 提供 SAF→FTP/SFTP，主项目 Apache-2.0；可参考文档树到协议接口的适配，
  但协议服务不提供本机挂载路径。其[官方安全通告](https://github.com/wolpi/prim-ftpd/security/advisories/GHSA-j72x-x52h-247j)
  记录 8.2 修复未经授权的服务启动入口，说明若采用网络桥，还须独立处理启动授权和绑定地址，不能照搬默认暴露。

### 推荐的两条路线

| 路线 | 普通路径及数据位置 | 适用条件与成本 |
| --- | --- | --- |
| 真实 PATH 原位优先 | 现有 bind `/workspace`，源码不搬动 | 仍须实际权限、身份与读写探针；共享盘未必支持 symlink/锁等。构建工具本体、依赖和输出可显式放私有目录，不能静默遮蔽原目录中的同名内容。成本最低，继续作为默认。 |
| URI 只读路径视图＋私有 Git/输出 | PRoot syscall 扩展处理虚拟目录；所需文件优先真实可用 FD，否则限额按需缓存单文件；Git 元数据和构建产物为真实私有路径 | 先验证 ls/stat/open/read/seek/dup/fork/exec，必要时只读 mmap。不是现有 RPC 加一个 bind 参数。需要 native tracer、FD 传递、虚拟目录/句柄生命周期和物理取消，成本中高，应先做止损原型。 |

只读桥的首选验证结构是既有 PRoot syscall→范围受控 SAF broker→SCM_RIGHTS/受控注入原件 FD。
先实测 Android/SELinux、guest ABI、fcntl/dup/close-on-exec 等条件；不能通过另一个进程的 FD 数字或 /proc 路径猜测等价性。
pipe/proxy FD 不满足操作时才考虑有界单文件读缓存，明确读取修订；不默认缓存整个目录或 close 自动写回。
这只是推荐验证结构，尚未做 FD 投递或 syscall hook 实验。

Git 支持 [GIT_DIR / --separate-git-dir](https://git-scm.com/docs/git-init)，可将对象、索引和锁留在私有目录，
通过每次调用显式指定 git-dir/work-tree，避免为指针而修改 SAF 原件。
这不能省掉工作树的目录枚举/文件读取，也不自动解决 checkout、merge、clean、临时替换、symlink 或构建源码写入。
已有仓库与新建私有 sidecar 仓库的历史/所有权不同，不能默默移动或删除来源 `.git`，也不自动执行导入的 config/hooks/filter。

只读视图验证通过后，`status/add/commit` 可作为第一批候选，因为其常规写入主要可留在私有 Git 元数据；
仍须验证实际 syscall、过滤器/子模块等边界。构建先选支持 out-of-tree 输出的代表任务；npm install 等会改源码树的流程不在同一保证内。
若 Git 实际读取所有源文件，按需读取也可能覆盖整个项目；它保证没有预先全量镜像，不保证永远只传少数字节。

### 不应混淆的方案

- 单文件 FD、`/proc/.../fd`、软链或 `LD_PRELOAD` 都不是通用目录桥。
  FD 需跨进程正确传递；static binary、直接 syscall、glibc/musl/bionic 差异使 preload 不适合通用保证。
- 私有锁只能协调 Helix 参与者，不能防止外部 App 同时改 Provider 原件。写前后 hash 可检测部分冲突，不能制造 CAS/原子替换。
- 缓存修改后 close 自动写回无法掩盖上传失败、崩溃或撤权；如需要这条路线，必须明确是有日志/冲突/确认的工作副本，不能叫严格原位写穿。
- FUSE/sshfs/davfs/网络协议服务本身仍需要可用的挂载消费端；不能假设已有 root、/dev/fuse、mount 权限或 all-files 授权。
  为此新增 QEMU/完整虚拟机、通用 POSIX 内核或替换整个 Agent runtime，不是当前最小路线。

### 下一轮有界验证及停止条件（本次未执行）

1. 用隔离 Provider 建能力矩阵：本地 regular FD、pipe、proxy FD、未知/重复名字、异步失败。
   先验证被拒路径、撤权和超时，再决定能否提供 seeking/mmap；枚举失败不可返回空目录冒充成功。
2. 在锁定 PRoot 源上仅验证只读目录/FD 桥，覆盖 openat/statx/getdents、dup/fork/exec、dirfd/cwd、
   unlink 后已打开句柄、取消及 Broker/tracee 崩溃。不能在 tracer 主事件循环同步等待无界 Provider 调用。
   普通 FD 已打开后通常不能靠撤销 URI 许可追回；应停止后续访问，必要时终止相关 executor，不承诺抹除已读字节。
3. 私有 git-dir＋只读 worktree 测 status/diff/add/commit，使用合成仓库且禁用测试无关 hook/filter/网络。
   核对实际原文、提交树、外部同长度编辑、目录 rename/删除及恢复；Provider 不通知时不能靠 inotify 或不可靠 mtime 判定新鲜。
4. 写能力另测 O_EXCL 竞争、rename 覆盖、ftruncate/fsync/close、symlink/hardlink、mmap 写及 crash/revoke。
   不支持就明确 errno/unsupported；不得用 delete+rename 伪装原子替换，不能用本地锁“证明”跨 App 无竞争。
5. 构建读源码、私有输出，记录 syscall 缺口、Binder/ptrace 次数、读字节、缓存/FD/内存峰值与取消退出。
   clean 缓存可限额回收；有未确认写入不可当 clean 丢弃。只读桥不成立就停止，不升级为无限完整 POSIX 工程。

本轮仅调研及源码核对；没有改产品、构建、设备操作、权限变更或新增 Git 提交。
