# 订阅组件手动域名解析覆盖

归属 HXA-190；所有者要求设计并实现无需 Root、带手动入口的应用内域名映射。

## 入口与职责

入口放在 **Helix Subscriptions 首页 → 网络设置 · 域名解析**。主应用的订阅 Provider 仍通过现有“管理账号”入口打开订阅组件；配置只由订阅组件管理，不在主应用复制第二份设置。

原因：登录请求、目录发现、极小测试及模型响应都由订阅 Runtime UID 联网。主应用拥有会话但不拥有订阅网络客户端或凭据。应用内 DNS 不能影响外部登录浏览器、其他应用或普通 API Provider；不是系统 hosts、VPN、代理或远程 DNS 服务。

## 用户操作

- 输入精确域名与 1～16 个 IPv4/IPv6 地址（逗号、空格或换行分隔）。域名支持大小写及 IDN 规范化；不接受 URL、通配符、端口或 IPv6 区域标识。
- 有效期可选 1 小时、24 小时、7 天；默认 24 小时。保存相同域名更新原项；保存其他域名新增项。列表显示地址、当前状态和到期时间。
- 可编辑、删除单项，或清除全部恢复系统 DNS。编辑后保存会按所选时长重新计算有效期。
- 配置作用于后续 DNS 查询和新连接；不打断正在生成的回复，也不强制关闭已有连接。到期/删除不再为新连接返回覆盖地址。

## 实现约束

`SubscriptionDnsSettings` 负责输入校验、持久化及有效期判断；`SubscriptionsApplication` 初始化 Runtime 私有配置，不启动网络或绑定服务。所有既有订阅客户端继续使用 `BoundedDnsCache`，它在系统缓存之前检查有效覆盖；覆盖结果不进入系统 DNS 缓存。

使用标准 OkHttp DNS 接口，只改变目标 IP 列表，保留原请求 URL、HTTP Host、HTTPS SNI 与证书校验。无需 Root 或新增系统权限；不导入凭据，不向主 App 提供配置写入 IPC，也不开放模型工具。IP 不固化到代码中；迁移本次调试地址通过手动配置页完成。

配置保存在 Runtime 的私有 SharedPreferences，沿用既有应用备份关闭设置。进程重建后恢复未过期配置；损坏配置及时间早于创建时间的项不激活覆盖。保存失败向用户报告，不伪造成功。

## 验证

主机针对性覆盖：持久化恢复、过期/删除/更新、其他域名回退、非法输入不覆盖旧值、系统缓存与有效覆盖切换、损坏配置、时钟回拨。设备验证仅在显式启用 `helixDnsProbe` 时执行：确认无系统 hosts 映射，通过应用自定义解析进行无凭据 HTTPS GET，预期 HTTP 401 和有效 TLS；不发送模型生成请求。

历史系统 hosts 临时挂载应在迁移配置后撤销，避免用系统层成功掩盖应用内配置缺陷。设备结果与安装证据位于忽略的 `build/debug/2026-09-10/`；脚本位于 `scripts/debug/2026-09-10/`。本项不代表旧手机长回复中断已完成验收。

### 本轮执行结果

- `:runtime:cli-app:assembleDebug :runtime:cli-app:assembleDebugAndroidTest` 构建成功。
- `:runtime:cli-app:testDebugUnitTest --tests '*SubscriptionDnsSettingsTest'`：4 通过，0 跳过/失败；改动文件 Spotless 与 diff 检查通过。
- `migrate-phone-dns-to-app.py` 覆盖安装订阅组件，通过设置界面保存电脑当前解析的 `chatgpt.com` 地址，默认 24 小时；撤销旧 hosts 挂载并执行 ADB unroot。未清除登录或主应用数据。
- 仅执行 `SubscriptionDnsDeviceTest`，显式参数 `helixDnsProbe=true`：1 通过，实际验证没有系统 hosts 映射、应用 UID 非 root、应用内解析和 TLS/HTTP 401 正常。未读取凭据、未发送模型生成。
- 临时测试 APK 已卸载；手机停留在手动配置页。当前映射会自动到期，用户可随时编辑续期或删除。
