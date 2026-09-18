# 实际旧 APK 覆盖升级补验（2026-09-18）

关联：[HXA-193](../../development/tasks/HXA-193.md)、[原恢复专项与审查修正](hxa-193-upgrade-recovery-2026-09-18.md)。

## 为什么此前未执行

前次执行者的 `UpgradeRecoveryDeviceTest` 在当前 APK 内使用当前 codec 构造数据，再重启 Runtime。该路径验证冷启动恢复，但没有安装旧 APK、让旧代码写入数据、再替换 APK；整合审查因此撤回整体完成声明。这是验收覆盖遗漏，不是 Android 不支持升级，也不需要真实付费账号才能补验。

## 本次实际链路

旧生产源码为 `03617128`，新生产源码为 `9055438f`。两个独立 worktree 分别构建 developer debug APK；仅向旧 worktree 添加与新版完全相同的 androidTest 源码，不修改旧版生产源码。新增测试不会作为新生产 APK 的代码打包。

新增 `ApkReplacementUpgradeDeviceTest` 通过 `upgradePhase=seed/verify` 分两次执行：

1. 全新独占模拟器安装旧 APK 和旧测试 APK。旧代码通过真实 Room/repository 保存会话、两条消息及 Provider 配置，并保存文件内容；真实 Binder 调用 debug 本地 fixture，产生成功但尚未确认消费的 Runtime Job 与事件结果。
2. 旧代码停止 Runtime，使用旧 codec 写入 PENDING 中断夹具及旧路径验证锚。PENDING 是构造夹具，不声称有真实执行中的外部请求。
3. 拉取设备上实际安装的 APK，校验其 hash 等于旧构建产物。执行 `adb install -r` 替换应用与测试 APK，不卸载、不清除数据；再次拉取 APK，校验为不同 hash 的新构建产物。
4. 新代码验证进程 PID 改变、应用 UID 保持；会话、消息内容、Provider 配置和文件字节不变；旧验证锚仍保留但不激活当前 Runtime。
5. 新 Runtime 通过 Binder 取回旧成功结果；旧 PENDING 记录结算为 `INTERRUPTED`。确认结果后存在持久消费标记，重复对账不再返回事件。

测试仅在专用两阶段 runner 下执行。普通套件未提供参数时明确条件跳过；提供未知参数则失败，不用普通单 APK 测试冒充升级验收。

## 制品身份

| 制品 | SHA-256 |
| --- | --- |
| 旧 developer debug APK | `23aa72cc54f24f56aacc71353bba320d250c94246e5ae05a4f061391e206d6a2` |
| 新 developer debug APK | `fe2b4a8be1081598424998c027c16d969799bff90ef4cb72e990dadd631a651d` |
| 最终旧 androidTest APK | `bad3fa7ad8f036e76b10beee1f99100e5c33a583d10c9dc54016ba08e471106c` |
| 最终新 androidTest APK | `5764986a188544f22ceb1110a1a5b84e1775896153c64adb5ecb530fbe5ec870` |
| 两侧相同的测试源码 | `e7fe1b1f3283756c63582be5d6803b221ce70fa086ced51f24f26c8bf307992b` |

`apksigner verify --print-certs` 验证两 APK 的签名证书 SHA-256 相同：`79dbca2336134fa85f92eeb74b6646b3e66aed91c2a76a056295bb2670d849ca`。两者 applicationId 均为 `com.helix.agent.developer`，versionCode 均为 1，versionName 均为 0.1.0。

## 复现

在两个对应 revision 的独立 worktree 中准备同一锁定归档；将本次测试源文件复制到旧 worktree 的同名 androidTest 路径，再分别构建：

```bash
HELIX_ROOTFS_ARCHIVE=<锁定归档> ./scripts/build-proot-assets.sh
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
```

新 worktree 中执行，AVD 必须专用，端口必须未占用，输出目录必须不存在：

```bash
bash scripts/debug/2026-09-18/run-apk-upgrade.sh <旧worktree> HelixApkUpgrade_API29_20260918 5670 build/apk-upgrade/api29-final
bash scripts/debug/2026-09-18/run-apk-upgrade.sh <旧worktree> HelixApkUpgrade_API36_20260918 5672 build/apk-upgrade/api36-final
./gradlew spotlessCheck detekt :app:assembleDeveloperDebugAndroidTest
bash scripts/check-all.sh --source
git diff --check
```

runner 复用 `run-owned-emulator.py`，拒绝现有 serial，自启独占进程，并在 finally 仅关闭自有进程组。原始日志和 APK 留在忽略的 `build/apk-upgrade/`，不提交二进制、真实用户数据或机器路径。

## 实际结果

| 最终运行 | 旧版 seed | 新版 verify | 安装与清理 |
| --- | --- | --- | --- |
| API29 / `api29-final` | `OK (1 test)`，1.885s | `OK (1 test)`，1.664s | 两次替换成功，`upgrade-result.json` PASS，`closed.json` exit 0 |
| API36 / `api36-final` | `OK (1 test)`，6.815s | `OK (1 test)`，4.885s | 两次替换成功，`upgrade-result.json` PASS，`closed.json` exit 0 |

合计两条完整安装旅程、四次显式阶段测试，均无失败或条件跳过。两端安装包 hash 与上表一致。API29 首轮也通过；随后仅补充无参数时的条件跳过逻辑，重建两版测试 APK，并以这里的最终双 API 重跑作为交付证据。

旧/新 APK 构建均成功，最终 `spotlessCheck detekt :app:assembleDeveloperDebugAndroidTest` 成功；`check-all.sh --source` 与 `git diff --check` 通过。两侧固定归档构建通过 hash 与 360 ELF 校验。没有把本次定向测试称为重新执行完整产品套件。

## 验收边界

- 本次是实际同签名开发包的源码版本替换，不是 versionCode 递增、正式发行签名、签名轮换或商店升级验收；后者仍属于 HXA-122/121。
- 仅证明选定 `03617128 → 9055438f` 路径。两版未改变这里使用的 Runtime codec/数据库 schema，不外推为所有历史格式迁移通过。
- 成功 Job 经过真实旧版 Binder/持久化，但模型输出来自 debug fixture；没有调用真实订阅账号。中断 Job 与旧锚是旧版序列化夹具。
- 不修改产品行为、不重做已有 Runtime 架构，不触碰日常真机或其他执行者的模拟器。
- HXA-193 的实际覆盖升级缺口由本记录单独验收；默认滚动镜像重建仍保留前次记录的未闭合边界，本次没有重跑，也不因这次专项自动宣布完整 HXA 已完成。
