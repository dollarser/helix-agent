# Mobile Use 模拟短按：模拟器验证

日期：2026-10-04。所有者在模拟短按实现后明确要求模拟器测试。本轮使用自有测试页面，不操作微信/抖音/小红书或真实账号；不验证检测规避。

## 当前通过范围

| 设备 / 后端 | 实测按压时间 | 独立页面事实 |
| --- | --- | --- |
| `Helix_API_36`，API36 / Shizuku UID 2000 | 98、73、101 ms | 三次调用分别累计 1、2、3 次 DOWN、UP 和 click；每次同点，未发生拖动 |
| `Helix_MobileUse_Root_API34_20261004`，API34 / 应用授权 Root | 125、122、111 ms | 三次调用分别累计 1、2、3 次 DOWN、UP 和 click；每次同点，未发生拖动 |

两组均经已注册 Mobile Use 插件和生产 Dispatcher 执行，返回实际后端与成功审计，每次一条 tool_dispatch。预取消明确返回 Cancelled；READ_ONLY、旧 scope、范围外调用被拒绝；不存在的目标返回 TARGET_NOT_FOUND。三次结束后再次观察计数仍为 3。**2 个设备用例通过，共 6 次生产路径短按**，不是两台设备的完整回归。

生产请求时长为 60–120 ms；Root 的 125/122 ms 表明平台调度会延长实际事件时间。本用例保留 60–250 ms 的设备容差，没有将实测超出 120 ms 隐去或称为严格实时保证。中途取消、进程死亡和 UNKNOWN 恢复时序未做新的设备矩阵；本轮取消仅覆盖派发前。

## 失败、定位与修复

早期失败保留在日志，不计入通过：

- 独立测试 APK 进程不包含完整 Kotlin runtime，捕获可变局部变量引入 `Ref$IntRef` 后崩溃。将触摸计数改为 Activity 字段，没有往生产 APK 添加依赖。
- API36 的测试按钮被标题栏遮住，层级存在但命令执行后没有点击。截图、层级和一次宿主直接输入诊断定位遮挡；调整测试页面布局预留顶部空间，统一短按 fixture 的按钮文本显示。宿主诊断输入不计入生产验证。
- 测试等待函数在首次成功后再次读取瞬态状态而误报；改为首次满足即返回。观察结果改为等待新鲜页面事实，不对点击重试。
- 授权范围和页面切换后立即调用曾得到 ACTION_OUTCOME_UNKNOWN。增加基于两次同 generation 观察的页面稳定等待后，两组正式复验通过。没有放宽 UNKNOWN 或拒绝断言，也没有修改生产回退规则；不能据此宣称所有动态窗口竞态消失。

本轮修改仅涉及测试夹具、设备验证脚本和文档；生产短按实现沿用上一轮候选。

## 命令与制品

```bash
./gradlew :app:assembleDeveloperDebugAndroidTest --max-workers=2
python3 scripts/debug/2026-10-04/run-touch-press-device.py --serial emulator-5554 --backend shizuku
python3 scripts/debug/2026-10-04/run-touch-press-device.py --serial emulator-5572 --backend root
./gradlew spotlessCheck detekt --max-workers=2
bash scripts/check-docs.sh
git diff --check
```

主机日志、失败日志、页面诊断和本轮正式通过记录保存在忽略目录 `build/mobile-use-touch-device-2026-10-04/`。正式通过文件为 `shizuku-1791097816.txt`、`root-1791097816.txt`。

| 制品 | SHA-256 |
| --- | --- |
| Developer debug App | `4ee1ece9a9db10b7750ff031ad26eb7b8b1e16d35d22e739a8eecffda7c6dd59` |
| Developer AndroidTest | `587771b6cc72c12faac948e33122c997359712650d493ace1e0f7dbb5875db9a` |

## 数据与设备恢复

API36 的快照保存返回 `UNSUPPORTED_VK_APP`，未声称快照成功。两台模拟器均在安装前备份原 APK 与应用私有文件至忽略目录；测试结束后恢复原 APK 和捕获的文件，没有清除应用数据。测试脚本恢复原无障碍设置，清理本次会话并断开本次 Root 连接。API34 冷启动后使用既有官方 Magisk 临时 emulator setup 恢复应用 Root，没有改写 SDK 共享镜像；结束关闭本轮启动的 API34，原在线 API36 保留。设备恢复到测试前 App，因此不声称模拟器当前安装的是上表候选。

未提交/推送。无真机/OEM、真实账号、防检测或发布验收结论。
