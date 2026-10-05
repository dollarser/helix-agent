# Bug Fix: Mobile Use 依赖迁移遗漏 Release 锁配置

Date: 2026-10-06
Status: fixed
Related HXA: HXA-244；所有者要求修复 GitHub CI

## Problem

GitHub Android CI 在 `a0ed95b5` 和 `5a81cf74` 的 analysis/tests-build 两组失败；source 和 runtime-assets 通过。外层异常为 configuration cache 的 DefaultProvider 序列化失败，内层实际失败是 `:extensions:mobile-use:releaseCompileClasspath` 解析违反依赖锁。

## Impact

完整 Release lint/构建无法进行，Debug 局部通过不能代表 CI 通过。没有证据表明需要清空用户数据、禁用配置缓存或放宽依赖校验。

## Root cause

Mobile Use 与宿主 device-access 的依赖迁移后，仅更新了部分 Debug 配置锁。Release 缺少原有固定版本的 Shizuku 13.1.5、libsu 6.0.0 和 annotation 1.7.0；部分测试 lint 配置也未同步。配置缓存尝试保存依赖解析结果时包装了底层锁错误。

## Fix and invariants

通过现有全项目 dependencies/--write-locks 流程重新解析，只更新 `extensions/mobile-use/gradle.lockfile` 和 `tools/device-access/gradle.lockfile` 的配置归属。没有升级依赖、删除版本锁、禁用检查、修改 CI 为 Debug-only 或跳过 Release。

依赖解析通过后，完整 lint 暴露权限 UI 的 6 处 InlinedApi 错误：API29 路径引用了 API30/33 的权限名或设置动作。后续修复在权限清单、应用可见性检查、全部文件权限检测和设置回调处添加实际 SDK 判断；旧系统回退应用设置。保留 minSdk 29，不屏蔽 lint。该问题此前被更早的依赖解析失败挡住，Debug 编译也不能替代 lint。

## Alternatives considered

重跑 CI 或清缓存不能补齐提交中的依赖锁。手改锁容易漏掉传递依赖和 lint 配置，因此使用仓库已有生成/复验流程。

## Regression verification

首次 `scripts/check-lockfiles.sh` 按预期报告两份锁发生变化；重新运行验证 38 份锁稳定。完整 CI 对应主机命令为 `scripts/check-all.sh --analysis` 与 `--tests-build`，日志位于忽略的 `build/ci-lock-fix/`。远端验收以修复提交对应的 GitHub run 为准，不把本地主机执行等同远端成功。

首次完整 analysis 在依赖修复后因上述 6 处 API 错误失败（`analysis.log`）；版本判断修复后的复验记录为 `analysis-fixed.log`。这两类失败分别记录，不将后续问题归咎于配置缓存。

## Residual risk

此次仅处理依赖解析与主机 CI，不增加设备、真实模型、发行签名或 OEM 验收。后续模块依赖调整必须同步验证全变体配置，不能只运行局部 Debug 构建。

## Related records

- [原失败运行](https://github.com/dollarser/helix-agent/actions/runs/37336349390)
- [CI 说明](../development/ci.md)
- [主机与设备边界](../development/verification-matrix.md)
