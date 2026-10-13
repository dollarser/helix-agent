# Debug 模型请求日志关联

日期：2026-10-14。HXA-282，沿accepted ADR-PROVIDER-001。Owner已升级30008兼容代理，使其记录代理request ID、有效思考字段和SGLang SSE response ID；要求Helix两项关联身份仅在Debug开发时自动发送，无用户设置入口。本轮主机实现与验证，不自动使用设备/真实模型或启动长测。

## 服务端补充与归因边界

下列为Owner提供的服务端核对结论，本机没有读取远端原日志或模型文件。rep93/140已匹配同一SGLang进程：生成只有普通文字，随后248046/im_end；工具定义存在，无输出长度/上下文/工具解析截断证据。与本机旧SSE无工具片段一致，直接停止机制已确认，诱因未确认，不归罪Helix漏解析或SGLang丢弃已生成调用。

Owner报告model_type=qwen3_5、Qwen3_5ForConditionalGeneration、Qwen2Tokenizer，模型与tokenizer来自同一目录；未固定上游revision。版本报告0.5.18.dev721+ge6df23f3c，启动前checkout为6220f45d，版本后缀不独立证明加载的源码身份。reasoning_parser=qwen3、tool_call_parser=qwen3_coder；max_new_tokens8192、temperature1.0、top_p0.95、top_k20，无自定义stop/stop_token_ids、ignore_eos=false、EAGLE启用，失败渲染提示出现xhigh。均不把这些相关性写作诱因证明。

模板结论仍需补证：default HuggingFace模板日志不能独立证明使用tokenizer_config内嵌模板；[Transformers v4.57.1加载源码](https://github.com/huggingface/transformers/blob/v4.57.1/src/transformers/tokenization_utils_base.py#L1976)会优先独立chat_template.jinja。应核对实际Transformers版本和运行进程最终模板hash；当前磁盘重载也不独立证明旧进程已加载内容。旧R5 FAIL_FUNCTIONAL及修后8/8有界结果保持原身份，不改写。

## 当前实现

复用现有LocalModelCallContext，不改AgentLoop、ModelRequest或三种协议适配器。在App的HTTP seam读原Turn/model-call的opaque ID，仅开发关联、不序列化执行能力，不加session/用户内容/密钥或正文日志。模型JSON不变；同一传输请求重复使用其身份，新的模型步骤/已有自动重试读取journal实际新model-call，原Turn身份不变，不改恢复策略。

生产factory的Debug标志取已安装App的ApplicationInfo.FLAG_DEBUGGABLE，默认构造关闭；仅USER_CONFIGURED网络API、Debug及显式自定义资格满足时给三个协议使用的WireClient加装饰。订阅/设备内模型经原additionalFactory原样返回；官方表单和Release不装饰。没有用户开关或Room schema变化。

Owner要求不用域名排除。现有草稿templateId没有持久化到Provider行，因此复用LineStore记录开发诊断资格：明确创建/保存generic-openai自定义表单后，按provider ID与精确transport.cacheKey绑定；官方表单清除、编辑先清再在DB提交后记录、删除清除。它只是开发关联资格，不是产品来源/权限的第二权威；进程中断最多丢失诊断，未知/缺失/改变绑定不会扩大行为。旧配置不猜测，需通过自定义表单保存后才具备资格。Release不记录/读取启用；原测试/元数据存储不复用同一个key。

装饰器只为有LocalModelCallContext的POST添加两头，保留原body字节、认证及其他头，GET/无身份请求保持原对象。ASCII/长度及大小写冲突失败在发送前拒绝，取消/底层错误原样传播。ProviderHeaders禁止配置这两个保留名称，不能手工开启或伪造；不持久化外部response ID、不更改x-request-id、不新增追踪框架。

参考[OpenAI请求ID实践](https://developers.openai.com/api/reference/overview/#debugging-requests)的传输诊断分层，不复制实现；这里是Helix/自有代理约定，不是模型通用参数。代理已升级是Owner报告，本轮尚未调用它验证关联。

## 验证

进行中。主机覆盖显式来源/旧记录默认关闭、Debug/Release/官方/订阅/设备内模型门禁、三个实际协议stream的协程上下文到HTTP精确关联与JSON不变、无身份/GET、同实例重复/并发隔离、ASCII/长度/伪造边界、取消和HTTP错误保持。设备/真实服务not requested，不计外部服务通过，不覆盖日常APK。

早期较广实现的all-r1因App没有生成BuildConfig而编译失败，随后按Owner限制改为App层复用已有context与ApplicationInfo，未采纳开启新BuildConfig/修改核心请求或三个适配器的方案。target-r2取消夹具误将普通WireClient接口当SAM而编译失败，已改显式对象；不跳过检查，最终结果后续补录。

target-r3/r4因取消夹具新增object长行格式失败，拆为独立fixture后target-r5通过，两渠道DebugModelCorrelationTest各6方法、零失败/错误/skip。三个协议的真实provider.stream/flowOn均在假HTTP 401下保持AUTH终态并携带正确Debug身份，body字节与关闭/官方路径一致；这是真实生产编码/发送层的主机测试，不是外部模型验收。全仓all-r2进行中，设备/服务仍not requested。

最终`debug-correlation-all-r2.txt`的`check-all.sh --all`退出0：source/JVM、Spotless/Detekt、两渠道Debug/Release lint与APK、锁及制品/Runtime边界均通过。`debug-correlation-androidtest-r1.txt`双渠道AndroidTest APK编译退出0。当前相关JUnit：两渠道新增类各6方法，core ProviderHeadersTest10方法（新增1），均零失败/错误/skip。以实际方法和门禁结果接受，不增加固定总数门禁。

对实际APK执行apkanalyzer manifest debuggable：consumer/developer Debug均true，两个Release unsigned均false；首次误用不带unsigned的Release文件名不存在，改用实际输出后四项命令均退出0。源代码已冻结，后续仅结算文档；没有设备安装/测试或模型调用，真实代理关联not requested。旧配置没有来源标记，在新版Debug通过自定义表单保存一次后才启用；不自动把旧官方/未知来源认作自定义，也不要求普通用户配置调试头。
