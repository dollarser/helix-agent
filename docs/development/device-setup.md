# 本地设备配置与调试

本文承接[开发环境](environment.md)的设备操作参考；验证范围见[验收矩阵](verification-matrix.md)。授权以 [AGENTS.md](../../AGENTS.md#verification-and-device-authorization) 为准，本文命令不授予真机、外部服务或宿主机设置修改权限。

## 设备配置与验证参考

本节是配置与覆盖参考，不授予设备操作权限；所有安装、启动、ADB 与 instrumentation 示例仅在当前任务已获对应授权时执行。输入默认值见[验收参考](verification-matrix.md#模拟器输入默认值)。

### 1. 按变更选择验证

- 当前无设备 CI：按变更分档执行源码检查、JVM unit tests、Spotless、Detekt、双 flavor Lint/APK、依赖/制品扫描，见 [CI 分层](verification-matrix.md#history-ci)。
- Android UI、生命周期、持久化恢复或平台能力变化时，按任务/HXA 准备适用的 instrumentation APK、用例与预期；设备执行按 AGENTS 的项目模拟器预授权与真机当前任务明确授权分别处理。未要求记 `not requested`，已要求未完成记 `pending`，不伪报设备通过。
- 本地模拟器按主机支持的 ABI 选择，Apple Silicon 使用 arm64-v8a；报告必须写真实 API/ABI，不能把一种架构的结果冒充另一种。GitHub CI 不执行设备矩阵，也不规划 emulator job。

### 2. 设备覆盖参考（按任务风险与授权选用）

| 设备 | 架构/API | 用途 |
| --- | --- | --- |
| Emulator A | 主机原生 ABI / API 29 | 最低版本行为；Apple Silicon 用 arm64-v8a，Linux x86-64 用 x86_64 |
| Emulator B | 主机原生 ABI / API 36 | 当前 target、自动测试；Apple Silicon 用 arm64-v8a，Linux x86-64 用 x86_64 |
| Device A | arm64 / API 34 或 35 | 主流真机、功耗/后台 |
| Device B | arm64 / API 36 | 新系统、真实 16 KiB page、QuickJS/PRoot 全部 ELF/smoke、前台限制 |
| Low-memory device | arm64 / 4–6 GiB RAM | QuickJS/PRoot 资源压力 |
| Rooted test device | arm64 / API 34+ | libsu 授权、拒绝、RootService；不得使用主力手机 |
| Secondary automation device | arm64 / API 34+ | Accessibility 跨 App 和敏感界面拒绝 |

QuickJS、WebView、PRoot、Accessibility、Root 和 CLI Runtime 的模拟器结果不能覆盖真实硬件、OEM 或发行矩阵；是否补做真机验收由当前任务范围与授权决定。Root/Accessibility 真机自动化使用专用测试设备和自建 fixture App。

### 2.1. 设备占用与并行 worktree

Android 设备是共享的外部状态，不受 Git worktree 隔离。开始 instrumentation 前必须先
执行 `adb devices -l`，确认目标设备没有其他任务正在安装 APK、运行 instrumentation、修改
权限/locale/网络转发或重启；无法确认空闲时，选择无人使用的设备或新建独立 AVD，不抢占
未知所有者的会话。需要长期占用时在任务记录中声明设备/API/ABI/用途，不记录真实序列号。

同一设备上的 consumer/developer、主 App/test APK/Runtime companion 安装默认串行。测试
Provider authority 必须由 `${applicationId}` 派生或显式保证变体唯一；固定 authority、端口、
applicationId、系统 locale、持久 grant 和已安装旧 APK 都可能跨 worktree 冲突。出现
`INSTALL_FAILED_CONFLICTING_PROVIDER`、`ClassNotFoundException`、找不到测试类或行为与当前
源码不符时，先核对实际安装的主包、test package、version/commit 对应产物和 companion，
再重装正确的 app/test 配对；不得把被其他分支覆盖的 APK 当作当前实现或平台证据。

设备资源不足或占用冲突属于测试调度问题，不授权降低产品要求、删除断言、跳过安全路径或
把专项设备门禁改写成 JVM/in-process 替代证据。只有最小、与 Helix 实现无关的真实边界
复现仍失败，才可以把问题升级为平台阻塞候选。

### 3. 创建 Helix AVD

安装与主机架构匹配的 Google APIs 镜像。Apple Silicon 使用 `arm64-v8a`；Linux x86-64 使用 `x86_64`，不能为了复用命令而安装错误 ABI：

```bash
helix_host_abi="arm64-v8a" # Linux x86-64 改为 x86_64
sdkmanager \
  "system-images;android-29;google_apis;${helix_host_abi}" \
  "system-images;android-36;google_apis;${helix_host_abi}"

printf 'no\n' | avdmanager create avd \
  --name Helix_API_29 \
  --package "system-images;android-29;google_apis;${helix_host_abi}"
printf 'no\n' | avdmanager create avd \
  --name Helix_API_36 \
  --package "system-images;android-36;google_apis;${helix_host_abi}"
```

参考 AVD 使用 **1080×2400 @ 420dpi**，复现该基线可在其 `config.ini` 设置 `hw.lcd.width=1080`、`hw.lcd.height=2400`、`hw.lcd.density=420`。这不是产品最小屏幕要求：窄屏、大字体、横屏用例应保留相应配置，节点不可见时先区分滚动、布局和夹具假设，不能仅放大屏幕使断言通过。已有 `wm size` / `wm density` 覆盖时，先核对目标实例及当前测试配置再决定是否恢复默认。

项目模拟器启动统一按[输入默认值](verification-matrix.md#模拟器输入默认值)启用 `hw.keyboard=yes` 与 `show_ime_with_hard_keyboard=1`，不只为 OAuth 用例启用。更改创建配置后用 `-no-snapshot-load` 或 `-no-snapshot` 冷启动；`-no-snapshot-save` 仍可能加载旧快照。实际拼音输入验证只适用于输入/IME 任务或所有者明确要求。

若同名 AVD 已存在，不要用 `--force` 覆盖；先通过 Android Studio Device Manager 检查其 API、ABI、磁盘和快照状态。普通开发从 Device Manager 启动即可；命令行冷启动参考：

```bash
emulator -avd Helix_API_36 -no-snapshot-load
adb -s EMULATOR_SERIAL wait-for-device
adb -s EMULATOR_SERIAL shell getprop ro.build.version.sdk
adb -s EMULATOR_SERIAL shell getprop ro.product.cpu.abi
```

先以 `adb devices -l` 核对刚启动实例，将 `EMULATOR_SERIAL` 替换为其明确序列号；预期分别为 `36` 和与所装镜像一致的 ABI。设备测试报告必须记录 API、ABI、模拟器/真机和实际 test count；`BUILD SUCCESSFUL` 不能单独替代功能证据。

### 4. 真机准备

1. 在专用测试设备开启 Developer options 与 USB debugging。
2. 首次连接时在设备端确认电脑指纹；不要在共享或不可信电脑上选择永久信任。
3. 执行 `adb devices -l`，状态必须为 `device`，不能是 `unauthorized` 或 `offline`。
4. 同时连接多个设备时，先设置当前终端的 `ANDROID_SERIAL`，避免 instrumentation 跑到错误设备；完成后取消该临时变量。
5. 不在日志、完成记录或仓库中保存硬件序列号、个人通知、真实账号或用户文件。

### 5. 真机安装、启动与日志闭环

先确认目标设备、目标渠道与实际系统边界，再构建当前 developer 测试主包；它尚不等于最终商店/官网 artifact。下列占位符不得替换后提交到仓库：

```bash
export ANDROID_SERIAL="<adb-device-serial>"

adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi

./gradlew :app:assembleDeveloperDebug
adb install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
adb shell monkey -p com.helix.agent.developer -c android.intent.category.LAUNCHER 1
adb shell pidof com.helix.agent.developer
```

`pidof` 有输出只证明进程存活，不代表界面、Provider 或 Tool 功能已验收。冷启动和异常日志可分别检查：

```bash
adb shell am force-stop com.helix.agent.developer
adb shell monkey -p com.helix.agent.developer -c android.intent.category.LAUNCHER 1
adb logcat -d -v threadtime | rg -i \
  'FATAL EXCEPTION|ANR in com\.helix\.agent\.developer'
```

首次启动告知、通知权限、Accessibility、All files access 和 Root 等都必须由测试人员在 App/系统 UI 中做真实选择。只读查验通知状态的示例：

```bash
adb shell dumpsys package com.helix.agent.developer | \
  rg -A2 'android.permission.POST_NOTIFICATIONS'
```

不使用 `pm grant`、直接改 App 数据库或自动点击敏感系统页伪造用户授权。只需重装测试 APK 时不要顺带清除主包；若任务确需处置测试数据，按 AGENTS 的设备类型、目标核验与当前用户限制执行。完成后执行 `unset ANDROID_SERIAL`。

### 6. 真机访问本机或远程自建模型

先分清两个“本机”：手机中的 `127.0.0.1` 是手机自己，开发机中的 `127.0.0.1` 是开发机自己。`ssh -L 30008:127.0.0.1:30008 <ssh-host>` 默认只在开发机的 loopback 上监听，手机不能通过开发机的局域网 IP 访问它。

USB 连接调试时优先使用 `adb reverse`，无需向局域网开放模型端口：

```bash
# 终端 A：只向开发机 loopback 建立到远程模型服务的隧道
ssh -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:30008:127.0.0.1:30008 <ssh-host>

# 终端 B：将手机 127.0.0.1:30008 反向到开发机 127.0.0.1:30008
adb reverse tcp:30008 tcp:30008
adb reverse --list
```

此时 App 或 instrumentation 使用 `http://127.0.0.1:30008/v1`。远程 SGLang 可以继续只监听远程主机的 `127.0.0.1:30008`；SSH 进程是远程目标的访问者。调试结束后移除 reverse，并在终端 A 按 `Ctrl-C` 停止 SSH：

```bash
adb reverse --remove tcp:30008
```

只有在验证 Wi-Fi/实际 LAN 路径时，才把 SSH 本地转发绑定到开发机的具体局域网地址：

```bash
helix_host_ip="<development-host-lan-ip>"

ssh -N -o ExitOnForwardFailure=yes \
  -L "${helix_host_ip}:30008:127.0.0.1:30008" <ssh-host>
```

不建议绑定 `0.0.0.0`；它会向开发机所有可达网卡暴露端口。手机和开发机必须位于互通网段，并允许 macOS/Linux 防火墙接收该端口。分层验证：

```bash
# 开发机：必须显示具体 LAN IP，而不是只有 127.0.0.1
lsof -nP -iTCP:30008 -sTCP:LISTEN
curl --fail --max-time 5 "http://${helix_host_ip}:30008/v1/models"

# 手机：先只验证 TCP 可达，再跑 Helix Provider smoke
adb shell nc -zvw 3 "${helix_host_ip}" 30008
```

developer 真机的 SGLang 产品类型冒烟测试可按方法级逐项执行，避免 OEM 后台策略中止整类 instrumentation 后丢失已完成项的证据：

```bash
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
adb install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
adb install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk

helix_smoke_class="com.helix.app.provider.SelfHostedSmokeTest"
helix_runner="com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"

adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangConfigurationCheckAndModelListPass" \
  "${helix_runner}"
adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangTextStreamCompletesWithTextDelta" \
  "${helix_runner}"
adb shell am instrument -w -r \
  -e helix.smoke.host "${helix_host_ip}" \
  -e class "${helix_smoke_class}#sglangToolCallCompletesWithClosedToolIndex" \
  "${helix_runner}"
```

使用 USB reverse 时将 `helix_host_ip` 设为 `127.0.0.1`。上述 SGLang smoke 固定使用 `30008`；同类中 Ollama smoke 使用 `11434`，服务不存在时必须显式 skip，不能记录为通过。明文 HTTP 可达性不是工具授权：自配 Provider 按当前 HTTP 提示契约保存，不再要求旧版逐 host:port 确认；Android 系统权限、MCP annotation 或进入 Advanced 均不能代替实际模型外发、工具或账号操作的权限边界。无认证模型端口只能在可信网络中临时开放。

本节真机示例只清理本轮 instrumentation 测试包，保留主 App 及其用户数据；项目模拟器的数据处置另按 AGENTS 与当前任务限制执行：

```bash
adb uninstall com.helix.agent.developer.test
adb shell pm list packages | rg 'com\.helix\.agent'
```
