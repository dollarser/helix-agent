# 脚本入口与留存

`./scripts/check-all.sh` 是本地和 CI 共用的主机门禁入口，需要 JDK 17、Android SDK（ANDROID_HOME）和已配置的项目依赖。默认执行 source → build → artifacts，任一失败即停止；不会启动设备测试。

| 分组 | 入口 / 约束 |
| --- | --- |
| 源码与文档 | `check-all.sh --source`：脚本自测、文档、ADR、i18n、Secret |
| 编译与回归 | `check-all.sh --build`：Spotless、Detekt、JVM、库及双 flavor Debug/Release lint、四 App Debug/Release 构建、锁文件 |
| APK 边界 | `check-all.sh --artifacts`：先构建 APK，再检查 variant 与 CLI Runtime 边界 |
| 记录索引 | `python3 scripts/generate-completion-index.py`，文档门禁检查生成结果是否过期 |
| 有外部依赖的发行门禁 | `check-cli-runtime-lock.sh` 会访问并下载锁定的远端制品；`assetGate` 需要指定待发行 runtime assets。二者不能用无资产的主机门禁代替，命令按对应 HXA 执行 |
| 设备与长稳 | `run-*`、`accept-hxa-*` 保留稳定入口及既有 HXA 参数；不由 check-all 自动调用 |
| 临时调试 | 一次性编辑器、诊断与自动测试辅助脚本按日期放入 `scripts/debug/YYYY-MM-DD/`，同目录 README 说明能否重跑；产物写入忽略的 build 目录 |

历史验收脚本保持路径以供完成记录复现，不因为归档整理破坏旧命令。一次性编辑脚本禁止在最终源码重复执行。测试须显式选择自建、独占设备；不能使用其他任务启动的模拟器，用完关闭自建实例。`__pycache__/` 已由全局 gitignore 规则忽略，不提交解释器缓存。

Release 构建验证不等于签名发行、R8 行为验证、完整 SBOM/许可证验收或商店审核。CI 保留独立的提交区间 whitespace 检查。
