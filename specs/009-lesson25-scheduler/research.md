# Phase 0 Research: 定时任务调度（第 25 节）

**Feature**: `specs/009-lesson25-scheduler` | **Date**: 2026-10-29

本节的技术问题全部可从前序交付物与本地依赖直接求证，无需外部调研。以下每条决策均给出依据与替代方案被否的原因。

## D-01 `AgentScheduler` 落 `sableos-core`，因此 core 需要 `spring-context`

- **决策**：《技术方案》§8.5 明确「`AgentScheduler` 模块（归 `sableos-core`）」且「基于 Spring 的 `ThreadPoolTaskScheduler` 加 `CronTrigger`」。故在 `sableos-core/pom.xml` 增加 `org.springframework:spring-context`（compile，版本由父 POM 的 Boot BOM 管理，不写 `<version>`）。
- **实测**：`mvn -pl sableos-core dependency:tree` 显示 core 当前只在 **test** 作用域经 `spring-boot-starter-test` 看到 `spring-context:6.2.19`，compile 作用域没有。不加依赖无法编译。
- **替代方案被否**：把 `AgentScheduler` 挪到 `sableos-tool` / `sableos-boot` 违反 §8.5 与 skill 模块落位表；在 core 自定义「调度注册」抽象接口再把 Spring 实现放 cli，属文档外的抽象层（skill 明令不建）。
- **影响面**：core 自此不再是「零 Spring」模块。这是技术方案既定安排（§8.5），`AGENTS.md` 的硬约束与架构测试（只约束 `com.sableos.*` 依赖方向）均不受影响。

## D-02 依赖 `TaskScheduler` 接口，Bean 实例仍是 `ThreadPoolTaskScheduler`

- **决策**：`AgentScheduler` 构造参数类型取 `org.springframework.scheduling.TaskScheduler`；装配层提供的实例为 `ThreadPoolTaskScheduler`（§8.5 点名的具体类型）。
- **理由**：`schedule(Runnable, Trigger)` 正是 `TaskScheduler` 的方法，接口足够表达所需能力；单测可直接 mock 接口并按参数捕获验证注册内容，不必为具体类付出 `ThreadPoolTaskScheduler` 初始化成本（`initialize()` 会起真实线程池）。
- **课程骨架同构性**：课件字段写 `ThreadPoolTaskScheduler`，属示意；其测试关注「注册参数传对」而非具体类型，ABI 不变。

## D-03 cron + 时区都经 `CronTrigger`，时区用 `ZoneId` 严格解析

- **实测**（`javap` Spring 6.2.19）：`CronTrigger` 有 `(String)`, `(String, TimeZone)`, `(String, ZoneId)` 三个重载；`getExpression()` 可取回表达式，但**没有** `getTimeZone()`，且 `equals()` 只比较表达式、不比时区（字节码已核）。
- **决策**：一律走 `new CronTrigger(cron, ZoneId.of(zone))`。`ZoneId.of` 对非法时区抛异常（可捕获后按 FR-010 跳过该条）；不用 `TimeZone.getTimeZone`，它对非法 ID **静默返回 GMT**，会让「时区写错」变成「按伦敦时间跑」且毫无痕迹。
- **时区断言策略**：测试无法从 `CronTrigger` 读回时区，改为**行为断言**——给定 `lastActualExecution` 固定时刻，用 `trigger.nextExecution(SimpleTriggerContext)` 求下一次触发时刻，断言其等于「按配置时区解释后」的即时点。同一 cron 在 UTC 与 `Asia/Shanghai` 下结果相差 8 小时，该断言足以钉死时区没有被服务器默认时区顶替。
- **时区缺失**：视为非法配置（FR-003 要求按声明时区解释），记错误日志并跳过该条，不回落服务器默认时区。

## D-04 重叠保护：按任务 id 的 `ConcurrentHashMap<String, Lock>` + `tryLock`

- **决策**：与课件骨架逐字同构——`ConcurrentMap<String, Lock> taskLocks`，`computeIfAbsent(id, k -> new ReentrantLock())`，`tryLock()` 失败即记 WARN 并返回；`finally` 无条件 `unlock()`。
- **测试入口**：`lockFor(String taskId)` 提供包级可见的取锁方法，供 harness 用真实锁模拟「上一次仍占着」；不新增 public API 之外的对外概念（同包测试可直接调用）。
- **`synchronized` 规避**：用 `ReentrantLock` 而非 `synchronized`，与 AGENTS §5.2「`synchronized` 内不得阻塞」同向——虽然锁内会跑完整 ReAct（必然含 IO），但这正是 `ReentrantLock` 的适用场景；锁只在同一任务的触发之间互斥，不与其他任务共享。

## D-05 链路标识：每次触发用 `TracedExecutors.wrapRoot` 开新根链路

- **决策**：注册时把 `runOnce` 包成 `TracedExecutors.wrapRoot(() -> runOnce(profile, sc), TraceContext.CHANNEL_SCHEDULER)`。`ThreadPoolTaskScheduler` 无 `setTaskDecorator`，该工具的 javadoc 已明确指定这是定时入口的用法；跳过分支同样落在这条新链路里，不会复用上次 traceId。
- **不新增 `TaskDecorator` bean**：全容器仍只允许 `ObservabilityConfig` 那一个（AGENTS §5.2）。

## D-06 会话身份：只调用 `SessionManager.getOrCreate`，不自行拼 session_id

- **决策**：`sessionManager.getOrCreate(TraceContext.CHANNEL_SCHEDULER, "scheduler", profile.name())`。渠道常量直接复用 `TraceContext.CHANNEL_SCHEDULER`（值即 `scheduler`）；用户标识用私有常量 `SCHEDULER_USER_ID = "scheduler"`。
- **依据**：H4④ 要求 `session_id` 的拼接只发生在 `SessionManager.sessionId` 一处；`AgentService.process` 内部 `attachBusiness` 会把 `session.channel()`（即 `scheduler`）投影进 MDC。

## D-07 注册时机：`serve` / `gateway` 启动后显式调用 `registerAll`

- **决策**：`AgentScheduler` 以显式 `@Bean` 装配（新增 `sableos-boot/.../scheduler/SchedulerConfiguration`）；`ServeCommand`、`GatewayCommand` 在上下文启动成功后调用 `context.getBean(AgentScheduler.class).registerAll()`。`chat` 不调用。
- **依据**：《技术方案》§8.6「定时任务随 `serve`/`gateway` 一起常驻调度」。三种模式共用同一 `SableOsRuntime` 装配根，若用 `@PostConstruct` 让 Bean 自己注册，`chat` 也会开始跑定时任务，与 §8.6 不符。
- **不新增配置键**：不用 `@ConditionalOnProperty` 之类的开关——那会引入本节交付物之外的配置概念。

## D-08 失败隔离与日志

- **决策**：`runOnce` 内部 `try { … } catch (Exception e) { LOG.error("定时任务执行失败：id={} reason={}", safe(id), safe(e.getMessage()), e); } finally { lock.unlock(); }`。
- **日志净化**：任务 id 与消息内容都来自用户可编辑的 `AGENT.md`，入日志前一律 `replaceAll("[\\r\\n]", "_")`（AGENTS §5.3 指定形态，勿改 `Pattern/Matcher`）。
- **不外抛**：异常在 `runOnce` 内吞掉并记账；`ScheduledFuture` 的异常不会把 `ThreadPoolTaskScheduler` 带崩，但吞在入口处更明确，也让「失败后锁已释放」可被单测直接验证。

## D-09 注册期校验（FR-010）

- **决策**：逐条注册时校验 id / cron / zone 非空；任一缺失或解析失败（`CronExpression` 非法、`ZoneId.of` 抛错）只记该条的 error 日志并继续下一条，绝不中断 `registerAll`。
- **理由**：与 `AgentLoader`「坏 Agent 不阻断启动」的口径一致；消息内容不做校验（spec 边界：消息语义不归调度模块）。
