# Bug Fix: 格式检查遍历生成目录与并行编译竞态

Status: fixed
Date: 2026-09-18
Related HXA: HXA-195, HXA-204, HXA-205
Affected modules: root Gradle verification

## Problem

三项交付整合后的首轮 `check-all.sh --all` 在 `spotlessKotlinGradle` 失败，错误为无法读取 `tools/framework/build/classes/kotlin/test/com/helix`；没有格式规则违规。

## Impact

主机门禁会因并行编译/缓存恢复替换生成目录而失败，阻碍同一份源码的稳定验证。

## Root cause

Gradle 脚本扫描先用全目录 glob 建立 target，再通过单独的 `targetExclude` 扣除 build 文件；最初的文件树遍历仍可能访问正在被替换的编译输出目录。

## Fix and invariants

改为同一个 `fileTree` 中指定 include/exclude，使生成目录在遍历阶段排除。Kotlin Gradle 脚本的有效匹配范围及 ktlint 版本不变，不跳过任何源脚本检查。

## Alternatives considered

没有删除格式检查、忽略异常或关闭整个工程的并行构建。仅重跑可能暂时避开竞态，但不能消除扫描生成目录的原因。

## Regression verification

修改后 `spotlessApply` 与双变体 Debug/AndroidTest 构建通过，随后正常并行配置下 `bash scripts/check-all.sh --all` exit 0。完整主机测试、lint、Debug/Release、锁与打包边界通过。原始失败与成功日志分别为 `build/merge-193-195-batch-b/host-all.log`、`host-final.log`。

## Residual risk

这次验证覆盖当前构建组合，不宣称穷尽所有文件系统或并行时序；执行代码及 Android 生命周期没有因此修改。

## Related records

- [开发验证入口](../development/verification-matrix.md)
- [实施状态](../development/status.md)
