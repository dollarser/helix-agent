# 模型入口与配置导航优化（2026-10-04）

## 范围与依据

所有者要求：未配置模型时，选模入口也能添加 API 或本地模型；设置统一命名，移除配置页重复标题和无效返回层级。
设计依据：[Provider ADR](../../adr/provider/001-models-and-connection.md)。

## 实现

- 会话栏和会话设置的选模弹窗独立于已有 Provider 显示来源配置入口。API、本地模型始终提供；订阅由 ProviderService 的渠道来源列表决定。搜索无结果仍可配置。
- 来源入口通过导航参数直达对应分类；未完成配置的已有来源同样直达其分类。配置导航不会选择会话模型、登录、下载或发起探测；会话栏离开页面前仍走草稿保存流程。
- 导航名称统一为“模型”。移除内部 Provider 标题、分类首页与“返回模型来源”，采用始终可切换的分类控件；普通入口默认 API，直接入口选中请求分类，非法或渠道不支持的分类回退 API。
- API / 订阅 / 本地模型分别提供说明和空状态；API 主操作为“添加 API”，本地模型为“下载本地模型”。中文本地模型分类不再显示英文标题。
- 保留已有模型选择、连接验证、账号授权及渠道约束。新增来源入口置于可滚动区域，分类按钮允许换行。

## 当前主机验证

以下命令在当前工作区执行，通过；工作区另有未提交的 Mobile Use 等工作，不属于本记录范围。

```text
./gradlew :app:testDeveloperDebugUnitTest --tests '*ProviderChannelPolicyTest' --tests '*ProviderModelSelectionTest' --tests '*ProviderSetupStepTest' --tests '*ShellRepositoryTest' :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin :app:assembleDeveloperDebug :app:assembleConsumerDebug spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

- JVM：26 项通过，0 失败、0 跳过（渠道 3、模型选择 11、配置状态 8、导航仓库 4）。
- consumer / developer Debug APK：构建通过。
- consumer / developer AndroidTest Kotlin：编译通过。
- Spotless、detekt、文档和差异空白检查：通过。
- 首轮静态检查发现本次修改的长行、格式及导航组合函数复杂度问题；通过格式修正及提取导航参数辅助函数解决，复跑通过。

## 设备验证边界

设备状态：`not requested`。当前模型配置任务未请求设备验证，未启动、安装或操作模拟器/真机。
新增 `ModelSourceNavigationDeviceTest` 四项界面用例：空列表 API/本地入口、搜索不隐藏本地入口、允许订阅渠道的空来源入口、分类切换。它们仅完成编译，未运行；不能据此宣称点击或视觉验收通过。既有订阅未测试模型禁用用例保留。
