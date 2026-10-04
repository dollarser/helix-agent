# HXA-246 本机项目主机交付记录

日期：2026-10-04。当前所有者要求参考竞品设计并重构项目，随后明确无需兼容旧接口/开发数据。基线 HEAD `53bb00f6`，工作树包含大量此前任务改动；本轮保留这些改动，不提交、不推送、不清理应用数据。设计见 [ADR-WORKSPACE-005](../../adr/workspace/005-projects.md)，使用见[项目功能](../../product/projects.md)。

## 交付范围

- Room v1 baseline 新增 projects/project_sessions；资源行移除歧义 projectId；项目主目录加入清理引用保护。项目聚合使用修订号和事务内审计。
- 项目列表、搜索、归档/恢复、编辑/删除；详情会话/文件/任务/成果；会话设置提供归属及回到项目入口。
- 新会话共享主目录与当前明确模型选择，权限来自新会话默认值；已有会话加入/移出只改归属，不移动文件。分支从未归档来源项目继承归属。
- 活动 Turn、排队输入、未完成 Goal、非终态工具调用阻止成员转移/删除。项目指令走现有 PromptRegistry；项目记忆按显式身份解析，工具进一步匹配持久 session/turn/toolCallId 和运行状态，旧调用不能重定向。
- 项目历史读取成员会话的完整记录，空集合保持为空；未归属计划不展示。文件管理器“工作目录”统一容纳项目和会话，独立首页不变。
- 不新增旧库迁移或兼容接口。旧开发数据库可能重建，数据库外文件保留。项目删除保留会话、目录、文件和已有记忆；重建同名项目不会继承旧记忆身份。

## 验证与修复

首轮编译通过后，后续检查暴露旧 Workspace 测试引用已移除字段、数据库表数仍写死 50、Room 不是 AutoCloseable 的测试写法和新增代码格式/聚合阈值问题。对应修复为新资源身份断言、当前 52 表基线、finally 显式关闭测试数据库和局部格式/职责说明；没有删除场景、放宽失败结果或跳过测试。

最终命令（日志保存在本地忽略的 `build/projects-verification.log`）：

```sh
./gradlew :core:storage:testDebugUnitTest \
  :app:testDeveloperDebugUnitTest \
  --tests '*MemoryServiceTest' --tests '*MemoryToolsTest' \
  --tests '*ShellRepositoryTest' --tests '*SettingsNavigationTest' \
  --tests '*TasksDashboardProjectionTest' \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:compileDeveloperDebugAndroidTestKotlin \
  :app:compileConsumerDebugAndroidTestKotlin \
  :core:storage:compileDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

| 检查 | 当前结果与范围 |
| --- | --- |
| Storage JVM | 230 项通过，0 失败、0 跳过；含 11 项新增项目聚合用例 |
| App Developer 定向 JVM | 40 项通过，0 失败、0 跳过；记忆服务/工具、侧栏、任务投影 |
| Consumer / Developer Debug APK | 构建通过，未安装 |
| App 两渠道与 Storage AndroidTest Kotlin | 编译通过，不代表执行通过 |
| Spotless / detekt | 通过 |
| 文档与 diff 检查 | 通过 |
| 模拟器、真机、真实模型/账号 | not requested，未执行 |

设备用例已准备：`ProjectPersistenceDeviceTest` 3 项覆盖新库重开、删除保留、活动调用核验、未完成调用阻止移动、事务回滚；`ProjectServiceDeviceTest` 2 项覆盖目录共享/成员变化及全局最近窗口之外的项目历史。它们尚未运行。

最终 JVM XML 合计 270 项通过。安装包位于 `app/build/outputs/apk/<channel>/debug/`，为当前工作树的 Debug 制品，包含本轮之前已存在的改动：

| 制品 | SHA-256 |
| --- | --- |
| `app-developer-debug.apk` | `ed6ed6d8213aa6bd72a2c64ecc141ccc0652c2920714448f4659c66a4cb9f719` |
| `app-consumer-debug.apk` | `44a2f00497040ce841364bb621bb6fc7547c3d288879292058e4b59a7bf43fce` |

## 验收边界

本记录证明本地主机候选与制品可构建，不证明设备交互、实际 Room 重开、SAF/OEM、旋转/大字体、大项目性能或发布质量。下一次设备授权后应验证创建项目、选目录、新会话、移动、归档恢复、删除保留、项目记忆、历史视图和进程重开；不得无授权启动设备或用旧任务设备结果替代。

完整项目历史目前按成员读取，极大历史量的分页体验仍可后续优化。云同步、协作、Git 自动快照/回退不属于本次交付，已有 Git 入口仍不等于恢复能力。
