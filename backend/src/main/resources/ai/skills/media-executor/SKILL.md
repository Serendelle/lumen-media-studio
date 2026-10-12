---
id: media-executor
title: 处理执行官
version: 1.0.0
agent: 03
tools: (engine) video.compress, video.convert, video.watermark, audio.extract, image.process, image.removebg, pdf.process, metadata.scrub, metadata.modify
terminal_tool: none
max_rounds: 0
mode: deterministic
---

# 处理执行官 · Media Executor

> 本 Agent 由**确定性代码**实现（`ai/agent/MediaExecutor.java`），不调用语言模型。
> 执行阶段需要的是准确、可追踪、可取消，而不是生成文字；这样既省去模型调用的成本与延迟，也杜绝了「模型自行改参数」的风险。

## 角色
你是 Agent 03，严格按照**用户已确认**的计划，调用真实的处理引擎执行每一步。

## 目标
每一步都使用与专业模式完全相同的任务引擎（`TaskEngine`），产出可下载的新文件，并实时汇报真实进度。

## 职责
1. 只执行版本号与用户确认版本一致的计划；计划在确认后若有变化，必须重新确认。
2. 按依赖顺序执行：`input: "step:N"` 使用第 N 步的输出文件。
3. 每一步提交前，由引擎再次做参数校验与归属校验。
4. 把任务的排队、进度、完成、失败如实推送给前端。
5. 用户取消会话时，取消正在运行的任务。

## 语气
不产生对话文字，只产生结构化事件（step 事件）。

## 输入
已确认的计划（plan + version）。

## 输出
每一步的 `{stepId, taskId, status, progress, stage, outputFileId}`。

## 可调用工具
处理引擎中的 9 种任务类型（见 front matter），全部经过 `TaskEngine.submit` 的校验、配额与幂等控制。

## 能力边界
- 不修改参数，不增加或删除步骤。
- 不覆盖原文件。

## 禁止行为
- 未经确认不执行。
- 不重复提交同一步（每一步使用由会话 ID、计划版本和步骤号组成的固定幂等键）。

## 失败处理
- 某一步失败：标记为失败并记录原因；依赖它的步骤标记为「已跳过」；与它无关的步骤继续执行。
- 引擎排队已满或配额已满：如实报告，不无限等待。

## 交接条件
所有步骤都结束（成功、失败或跳过）后交给质量审核官。
