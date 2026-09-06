# M11 与 main 编号合并记录

2026-09-06，项目所有者明确要求保留 main 的编号，将尚未合入 main 的 M11 冲突记录重新编号并更新引用。

| M11 原编号 | 当前编号 | 内容 |
| --- | --- | --- |
| HXA-124 | HXA-137 | Claude Free 身份登录与资格门禁 |
| HXA-125 | HXA-138 | Grok Device Code 登录与套餐门禁 |
| HXA-126 | HXA-139 | Device Code UX、诊断与 Codex 登录 |
| HXA-127 | HXA-140 | Codex 最小模型 smoke |
| HXA-128 | HXA-141 | Runtime 持久模型 Job |
| HXA-129 | HXA-142 | 订阅实验停止线 |
| HXA-130 | HXA-143 | Developer Provider 渠道修正 |
| ADR-0023（Copilot） | ADR-0026 | 第三方 Device Flow identity |

main 的 Connector HXA-124～130、ADR-0023 保留。M11 的 HXA-131～136 不变；任务依赖以 roadmap 正文为准，不以重编号后的数字大小推断时间顺序。历史 Git 提交仍保留原编号，当前完成记录、命令与引用采用新编号；本次迁移不重新声明历史测试已执行，不改变 ADR 状态或原决定。

同步范围为 main 已提交内容；主工作树的未提交修改不在本次合并中。合并和文档验证不替代新增 Provider 的功能验收。

本次同步 main `61bad35`；编号迁移提交为 `211fbb5`。合并保留两侧资源键、M11 订阅接线与 main 的预算、恢复和 Connector 实现。状态摘要按既有 HXA-135/136 完成记录修正，不再写“Provider 尚未接入”。

实际验证（2026-09-06）：

- `./scripts/check-docs.sh`、`./scripts/verify-adr.sh`、`./scripts/check-i18n.sh`、`./scripts/check-lockfiles.sh`、`./scripts/check-secrets.sh`、`git diff --check`：均通过。
- `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:cli-client:test :runtime:cli-app:testDebugUnitTest --no-configuration-cache --no-daemon --max-workers=2`：exit 0，BUILD SUCCESSFUL；393 tasks，55 executed、19 from cache、319 up-to-date。
- 此次没有安装 APK、启动模拟器或消耗真实账号额度；设备、R8 和新增 Provider 验收不属于上述合并构建结果。
