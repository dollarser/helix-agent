# 两家官方MCP指定对象读取

日期：2026-10-15。归属[HXA-126](../../development/tasks/HXA-126.md)、[OAuth验收手册](../../development/mcp-oauth-acceptance.md)。Owner提供两家个人账号中的精确测试对象，继续使用已指定日常API36 Developer；真实对象链接、账号内容与凭据不进入仓库。

## 本轮执行

使用新增显式opt-in的`OfficialMcpReadDeviceTest`，沿用Helix自己的既有OAuth凭据。仅允许Linear官方`/mcp`的get_issue和Notion官方`/mcp`的notion-fetch；对象通过本机instrumentation参数提供。未请求其他页面、搜索整个工作区或使用其他宿主凭据；没有模型用量。

每家先拒绝一次精确工具审批，再批准读取同一对象。使用原Plugin会话选择、APPROVAL_REQUIRED、ChatService/Dispatcher、审批持久化、MCP实际执行与发送摘要，未换成直连curl或假工具结果。两家各1方法passed，实际四个业务判定通过：

- 拒绝结果为Denied，持久审批为DENIED，业务发送摘要为0。
- 批准结果为Succeeded，持久审批为APPROVED，发送摘要为1，MCP isError=false；实际文本返回包含Owner指定对象的标识。不据此声称逐字校验完整正文、全部工具或模型任务通过。

两家原Token在调用前都未自然过期（expiryKnown=true、naturalRefresh=not_due），未改写期限或凭据，不把成功读取记成真实刷新通过。测试结束恢复原工具选择，取消需等待实际worker退出；超时若未退出保留自有记录，不把取消Future当成物理退出。仅删除本次自有临时会话；原六张用户数据表逐行一致，数据库完整性和外键通过。

原始instrumentation、仅状态布尔值的logcat摘要、私有备份及数据比对留在忽略的`build/oauth-official-read-2026-10-15/`。测试APK冻结SHA-256为`dc3d198a85da88002e6390d6ab0a3e29f055bf9b37ccafefb07c2ea4e3f3b71d`；本阶段只更新测试APK，主APK仍为前轮客户端配置制品，不改库/清数据/卸载。首次本机执行短暂等待后恢复，初次格式校验因长行失败，拆行后编译通过；未放宽门禁或删除旧测试。

## 授权生命周期仍分开

Owner随后明确表示现在方便参与浏览器验收。Linear经原生“断开并撤销”后不再显示已授权，复用原公共客户端/read scope/精确回调重新登录，浏览器很快返回，原生OAuth已授权及工具选择界面再次出现；没有可人工拒绝的停留页面，不能记OAuth拒绝回调通过。也未截获撤销响应或核查旧Token被服务拒绝，不把本地状态变化冒充厂商撤销完成。

Notion随后同样从原生入口断开并发起原客户端授权。Owner按提示点击官方授权页“取消”；浏览器返回Notion页面，没有观察到OAuth错误回调返回Helix，不能伪造一次服务端拒绝回调。再次发起前由正常prepare原子替换旧尝试，后续正常重连结果另补。工具审批拒绝的通过，不替代上述OAuth页面/厂商生命周期边界。

Owner随后完成Notion新尝试的正常工作区授权。实际回到Helix，OAuth已授权、MCP工具选择出现，再仅选用notion-search/fetch，最终原生页面显示“工具已启用”。没有重复注册公共客户端，原本未开启的Linear工具保持未开启；两家现有授权已恢复。尝试在返回值构造处取撤销状态的调试断点因构造方法重载未设置成功，未暂停网络，随后退出调试器并移除ADB转发；没有为了补日志重复撤销，不把这次失败诊断算撤销验收通过。

最终完整`python3 scripts/with-host-slot.py -- ./scripts/check-all.sh --all`退出0：源码/全部JVM、spotless/detekt、双渠道Debug/Release lint/制品、41份依赖锁及制品边界通过（`all.log`）。新增设备验收类Developer测试APK编译并实际执行上述两家各1方法；Consumer测试APK随后编译退出0，未运行Consumer或真机，不继承为模型/长稳结果。剩余真实自然刷新、服务端拒绝回调与旧Token厂商失效核查保持开放，不能以本地清除或HTTP元数据替代。
