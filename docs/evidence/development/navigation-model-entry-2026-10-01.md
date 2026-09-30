# 模型入口与侧栏简化

所有者要求去掉“选择并设置为当前对话模型”，模型与连接提升为一级入口并排在 Work 上方，对话区去掉 Recent。

- 模型候选管理和本地安装不再提供应用到当前会话动作，也不接收 ChatService/会话选择回调；实际切换保留在会话模型选择器。
- 侧栏模型入口独立置顶于功能分组之前，Configure 仅保留其他配置项。Recent 条目、回调及无用会话列表订阅移除；当前会话、新建及完整历史入口保留。
- 回归用例覆盖窄屏/大字体的入口可达性与顺序、Recent 缺席、模型管理仅保存候选且不产生生成请求。本轮设备验证 not requested，仅编译 AndroidTest APK，不将上一轮设备结果当作本轮通过。

首次主机检查发现移除入口后的未使用参数/属性，已清理。最终结果记录于 `build/navigation-simplify-final.log`；源码 gate 记录于 `build/navigation-simplify-source.log`。修改未提交、未推送。

最终主机结果：Consumer 单元测试、双渠道 lint、Developer 应用及 AndroidTest APK、detekt 通过；此前 Developer 单元测试通过。最终测试制品和 spotlessCheck 通过（`build/navigation-simplify-test-package.log`）。源码 gate 与 `git diff --check` 通过。未操作设备或覆盖安装。
