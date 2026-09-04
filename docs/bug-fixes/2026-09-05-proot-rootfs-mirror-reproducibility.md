# Bug Fix: PRoot RootFS 镜像构建可复现性

Status: fixed
Date: 2026-09-05
Related HXA: HXA-081, HXA-082, HXA-073
Affected modules: `scripts/build-proot-assets.sh`, `runtime/proot-app` assets

## Problem

设备测试需要重建被 Gradle 卸载流程清除的 active RootFS。官方 Alpine CDN 下载长时间停滞；改用脚本公开支持的 `ALPINE_MIRROR` 后，最终 raw tar 未命中既有 `runtime-lock.json`。

## Impact

资产门正确 fail closed，没有把错误 RootFS 装入 APK；但镜像换源会改变最终存档，且旧内容哈希在当前相同版本 pin 下已无法重建，阻断全新设备环境的验收。

## Root cause

- 脚本把下载镜像写入 guest 的 `/etc/apk/repositories` 后直接归档，因此 transport 选择泄漏进产物内容，与“镜像只影响下载速度”的契约冲突。
- 即使恢复官方仓库文本，当前固定镜像 digest、53 个最终包及所有显式版本 pin 生成的内容仍与历史哈希不同。最终 lock 正确识别了漂移；没有足够证据把差异归因于单个包或降低校验。

## Fix and invariants

- 使用镜像完成 `apk update/add` 后，在归档前把 `/etc/apk/repositories` 恢复为固定 Alpine 官方地址；镜像只作 transport。
- 保持基础镜像 digest、组件 SHA-256、包名与版本 pin 不变；以 generate 模式发布新的最终 raw-tar 内容哈希 `674aa3ac…`，随后全新 verify 构建必须逐字节命中。
- 最终 raw tar、ELF gate 与设备端安装器仍全部 fail closed；没有增加“忽略 hash”或宽松分支。

## Alternatives considered

- 修改测试绕过 RootFS hash 或只复用旧模拟器数据会制造假绿，拒绝。
- 把镜像 URL 永久写入交付 RootFS 会让下载位置变成运行时 provenance，且不同开发者产物不同，拒绝。
- 在未验证可复现前直接接受一次 `--generate-lock` 输出会掩盖非确定性；实际采用 generate 后立即独立 verify 的双跑门。

## Regression verification

`ALPINE_MIRROR=https://mirrors.aliyun.com/alpine bash scripts/build-proot-assets.sh --generate-lock` 后，以相同环境执行 verify：两次均得到 raw tar 137,287,680 B、SHA-256 `674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`，并扫描 360 个 ELF，最小 `PT_LOAD p_align=16384`、asset gate PASS。该资产随后在 API 29/36 完成 companion 18/18 与主 App 跨 APK 8/8。

## Residual risk

Alpine 仓库对“相同版本号永不替换包字节”的外部保证仍不由 Helix 控制；最终 raw-tar 内容 lock 是权威防线，任何再次漂移都必须 fail closed、定位并显式重发 lock，不能自动接受。

## Related records

- [HXA-081 完成记录](../completion-records/HXA-081.md)
- [HXA-082 完成记录](../completion-records/HXA-082.md)
- [HXA-073 完成记录](../completion-records/HXA-073.md)
