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

10月14日主机交付时修后设备验证pending，未更新日常APK；10月15日Owner允许继续后，更新与定向复测结果见下方。Notion、Linear实际业务只读调用、真实刷新/厂商撤销仍未完成，不能宣布两家服务验收通过。

本机原始截图、脱敏进度及构建日志在忽略的`build/oauth-acceptance-2026-10-14/`；不提交账号内容、注册响应或凭据。临时脚本仅存忽略的`scripts/debug/2026-10-14/local/`。

## 2026-10-15 日常API36修后复测

Owner在备份、一次性补齐缺表/列并保留数据更新的问询后回复“继续”。同一日常API36、Developer包执行，原5个Turn均为COMPLETED；没有操作真机或调用模型，没有清数据/卸载或增加生产迁移。

- 重新备份停机后的数据库及shared preferences；按原方案转换新副本，52张原表的全部原列数据逐行不变，完整性/外键及目标schema比较通过。原包与备份保留在忽略目录，未导出凭据到日志。
- 最初临时文件传输没有成功，哈希校验在替换原库前中止；其后进程检查确认系统通知监听服务自动重绑，停机保护再次在改库前中止。Shell修改组件状态被系统拒绝，未改变设置；改用App自己的UID临时停用通知监听组件完成停机，结束在finally恢复原DEFAULT状态。不撤销/新增系统授权，不改变其他App。
- 转换库通过App UID的stdin传输，设备端SHA-256核对后原子替换；安装冻结修复APK成功，设备包SHA-256与上述修复制品一致，签名相同。应用正常启动后identity仍为目标值；11张用户数据表（会话、Turn、消息/附件/引用、项目、工作区、Provider、输入等）内容逐行与新备份一致，原3个会话/5个Turn保留，完整性和外键检查通过。
- Linear原OAuth授权保留。原生UI点击测试连接后返回工具选择界面，展开后获得真实工具列表（如get_attachment/list_comments/get_issue），没有旧格式错误。此项验证了实际MCP连接与metadata发现；没有启用全部工具或执行账号业务读写，不推断真实刷新/撤销已经通过。
- Notion市场条目安装成功，无自动登录或工具启用。官方公开元数据GET在urllib返回403、curl返回200；只执行一次注册POST，201、无client secret、精确包名回调。Client ID、default scope和redirect在UI核对一致后发起登录，已到官方Notion账号登录页，等待Owner在浏览器操作。default不声称只读授权；仍要求测试工作区，后续业务动作限制为只读。

本轮设备结果：保留数据更新/启动、Linear已有授权恢复和MCP连接/工具发现passed；Notion登录、两家实际业务读取及拒绝/刷新/厂商撤销pending。当前没有新长稳/模型结果。忽略的`build/oauth-acceptance-2026-10-15/`保存更新日志、保护中止、数据库副本、脱敏数据保留结果与UI证据；临时脚本在同日local目录。

后续Owner报告“完成登录但是工具未激活”。现场核对Notion网页账号已登录并停在主页，Helix仍为“等待浏览器授权返回”，尚无Notion MCP授权成功证据；不能把网页登录当作OAuth完成。重新从原生配置发起新请求，核对Client ID/default scope/精确redirect后实际进入官方“连接Notion MCP”授权页，显示工作区选择、精确包名回调及“我认可并信任此URL”确认和继续按钮。留给Owner选择工作区并确认，尚未点击授权、启用工具或调用业务接口；最新边界为网页账号登录passed、MCP授权/工具激活pending。过期/未完成的旧尝试不回放，不重复注册客户端，不读取Cookie或账号凭据。

## Notion授权完成后的连接与激活修复

上述pending为当时状态。Owner离开约34分钟后返回的回调超过10分钟attempt期限；重新发起后由Owner完成工作区授权，原生UI实际显示“OAuth 2.0 已授权”。随后连接失败是另一问题，不再归因于网页登录或授权未完成。

- 早期调试观察到可选GET/SSE返回405，以及SDK -32001超时；调试暂停可能影响超时，不能用其认定根因。Kotlin SDK 0.15.0对可选GET的405有明确处理，不将其误判为所有POST请求失败。
- Owner停止操作后无调试暂停复测仍失败。仅在最终UI错误边界检查异常类别/栈，定位到`McpMetadataHash.toBoundedJson`；定向异常位置确认单个Schema为80,556字节，超过旧65,536上限。不是授权或tools/list网络失败。
- 默认单Schema上限调整为131,072字节，整体元数据524,288字节、网络JSON/SSE响应16 MiB边界保持；不截断Schema。新增真实HTTP握手fixture覆盖超过旧上限的完整Schema，以及超过新上限仍拒绝、不执行tools/call。总量拒绝、取消、凭据和跨源保护保留。
- 修后实际Notion连接和工具列表返回成功。选择notion-search/fetch又在`McpToolSchemaAdapter`拒绝，定向检查仅输出违例路径/关键字，发现搜索日期字段的`format`注解不在内置工具子集。
- 对照[JSON Schema 2020-12 §7.2](https://json-schema.org/draft/2020-12/json-schema-validation#section-7.2)，仅对精确默认方言的Schema在适配层归一化字符串format注解；不改内置工具校验器。只遍历properties/items/additionalProperties中的Schema位置，保留属性名和enum字面量、源哈希、required/type/minLength等约束。未知方言、嵌套声明、显式vocabulary/format-assertion及不支持约束继续拒绝，回归覆盖这些边界。未复制参考实现。
- 元数据大小和Schema不兼容使用固定错误类别，工具启用失败也通过同一安全分类；不显示远端异常正文、不再误导重新登录。
- 最终Developer APK SHA-256为`6375a203a922fed2ab1dd5ec5b07ad96d76b593b37a15fb44e7ed0edb6e89679`。两次相同schema更新均先停机备份，六张核心用户表逐行一致、完整性通过，通知监听组件恢复DEFAULT；本阶段没有改库、清数据、卸载、重复注册或重做OAuth。
- 同一日常API36最终原生页面实际显示“工具已启用”，仅选择notion-search与notion-fetch；其余工具未启用。此项证明真实OAuth保留、MCP连接、metadata及指定只读工具激活passed，不证明业务tools/call、刷新、权限拒绝或厂商撤销通过。无模型用量、真机或长稳结果。

本阶段日志/备份/UI位于忽略的`build/oauth-acceptance-2026-10-15/`，终态UI为`notion-tools-enabled.xml`。最初命令误用了模块不存在的spotlessApply任务而失败，改用已有根任务；第一次完整门禁因异常声明与文件名不匹配触发detekt，已拆为同名文件，未放宽门禁。

最终`python3 scripts/with-host-slot.py -- ./scripts/check-all.sh --all`退出0，日志`notion-schema-all-v2.log`；全部源码/JVM、格式/静态检查、双渠道Debug/Release lint/制品、41份依赖锁与制品边界通过。受影响定向类包含握手10项、每渠道错误分类5项/方言适配3项，共26项通过，旧测试保留并增加负向边界。随后同一host slot运行双渠道AndroidTest APK编译退出0（`notion-schema-test-apks.log`），不冒充设备夹具执行。最终文档检查864 Markdown/268 HXA通过。调试器退出、ADB转发及设备临时XML清理；既有授权保留，留在工具已启用页面。实际业务读取需要已知测试对象及正常Dispatcher调用，仍与本轮连接修复分开。
