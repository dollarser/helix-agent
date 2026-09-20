# 验收规则与公共命令

## 默认门禁

```bash
./scripts/check-all.sh --source
./scripts/check-all.sh --all
git diff --check
```

`--source` 只验证文档、脚本契约、国际化与 Secret；不证明功能实现。`--all` 的实际构建/测试/制品范围以脚本为准，设备、真实账号与长稳另行执行。依赖下载允许联网，本机 fixture 服务合法；默认测试不访问真实业务服务、不依赖账号或付费配额。外部 smoke 须显式启用，条件不足记 skip，启用后失败必须记 fail，不能改成 skip。

每个下一 HXA 先检查已知基线失败，保留并修复场景，不能因为“非本次新增”忽略必过门禁。依赖可为兼容性升级，但必须固定可复现版本、锁文件、来源和许可证。

## 任务验收入口

[任务索引](roadmap.md)链接每个未完成任务的范围、测试与附加要求；完成记录保存实际命令、exit code、测试数、跳过原因、设备与剩余限制。本页不再维护第二张任务状态表。

## 产品公共命令 P1/P2/P3

仓库根执行，先配置仓库要求的JDK17/Android SDK。下列是待实施任务的命令，不是本轮已通过结果。

P1（每任务）：

```bash
./scripts/check-all.sh --source
./gradlew spotlessCheck detekt
git diff --check
```

P2（审批与存储）：

```bash
./gradlew :core:model:test :core:policy:test :core:agent:test :tools:framework:test :core:storage:testDebugUnitTest
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :core:storage:assembleDebugAndroidTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

P3（产品UI/构建，200/201也需执行）：

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug
python3 scripts/debug/2026-09-09/run-owned-emulator.py --help
```

新增设备类放标准app androidTest source set；涉及Runtime的developer专属子集分开。实现者按runner实际参数保存日期脚本，API29/36分别启动新独占模拟器，不借已有serial；finally只结束自有进程。测试名称不存在/零执行/跳过不能算通过。数据库变更还要运行真实新迁移类；脚本写出最终命令、测试数、exit code与日志路径。

## 终端公共命令 G1/G2/G3/G4

先设置仓库要求的 JDK 17/Android SDK，核实 task 存在。以下命令从仓库根执行，新增测试类必须在对应 HXA 落入现有 source set；不存在时不能选择它后接受“0 tests”。

**G1：源码与格式（每阶段）**

```bash
./scripts/check-all.sh --source
./gradlew spotlessCheck detekt
git diff --check
```

**G2：主机/编译（按修改范围运行；195～199 全部运行）**

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :runtime:proot-core:test :runtime:proot-ipc:testDebugUnitTest :runtime:proot-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug :runtime:proot-app:lintDebug :runtime:proot-client:lintDebug
```

变更 storage 或 agent 时追加 `./gradlew :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest :core:agent:test`，并用独占设备执行新迁移测试。实际任务形态以实现前查询结果为准；若漂移先修矩阵，不跳过。

**G3：Runtime 回归与新增设备类**

```bash
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_29 --port 5622 --output build/terminal-api29-fresh
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_36 --port 5620 --output build/terminal-api36-fresh
python3 scripts/debug/2026-09-09/run-owned-emulator.py --help
```

前两行执行既有 Runtime 回归（2026-09-20 当前 46 项，以脚本 CASES 和实际非零用例结果为准），不包含各 HXA 全部新增测试，不能充作新增功能验收。实现者按 help 的实际参数为本 HXA 新类保存启动脚本到 `scripts/debug/YYYY-MM-DD/`，使用 developer 主 APK 与 androidTest APK、`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`。每次新 output、未占用端口与新建独占模拟器进程；禁止借用现存 serial，finally 只清理自有进程。AVD 名/端口不适用时按本机状态显式替换并记录。

**G4：集成/产物**

```bash
./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease
./scripts/check-all.sh --artifacts
python3 scripts/verify-integrated-runtime-apks.py --build-type release
./scripts/check-all.sh
```

正式 consumer 边界需扩展最终 APK/dex/native 检查到新增终端组件；不能只隐藏按钮。不得删除测试或放宽 detekt/lint 使门禁变绿；兼容性升级可以更新资产 lock，但必须重新核验来源、哈希、许可证和设备兼容性。RootFS 资产来源遵循 [HXA-193 记录](../evidence/development/integrated-developer-runtimes.md)。

## 设备、恢复与发布

- 每次运行启动自有独占模拟器，记录 PID/serial/AVD/API/ABI，拒绝借用已有实例，finally 只关闭本次进程。测试脚本先保存至 scripts/debug 日期目录，输出放忽略的 build。
- API29/36 × consumer/developer 是涉及产品/授权公共能力的基本矩阵；变体专属功能如实限定。真机/OEM/低内存/Doze/Root/系统 Binder 等证据不能由模拟器替代。
- 进程重启测试须确认 fixture 持久化及 PID 变化；Gradle connected 测试可能卸载 App，跨 run 协议采用经验证的 am instrument 入口，不能把 Activity 重建当进程恢复。
- 取消、拒绝、未知副作用、数据迁移、损坏、低空间与并发竞态按任务要求测试。未知结果只对账，不靠重放验证成功。
- 新测试类尚未实现时写明待实现；0 tests 或跳过不能完成验收。构建成功不等于功能、账户或安全验收。
- Release 追加签名、升级/回退、制品排除、SBOM/notice、权限申报与真实渠道证据，见[安全与发布](../security/testing-and-release.md)。
