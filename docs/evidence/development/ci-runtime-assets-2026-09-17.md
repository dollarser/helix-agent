# 远端 CI 与 Runtime 资产复核

日期：2026-09-17。关联 [HXA-193](../../completion-records/HXA-193.md)。此记录区分历史失败、修正、已通过的远端复验与剩余验收，不关闭 HXA。

## 初始远端失败

[运行35049947368](https://github.com/dollarser/helix-agent/actions/runs/35049947368) 对应 main `bcc2a6dcd5b04bc96d43d729bafd5f065b867986`。runtime-assets 在 Configure Android SDK for the JVM asset gate 失败，后续资产构建和 verify job 都未执行。日志为 `sdkmanager tools` 后的 `Warning: Failed to find package 'tools'`，退出1。

锁定的 setup-android action 的 [action.yml](https://github.com/android-actions/setup-android/blob/40fd30fb8d7440372e1316f5d1809ec01dcd3699/action.yml) 默认 packages 为 `tools platform-tools`；[源码](https://github.com/android-actions/setup-android/blob/40fd30fb8d7440372e1316f5d1809ec01dcd3699/src/main.ts) 对输入分词、过滤空项后逐包安装。两个 job 显式配置 `packages: ""`，只由原有后续 sdkmanager 步骤安装所需 platform/build-tools/platform-tools。没有跳过 SDK license、资产门禁或 APK 检查，也没有升级 action。

查询时远端 main 比本地准备基线 `0400162c` 少56个提交；最近远端失败不能代表新版本代码测试失败。仓库变量列表为空，也没有现有 Release 可供选取锁定归档；后续状态可能变化，发布前须重新核对。

## 独立的 RootFS 阻塞

在新建工作树运行默认 `./scripts/build-proot-assets.sh`：三个 Termux 包下载及哈希通过，但 Docker 重建退出2。当前镜像索引仅选择 `xz-libs-5.8.4-r0`，冲突为 `world[xz-libs=5.8.3-r0]`。这次重新复现了历史资产来源问题，但它不是上述远端运行的直接失败点。

现存锁定 raw tar 大小137287680字节，SHA-256为 `674aa3ac68200bfe67b01964573fce2c6780c726fe23ca640e998337cf47f509`，与 runtime-lock.json 一致。优先交付已有锁定归档并设置现有 `HELIX_ROOTFS_ARCHIVE_URL`，可保持已验收 Runtime 字节不变；若选择依赖升级，则必须另做 lock/许可证/设备验证，不以修改 hash 掩盖来源问题。

## 本地验证与交付边界

- `ruby scripts/debug/2026-09-17/validate-ci-yaml.rb`：workflow YAML 解析通过。
- `./scripts/check-all.sh --source`、`git diff --check`：通过。
- 默认资产重建如上失败，日志保存在忽略的 `build/ci-investigation/default-assets.log`；未删失败场景或放宽校验。
- 使用 `HELIX_ROOTFS_ARCHIVE=<本地锁定raw-tar> ./scripts/build-proot-assets.sh`：exit 0；三个Termux包重新下载并校验、归档hash匹配、360个ELF全部通过aarch64/16 KiB门禁并完成资产放置。源文件位于现有主工作树的忽略资产目录，不入 Git；日志为 `build/ci-investigation/archived-assets.log`。这是已知归档的资产准备验证，不是从滚动镜像重建成功或干净远端下载证明。

所有者随后授权推送验证。修复分支 `codex/ci-sdk-bootstrap` 已推送；[锁定资产预发布](https://github.com/dollarser/helix-agent/releases/tag/runtime-assets-20260917) 提供上述 raw tar、runtime-lock 与来源/许可证说明，不是应用发行。`HELIX_ROOTFS_ARCHIVE_URL` 配置为该版本固定资产地址，下载仍经过原有 hash 与 ELF 门禁。当前快照记录到远端准备完成，CI最终结果以修复分支的实际 Actions 为准，不预先声明通过；HXA-193 升级恢复和其他设备验收仍独立开放。

## 首次修复分支远端验证

[运行35237161334](https://github.com/dollarser/helix-agent/actions/runs/35237161334) 对应 `556b5095`。runtime-assets 成功：SDK初始化、公开归档下载与锁定hash、Linux扫描到的664个ELF校验及artifact传输均通过。macOS也完成SDK初始化，但源码门禁因 `check-secrets: ripgrep (rg) is required and not installed; refusing to pass.` 退出1；文档、ADR和国际化此前已通过。原来依赖开发机已装rg，workflow未声明此工具。

现为macOS增加显式安装/检查ripgrep的步骤，不改Secret检查或将缺工具视为跳过。远端完整验证仍须在后续运行通过；不同宿主的ELF扫描计数不冒充相同文件遍历行为，归档身份以一致的SHA-256为准。

## 原生工具链补齐

[运行35237839838](https://github.com/dollarser/helix-agent/actions/runs/35237839838) 对应 `997677d1`。两个job的SDK设置、资产管线、源码门禁通过，主机测试及完整lint/静态检查的第一条Gradle命令通过（20m19s）。后续APK构建在proot-app的arm64-v8a/x86_64 CMake配置失败：`[CXX1300] CMake '3.31.6' was not found in SDK, PATH, or by cmake.dir property.`

项目已固定CMake 3.31.6与NDK 28.2.13676358，原workflow却只装platform/build-tools，依赖开发机或runner预装工具。当前SDK可用包列表再次确认该版本存在；workflow改为显式安装并在测试前检查可执行文件和NDK元数据，不改项目版本、不移除ABI或跳过原生构建。完整远端结果仍按后续运行判定。

## 并发测试夹具修正

[运行35241184230](https://github.com/dollarser/helix-agent/actions/runs/35241184230) 对应 `a92a374a`。CMake/NDK安装与预检通过，但tools:framework的178项测试中，`resultsComeBackInCallOrderEvenWhenCompletionIsOutOfOrder` 在断言慢调用最后结算处失败。此前“结果按调用顺序返回”断言没有失败。

原夹具用150ms/20ms sleep制造完成乱序，繁忙runner不保证线程在该窗口内启动/完成；RecordingSink又以非线程安全MutableList接收并发事件。修正仅限测试：首调用等待其余两次审计记录完成的CountDownLatch，记录器改用CopyOnWriteArrayList。保留并加强断言：实际结算顺序必须2→3→1，而返回结果仍1→2→3；有界等待防止回归造成挂死。不修改生产调度器、不删除测试或增加忽略规则。

本地 `./gradlew :tools:framework:test spotlessCheck detekt --console=plain` 通过；原始报告保留在 `build/ci-investigation/framework-suite-xml`。独立JVM重复入口 `bash scripts/debug/2026-09-17/repeat-scheduler-order.sh` 已执行成功，10/10轮通过；逐轮强制执行同一测试任务，不以Gradle缓存命中算重复验证。

## 最终验证与开发基线收口（2026-09-18）

[运行35242909407](https://github.com/dollarser/helix-agent/actions/runs/35242909407) 在提交 `437f8d4972a85c737bea1baca5eb9b9675eef5f0` 全部通过：runtime-assets 2m49s，verify 35m23s。源码、主机测试、静态检查、全部 lint、依赖锁、Debug/Release 构建、APK 边界与空白检查通过；Debug APK 与 locked-runtime-inputs 均上传。此前各节的待复验描述为对应轮次的历史状态。

[PR #1](https://github.com/dollarser/helix-agent/pull/1) 已合入远端 main，合并提交 `036171286a87a9874ed69d3152c26b09d773ed0f`；合并树与上述已验证 head 一致。本地 main 与批次 B 开发基线均已同步。main push 触发的独立运行按 Actions 实际状态记录，不把 PR 运行称作 main 运行。

SDK/NDK/CMake/rg 已在耗时检查前显式准备与检查。本轮不继续拆分构建任务或改变缓存策略；35m23s 是整体验证耗时，尚没有充分阶段测量证明拆分收益。后续 CI 提效单独处理，不阻塞批次 B。

HXA-193 仍保留升级后数据/结果/验证状态恢复，以及默认滚动镜像无法按旧锁重建的边界。固定归档下载准备通过不等于源码重建通过；真实账号、真机长稳和签名发行也未由本轮关闭。
