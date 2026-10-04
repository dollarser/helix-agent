# 设置导航归位（2026-10-04）

## 范围与行为

所有者确认此前菜单审核并要求落实剩余优化。本轮仅调整入口归属和页面分区，不更改权限、运行环境安装机制、执行记录或文件数据。

- 移除侧栏“配置”分组；模型、扩展、终端为直接入口，工作和设置仍点击展开。
- 移除“准备与能力”中转页。准备检查、能力状态放入“设置 → 诊断与审计 → 故障排查”；运行环境成为设置的独立子入口。
- 诊断与审计区分“故障排查”和“操作记录”。保留既有脱敏诊断预览和有界审计查询，不新增后台检查、数据上传或授权。
- 既有 `setup` 路由直接显示准备检查，`setup/readiness`、`setup/capabilities`、`setup/runtime` 保持可达，已有功能修复跳转不失效；侧栏根据真实归属选中诊断或运行环境。
- 准备检查仍可重复执行，继续使用既有模型和环境修复入口。本轮未新增启动拦截或强制首次使用流程。
- 会话 Git、全部任务、全部成果及独立文件管理保持上一轮归属。

## 验证与边界

主机验证命令：

```text
./gradlew :app:testDeveloperDebugUnitTest --tests '*SettingsNavigationTest' :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

导航单测覆盖唯一入口、系统权限子页和准备检查/能力状态/运行环境的归属；设备用例更新真实导航路径、配置分组不存在、独立扩展入口及准备检查返回行为。设备用例仅编译，执行状态为 `not requested`；没有使用模拟器或安装本轮 APK，没有真实账号/服务验证，未提交或推送。

结果：3 项导航 JVM 用例通过，0 失败/跳过；Developer / Consumer Debug 构建、两种 AndroidTest Kotlin 编译、Spotless、detekt、文档检查和差异检查通过。首轮静态检查发现导航复杂度、审计函数长度及前轮插件测试格式问题，已以简化条件、归并标题及格式修正解决后复跑。日志保存在忽略的 `build/settings-navigation-final.log` 和 `build/settings-navigation-docs.log`。
