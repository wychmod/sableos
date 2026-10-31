# Requirements Checklist: 人推链路串联

**Feature**: `011-lesson27-human-trigger-flow` | **Date**: 2026-10-31

## 需求完整性

- [x] 每个用户故事都有 Independent Test（可单独验证）
- [x] 每个功能需求（FR-001 ~ FR-008）都有对应的验收场景或 measurable outcome
- [x] 边界情况被明确列出（`limit <= 0`、空白 status、mock 健康探测、工作区根覆盖、无 key 跳过）
- [x] 范围边界写清：不含定时链路与 Demo 准备（归 28 节），不含认证/流式/分页游标

## 可验证性

- [x] SC-001 ~ SC-006 全部可由自动化测试或一次命令断言
- [x] 「不多不少」的对账口径给出了具体数字（llm_calls=2 / tool_invocations=1 / 历史=4）
- [x] 「三面同源」落到可执行断言（CLI 写入 → REST 读回比对），而非口号
- [x] 失败路径逐类可造（沙箱拦截 / 工具抛异常 / Provider 不可达）

## 一致性

- [x] 端点数量口径全文统一为 11（README 能力表与端点表、TechnicalSolution §7.2 / 第 13 章 / 五大能力清单、frontend README）
- [x] `listRecent` 的语义（过滤在存储侧）在 spec / plan / research / data-model / 课件五处一致
- [x] Bootstrap 缺失口径与 17 节契约一致（WARN），不与课件原文字面冲突
- [x] 课件中不适用于本仓库的路径/命令已改正（`bin/start.sh`、`config/application.yml`、`.sableos/profiles/`、`serve --port`、`config/application.yml.example`）

## 工程质量门槛

- [x] 无新增第三方依赖
- [x] 无新表 / 无新列 / 不改 Flyway
- [x] `session_id` 拼接仍只在 `SessionManager.sessionId` 一处
- [x] Spring AI 自动 tool 执行保持关闭
- [x] 计划在收尾跑 `mvn clean verify`（Spotless / P3C / Checkstyle / SpotBugs 全绿）
