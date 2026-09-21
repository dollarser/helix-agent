# Bug Fix: 只读 Git 查看执行仓库 filter

Status: fixed
Date: 2026-09-21
Related HXA: HXA-200, HXA-206
Affected modules: app Git viewer

## Problem

只读 Git status/diff 可能执行测试仓库配置的 clean filter；发现于 HXA-206 承接的历史 Git R1 验收。

## Impact

查看工作树可能产生未经用户请求的命令副作用。范围仅为已有 status/diff，不增加 Git 写入、远端或凭据功能。

## Root cause

锁定的 JGit 在普通工作树 status/diff 中可能使用 `.gitattributes` 和仓库配置的 clean filter，进而启动外部命令。新建测试仓库的复现确认命令标记被写入；“只读 API”并不能证明不会运行用户仓库配置。此前消除 TrustAll lint 的补丁不覆盖此行为。

## Fix and invariants

`GitWorkspaceReader` 改为每个仓库单独使用 `ReadOnlyGitFileSystem`，禁止发现/启动外部 Git 与进程；不更改 JGit 全局 FS。`ReadOnlyGitTree` 禁用 clean filter，并让子目录继续使用同一实现。工作树 diff 比较实际字节，不改写仓库配置；staged diff 仍读取对象库。status 不递归检查子模块工作树，保留 gitlink 记录变化。这是只读查看器的语义，不承诺复现命令行 Git 所有用户 filter 的转换效果。

## Alternatives considered

未删除用户配置或全局覆盖 JGit FS，避免查看动作改变仓库或影响其他用途；也未保留 required filter 执行后只捕获异常，因为命令副作用已可能发生。拒绝所有带 filter 的仓库会使原始字节 diff 不必要地失效，故采用局部禁止执行与原始工作树比较。

## Regression verification

- consumer/developer JVM 各 15/15，包括根目录和子目录 required filter、不执行标记、配置不变及现有状态/diff 回归。
- API29/36 × consumer/developer debug 实际 status/diff 恶意 filter/hooks/transport fixture 通过，指定 loopback listener 无连接。
- 四象限实际安装非 debuggable release 包，普通 UI 打开 diff；配置未变、命令标记不存在、指定 HTTP fixture 请求数为 0，安装 APK 哈希与测试包一致。
- 全量主机门禁和 release APK 边界通过。release 使用本机 debug 证书，仅为替换安装验证，不是签名发行。

## Residual risk

不据固定 fixture 宣称任意仓库配置、所有网络路径或完整 Git 功能都已审计。该修复属于 HXA-206 的既有 R1 范围，未改变 Runtime、Agent 授权或网络策略。

## Related records

完整命令、APK 和设备事实见[199/206 验收记录](../evidence/development/acceptance-199-206-2026-09-21.md)，原交付见[HXA-200](../completion-records/HXA-200.md)。
