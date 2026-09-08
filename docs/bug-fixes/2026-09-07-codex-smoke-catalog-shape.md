# Bug Fix: Codex 探测模型目录未正确验证字段类型

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102, HXA-110
Affected modules: runtime/cli-app

## Problem

固定订阅连通性测试直接访问模型目录项的 jsonObject/jsonPrimitive。目录项不是对象时抛出未分类 IllegalArgumentException；数字 slug 又会被转换成字符串并当作模型名称。

## Impact

错误目录可能产生不正确的后续模型请求，或丢失 models-protocol 阶段分类，影响用户判断连通性失败位置。

## Root cause

目录顶层 JSON 解析有错误映射，但目录项解析位于映射之外；JsonPrimitive.contentOrNull 并不要求原始字段为字符串。

## Fix and invariants

提取 CodexSmokeCatalog，要求目录为 models 数组、候选项为对象，非空 slug/visibility 为字符串。错误结构归为 models-protocol；空目录或没有可选模型仍为 models-empty。保留原目录顺序、隐藏项过滤、空名称与超过 128 字符名称过滤，以及 1 MiB 原始响应上限。没有增加模型重试、刷新或账号调用。

## Alternatives considered

不把数字强制转换为模型名称，也不把错误候选项静默当成正常空目录。字段类型错误与没有可用模型保持独立分类。

## Regression verification

修复前八项中两项失败：非对象项没有返回协议分类，数字 slug 被接受。修复后八项均通过，包括顺序、隐藏/空名称过滤、空目录、畸形 JSON、字段类型、超限及精确字节上限。

Runtime JVM 96/96、零跳过，模块 Lint、Debug 构建、Release Kotlin 编译和 Spotless 通过。根 Detekt 14→13，整体门禁仍未通过。命令、首轮失败与复验日志、产物 hash：`build/main-verification/codex-smoke-catalog-result.json`。

## Residual risk

本轮使用本地目录响应，不代表真实订阅账号、登录 UI、模型网络调用或发布验收；刷新异常处理还有独立静态问题待处理。

## Related records

- [HXA-102](../completion-records/HXA-102.md)
- [主分支优化待办](../development/main-optimization-todo.md)
- [响应完成状态修复](2026-09-07-codex-smoke-stream-completion.md)
