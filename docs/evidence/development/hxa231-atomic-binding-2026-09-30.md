# HXA-231 R1：原子工具绑定主机验收

日期：2026-09-30。分支 `main`，基点 `7480141bafc677115df63d09a6afa5c7c733d36a` 加现有未提交工作树。此次未提交、推送、安装或启动设备，也未调用真实模型服务/账号。不能把本结果写成 clean commit 或完整产品设备验收。

源码指纹：Kotlin/Gradle Kotlin 与 src XML 的排序 SHA-256 清单摘要 `1085ba25af8d25d11cf660f7c8dfaf928b71234bcb85caaaff81812c9b7cd964`。本地清单位于 `build/r1-source-manifest.sha256`，含既有工作树改动；不是只对 R1 单独提交的验证。

## 最终门禁

通过 `scripts/with-host-slot.py` 执行，exit 0：

```sh
./gradlew spotlessApply spotlessCheck detekt -PincludeSpikes=true test \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

完整本地日志 `build/r1-acceptance.log`。`test` 包含工程/Spike JVM 测试；未受影响的任务可由 Gradle 判定 UP-TO-DATE，不声称所有 case 均强制重跑。AndroidTest APK 只编译，未执行 instrumentation。

`check-all.sh --source` 最终 exit 0，日志 `build/r1-source-acceptance.log`：文档/ADR、脚本测试、国际化与 secret scan 全部通过。`git diff --check` exit 0。源码 gate 不代表设备通过。

## 关键报告

| 模块/报告目录 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `core/model:test` | 161 | 0 | 0 | 0 |
| `core/policy:test` | 187 | 0 | 0 | 0 |
| `core/agent:test` | 195 | 0 | 0 | 0 |
| `tools/framework:test` | 220 | 0 | 0 | 0 |
| `extensions/plugin:test` | 7 | 0 | 0 | 0 |
| `extensions/mcp:test` | 52 | 0 | 0 | 0 |
| `extensions/a2a:testDebugUnitTest` | 13 | 0 | 0 | 0 |
| `provider/openai-chat:test` | 64 | 0 | 0 | 0 |
| `provider/openai-responses:test` | 64 | 0 | 0 | 0 |
| `provider/anthropic:test` | 84 | 0 | 0 | 0 |
| `app:testConsumerDebugUnitTest` | 961 | 0 | 0 | 4 |
| `app:testDeveloperDebugUnitTest` | 1009 | 0 | 0 | 4 |

表中 skipped 保留，不算 PASS。报告位于各模块 `build/test-results/`；本次范围之外的旧发行/设备报告不纳入结论。

## 反例覆盖

- AtomicToolBindingTest：并发批次读写、重复/跨 owner 冲突、删除/替换、无关更新、原样重复发布、稳定安装身份与进程 incarnation 分离、持久准备失败保留旧集合、准备期间读/准入不中断、拒绝递归发布、生产方可变集合不能修改已发布契约。
- ModelToolBindingsTest / RequestContextManifestTest：只按实际曝光名称解析，alias 不能伪造内部名，空工具面和无绑定 schema 拒绝、旧请求保持旧引用、manifest 记录和控制字符转义/大小边界。
- ToolDispatcherTest / ToolSchedulerTest：审批中替换不执行/不消费旧证明；无关发布不误伤；排队旧 read 被替换为 write 后拒绝而非套用旧 footprint；冻结的缺失绑定不会变成后来注册的同名工具。
- PluginRegistryTest：最后一项发生碰撞时，前面的候选也不发布，原快照和已安装列表保留。
- 三 Provider encoder 测试：增加 host BindingRef 前后的 wire 字节完全一致；无内部绑定元数据外发。
- McpToolDiscoveryTest：精确全名/名称命中优先；零命中保留仍可用窗口，并清除已撤销、替换或模式不再允许的项。
- 旧工具注册测试、双渠道设备夹具均迁移到统一 Binding API；删除反射修改独立实现表的路径。

## APK

| 本地产物 | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/consumer/debug/app-consumer-debug.apk` | `e704ec950f86e46238f47ab73876650de746933e3544a31712ad9b77f3174f60` |
| `app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk` | `e16f3a0c7596c68c4c5e3967d4f1057f04b02f14b0a15e4caf6abd6870ed3d09` |
| `app/build/outputs/apk/developer/debug/app-developer-debug.apk` | `3d69d3d8d81c77ab4905cdf3f1e7fea6b23b32e6ebac93cdba0e4ea7233302de` |
| `app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk` | `c10c1c51d0df8656d0324a23fe8e81f20fc8bdf9b33f77ef9f5f67b06f0fc421` |

## 边界与归因

- device / emulator / physical device / real service：**not requested**。历史 HXA-231 前置基线和 HXA-232 API36 结果不覆盖本次 R1。
- 初轮全量迁移暴露 Plugin/A2A/QuickJS 测试中残留的旧构造或 descriptor-only 注册接口；夹具已迁移，未跳过测试。新增静态检查问题已修正，最终门禁重新通过。
- 复查发现发布候选仍可能引用可变 schema/能力集合，已加入深复制/不可变快照测试。A2A 提交保持内容发布锁先于 Room 的既有顺序，未新建第二套清理或执行 owner。
- 先前 BrowserDownloaderTest 的 non-http redirect 偶发 404 根因仍未定位；本轮完整门禁通过不等于证明该偶发问题已消失。
- 远端 implementationRevision 是连接/声明身份摘要，不证明远端代码未改变。旧 executor 只随在途工作存活，Registry 不保存历史版本链；真实服务重连/进程骤停仍须后续明确授权的有界设备/服务测试。
- 本轮不调整 64-tool 上限、审批/权限范围、effect owner 或 UNKNOWN 事实；不启动 R2/J1、完整插件生命周期及新的 Activity IA。
