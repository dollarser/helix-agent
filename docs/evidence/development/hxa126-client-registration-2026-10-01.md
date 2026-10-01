# HXA-126：客户端注册与 OAuth 回调绑定增量

日期：2026-10-01。基于 `05e91003` 加本轮未提交的 HXA-233 / OAuth 改动。所有者授权依次推进剩余工作并参考官方实践；本记录不代表全部任务或两个真实服务验收完成。

## 实现

按 MCP 2026-07-28 规范，保留预注册公共客户端，增加支持时的 HTTPS Client ID Metadata Document（CIMD），动态注册只作为显式确认后的兼容后备。界面展示 issuer、注册目标与精确 redirect；发现配置不自动 POST，不启用工具或提交用户账号。CIMD 精确校验 client_id、公共认证方式和 redirect，禁用重定向，复用受控 DNS/端点检查、有界响应和超时。

注册写前状态、issuer/redirect 归属、配置选择和发布代数保存在现有 SecretStore；未知结果不自动重注册。显式忘记不会声称远端注销。晚到的 GET/POST 不得覆盖新选择或恢复已忘记配置；已选 CIMD 在登录前重新校验。

修复了元数据声明必需的 `iss` 未持久化的问题：包括错误回调在内均精确校验，缺失、不同大小写/端口/尾斜杠不当作相同 issuer。短期 attempt codec v2 必须带布尔规则；旧 attempt 重新登录，不降级为 false。刷新、设备码申请/轮询携带原 resource，OAuth POST 不透明重试。

追加修复登录准备竞争：在元数据/设备码 I/O 前持久预留准备身份；取消、新请求或清理后旧准备不可创建有效 attempt。UI 在启动协程前标记忙碌，取消同时取消准备与轮询 Job；持久代数而非 UI 标志决定旧结果能否发布。

## 官方依据

- [MCP 2026-07-28 客户端注册](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration)：预注册、CIMD 与 DCR 后备。
- [MCP 2026-07-28 授权](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)：issuer 精确匹配及 resource 绑定。

2026-10-01 读取官方正文；未复制外部代码、添加框架或升级依赖。App 单测显式依赖项目已锁定的 OkHttp 5.5.0，以验证现有传输；仅修改测试配置锁，不新增生产依赖。

## 主机结果

```bash
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :runtime:cli-client:assembleDebugAndroidTest --continue --console=plain
```

最终退出码 0，BUILD SUCCESSFUL；1019 tasks：61 executed、3 from-cache、955 up-to-date。缓存复用不表示所有用例被强制重跑。对应当前 XML 新增独立用例：

| 套件 | tests | failures/errors/skipped |
| --- | ---: | --- |
| McpOAuthClientRegistrationTest | 9 | 0/0/0 |
| OAuthClientRegistrationsTest | 8 | 0/0/0 |
| OAuthIssuerBindingTest | 5 | 0/0/0 |
| OAuthDevicePreparationTest | 2 | 0/0/0 |

新增 **24/24**；App 共享用例不把两渠道相加冒充独立场景。前轮失败保留：App 未启用序列化编译器，改用现有显式严格 JSON codec；单测缺直接 HTTP 依赖，显式加入已锁定 testImplementation；JUnit 表达式返回异常对象的用例改为 Unit 后完整执行。静态检查已修复，没有关闭全局门禁或删除断言。

`git diff --check` 通过。source/制品最终命令单独追加实际结果，不用前阶段绿色替代。

## 设备、服务和剩余条件

新增 OAuthClientSetupDeviceTest 两项独立 UI 场景已编译到双渠道测试 APK，**未执行设备**。本轮未使用真实服务、账号或付费额度；两家独立服务的注册/登录/拒绝/刷新/撤销/重连仍需指定配置和授权。

CIMD 消费端已实现，不代表已为 Helix 托管元数据文档；自有 HTTPS 域名、App Link 关联及签名身份仍需发行输入。Device Flow 进程死亡后由用户重新发起，不后台无限轮询；商店、OEM、真机和 clean P5 未因主机通过而完成。HXA-126 保留这些明确验收边界，其他独立本地工作继续。
