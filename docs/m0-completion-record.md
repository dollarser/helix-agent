# M0 工程基线完成记录

完成日期：2026-08-31。范围：HXA-001、HXA-002、HXA-003。

本记录只陈述实际执行证据。所有命令均从仓库根目录执行；未推送产生的 GitHub Actions 远端运行不计为已通过。

## 1. 验收环境

| 项目 | 实际值 |
| --- | --- |
| 主机 | macOS arm64 |
| Java | Homebrew OpenJDK 17 |
| Gradle | Wrapper 9.5.0，distribution SHA-256 已固定 |
| Android | compile/target SDK 36，min SDK 29 |
| 设备验收 | `Helix_API_36` AVD，API 36，arm64-v8a，Emulator 37.1.11 |
| 项目许可证 | Apache License 2.0 |

## 2. HXA-001 Gradle 多模块工程

实际命令与结果：

```text
./gradlew projects
exit 0；根工程及 28 个子项目可解析。

./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug test
exit 0；四个 debug APK 和根测试任务图成功。

./scripts/verify-variant-boundaries.sh
exit 0；四个 applicationId、Runtime manifest 权限、依赖图和 APK marker 边界通过。

./scripts/check-lockfiles.sh
exit 0；29 个 lockfile 重新解析后 hash 不变。
```

关键产物：

- `app-consumer-debug.apk`：`com.helix.agent`。
- `app-developer-debug.apk`：`com.helix.agent.developer`。
- `runtime:proot-app`：`com.helix.runtime.proot`，不声明 INTERNET。
- `runtime:cli-app`：`com.helix.runtime.cli`，只预留 INTERNET，不接业务。
- consumer APK 不含五个 developer-only marker；developer APK 全部包含。
- [验收命令矩阵](verification-matrix.md) 覆盖路线中的 83 个 HXA 编号。

决策记录：不适用。此任务落实已批准的模块/applicationId/变体契约；许可证由项目所有者明确选择，并由根许可证文件记录，没有形成新的候选架构决定。

## 3. HXA-002 质量和供应链门禁

实际命令与结果：

```text
./gradlew spotlessCheck detekt test lintConsumerDebug lintDeveloperDebug
exit 0；388 个任务执行或复用缓存，无 Lint issue。

./scripts/check-lockfiles.sh
exit 0；29 个 lockfile 稳定，无动态版或 SNAPSHOT 依赖。

./scripts/check-secrets.sh
exit 0；未发现匹配的常见真实 token/private key 形态。

./scripts/verify-adr.sh
exit 0；ADR 文件名、字段、章节、链接和双向取代关系机械门禁通过；当前决策记录数为 0。

git diff --check
exit 0。
```

其他证据：

- `gradle/verification-metadata.xml` 已生成；正常构建在 dependency verification 开启状态通过。
- Gradle Wrapper distribution checksum 已固定。
- CI 使用 commit SHA 固定的 checkout、JDK、Gradle 和 Android SDK actions，并执行 M0 静态检查、构建和 shell 门禁。
- GitHub Actions 远端运行需要仓库推送；当前没有伪造远端通过结论。

决策记录：不适用。该任务实现路线中已规定的机械质量门禁，未改变产品、安全或跨模块契约。

## 4. HXA-003 AppContainer 与导航壳

实际命令与结果：

```text
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
exit 0；两个变体各执行 3 个 ShellRepository 测试，0 failure。

./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug
exit 0；两个 APK 均成功。

./gradlew :app:connectedConsumerDebugAndroidTest
exit 0；API 36 arm64-v8a AVD 执行 1 个 Compose 测试，验证七个入口并导航到浏览器空状态。
```

安装启动证据：

```text
adb -s emulator-5556 install -r app/build/outputs/apk/consumer/debug/app-consumer-debug.apk
Success

adb -s emulator-5556 shell am start -W \
  -n com.helix.agent/com.helix.app.MainActivity
Status: ok；LaunchState: COLD；Activity: com.helix.agent/com.helix.app.MainActivity。
```

UI Automator 实际读取到“会话功能尚未启用”“会话状态与 Agent 循环将在 M1–M2 实现”和“当前为 consumer 分发版本”；应用进程存在。

决策记录：不适用。手工 DI 和七个 route 已由 HXA-003 明确规定，本实现没有新增跨模块公开契约或安全边界。

## 5. M0 退出结论

M0 的代码范围已完成：App 可安装、冷启动并展示可切换的七页空壳；本地 CI 等价门禁、双变体单元测试、Lint、构建和 API 36 仪器测试通过。下一项只能进入 HXA-010，不能从空壳直接跳到 Provider、浏览器或代码执行。
