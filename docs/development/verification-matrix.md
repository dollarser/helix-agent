# 验收规则与公共命令

## 按变更范围选用的主机命令

本页是命令参考，不要求每个任务运行所有分组。当前任务/HXA 明确要求的门禁仍需执行；纯文档、小范围源码和跨模块行为变更分别选择相关检查。

```bash
./scripts/check-all.sh --debug
git diff --check
```

日常源码编辑与调试使用默认的 `./scripts/check-all.sh` 或显式 `--debug`，执行源码检查、Debug 静态分析、测试/构建及制品边界检查。纯文档或其他有界改动按风险选取相关检查；发布准备、明确要求的全门禁，以及涉及 R8、keep rule、JNI 或反射入口的回归再选用 `--all`。

```bash
./scripts/check-all.sh --source
./scripts/check-all.sh --all
```

`--source` 只验证主机脚本测试、文档/ADR、国际化与 Secret，不证明功能实现。`--all` 另含 Release 分析、构建与制品验证；实际范围以脚本为准，Debug 通过不代表 Release 通过。

GitHub Actions 始终不启动模拟器、不连接真机、不执行 device instrumentation/matrix；CI 可做主机测试、静态分析、APK 与 AndroidTest APK 编译。AI 代理默认只准备设备制品/清单，只有项目所有者在当前任务明确要求模拟器或真机验证时才运行对应本地设备测试。状态统一为：未要求=`not requested`；已要求但尚未完成/条件缺失=`pending`；只有本轮实际执行才可记 `passed`/`failed`。真实账号与长稳另行执行。依赖下载允许联网，本机 fixture 服务合法；默认测试不访问真实业务服务、不依赖账号或付费配额。外部 smoke 须显式启用，条件不足记 skip，启用后失败必须记 fail，不能改成 skip。

当前必过主机门禁的基线失败仍需修复，不能因“非本次新增”忽略；项目所有者已报告的相关设备失败也须修复并准备复验。设备许可、状态与依赖升级边界按 [AGENTS.md](../../AGENTS.md)，未要求的设备矩阵不自动阻断主机交付。

## 任务验收入口

[任务索引](roadmap.md)链接每个未完成任务的范围、测试与附加要求；完成记录保存实际命令、exit code、测试数、跳过原因、设备与剩余限制。本页不再维护第二张任务状态表。

199/206 的共享实际执行入口为 `scripts/run-acceptance-matrix.py`，在共享 host slot 下复用 owned runner；`verify-terminal-runtime.py` / `verify-product-journeys.py` 的 `--owned-run` 只验证已有批次证据，`DEVICE_BATCH_PASS` 不表示整项任务通过。完整源码/制品/设备与真机边界见[本轮验收记录](../evidence/development/acceptance-199-206-2026-09-21.md)。

## 产品公共命令 P1/P2/P3

仓库根执行，先配置仓库要求的JDK17/Android SDK。下列是待实施任务的命令，不是本轮已通过结果。

P1（源码与格式）：

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
```

新增设备类放标准 app androidTest source set；涉及 Runtime 的 developer 专属子集分开。AI 代理默认编译测试 APK 并按 runner 实际参数准备日期脚本和清单；只有 owner 当前任务明确要求设备验证时才启动/使用相应模拟器或真机。执行模拟器验证时使用明确归属的独占实例，不借未授权 serial；执行真机验证时只使用 owner 明确指定或明确授权的设备，并保留无关用户数据。测试名称不存在、零执行或跳过不能算通过；持久化与恢复的真实设备用例同样遵守 owner-explicit 规则，并记录命令、测试数、exit code 和日志路径。

## 终端公共命令 G1/G2/G3/G4

先设置仓库要求的 JDK 17/Android SDK，核实 task 存在。以下命令从仓库根执行，新增测试类必须在对应 HXA 落入现有 source set；不存在时不能选择它后接受“0 tests”。

**G1：源码与格式**

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

变更 storage 或 agent 时追加 `./gradlew :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest :core:agent:test`。模型准备适用的持久化/恢复测试 APK 和人工步骤；用户用自有独占设备执行并回填。实际任务形态以实现前查询结果为准；若漂移先修矩阵，不跳过。

**G3：Runtime 回归与新增设备类（用户手动执行，或所有者当次明确授权后由代理执行；GitHub Actions 不运行）**

```bash
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_29 --port 5622 --output build/terminal-api29-fresh
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_36 --port 5620 --output build/terminal-api36-fresh
python3 scripts/run-owned-emulator.py --help
```

前两行执行既有 Runtime 回归，以脚本 CASES 和实际非零方法结果为准，不包含各 HXA 全部新增测试，不能充作新增功能验收。模型按 help 的实际参数为本 HXA 新类准备启动脚本到 `scripts/local/debug/YYYY-MM-DD/`，交付 developer 主 APK 与 androidTest APK、`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner` 的人工步骤。用户手动运行时使用新 output、未占用端口和自有独占模拟器进程；默认不借现存 serial，只关闭自己启动的进程。所有者指定已有设备时明确记录使用边界，不套用 owned runner 的自动关闭流程。AVD 名/端口不适用时按本机状态显式替换并记录。

**G4：集成/产物**

```bash
./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease
./scripts/check-all.sh --artifacts
python3 scripts/verify-integrated-runtime-apks.py --build-type release
./scripts/check-all.sh --all
```

正式 consumer 边界需扩展最终 APK/dex/native 检查到新增终端组件；不能只隐藏按钮。不得仅为门禁变绿删除测试或放宽 detekt/lint；已接受的新契约可以替换旧实现专属断言，同时保留有效能力及权限、取消、恢复等正确性覆盖。兼容性升级可以更新资产 lock，但必须重新核验来源、哈希、许可证和设备兼容性。RootFS 资产来源遵循 [HXA-193 记录](../evidence/development/integrated-developer-runtimes.md)。

## 设备、恢复与发布

平台/外部协议兼容和当前状态的原子持久、取消、恢复仍须按影响验证。上线前不为旧 Helix 内部格式或开发测试库新增 App 兼容迁移链；必要时采用外部一次性转换，或在具体操作获授权后定向干净安装。测试数据无需保留不等于自动获得清库、卸载或使用设备的权限，不触及无关数据；内部版本约束见 [AGENTS.md](../../AGENTS.md)。

日常设备完成测试后，移除本轮使用且已确认身份的 instrumentation/test APK，保留主应用及用户数据；清理前记录包名和是否仍被其他任务使用。Helix 的测试包含语音识别 Activity 与文档 Provider 夹具，长期留在日常设备会出现在系统选择器中。遇到此类残留，应查询具体 Intent 的处理包，卸载已确认的测试包后复查，不按 `test` 字样批量删除未知应用。

- 默认由用户手动运行模拟器与真机验收；代理只有取得所有者对当前任务、相应设备类型与范围的明确授权后才执行。默认使用自有独占模拟器，记录 PID/serial/AVD/API/ABI，只关闭自己启动的进程；所有者明确指定日常实例时按指定范围使用并保留既有数据。未获当次设备授权时，代理仅准备 APK、测试 APK、脚本、fixture、预期与记录模板；脚本放 `scripts/local/debug/YYYY-MM-DD/`，运行输出放忽略的 `build/`。
- API29/36 × consumer/developer 是涉及产品/授权公共能力的参考矩阵，由用户按任务适用范围手动运行并回填；变体专属功能如实限定。真机/OEM/低内存/Doze/Root/系统 Binder 等证据不能由模拟器替代。未回填不得记设备通过；推送另按当前授权和已接受的交付条件判断，未要求的设备矩阵不自动成为所有主机切片的前置条件。
- 进程重启测试须确认 fixture 持久化及 PID 变化；Gradle connected 测试可能卸载 App，跨 run 协议采用经验证的 am instrument 入口，不能把 Activity 重建当进程恢复。
- 取消、拒绝、未知副作用、当前状态恢复、损坏、低空间与并发竞态按任务要求测试；外部一次性数据转换仅在任务涉及该操作时验证。未知结果只对账，不靠重放验证成功。
- 新测试类尚未实现时写明待实现；0 tests 或跳过不能完成验收。构建成功不等于功能、账户或安全验收。
- Release 追加签名、升级/回退、制品排除、SBOM/notice、权限申报与真实渠道证据，见[安全与发布](../security/testing-and-release.md)。

最终候选须对应成功构建、源码输入与实际安装对象；失败构建遗留 APK 的运行只能保留为旧包证据或 INVALID，不能算本次修改通过。需要真实模型验收时，分别核对工具执行、独立效果、持久结果与实际模型消费；只有统计汇总通过不能关闭业务失败。具体做法见[调试手册](debugging-playbook.md#成功构建与最终候选)。

### 模拟器输入默认值

每次已获授权的模拟器启动须启用宿主键盘 `hw.keyboard=yes`，并设置 `show_ime_with_hard_keyboard=1` 保留 IME。保留已有中文 IME 和简体中文拼音配置；新 AVD 交付 owner 前准备该语言。现有 runner 通过 `scripts/emulator_input.py` 应用键盘默认值。只有任务涉及输入/IME 或 owner 明确要求准备手动中文输入时，才验证实际拼音组合与中文提交；这不是其他设备测试的额外阻塞条件，也不授予设备操作权限。
