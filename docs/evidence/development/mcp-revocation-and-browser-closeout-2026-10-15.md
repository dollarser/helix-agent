# 官方 MCP 撤销与浏览器收尾

日期：2026-10-15。范围：[HXA-126](../../development/tasks/HXA-126.md)、[HXA-282](../../development/tasks/HXA-282.md)。Owner要求先完成需本人参与的OAuth流程；继续使用已登录的日常API36/Developer，不操作新连接的OnePlus、不调用模型或长稳。

## 已确认的独立结果

新增显式opt-in的OfficialMcpRevocationDeviceTest，旧凭据仅保留在App内存。只探测同一官方MCP端点的initialize，不查询账号目录或业务内容；先确认旧凭据HTTP 200，再调用正式PluginService.revokeOAuth，分别核对本地清除、工具停用、已保存客户端保留与厂商回执，最后以同一旧凭据再次探测。没有导出Token、密码或业务对象。

- Linear：初次方法passed；vendorReported=true/vendorStatus=200，旧凭据baseline=200、撤销后401。当前只证明测试所持旧access bearer失效，不泛化为账号所有Token或其他客户端授权被撤销。
- Notion：方法passed；相同独立事实均成立，旧凭据200→401。不是仅凭厂商200或本地删除宣布失效。

Linear原生“连接账号”复用已保存客户端，浏览器自动完成正常回调；Owner明确未点击按钮。没有实际拒绝页面，因此不计拒绝授权通过。[Linear官方OAuth文档](https://linear.app/developers/oauth-2-0-authentication)说明prompt=consent可显示确认页，但本轮MCP入口添加该参数仍自动回调，未证明该MCP服务按同样语义支持它。仅测试夹具使用此参数，不改变产品默认或scope。

最初将浏览器未停留误判为后台启动拦截，后续ActivityTaskManager已显示打开与自动回调，已更正；不以BAL相关正常启动日志当系统拒绝证据。另首个测试APK编译因App没有直接导出OkHttp依赖失败，改用平台HttpURLConnection进行有界只读探测，不增加依赖。所有真实撤销先有200基线，错误/缺乏基线不会继续撤销。

## 正在执行的边界

Notion撤销后已通过正常“连接账号”到达真实“连接Notion MCP”页面；Owner明确点击取消。设备没有新增callback activity记录，原尝试仍未消费且未过期，返回Helix仍显示等待浏览器授权，故不记真实拒绝回调通过。随后已打开新的正常授权页，等待Owner授权；最终两家指定对象读取、原Notion两个只读工具恢复、六张用户表及临时状态的最终核对尚未记录，不能据此前置结果宣称整个收尾完成。

私有备份和不含凭据的摘要分别留在忽略的build/oauth-interactive-closeout-2026-10-15/before、linear-revoke-safe.log、notion-revoke-safe.log及各instrumentation日志。首轮Linear撤销APK与后续Notion撤销/确认页准备APK分别留hash，不混称为同一制品；最后删除临时确认页方法并格式化，双渠道编译单独验证，不为格式变化重复撤销。只安装测试APK，主APK保持原版本。准备确认页的短期auth URI仅在本机受保护文件传递，设备临时文件已删除，不入库。

Linear撤销后已产生新的授权记录，先前记录的到期UTC属于旧凭据，不能再据此安排自然刷新。待当前Notion正常授权完成后的最终只读复验采集Linear新记录的安全到期UTC；不重复已通过的Notion自然刷新。OnePlus当前已连接，但本轮未操作、未验收，不写成设备未连接。

## Owner终止拒绝回调专项

2026-10-15 Owner明确要求停止拒绝回调测试。该范围按Owner决定结束，不再请求取消、不再尝试强制确认页、不再列作重构前阻塞，也不记作passed；已观察到的Notion无回调与Linear自动授权事实保留。仅继续已开展的正常重连、指定对象读取、安全期限采集、原工具和数据/权限恢复。

Owner进一步明确删除该验收要求，而非仅暂停：已从固定OAuth旅程、HXA-126/282当前待办及实施状态门禁移除，临时Linear强制确认页测试方法也删除，保留已完成撤销的显式opt-in探测。历史试验不改写为通过，生产安全校验及原有确定性测试保持。当前Notion正常重连尚待完成；其已打开请求后来实际超过期限，未重复发起授权。
