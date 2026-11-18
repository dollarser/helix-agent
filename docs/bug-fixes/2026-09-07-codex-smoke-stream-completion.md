# Bug Fix: Codex 固定连通性探测的完成、目录与刷新边界

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110

以下按独立缺陷保留当时的原因、失败与验收范围；各节的 fixed 只指原候选，不合并测试计数或把后续通过回填到早期失败。当前契约仍查主题 ADR。

## Problem

<a id="case-2026-09-07-codex-smoke-stream-completion"></a>

### Codex 连通性测试把未完成响应判为成功

Affected modules: runtime/cli-app

订阅可行性连通性测试原本仅比较流中累计的文本是否为 HELIX_OK。响应只返回该文本后断开、只带 DONE 标记，或 completed 事件内部状态为 incomplete 时，均可能误判成功。单行字节上限也在整行读入后才检查。

<a id="case-2026-09-07-codex-smoke-catalog-shape"></a>

### Codex 探测模型目录未正确验证字段类型

Affected modules: runtime/cli-app

固定订阅连通性测试直接访问模型目录项的 jsonObject/jsonPrimitive。目录项不是对象时抛出未分类 IllegalArgumentException；数字 slug 又会被转换成字符串并当作模型名称。

<a id="case-2026-09-07-codex-smoke-refresh-exceptions"></a>

### Codex 探测刷新异常被统一改写

Affected modules: runtime/cli-app

模型目录返回 401 后，探测层用 catch Exception 包裹刷新。除端点错误和 IOException 外，取消异常与参数异常都会被改写为 CodexSmokeException，丢失原始异常类型。

## Impact

### Codex 连通性测试把未完成响应判为成功

账号连通性结果不能证明一次模型响应正常完成；超长单行可能先被整体读入，再触发大小错误。这是 Runtime 内用户触发的固定探测路径，不是生产 Provider Job 协议。

### Codex 探测模型目录未正确验证字段类型

错误目录可能产生不正确的后续模型请求，或丢失 models-protocol 阶段分类，影响用户判断连通性失败位置。

### Codex 探测刷新异常被统一改写

调用边界无法区分刷新取消与协议处理失败，诊断依赖异常消息关键字。该路径仍会失败，但错误分类不准确。

## Root cause

### Codex 连通性测试把未完成响应判为成功

原解析循环没有记录响应完成状态，只识别失败事件；readUtf8Line 返回后才累计字节数，无法限制该次整行读取。

### Codex 探测模型目录未正确验证字段类型

目录顶层 JSON 解析有错误映射，但目录项解析位于映射之外；JsonPrimitive.contentOrNull 并不要求原始字段为字符串。

### Codex 探测刷新异常被统一改写

探测层重复承担了控制器已有的刷新错误处理，并通过消息内容猜测协议阶段。

## Fix and invariants

### Codex 连通性测试把未完成响应判为成功

提取 CodexSmokeStream，复用已有有界读取工具，在 256 KiB 原始字节范围内读取后解析。成功必须同时具备 response.completed、其 response.status 为 completed，以及固定文本 HELIX_OK。DONE 不是完成证明；失败事件、畸形 JSON、缺失终态和超过 64 字符的文本均不能通过。保留固定提示、无工具、Runtime 凭据隔离及原网络超时，无模型重试或账号调用扩展。

### Codex 探测模型目录未正确验证字段类型

提取 CodexSmokeCatalog，要求目录为 models 数组、候选项为对象，非空 slug/visibility 为字符串。错误结构归为 models-protocol；空目录或没有可选模型仍为 models-empty。保留原目录顺序、隐藏项过滤、空名称与超过 128 字符名称过滤，以及 1 MiB 原始响应上限。没有增加模型重试、刷新或账号调用。

### Codex 探测刷新异常被统一改写

移除探测层的通用捕获和消息猜测，直接调用原 CodexLoginController.refresh。原始取消、参数、端点及 IO 异常向调用方传播；永久无效凭据仍由控制器删除，临时失败仍保留凭据。首次目录 401 仍仅刷新一次，重查仍失败时不再刷新，也不发模型 POST。生产 Job 的终态处理和 UI 的错误文案映射保留，不以异常传播等同于完整网络取消验收。

## Alternatives considered

### Codex 连通性测试把未完成响应判为成功

单靠文本或 DONE 不能证明服务端正常完成；读完整行后再检查上限不能约束单行读取。固定探测总量较小，采用有界缓冲可以明确限制读取量，无需改变生产流式 Provider。

### Codex 探测模型目录未正确验证字段类型

不把数字强制转换为模型名称，也不把错误候选项静默当成正常空目录。字段类型错误与没有可用模型保持独立分类。

### Codex 探测刷新异常被统一改写

不通过更换 catch 语法或增加豁免保留通用包装；探测层不重复实现凭据失效规则，也不从原始消息推断错误类型。

## Regression verification

### Codex 连通性测试把未完成响应判为成功

修复前六项中四项失败：文本匹配但没有完成事件、DONE 替代完成、completed 内部状态错误、超长单行读入过量。另补精确字节上限、畸形事件与文本上限，九项解析回归全部通过。

Runtime 全部 JVM 88/88、零跳过；模块 Lint、Debug APK、Release Kotlin 编译和 Spotless 通过。根 Detekt 18→14，仍未通过全仓静态门禁。前后日志、命令与产物 hash 见 `build/main-verification/codex-smoke-stream-result.json`。

### Codex 探测模型目录未正确验证字段类型

修复前八项中两项失败：非对象项没有返回协议分类，数字 slug 被接受。修复后八项均通过，包括顺序、隐藏/空名称过滤、空目录、畸形 JSON、字段类型、超限及精确字节上限。

Runtime JVM 96/96、零跳过，模块 Lint、Debug 构建、Release Kotlin 编译和 Spotless 通过。根 Detekt 14→13，整体门禁仍未通过。命令、首轮失败与复验日志、产物 hash：`build/main-verification/codex-smoke-catalog-result.json`。

### Codex 探测刷新异常被统一改写

本地 OkHttp 应用拦截夹具提供目录与模型响应，凭据为内存虚构值，无真实网络。修复前六项中两项失败：取消及参数异常被改写。修复后六项全部通过，同时覆盖永久拒绝删除凭据、临时失败保留凭据、轮换令牌用于后续 GET/POST，以及重复 401 不再刷新或 POST。

Runtime JVM 102/102、零跳过；模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 和根 Detekt 全部通过，Detekt 3→0。夹具初次编译/格式错误、有效失败基线与最终结果分别保留，见 `build/main-verification/codex-smoke-refresh-result.json`。

## Residual risk

### Codex 连通性测试把未完成响应判为成功

测试使用本地响应字节，未复验真实订阅账号、真实网络取消或登录 UI；Release 编译不等于发布验收。模型目录和刷新异常处理还有独立静态问题待处理。

### Codex 探测模型目录未正确验证字段类型

本轮使用本地目录响应，不代表真实订阅账号、登录 UI、模型网络调用或发布验收；刷新异常处理还有独立静态问题待处理。

### Codex 探测刷新异常被统一改写

未运行真实订阅刷新、账号网络或 UI 取消流程。根 Detekt 通过不代表全仓 JVM、根 lintDebug、模拟器完整矩阵或产品验收完成。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [当时主分支优化跟进](../evidence/development/main-optimization-todo.md)
- [响应完成状态修复](2026-09-07-codex-smoke-stream-completion.md)
- [模型目录结构验证](2026-09-07-codex-smoke-stream-completion.md#case-2026-09-07-codex-smoke-catalog-shape)
