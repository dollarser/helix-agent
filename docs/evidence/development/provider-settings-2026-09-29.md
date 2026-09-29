# Provider 设置层级与配置表单

日期：2026-09-29。所有者明确要求三类入口、可选 API Key、字段顺序、模型发现多选以及统一订阅操作。工作基于 `4a1443df`，位于独立 `codex/provider-settings` 分支，复用空闲工作树；主目录的会话/视觉并行改动未修改。

## 实现范围

- 首页为本地模型、API/自建服务、订阅账号三个入口，点开后才显示该类 Provider；本地安装与网络新增入口放到相应分类内。
- 网络表单按名称、endpoint、API Key、高级 header、模型 ID 排列。所有网络模板都显示可选 Key。修复更新非必填 Key 模板时删除已有凭据的旧分支，留空保留，填写替换。
- 高级 header 编辑保留其余已有 header，继续原白名单校验。在线目录发现使用当前表单、临时 CredentialLookup 和正式协议 adapter，只发目录请求，不创建临时配置、不保存密钥、不写连接或能力通过。
- HTTP 目录发现必须先明确确认；编辑 endpoint 后清除确认，目录结果受表单快照约束。读取已有凭据仅限 endpoint 未改变的编辑查询；新地址需要重新填写查询所需凭据。
- 可多选模型并手输默认 ID，用户选择单独持久化，不作为连接或能力通过证据。仍需连接测试，能力未知保持未知。
- 订阅控制统一为管理登录、上下文窗口、连接测试、能力检测，采用同宽按钮；真实账号适配范围不变。

## 验证口径

最终双渠道 app unit、debug lint、debug APK、AndroidTest APK、spotlessCheck、detekt 全部通过（876 个 Gradle task，不是测试用例数），日志 `build/provider-settings-final-gates.log`。新增 `ProviderDraftDiscoveryTest` 每渠道 3/3，通过有/无 Key 的三种自部署模板请求、未确认明文拒绝、选择持久化且不伪造测试通过状态。凭据添加/保留/替换及表单多选另有 instrumentation 覆盖。

`check-all.sh --source` 通过：618 Markdown、213 HXA、35 ADR、1814 三语言资源键；日志 `build/provider-settings-source.log`。`git diff --check` 通过。首次格式和静态检查发现的长行/参数与复杂度问题已修正并重跑；失败日志保留，不计为通过。

初始实现时，新建/更新界面 instrumentation 只编译，设备验证 `not requested`；未访问真实模型服务、订阅账号或付费额度。临时网络鉴权测试使用内存 WireClient，数据均为合成 fixture。窄屏、大字体、实际账号登录和跨进程 UI 操作仍需设备定向验收。

两份构建所需的 Plugin/Mobile Use 依赖锁从主目录既有收口工作复制，内容未更改；不是本轮新增依赖。改动未提交、未合并 main、未推送。随后所有者手机试用触发的返回/交互修复及定向真机结果见[设置交互证据](settings-interactions-2026-09-29.md)。

历史脚本 `scripts/debug/2026-09-29/provider-ui-edit.py` 是本次一次性编辑记录，不是产品运行入口。
