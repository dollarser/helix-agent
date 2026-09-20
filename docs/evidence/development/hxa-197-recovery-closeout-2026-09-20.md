# HXA-197 输入、压力与进程恢复收口

日期：2026-09-20。本记录补齐单手动终端的生产页面压力、运行中租期及实际进程死亡对账；交付范围见[完成记录](../../completion-records/HXA-197.md)。

## 发现与修复

普通应用页面通过真实逐键事件输入 `. recover.sh` 时，原 UI `Channel<ByteArray>(4)` 把容量绑定到了事件数量，短命令也会拥塞。截图显示命令被截为 `. recove.`，页面同时提示输入拒绝。此前一次 `InputConnection.commitText` 提交整段文本的测试没有暴露此问题。

新增 `TerminalInputQueue`：32 KiB 固定字节环、合并小事件、单写者每批最多 8 KiB，另有一个在途批次。单次超过 8 KiB 或超过剩余容量整块拒绝；关闭丢弃尚未提交的 UI 输入；失败/未知输入不重发。两个主机测试覆盖逐字节中文/命令顺序、环绕、容量、复制隔离、大粘贴及关闭。Runtime 原有 32 KiB/64 块输入边界不变。

## 最终制品与执行

下列设备旅程使用相同 developer 制品：

- 主 APK SHA-256：`7c579f5794346bbb54c9fdf990ec09d2c3490bb55cdb0ac8610e759a6d36dea5`。
- 测试 APK SHA-256：`6a0e7417d701cc493443b171fc4ba1019bcd645f06d46b1ce8c37af90e72ce4e`。

所有重型命令前缀均为 `python3 scripts/debug/2026-09-18/with-host-slot.py --`。使用自有 `Helix191_API29` / `Helix191_API36` 模拟器进程、端口 5584/5586；每轮保存 owner、制品哈希及 `closed.json`，均正常退出，未借用其他设备。

| 命令 / 日志 | 实际结果 |
| --- | --- |
| `./gradlew spotlessApply`，`hxa197-input-format-v2.log` | exit 0 |
| `./gradlew :app:assembleDeveloperDebugAndroidTest :app:testDeveloperDebugUnitTest detekt`，`hxa197-input-build-v2.log` | exit 0；新增队列单测 2/2 |
| `./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest`，`hxa197-input-apk-v1.log` | exit 0；显式打包被测主 APK |
| `./scripts/check-all.sh --all`，`hxa197-recovery-all-v1.log` | exit 0；源码/格式/Detekt/单测/lint、Debug/Release、36 个锁及 variant/Runtime 边界 |
| `python3 scripts/debug/2026-09-20/run-terminal-recovery-gates.py hxa197-recovery-final-v1` | 双 API 普通应用旅程 2/2；准备 instrumentation 另计 2/2 |
| `python3 scripts/debug/2026-09-20/run-terminal-page-gates.py hxa197-pressure-final-v1 --runtime-only` | 双 API 各 49/49，56.503s / 70.733s；release APK 边界通过 |

49 项是既有 Runtime 回归与本任务测试的组合，不是 49 项新增终端功能；本轮单会话服务 3 项、页面 2 项包含在其中。未重跑主题四象限；SDK/Compose 升级的 60 项历史证据保留在[页面接线记录](hxa-197-terminal-page-2026-09-20.md)，本轮只改 developer 输入适配和测试。

## 实际验证内容

- 关闭生产终端 Activity 后，Python 输出 `中文` × 180000，即 1,080,000 UTF-8 字节，完成标记正常写出；没有 UI reader 也持续 drain。重连原 session，显示输出缺口提示及绿色 `TAIL_READY`，原环境变量保留；保存并人工核对双 API `terminal-overflow.png`。
- 真实 InputConnection 提交 8193 字符，显示输入拒绝；再用 Ctrl-C 操作前台 `sleep 120`，检查退出状态 130 和原 PID 消失。既有中文、REPL、软键盘、Activity 重建、停止结算同时回归通过。
- 7000ms 租期旅程先确认长命令 PID/cmdline 为 `sleep` 且 session 为 RUNNING，再等待 `LEASE_EXPIRED`；证明长进程消失，有停止证明但 owner 保留到显式结算。不是仅测试启动前到期。
- 普通应用经 Files → Local files → 合成目录 → Open terminal → Start session 启动；准备 instrumentation 只写文件，不启动终端。宿主杀主 PID 后，Runtime PID 和 boot_id 不变，原命令完成；重启主页面、连接原会话，实际输入读取原 shell PID 与环境变量，启动计数仍为 1。
- 再只杀匹配的 `:proot` PID，页面显示 `UNKNOWN / RUNTIME_LOST`，同一启动周期内结算按钮禁用，原持久身份不变。实际 reboot 后 boot_id 改变，用户查询原会话并显式结算，两个 owner 记录均清空；命令没有重跑，UNKNOWN 不被改写成成功。

原始事实：`build/hxa197-recovery-final-v1-api{29,36}/ordinary-terminal-result.json`；API29 主 PID 2860→3656、Runtime 3496、shell 3541；API36 主 PID 3499→4754、Runtime 4519、shell 4551。宿主使用自有模拟器管理员信号控制，不给应用 Root 能力。

## 失败证据与边界

- `hxa197-host-v1`：准备测试没有 Activity 生命周期，异步偏好未落盘；增加真实 Activity 生命周期后修复。不是终端恢复通过。
- `host-v2`：宿主输入早于 IME 焦点，改为检查系统 IME/输入视图；`host-v3` 此后暴露真实短队列丢字符，截图保留。
- `host-v4`：只打包测试 APK 导致仍安装旧主 APK；显式构建主 APK，并将页面 runner 同步改为同时打包主/测试 APK。旧结果不能判断修复效果。
- `host-v5`：已通过原 shell 重连，失败于把文字节点的 enabled 当作按钮状态；最终检查真实 clickable 父节点的禁用状态，不放宽产品断言。
- 早期 Detekt 的长行、未使用循环变量及返回数问题按规则修复，未关闭门禁。

30 分钟 idle、默认两小时的完整实际时长、OEM/Doze/热压/长稳及真实 16 KiB 设备没有在这里验证，转入既有 199 专项；ELF/APK 静态对齐不等于 16 KiB 设备运行。此次不含多终端、Agent 自动输入、真实账号或 206 产品集成验收，也未推送/合并/发布。
