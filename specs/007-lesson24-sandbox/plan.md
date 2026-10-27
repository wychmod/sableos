# Implementation Plan: Sandbox 白名单校验

**Branch**: `007-lesson24-sandbox` | **Date**: 2026-10-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-lesson24-sandbox/spec.md`

## Summary

把第 20 节预留的沙箱抽象兑现为唯一一档生产实现：在 `sableos-tool` 新增应用层白名单校验（路径 / 命令 / 域名三类），通过三个 `@ConfigurationProperties` 读取 `file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`；装配层删除临时放行 bean，改为组件扫描注入白名单实现；临时放行类移入测试源码继续充当前序测试的放行替身。四个涉外工具（文件 / Shell / HTTP / 通知）已经在执行入口调用校验，本节不修改其执行骨架；校验失败继续复用 `ToolExecutor` 的失败审计路径。补建 `NotifyToolsTest` 钉死"先校验后发送"，并新增 `WhitelistSandboxTest` 覆盖三类规则与两个绕过点。SMTP 端点白名单按课件三项口径留待扩展阶段。

## Technical Context

**Language/Version**: Java 21（父 POM enforcer 钉死 JDK 21 / Maven 版本）

**Primary Dependencies**: Spring Boot 3.5.16、Spring Framework 6.2.19（`spring-context`/`spring-beans` 经 `sableos-core` 传递）；`sableos-tool` 需显式引入 `org.springframework.boot:spring-boot`（仅 `@ConfigurationProperties` 注解，版本由 Boot BOM 管理，先例见 `sableos-provider/pom.xml`）；Spring AI `spring-ai-model` 已有，不新增；测试用父 POM 的 `spring-boot-starter-test`（JUnit 5 / Mockito / AssertJ）

**Storage**: N/A（本节不新增表、不改迁移；白名单是内存配置）

**Testing**: JUnit 5 + Mockito + AssertJ；`WhitelistSandboxTest` 单元测试、`NotifyToolsTest` 接线与顺序断言；既有 `FileToolsTest`/`ShellToolsTest`/`HttpToolsTest` 回归保持绿；无新增 `@Tag("integration")` 测试

**Target Platform**: JVM 单二进制（`sableos-boot` fat JAR / CLI）

**Project Type**: Maven 多模块后端单体

**Performance Goals**: 无用户可感延迟目标；校验为内存比较，位于真实 IO 之前，复杂度 O(白名单条目数)，不引入网络或磁盘访问

**Constraints**: 空列表/缺失配置 = 全部拒绝；接口签名与动作类型四值逐字沿用第 20 节；不新增模块、不新增第三方依赖；不实现 SMTP 校验；不修改 `ToolExecutor` 审计逻辑；校验不得发生在真实 IO 之后

**Scale/Scope**: 三类白名单；四个涉外工具；生产代码新增 1 个实现类 + 3 个属性记录 + `spring-boot` 模块依赖；测试新增 1 个实现测试 + 1 个通知接线测试；1 个类从主源码移入测试源码

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. JDK 21 + Spring Boot 3.x 单体 | ✅ | 不引入新运行时；沿用 Boot BOM 管理的 `spring-boot` 构件 |
| II. 自实现 ReAct 循环 | ✅ | 不触碰 ReAct / ToolExecutor 执行顺序 |
| III. Spring AI 只用一半 | ✅ | 不启用自动工具执行；仅沿用既有 `@Tool` 管道 |
| IV. Provider 显式映射 | ✅ | 本节无 Provider 改动 |
| V. Tool 只用一个 `sableos-tool` 模块 | ✅ | 沙箱实现、属性记录、测试全部落 `sableos-tool`；不拆模块 |
| VI. 审计 day one 落库 | ✅ | 校验失败复用 `ToolExecutor` 既有失败路径，不新建审计旁路、不改表结构 |
| VII. Sandbox 接口先行 | ✅ | `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 不改；只新增 `WhitelistSandbox` 实现 |
| VIII. 范围收敛 | ✅ | 只做应用层白名单第一档；容器/microVM、MCP 子进程、Profile Tool Policy 均不在本节 |
| IX. 每个 user story 可演示 | ✅ | 三个用户故事均有独立可跑的单元/接线测试；真实链路人工冒烟列入 quickstart |
| AGENTS §5.1 #7 禁用 `SecurityManager` | ✅ | 不引入 `SecurityManager` |
| AGENTS §5.2 线程/traceId 约束 | ✅ | 校验为同步内存调用，无线程池、无 `synchronized`、无 MDC 依赖 |
| AGENTS §5.2 用户可控字符串入日志 | ✅ | 本节不新增日志；拒绝原因只进异常消息与审计列，不入日志 |

**Post-Phase 1 re-check**: 设计未引入新违规。两处需在 Complexity Tracking 记录的取舍：①`spring-boot` 模块依赖；②SMTP 口径按课件三项落地（技术方案 §6.7 留待扩展阶段）。

## Project Structure

### Documentation (this feature)

```text
specs/007-lesson24-sandbox/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── sandbox-whitelist.md
├── checklists/
│   └── requirements.md
└── tasks.md              # /speckit-tasks 输出
```

### Source Code (repository root)

```text
sableos-tool/
├── pom.xml                                              # 修改：新增 spring-boot（@ConfigurationProperties）
├── src/main/java/com/sableos/tool/sandbox/
│   ├── WhitelistSandbox.java                            # 新增：唯一生产实现
│   ├── FileSandboxProperties.java                       # 新增：file.allowed_paths
│   ├── ShellSandboxProperties.java                      # 新增：shell.allowed_commands
│   └── HttpSandboxProperties.java                       # 新增：http.allowed_domains
│   └── PermissiveSandbox.java                           # 移动：src/main -> src/test（同包同 FQN）
├── src/test/java/com/sableos/tool/sandbox/
│   ├── WhitelistSandboxTest.java                        # 新增：三类规则 + 绕过 + deny-all
│   └── PermissiveSandbox.java                           # 移动：仅测试替身
└── src/test/java/com/sableos/tool/builtin/
    └── NotifyToolsTest.java                             # 新增：拦截 + 先校验后发送

sableos-boot/src/main/java/com/sableos/boot/tool/
└── ToolConfiguration.java                               # 修改：删 PermissiveSandbox @Bean；启用三个属性记录

sableos-core/src/main/java/com/sableos/core/tool/
└── ToolExecutor.java                                    # 修改：仅更新 24 节 TODO 注释（无行为变化）

sableos-boot/src/main/resources/
└── application.yaml                                     # 无改动：三个键已存在且注释已写 deny-all
```

**Structure Decision**: 沿用既有 9 模块骨架；沙箱整包留在 `sableos-tool`（AGENTS §5.2 禁止把 Tool 拆多模块），装配改动只在 `sableos-boot`，不向 `sableos-core` 引入新概念。

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代为何不行 |
|---|---|---|
| `sableos-tool` 新增 `org.springframework.boot:spring-boot` 依赖 | 课件要求三个 `@ConfigurationProperties` 记录落在 sandbox 包；该注解只在 `spring-boot` 构件中，当前模块仅经测试作用域可见 | 不引入则属性记录无法编译；改用 `@Value`/`Environment` 会偏离课件的三个属性记录交付物；把记录挪到 `sableos-boot` 会让配置契约离开实现包，且与课件包路径不一致 |
| SMTP 端点白名单按课件三项口径留待扩展阶段 | `ActionType` 四值定死且没有 SMTP 动作，`EmailNotifyAdapter` 属扩展阶段；强行实现只能新增动作类型或留下不可达私有方法（静态门禁会红） | 按技术方案四键实现需要修改第 20 节已定死的公共契约；留不可达方法会被 P3C/SpotBugs 拦截。已在 `AGENTS.md` §10 记录该漂移 |
