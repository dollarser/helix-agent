"""Clarify subscription transport ambiguity and connection-only status without changing IPC."""
from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/ui/ProviderRow.kt');s=p.read_text().replace('stringResource(R.string.conn_passed)','stringResource(\n                    if (status.capabilities.source == com.helix.provider.api.CapabilitySource.CONNECTION_ONLY) {\n                        R.string.conn_connection_only\n                    } else {\n                        R.string.conn_passed\n                    },\n                )')
s=s.replace('stringResource(ConnectionTestMapping.codeLabel(status.code)),','stringResource(\n                    if (row.managedExternally && status.code == com.helix.core.model.ModelErrorCode.TRANSPORT) {\n                        R.string.conn_subscription_transport\n                    } else {\n                        ConnectionTestMapping.codeLabel(status.code)\n                    },\n                ),');p.write_text(s)
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('app/src/main/res')/locale/'strings.xml';s=p.read_text();en=locale=='values-en'
 values={'conn_connection_only':'Connected · capabilities not checked' if en else '连接已通过 · 能力未检测','conn_subscription_transport':'Subscription connection failed. Check network/DNS/proxy settings. If the standalone test passes, open Helix Subscriptions and retry here to check the local component connection. This error alone does not establish login expiry.' if en else '订阅连接失败。请检查网络、DNS 或代理；若独立应用的极小测试通过，请打开 Helix Subscriptions 后回此重试，排查本地组件连接。此错误不能单独证明登录过期。'}
 for k,v in values.items():s=s.replace('</resources>',f'    <string name="{k}">{v}</string>\n</resources>')
 p.write_text(s)
