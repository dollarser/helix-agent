# 同 APK isolated UID PRoot 可行性验证

日期：2026-09-16。源码起点：`e28f4d14`。这是 HXA-209 A 的可行性结论与范围调整，不是 HXA-209 完成记录，也不是禁网功能验收通过。

## 结论与所有者决定

所有者指示：“验证 B 的可行性，可行就 B，不可行保持现状，不强求禁止联网”。

**B 不能直接承载当前 PRoot 的文件系统与任务模型，本轮不采用。** 保留 developer 单 APK、共享 UID 的 PRoot/Subscriptions；不增加独立 Runtime APK，也不把 Shell 改成遇到禁网设置就拒绝执行。统一 Agent 工具禁网配置从本轮 HXA-209 移出，CUSTOM 不提供无法兑现的联网 DENY；具体工具禁用和仍有要求的文件/远端写权限不受此决定影响。

这不是“Android 上 isolated UID 永远不能执行代码”的结论。系统 Shell、APK 原生程序和 tracing 均可用；失败在当前 PRoot 必需的 RootFS、临时目录和工作目录路径。继续实施需要另造文件代理/虚拟文件系统或更换执行方案，而不是简单设置 isolatedProcess 或多传几个 PFD。按所有者的回退条件，本轮不扩展到这些新架构。

## 方法与对照

使用 debug-only Service，non-exported、isolatedProcess=true；每个探测操作通过 bindIsolatedService 使用独立实例。测试使用合成文件，不访问真实会话、凭据或用户文件。主进程和 isolated 进程运行同一个 APK 内的 PRoot 二进制及同一条任务命令，主进程必须先成功读取合成工作文件。

为了避免把旧启动路径 `/system/bin/linker64` 被拒绝误判为不能执行 native：另将可执行文件按 `lib*.so` 打包到 APK 的 nativeLibraryDir，直接启动它，并单独验证原生 fork/PTRACE_TRACEME。PRoot 重用项目已有锁定制品；仅在实验副本把 DT_NEEDED 的 libtalloc.so.2 字符串等长改为 libtalloc.so，使 Android 标准 native 提取能够承载依赖。原资产、hash/lock、生产启动链和许可证均不改；实验二进制不提交。

## 实际结果

新建独占 API29、API36 arm64 模拟器，各执行一次最终 instrumentation 测试，均为 `OK (1 test)`，无跳过。每项测试包含主 UID 对照和 12 个独立 isolated 操作。测试通过只表示取证流程完成，下表明确区分操作失败。

| 检查 | API29 | API36 | 含义 |
| --- | --- | --- | --- |
| 独立 UID | 是，与宿主不同 | 是，与宿主不同 | isolatedProcess 生效 |
| INET socket | EACCES | EACCES | 独立进程没有网络权限 |
| 单个文件 PFD 读取 | 成功 | 成功 | 可以接收明确授予的文件句柄 |
| `/proc/self/fd` 重新打开该文件 | EACCES | EACCES | PFD 可读不等于可按路径重新打开 |
| 目录 PFD 传递 | Binder 事务拒绝；SELinux 有目录 read denial | 同样拒绝 | 不能把宿主目录句柄当共享工作目录 |
| 主 App 工作文件直接读取/写入 | EACCES | ENOENT | 宿主私有存储不可作为 isolated 工作目录 |
| 系统 Shell | exit 0 | exit 0 | 不应笼统声称 Shell 不能执行 |
| APK 原生执行＋fork/tracing | exit 0，TRACEME=0 | exit 0，TRACEME=0 | 原生执行本身不是决定性阻塞 |
| `/system/bin/linker64` 启动 | EACCES | EACCES | 原生产启动路径也不能原样复用 |
| 原生共享库加载 | 成功 | 成功 | JNI 不是被一概禁止 |
| PRoot `--version` | exit 0 | exit 0 | 程序/依赖打包已可运行，不等于任务可用 |
| 主 UID 的 PRoot 文件任务 | exit 0，读到 SYNTHETIC_INPUT | exit 0，读到 SYNTHETIC_INPUT | 同制品与任务的正对照 |
| isolated UID 的同一 PRoot 任务 | exit 1，can't sanitize binding，Permission denied | exit 1，can't sanitize binding，No such file or directory | 当前 RootFS/工作目录绑定失败 |

目录 FD 失败的 Binder 文案含“remote process probably died”，不能据此断言服务真的崩溃；依据是事务拒绝与 SELinux 日志。API36 的 ENOENT 也不能误判成宿主没创建文件：同一文件已在主 UID 对照和 PFD 读取中验证存在。

工作文件访问是后续任务生命周期的前置条件，未满足前不宣称完成 PRoot 执行、取消、恢复、产物导回的 isolated 全链路验收。未验证 release、其他 ABI、OEM 真机；本决定是当前支持基线下不采用现有 PRoot 的直接 isolated 迁移，不是普遍不可能性证明。

## 复现与产物

源码与 runner 位于 [isolated-proot-spike](../../../scripts/debug/2026-09-16/isolated-proot-spike/README.md)。fixture 只在显式构建时临时进入 developerDebug/androidTestDeveloper，结束后移除；正常构建不包含 Service、探针或额外 native 库。consumer 不变。

```bash
bash scripts/debug/2026-09-16/isolated-proot-spike/build.sh
POC_AVD=Helix_API_29 python3 scripts/debug/2026-09-16/isolated-proot-spike/run.py
POC_AVD=Helix_API_36 python3 scripts/debug/2026-09-16/isolated-proot-spike/run.py
```

完整报告与 logcat 保存在忽略的 `build/isolated-proot-spike/`。runner 拒绝既有 serial，以自己启动的 emulator PID 在 finally 中结束。两个最终实例已关闭，既有 emulator-5554 未使用、未停止。

生效决定已收敛到 ADR-PERMISSIONS-001、ADR-RUNTIME-001 与 HXA-209；不把研究探针接入生产，不修改已有网络策略，也不把撤销需求写成测试通过。

## 收尾验证

- 归档后的 build.sh 临时接线/构建/清理流程实际执行成功。
- 清理后重新执行普通 `:app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest`，成功；APK 无实验 native 库，合并 Manifest 无实验 Service。
- `./scripts/check-all.sh --source` 通过：435 Markdown、193 HXA、29 ADR、6 个脚本测试，以及国际化和密钥扫描。
- 脚本语法检查、native 编译警告检查及 `git diff --check` 通过。未修改生产 Kotlin/C 或数据库，不宣称重新执行全产品设备套件。
