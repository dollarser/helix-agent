"""Add localised manual DNS settings resources."""
from pathlib import Path
values={
'subscription_dns_title':('网络设置 · 域名解析','Network settings · DNS'),
'subscription_dns_help':('仅覆盖本订阅组件的域名解析，无需 Root，不影响其他应用。HTTPS 域名和证书校验保持不变。配置用于新建连接，正在进行的请求不受影响；过期或删除后恢复系统 DNS。','Override DNS only inside this subscription app, without Root or changes to other apps. HTTPS hostname and certificate checks remain enabled. Changes apply to new connections; active requests continue. Expired or removed entries use system DNS.'),
'subscription_dns_host':('域名（不含协议、端口或路径）','Hostname (no scheme, port or path)'),
'subscription_dns_addresses':('IP 地址（IPv4 或 IPv6，多个用换行或逗号分隔，最多 16 个）','IP addresses (IPv4 or IPv6; separate up to 16 with commas or newlines)'),
'subscription_dns_duration':('有效期','Valid for'),
'subscription_dns_hour':('1 小时','1 hour'),
'subscription_dns_day':('24 小时','24 hours'),
'subscription_dns_week':('7 天','7 days'),
'subscription_dns_save':('保存／更新映射','Save / update mapping'),
'subscription_dns_clear':('清除全部，恢复系统 DNS','Clear all and use system DNS'),
'subscription_dns_saved':('已保存，新连接使用更新后的配置。','Saved. New connections use the updated configuration.'),
'subscription_dns_invalid':('请输入有效域名及 1～16 个 IP 地址；不支持通配符、URL、端口或 IPv6 区域标识。','Enter a valid hostname and 1–16 IP addresses. Wildcards, URLs, ports and IPv6 zone IDs are not supported.'),
'subscription_dns_save_failed':('保存失败，配置未更新，请重试。','Could not save the configuration. Please retry.'),
'subscription_dns_empty':('没有配置覆盖，使用系统 DNS。','No overrides. Using system DNS.'),
'subscription_dns_entry':('%1$s\n%2$s\n%3$s · 到期：%4$s','%1$s\n%2$s\n%3$s · Expires: %4$s'),
'subscription_dns_active':('生效中','Active'),
'subscription_dns_expired':('已过期，使用系统 DNS','Expired; using system DNS'),
'subscription_dns_edit':('编辑','Edit'),
'subscription_dns_delete':('删除','Delete'),
}
for folder in ['values','values-en','values-zh-rCN']:
 p=Path('runtime/cli-app/src/main/res')/folder/'strings.xml';s=p.read_text()
 import xml.sax.saxutils as xml
 added=''.join('    <string name="'+key+'">'+xml.escape(v[1 if folder=='values-en' else 0]).replace('\n','\\n')+'</string>\n' for key,v in values.items())
 p.write_text(s.replace('</resources>',added+'</resources>'))
p=Path('runtime/cli-app/src/main/res/values/dns_ids.xml')
p.write_text('<resources>\n'+''.join('    <item type="id" name="'+x+'" />\n' for x in ['subscription_dns_host','subscription_dns_addresses','subscription_dns_duration'])+'</resources>\n')
