# 系统授权与 Mobile Use 设置拆分

日期：2026-10-05。所有者要求把授权管理页中的通用权限与 Mobile Use 配置完全拆开；在上一轮未提交工作树上继续，不覆盖其余修改，不提交或推送。

## 修改范围

- 宿主入口保持“设置 → 权限与安全 → 授权管理”，直接使用独立的 `app/deviceaccess` 页面组件，不再嵌入 Mobile Use 设置。
- 系统页查询应用已声明无障碍服务的系统授权、Shizuku 服务/应用授权和 Helix Root 授权，不创建 Shizuku 自动化后端、不读取或操作 `mobile-use` Root 连接。
- Root 系统授权使用独立 consumer `helix-system-authorization`，仅显式点击才请求 Root。页面初始化只创建被动访问对象，不申请系统权限、绑定插件连接或启动任务。
- Mobile Use 插件设置保留后端优先级、无障碍引擎运行状态、专用 Root 连接及全局应用范围；缺权限时跳转宿主授权管理。关闭专用连接不撤销系统授权或断开其他 consumer。
- 删除 `AutomationModule.Permissions`、`MobileUsePermissions` 和混合的 `ShizukuSettings` 组件。不是只调整分组标题。

## 验证

主机：合同测试 11/11；app developer 单测 1222 项，1218 通过，4 个既有外部样本/账号条件跳过，0 失败。双渠道 debug APK/AndroidTest APK 编译已完成。最终联合 Gradle 检查通过（1 分 40 秒），含双渠道 lint、detekt、spotless；双渠道 APK 运行时检查通过。

新增/更新测试覆盖宿主页面不依赖插件组件、不包含插件范围/断连控制、打开页面不改变 Mobile Use 连接、显式 Shizuku 操作才打开对应系统目标，以及插件配置中不出现系统授权按钮。

设备状态 **not requested**：本次没有启动/使用模拟器，没有安装到设备，没有调用真实模型。设备测试仅编译，上一轮设备结果不计入本次通过。

## 复验命令

```sh
python3 -m unittest scripts.tests.test_mobile_use_contract
./gradlew :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:lintDeveloperDebug :app:lintConsumerDebug detekt spotlessCheck --offline --console=plain
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/verify-variant-boundaries.sh
bash scripts/check-docs.sh
git diff --check
```

主机日志位于忽略的 `build/permission-split-*.log`。新增 Root 授权展示测试覆盖缓存授权与连接分离、撤权覆盖旧连接状态、请求中禁止重复申请；页面测试只编译，未宣称设备通过。
