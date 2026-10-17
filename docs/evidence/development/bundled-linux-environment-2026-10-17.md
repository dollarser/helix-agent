# 内置 Linux 环境默认使用与维护收敛

Date: 2026-10-17
Task: [HXA-305](../../development/tasks/HXA-305.md)
Status: 已完成；全仓主机门禁通过；设备与真实模型 not requested

## 范围和设计判断

从干净 `main` / `8431718f` 开始。Owner 要求优化 PRoot 设置，并追加 Advanced 默认开启的设计；本轮采用内置能力默认可用、首次获准执行时准备资源。启动应用、打开设置、切换权限模式、发现工具和恢复原任务均不安装、不发起命令。权限模式不会自动提升，也不增加常驻保活或新的启用开关。无本地技能目录；使用已有 CodeGraph 定位并核对当前源码，未引入新框架或依赖。

研究采用点及原始来源见 [HXA-305](../../development/tasks/HXA-305.md#一手资料与采用点)：借鉴 Claude Code 使用与诊断分离、PRoot-Distro 区分维护动作和数据影响、Android 设置的状态与次级操作分组。Helix 保留单 APK、私有执行进程、会话/工具授权和 Android 资源限制，不照搬独立运行时下载、发行版管理或命令行直接删除。

## 实现和审查记录

- 设置名称统一为“运行环境”“Linux 运行环境”。概览只展示状态、按需准备说明和“环境详情”；管理页按状态提供准备、检查安装、更新、修复或删除并重新准备。维护、恢复上次环境、删除、开源许可和技术详情折叠展示。离线许可与来源仍随包可查。
- 删除旧 `VerifiedRuntimeStore`、手工验证记录、新基线确认与双重删除入口。当前激活记录、安装清单、版本和必要文件决定状态；显式检查面对实际安装内容，检查失败写回原清单，不能靠旧记录恢复“已准备”。诊断握手仍绑定实际安装与当前包的版本。
- `RuntimePreparation` 对缺失环境的并发首次准备去重，已准备快路径不锁住普通执行。已有损坏/过时环境不自动替换；激活记录丢失而旧安装仍存在时拒绝自动清理，要求明确重置。准备失败如实报告。
- `ProotJobRunner` 在原输入校验、授权和容量接纳之后准备资源，并在启动命令前重新检查原取消/预算。人工终端先占用容量，再准备；失败释放未启动终端的预留。查询、附着与恢复没有接入准备入口，原作业身份与结算不变。
- 维护继续受真实任务/终端占用限制。删除一次确认，取消不改变安装；重装明确内部自装软件和文件不合并、保留一个恢复版本；删除说明内部尚未收取的结果会丢失，已保存会话/工作区文件不删。
- 收尾复查实际复现 Kotlin `File.deleteRecursively()` 会跟随目录链接：在合成环境中删掉了相邻工作区标记，悬空根链接还会被误报已删除。改用不跟随链接的树遍历，覆盖环境删除、安装临时/旧版本清理、作业 payload 和日志清理；删除失败返回失败，扫除列表只报告真正删除的目录。未宣称 PRoot 成为恶意代码安全沙箱。
- 三套语言资源同步；移除“Linux 沙箱”的误称，并明确删除后下次获准使用会重新准备。修正打包来源说明中已失效的“默认禁网/禁止包安装”描述，许可正文未变。后续 [HXA-288](../../development/tasks/HXA-288.md#当前-linux-环境维护基线hxa-305) 保留新契约与反例测试，不保留旧实现兼容要求。

主要改动入口：

| 职责 | 文件 |
| --- | --- |
| 设置概览和维护交互 | [ProotRuntimeSection](../../../app/src/main/kotlin/com/helix/app/ui/ProotRuntimeSection.kt)、[ProotRepairActivity](../../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotRepairActivity.kt)及对应三套语言资源 |
| 安装事实与首次准备 | [RuntimeInstallation](../../../runtime/proot-core/src/main/kotlin/com/helix/runtime/proot/core/RuntimeInstallation.kt)、[RuntimePreparation](../../../runtime/proot-core/src/main/kotlin/com/helix/runtime/proot/core/RuntimePreparation.kt) |
| 生产执行接入 | [ProotJobRunner](../../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt)、[ProotTerminalHost](../../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotTerminalHost.kt)、[ProotToolModule](../../../app/src/developer/kotlin/com/helix/app/proot/ProotToolModule.kt) |
| 安装状态与连接诊断 | [ProotRuntimeSupervisor](../../../runtime/proot-client/src/main/kotlin/com/helix/runtime/proot/client/ProotRuntimeSupervisor.kt)、共享就绪投影及两渠道接缝 |
| 文件清理边界 | [RuntimeTreeDeletion](../../../runtime/proot-core/src/main/kotlin/com/helix/runtime/proot/core/RuntimeTreeDeletion.kt)、安装清理、环境删除、作业/日志调用方及回归测试 |

## 当前验证

原始命令日志位于忽略目录 `build/hxa305/`，临时复现/统计脚本位于忽略的本轮 debug 目录。所有文件与链接测试均用合成临时数据。

- `:runtime:proot-app:testDebugUnitTest --tests '*ProotRuntimeRemovalTest'` 修复前 5 项中 3 项失败，准确覆盖目录链接、根链接与悬空链接；失败保存在 `removal-regression-before.log`。修复后该套件新增作业 payload 反例，共 6 项通过。
- `./gradlew :runtime:proot-core:test :runtime:proot-app:testDebugUnitTest :runtime:proot-client:testDebugUnitTest :app:testDeveloperDebugUnitTest :app:testConsumerDebugUnitTest :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin` 退出 0，见 `targeted-final.log`。安装套件 29 项、删除套件 6 项、日志套件 11 项均通过。新增共 14 项 JVM 回归（9 项安装事实/并发/失败、4 项删除、1 项日志链接清理），并加强原有两个更新/孤立目录清理测试。旧人工验证存储及其 9 项测试随旧机制删除，边界由实际安装事实回归替代，未跳过失败测试。
- 最终 `./scripts/check-all.sh --all` 退出 0，见 `all-gates-closeout.log`；删除修复后、最终两处文案调整前的门禁在 `all-gates-final.log`，不混用制品。Python 275 项通过；Gradle 本轮任务对应 XML 共 6,089 项，6,081 通过、零失败/错误、8 项 opt-in 跳过（包含本轮有效 up-to-date/cache 报告）。Runtime 四模块共 262 项零跳过；完整列表在本地 `test-summary.json`。
- 8 项跳过为两渠道各四项外部包/配置/公开服务验收：QwenWork、WorkBuddy 包，固定公开配置，以及公开文档 MCP。此默认门禁未提供其 opt-in 输入；HXA-303 的历史补验不算本轮运行，也不重新列为当前任务阻塞。
- 格式、detekt、双渠道全部 lint、Debug/Release/R8 构建、release-smoke 测试 APK 编译、42 份依赖锁与渠道/运行时制品边界通过。文档、ADR、三语言资源及秘密扫描通过；日志中的模拟设备故障文字属于 Python 报告器反例夹具，不是本轮设备活动。
- 实施中发现的 consumer 类型接缝、设备测试导入、格式/复杂度及删除旧文件造成的历史文档断链已修正；历史记录仅修复失效链接，没有改写当时证据。未降低门禁。

## 未执行边界

设备、日常模拟器、真实模型和外部账号均 **not requested**，没有启动、连接或安装；AndroidTest 编译不能算设备测试。新首用体验、实际 Android 原生命令、后台生命周期及 FFmpeg/PRoot 媒体端到端仍需在获得本轮设备授权后独立执行；历史 HXA-304 结果不覆盖此版本。全仓主机结果也不证明所有 OEM、ABI 或真实模型任务可用。

保持 0.0.6 / versionCode 1，没有提交、推送、发布或执行后续整体重构。
