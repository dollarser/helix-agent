# Bug Fix: Codex 连通性测试把未完成响应判为成功

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-app

## Problem

订阅可行性连通性测试原本仅比较流中累计的文本是否为 HELIX_OK。响应只返回该文本后断开、只带 DONE 标记，或 completed 事件内部状态为 incomplete 时，均可能误判成功。单行字节上限也在整行读入后才检查。

## Impact

账号连通性结果不能证明一次模型响应正常完成；超长单行可能先被整体读入，再触发大小错误。这是 Runtime 内用户触发的固定探测路径，不是生产 Provider Job 协议。

## Root cause

原解析循环没有记录响应完成状态，只识别失败事件；readUtf8Line 返回后才累计字节数，无法限制该次整行读取。

## Fix and invariants

提取 CodexSmokeStream，复用已有有界读取工具，在 256 KiB 原始字节范围内读取后解析。成功必须同时具备 response.completed、其 response.status 为 completed，以及固定文本 HELIX_OK。DONE 不是完成证明；失败事件、畸形 JSON、缺失终态和超过 64 字符的文本均不能通过。保留固定提示、无工具、Runtime 凭据隔离及原网络超时，无模型重试或账号调用扩展。

## Alternatives considered

单靠文本或 DONE 不能证明服务端正常完成；读完整行后再检查上限不能约束单行读取。固定探测总量较小，采用有界缓冲可以明确限制读取量，无需改变生产流式 Provider。

## Regression verification

修复前六项中四项失败：文本匹配但没有完成事件、DONE 替代完成、completed 内部状态错误、超长单行读入过量。另补精确字节上限、畸形事件与文本上限，九项解析回归全部通过。

Runtime 全部 JVM 88/88、零跳过；模块 Lint、Debug APK、Release Kotlin 编译和 Spotless 通过。根 Detekt 18→14，仍未通过全仓静态门禁。前后日志、命令与产物 hash 见 `build/main-verification/codex-smoke-stream-result.json`。

## Residual risk

测试使用本地响应字节，未复验真实订阅账号、真实网络取消或登录 UI；Release 编译不等于发布验收。模型目录和刷新异常处理还有独立静态问题待处理。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [主分支优化待办](../development/main-optimization-todo.md)
