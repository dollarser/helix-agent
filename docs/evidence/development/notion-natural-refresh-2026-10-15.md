# Notion 自然到期刷新与重启复用

日期：2026-10-15。范围：[HXA-126](../../development/tasks/HXA-126.md)、[HXA-282](../../development/tasks/HXA-282.md)。沿用Owner指定Notion页面与既有账号，只通过Helix正常连接及Dispatcher只读工具链验证。没有模型调用、业务写入、改时钟、伪造期限、撤销、新授权或Linear重复测试；长稳与提前结束专项保持暂停。

## 执行与独立事实

工作区main/1c63934d可用，保留并行文档修改。仅连接日常emulator-5554/API36 arm64/Developer；无真机。用户占用询问未收到回复，连续只读输入队列显示最近触摸距测试约24分钟、无在途instrumentation，才按可用证据继续有界操作。主APK SHA256为81f9157ff6de2ce615a9d8cd2e4218ded069d74a6afa9ccb847f07e1894627e5，既有测试APK为5dd58b4ed50ee56320e05f537f2e8a3378255c870ac7da24420a38fd0b431b6f；本轮没有安装APK。

主机与设备UTC有约6分钟偏差，先核对设备当前时间；主机超过旧客户端期限不等于设备已到刷新条件。首轮两次正常只读及工具审批拒绝passed，但实际naturalRefresh=not_due，旧期限未变；没有将这些读取算刷新。

随后设备自然进入既有SDK提前60秒的正常刷新窗口，沿原OfficialMcpReadDeviceTest运行一次：进入前仍为此前安全日志记录的同一旧客户端期限，排除用户活动已替本轮刷新的解释；实际prepare后新issuedAt严格增加、refreshing=false、isExpired=false，原issuer/resource/client/redirect绑定完全相等。正常Dispatcher审批拒绝没有发送业务，随后批准的notion-fetch成功且返回独立匹配指定页面。安全日志为naturalRefresh=passed。这里不是等待到expiresAt的零秒之后，而是正常生产SDK的刷新窗口触发，不修改时钟或存储。

独立force-stop后再次instrumentation，以不同App PID运行相同测试：进入前已从SecretStore读到新客户端期限，naturalRefresh=not_due；同一页面审批拒绝与批准只读再次passed。由此分别确认新记录持久化、真正进程重启复用和真实业务结果，不把内存成功等同落盘。总计本轮4次方法均passed，其中两次前置not_due、一次刷新、一次重启复用；仅后两次构成刷新/恢复证据。

## 失败、保留与边界

首次本地runner的收尾断言failed：它把appops的动态rejectTime时长字符串作为权限状态比较。实际SYSTEM_ALERT_WINDOW始终default，权限未变化。修正为比较权限模式而非距上次拒绝多久；另将System.out安全证据从实际logcat按前后记录归属采集，instrumentation结果流本身不包含println。这是测试记录问题，保留初轮失败，不改生产权限或刷新实现。

修正runner最终exit0；前后六张用户表逐行hash完全一致，integrity/FK通过，无障碍仍null/0、悬浮窗仍default、无ADB reverse。原Notion工具选择由fixture finally恢复，Linear未操作；临时notification listener禁用在finally恢复default-state，最终重新打开Helix。无遗留后台测试进程。

安全期限、时间/PID、APK身份及归属日志留在忽略的build/notion-natural-refresh-2026-10-15-v2/summary.json与notion-1-safe.log/notion-2-safe.log，私有数据库/偏好备份不入库。初轮记录保留在build/notion-natural-refresh-2026-10-15。仓库不收集账号内容、Client ID、Token或页面地址。

当前只关闭Notion自然刷新和重启持久复用；没有把refresh token或access token的字节差异导出，因此不额外声称厂商轮换了具体哪类Token。Linear仍未到其记录期限，未重复测试；OAuth真正拒绝回调、厂商撤销/旧凭据失效/重新授权、实体设备与发行输入分别开放。
