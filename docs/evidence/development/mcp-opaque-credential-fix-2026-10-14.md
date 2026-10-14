# MCP不透明凭据兼容修复

日期：2026-10-14。归属：[HXA-126](../../development/tasks/HXA-126.md)、[ADR-CONNECTORS-002](../../adr/connectors/002-oauth.md)。Owner要求修复真实Linear登录后的连接失败；本记录仅证明明确列出的执行范围。

## 复现与定位

- Owner指定日常API36模拟器，Developer包，v0.0.5；未连接/操作真机，未请求模型服务。
- 原安装APK SHA-256：`49e28d32c3d738a907fa747616d7fa6c869b820ddf8678342bab9d77ebcfda71`。不能据版本名认定它等于当前源码。
- 官方Linear公开元数据同时声明CIMD与DCR；现有UI仅提供CIMD且注册层拒绝在CIMD可用时执行DCR，本次没有自有元数据文档。手动注册本测试的公共客户端，官方返回201、无client secret、精确Developer包名回调；此路径不算内置注册UI通过。
- 初次自动输入被中文IME改写，浏览器报Invalid client_id；切换物理键盘英文模式后核对Client ID与注册响应完全一致、scope为read，再次发起后进入官方只读授权页。此失败不能归因于缺少平台开发者应用。
- Owner完成授权后，Helix显示OAuth已授权并返回扩展页，随后的自动/手动连接测试失败。临时JDWP捕获`IllegalArgumentException`，位置为`McpHandshakeKt.requireBearerCredential`，调用链为`McpHandshakeService.testConnection → McpAppService.testConnection → PluginService.testOAuth → EndpointRow`。
- 只检查凭据形状：含冒号，旧白名单拒绝它；未读取或输出实际凭据值。失败发生在SDK连接之前，不是服务端MCP握手拒绝，也不证明后续工具可用。调试器已解除暂停、退出，临时ADB转发移除。

## 修复与参考

- 共用校验接受有界、无空白的可见ASCII；握手与实际工具执行均原样发送不透明凭据。保留4096字节上限、危险字符拒绝、网络准入、资源/来源绑定与跨源重定向拦截，不增加授权或重放操作。
- 固定类型的格式异常不包含凭据值。连接UI仅显示本地分类；已授权的连接失败不再误导为账号登录失败，也不显示服务异常正文。
- 参考[官方MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk/blob/main/src/mcp/client/auth/oauth2.py)将access token原样放入Authorization的行为，未复制参考代码。[RFC6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750.html#section-2.1)的b64token不含冒号，本次明确记录为实际部署兼容，不能将放宽字母表说成该规范要求。
- 本次不修改CIMD/DCR选择、账号scope、Room或登录生命周期。上述注册入口限制独立保留。

## 验证状态

定向主机回归20项通过：MCP凭据兼容4、握手8、取消2，错误分类每渠道3。新增覆盖合成冒号凭据的握手、metadata和实际tools/call原样传输，危险输入/长度边界、异常脱敏，以及已授权/未授权错误分类；既有取消、来源隔离和重定向回归保留。两渠道测试APK编译通过，仅为编译，不是设备执行。

初次全仓门禁未通过：新增测试长行/格式，以及UI原有泛型异常边界命名后触发detekt；已整理格式，并在相同UI边界说明固定脱敏类别与保留取消语义，不修改门禁规则或删减断言。最终`python3 scripts/with-host-slot.py -- ./scripts/check-all.sh --all`退出0：源码/文档/i18n/机密检查、全部JVM、spotless/detekt、双渠道Debug/Release lint和制品、41份依赖锁及APK边界检查通过。随后双渠道AndroidTest APK重新编译退出0；没有执行设备夹具。

修复Developer APK已冻结在忽略的`build/oauth-acceptance-2026-10-14/helix-mcp-opaque-developer-debug.apk`，SHA-256为`4a22f9299c10a2f5c9d801f92473fbd2d3ef9114889fee2697807688af90e31a`；签名证书摘要与日常安装包一致。完整门禁日志为`fix-all-verified.log`，最终测试APK日志为`fix-test-apks-final.log`。主机负向fixture打印的模拟失败/未知状态不是新设备长测结果。

日常数据库实测identity为`eb439f0c60ced11912eaac8b50dada5b`，当前源码为`6ebb5a2fc8c369092fa96dd6c53a1e90`：缺`document_snapshots`表和`artifacts.purpose`列。只在主机副本上增加新表/索引、按当前默认值OUTPUT增加列并更新identity；52张原表的所有原列数据逐行一致，完整性/外键检查通过，按Room列/默认值比较语义与目标schema一致。较早已获Owner批准的一次性Skill列转换默认值保留；当前entity未声明默认值，不把该未声明项误判为新的数据转换需求。此副本演练不代表设备已转换或产品增加旧库迁移。原设备未写回；实际更新需先停止写入、取新备份，并取得当次数据处理确认。

修后设备验证pending；尚未更新日常APK，须先核对数据库风险并按当前授权边界处理。Notion、Linear实际业务只读调用、真实刷新/厂商撤销未完成，不能宣布两家服务验收通过。

本机原始截图、脱敏进度及构建日志在忽略的`build/oauth-acceptance-2026-10-14/`；不提交账号内容、注册响应或凭据。临时脚本仅存忽略的`scripts/debug/2026-10-14/local/`。
