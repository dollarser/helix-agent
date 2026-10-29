# CI 使用与验证边界

任务、触发条件及并发配置以 [workflow](../../.github/workflows/ci.yml) 为准；变更范围与结果汇总见 [范围选择](../../scripts/ci/plan.py)，本地与 CI 共用 [门禁脚本](../../scripts/check-all.sh)。不在文档重复维护任务图或运行结果。

## 使用

- 日常本地检查：`./scripts/check-all.sh --debug`（默认）。
- 发布前完整检查：`./scripts/check-all.sh --all`，包含 Release/R8；日常 Debug 成功不能代替完整发布门禁。
- GitHub 手动运行：在 Actions 中选择 Android CI，使用 `workflow_dispatch` 的 `full` 或 `debug` 范围。具体步骤以 workflow 为准。
- 排查失败：查看失败步骤、门禁日志及上传的诊断；耗时与缓存命中以该次运行的日志/profile 为准，不将排队时间或编译总时长当作单测耗时。

## 边界

范围选择可以跳过无关的昂贵工作，但必须保留稳定汇总检查；失败、取消或意外跳过不能算通过。仅文档运行成功不代表产品代码已重新构建。

GitHub CI 仅执行主机验证，不启动模拟器、连接真机、执行设备 instrumentation，或消费真实账号及付费服务。AndroidTest 源码或测试 APK 编译不等于设备测试通过；设备验收须有当前任务的明确授权。

CI 不自动发布签名制品、创建 tag/Release 或授予发布权限。发行验收须绑定实际源码与制品，具体版本与验证边界记录在对应发布说明中。
