"""Update scoped documentation/formatting for connection and capability separation."""
from pathlib import Path
import subprocess
import re
base=Path('app/src/main/res/values/strings.xml')
zh=Path('app/src/main/res/values-zh-rCN/strings.xml').read_text()
s=base.read_text()
for key in ['provider_capabilities_test','provider_capabilities_testing','provider_capabilities_unverified','provider_capabilities_passed','provider_capabilities_failed']:
    pattern=rf'    <string name="{key}">.*?</string>'
    s=re.sub(pattern,re.search(pattern,zh).group(0),s)
base.write_text(s)
p=Path('docs/adr/0044-subscription-progress-and-capability-probes.md')
s=p.read_text()
s=s.replace('Codex 日常连接测试依次验证目录/网络、完成前增量、合成 echo 调用与结果回填、内置色块图片理解，以及目录声明的首个显式推理强度。', '2026-09-10 所有者要求拆分：连接测试仅查询目录并完成一次无工具、无图片、默认推理的简短文本请求；能力检测由独立按钮触发，验证完成前增量、合成 echo 调用与结果回填、内置色块图片理解，以及目录声明的首个显式推理强度。连接通过不冒充全部能力通过，尚未检测标记为 CONNECTION_ONLY；能力检测失败不撤销已通过的连接状态。')
s=s.replace('连接测试会消耗数次合成模型请求，耗时取决于服务端声明的强度数量。', '连接测试只生成一次简短回复；独立能力检测需要多次合成模型请求。')
p.write_text(s)
p=Path('docs/development/subscription-connection-investigation-2026-09-10.md')
s=p.read_text()
s=s.replace('HXA-190 仍在进行。所有者要求先通过完整 Provider 能力探测，再执行 Chat/Plan/Act 对话验收。模拟器由 Claude 独占，本轮只用明确选择的物理设备。', 'HXA-190 仍在进行。最新安排是优先修 bug，拆分连接测试与能力检测，由所有者人工验证对话；早期完整能力闸门安排已被此要求取代。模拟器由 Claude 独占。')
s=s.replace('- 日常连接检查仍实际检查流式、工具回填、图片及一个服务端声明的推理档位；全档位矩阵保留独立诊断，不再因 ultra 单项失败禁止选择整个 Provider。未改为虚假通过，也未静默替换用户选择的推理强度。', '- 连接测试只查目录并生成一次简短文本；流式、工具回填、图片和一个动态推理档位移到独立能力检测。失败仅显示能力检测失败，不撤销连接成功；首次仅连通显示“能力尚未检测”。复用同一 Provider 的目录读取上下文窗口，避免再次冷绑定查询。')
s=s.replace('人工入口：更新后点击一次连接测试刷新旧失败状态，再检查连续 Chat、Plan、Act。默认或 low/medium/high 可先验证基本恢复；ultra 的服务端拒绝仍作为单项兼容性问题保留。', '''人工入口：新测试手机原先只有主应用，安装订阅组件后需在该组件重新登录。连接测试通过即可检查 Chat；另点能力检测验证工具等能力后检查 Plan/Act。订阅登录不可从旧手机或其他应用提取迁移。

### 长回复仍失败的最新定位

- 旧手机最新结果在约 7 秒内写入 canonical `Error(TRANSPORT)`，不是已移除的 120/150 秒时限；Runtime journal 的 SUCCEEDED 仍只表示结果落盘。
- 发现读流 IOException 会丢弃此前事件，使持久结果只剩 Error；已改为保留准确前缀并追加 Error，预览与最终结果保持一致，不伪造 Completed、不自动重放。
- 新增 `HelixSubscriptionIo` 脱敏日志：仅记录 headers/body 阶段、异常类型链、固定原因分类、事件数、耗时、应用取消/线程中断标志，不写原始异常消息、URL、凭据或对话内容。
- 原手机已断开，所有者指定后续使用 OnePlus 6T。尚未在新手机复现；具体 socket 中断原因未关闭，保留部分输出的修复不等于网络根因修复。''')
s=s.replace('待用户确认冻结工具及两包豁免后做对照。','用户已确认未使用冻结工具，不能以第三方工具豁免作为前置。')
start=s.index('## 后续门禁')
end=s.index('机器证据保存在',start)
s=s[:start]+'''## 后续验证

1. 按所有者要求不再自动发送对话；新手机登录后人工验证短连接、独立能力检测、连续及长回复。
2. 若长回复仍中断，读取新分类日志并关联 Runtime 状态，定位具体 I/O 终止原因；不盲目增加超时、截断阈值或自动重放。
3. 本轮只构建两包，并运行一项中断保留前缀的主机回归；全量发布验收和其他设备矩阵保留后续。

'''+s[end:]
p.write_text(s)
p=Path('docs/development/status.md')
s=p.read_text().replace('## In progress\n', '''## In progress

最新补充：连接测试与能力检测已拆分；仅连通显示能力尚未检测，能力失败不禁用已连接 Provider。网络中断时保留已输出前缀与错误，并增加不含正文/凭据的 I/O 分类日志。长回复 socket 中断根因仍未关闭；原手机已断开，所有者指定后续用 OnePlus 6T，需安装订阅组件并重新登录后人工复现。两包构建与单项中断前缀回归通过，不代表长回复已验收。
''',1)
s=s.replace('已增加真实流式、工具回填、图片识别及服务端声明推理档位的分阶段探测；必须先通过再继续对话验收。', '真实流式、工具回填、图片识别及推理档位保留独立能力检测；最新安排是所有者人工验收。')
p.write_text(s)
paths = ['app/src/main/kotlin/com/helix/app/'+p for p in ['provider/ProviderConnectionProbe.kt','provider/ProviderService.kt','provider/ProviderUiModels.kt','ui/ProviderRow.kt','ui/ProviderScreen.kt','ui/ProviderRowActions.kt']]
paths += ['provider/api/src/main/kotlin/com/helix/provider/api/ProviderCapabilities.kt','runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionModel.kt','runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionModelStream.kt','runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/SubscriptionPartialResultTest.kt']
with Path('build/debug/2026-09-10/subscription-check-split-format.log').open('w') as out:
    result=subprocess.run(['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(Path(p).resolve()) for p in paths)],stdout=out,stderr=subprocess.STDOUT)
raise SystemExit(result.returncode)
