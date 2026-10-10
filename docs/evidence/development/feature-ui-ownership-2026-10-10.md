# 项目与模型页面归属整理

日期：2026-10-10。范围：[HXA-259](../../completion-records/HXA-259.md)，所有者继续架构/测试维护余项；没有设备或真实模型操作。

## 改动与依据

7个项目页面/组件由app/ui迁至projects/ui，15个Provider页面/表单迁至provider/ui。MainActivity、会话设置、模型入口、存储清理入口和原JVM/AndroidTest显式导入新包；公共控件留在ui。内部可见性、路由、TestTag、渠道与原业务正文/注解保留；对22个迁移文件逐项排除package/import后比较Git原文一致。历史证据中的源码链接改为当前路径，不改历史命令或当时结果。

参考[OpenCode客户端职责](https://raw.githubusercontent.com/anomalyco/opencode/dev/AGENTS.md)及[Android分层建议](https://developer.android.com/topic/architecture/recommendations)，沿用现有功能包；没有复制代码、增加模块或框架。CodeGraph有界context结果与当前源码交叉核对，原22路径SHA守卫记录于ignored build/maintenance-followup-20261010/feature-ui-before.json。

## 实际主机验证

命令：`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`。

feature-ui-host-r4.log退出0，993任务（57执行、936up-to-date）；全仓JVM、detekt/格式、双渠道Debug lint/App/AndroidTest通过。首轮脚本因文件级注解中止，保留失败日志，按Git原文及SHA仅恢复本次部分移动后修正；后续编译暴露共享控件与SaveResult导入遗漏，逐项补实际导入，不扩大可见性或弱化测试。

`./scripts/check-all.sh --source`首轮发现原HTTP策略测试固定旧ProviderFormDialog路径；仅更新读取路径，原断言保留。修正后153项host及文档/ADR/国际化/秘密门禁通过，日志feature-ui-source-r2.log；所有产物在ignored build/maintenance-followup-20261010/。

设备与真实模型状态not requested，未启动/借用模拟器、覆盖日常APK或访问账号。AndroidTest仅编译，不是设备pass；目录归属改善不证明运行性能改善。性能与真机黑屏边界仍开放。
