package com.sableos.web.api;

import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.web.api.dto.ScheduleExecutionView;
import com.sableos.web.api.dto.ScheduleView;
import com.sableos.web.api.dto.UpdateScheduleRequest;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ConflictException;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理 v2 端点（第 28 节，契约见 {@code contracts/management-api.md}）。
 *
 * <p>薄门面：只做定位、参数校验与响应包装；状态与历史委托 {@link ScheduledTaskStore}，立即执行委托 {@link AgentScheduler}。 管理台只调用
 * v2；v1 兼容层在 {@link LegacyScheduleApiController}。
 */
@RestController
@RequestMapping("/api/v2/schedules")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class ScheduleApiController {

  private final ScheduledTaskStore scheduledTaskStore;
  private final AgentScheduler agentScheduler;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：构造器注入的两个协作者都是依赖注入引用，本类无返回它们的 getter，不构成暴露内部表示；"
              + "撤销条件：一旦新增暴露这些字段的 getter 或 public 字段，须重新评估。")
  public ScheduleApiController(
      ScheduledTaskStore scheduledTaskStore, AgentScheduler agentScheduler) {
    this.scheduledTaskStore = scheduledTaskStore;
    this.agentScheduler = agentScheduler;
  }

  @GetMapping
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(scheduledTaskStore.list().stream().map(ScheduleView::from).toList());
  }

  @GetMapping("/{scheduleId}/executions")
  public ApiResponse<List<ScheduleExecutionView>> executions(@PathVariable long scheduleId) {
    requireTask(scheduleId);
    return ApiResponse.ok(
        scheduledTaskStore.executions(scheduleId).stream()
            .map(ScheduleExecutionView::from)
            .toList());
  }

  @PostMapping("/{scheduleId}/run")
  public ApiResponse<ScheduleExecutionView> run(@PathVariable long scheduleId) {
    requireTask(scheduleId);
    return ApiResponse.ok(
        agentScheduler
            .runNow(scheduleId)
            .map(ScheduleExecutionView::from)
            .orElseThrow(() -> new ConflictException("上一次执行尚未结束，请稍后重试")));
  }

  @PutMapping("/{scheduleId}")
  public ApiResponse<ScheduleView> update(
      @PathVariable long scheduleId, @RequestBody UpdateScheduleRequest request) {
    if (request == null || request.enabled() == null) {
      throw new IllegalArgumentException("enabled 不能为空");
    }
    if (!scheduledTaskStore.setEnabled(scheduleId, request.enabled())) {
      throw new ResourceNotFoundException("定时任务不存在: " + scheduleId);
    }
    return ApiResponse.ok(ScheduleView.from(requireTask(scheduleId)));
  }

  private ScheduledTaskView requireTask(long scheduleId) {
    return scheduledTaskStore
        .find(scheduleId)
        .filter(view -> !view.retired())
        .orElseThrow(() -> new ResourceNotFoundException("定时任务不存在: " + scheduleId));
  }
}
