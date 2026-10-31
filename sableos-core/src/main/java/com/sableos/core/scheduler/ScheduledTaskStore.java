package com.sableos.core.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务运行态与执行历史的持久化契约（技术方案 §8.5 / §9.2，第 28 节）。
 *
 * <p>契约在 core、JPA 实现 {@code JpaScheduledTaskStore} 在 storage（依赖倒置，沿用 {@code SessionManager}
 * 先例）。{@link AgentScheduler} 只依赖本接口，core 不出现 JPA 类型。
 *
 * <p>定义来源仍是 Agent 运行声明的 {@code schedules}；本接口只管理"状态 + 历史"，不管理 cron 定义。
 */
public interface ScheduledTaskStore {

  /**
   * 登记或更新一条任务，返回当前运行态。
   *
   * <p>同一 {@code (profileName, scheduleKey)} 必须复用稳定 {@code scheduleId}；命中退役行时复活它。更新时保留 {@code
   * enabled} / {@code runCount} / {@code lastRunAt} / {@code lastStatus}。
   *
   * @param profileName 归属 Agent
   * @param scheduleKey Agent 内配置键
   * @param displayName 展示名
   * @param cron cron 表达式
   * @param zone 时区
   * @param message 到点发送的消息
   * @param nextRunAt 算出的下次触发时刻
   * @return 当前运行态
   */
  ScheduledTaskView reconcile(
      String profileName,
      String scheduleKey,
      String displayName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt);

  /**
   * 退役一条任务：不参与调度与活动列表，但保留状态与历史。任务不存在时静默 no-op。
   *
   * @param scheduleId 任务标识
   */
  void retire(long scheduleId);

  /**
   * 记录一次真正执行：插入执行历史，并更新任务的 {@code lastRunAt / lastStatus / runCount / nextRunAt}.
   *
   * @param scheduleId 任务标识
   * @param sessionId 定时会话标识
   * @param startedAt 开始时刻
   * @param success 是否成功
   * @param errorMessage 失败原因（成功时为 null）
   * @param durationMs 耗时毫秒
   * @param nextRunAt 新的下次触发时刻；null 表示保留原值
   * @return 落库后的执行历史；任务不存在时返回 {@link Optional#empty()}
   */
  Optional<TaskExecutionView> recordExecution(
      long scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt);

  /**
   * @param scheduleId 任务标识
   * @return 任务存在、启用且未退役时为 true；否则 false（含任务不存在）
   */
  boolean isEnabled(long scheduleId);

  /**
   * @param scheduleId 任务标识
   * @param enabled 新的启用状态
   * @return 命中并更新返回 true；任务不存在返回 false
   */
  boolean setEnabled(long scheduleId, boolean enabled);

  /**
   * @param scheduleId 任务标识
   * @return 任务视图（含退役行）；不存在为空
   */
  Optional<ScheduledTaskView> find(long scheduleId);

  /**
   * @return 全部未退役任务，按 Agent 名、任务键升序
   */
  List<ScheduledTaskView> list();

  /**
   * @param scheduleId 任务标识
   * @return 该任务的执行历史，按开始时刻倒序；任务不存在返回空列表
   */
  List<TaskExecutionView> executions(long scheduleId);
}
