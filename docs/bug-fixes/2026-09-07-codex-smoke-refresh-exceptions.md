# Bug Fix: Codex 探测刷新异常被统一改写

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-app

## Problem

模型目录返回 401 后，探测层用 catch Exception 包裹刷新。除端点错误和 IOException 外，取消异常与参数异常都会被改写为 CodexSmokeException，丢失原始异常类型。

## Impact

调用边界无法区分刷新取消与协议处理失败，诊断依赖异常消息关键字。该路径仍会失败，但错误分类不准确。

## Root cause

探测层重复承担了控制器已有的刷新错误处理，并通过消息内容猜测协议阶段。

## Fix and invariants

移除探测层的通用捕获和消息猜测，直接调用原 CodexLoginController.refresh。原始取消、参数、端点及 IO 异常向调用方传播；永久无效凭据仍由控制器删除，临时失败仍保留凭据。首次目录 401 仍仅刷新一次，重查仍失败时不再刷新，也不发模型 POST。生产 Job 的终态处理和 UI 的错误文案映射保留，不以异常传播等同于完整网络取消验收。

## Alternatives considered

不通过更换 catch 语法或增加豁免保留通用包装；探测层不重复实现凭据失效规则，也不从原始消息推断错误类型。

## Regression verification

本地 OkHttp 应用拦截夹具提供目录与模型响应，凭据为内存虚构值，无真实网络。修复前六项中两项失败：取消及参数异常被改写。修复后六项全部通过，同时覆盖永久拒绝删除凭据、临时失败保留凭据、轮换令牌用于后续 GET/POST，以及重复 401 不再刷新或 POST。

Runtime JVM 102/102、零跳过；模块 Lint、Debug 构建、Release Kotlin 编译、Spotless 和根 Detekt 全部通过，Detekt 3→0。夹具初次编译/格式错误、有效失败基线与最终结果分别保留，见 `build/main-verification/codex-smoke-refresh-result.json`。

## Residual risk

未运行真实订阅刷新、账号网络或 UI 取消流程。根 Detekt 通过不代表全仓 JVM、根 lintDebug、模拟器完整矩阵或产品验收完成。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [主分支优化待办](../development/main-optimization-todo.md)
- [模型目录结构验证](2026-09-07-codex-smoke-catalog-shape.md)
