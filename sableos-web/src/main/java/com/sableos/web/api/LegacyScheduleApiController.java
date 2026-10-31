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
 * 定时任务管理 v1 兼容层（第 28 节）：按任务键定位，保留课件要求的旧路径。
 *
 * <p>任务键只在 Agent 内唯一；多个 Agent 使用同一 key 时按 key 定位的路径返回 409，绝不静默选第一条。管理台不调用本类。
 */
@RestController
@RequestMapping("/api/v1/schedules")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class LegacyScheduleApiController {

  private final ScheduledTaskStore scheduledTaskStore;
  private final AgentScheduler agentScheduler;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：构造器注入的两个协作者都是依赖注入引用，本类无返回它们的 getter，不构成暴露内部表示；"
              + "撤销条件：一旦新增暴露这些字段的 getter 或 public 字段，须重新评估。")
  public LegacyScheduleApiController(
      ScheduledTaskStore scheduledTaskStore, AgentScheduler agentScheduler) {
    this.scheduledTaskStore = scheduledTaskStore;
    this.agentScheduler = agentScheduler;
  }

  @GetMapping
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(scheduledTaskStore.list().stream().map(ScheduleView::from).toList());
  }

  @GetMapping("/{key}/executions")
  public ApiResponse<List<ScheduleExecutionView>> executions(@PathVariable String key) {
    ScheduledTaskView task = resolve(key);
    return ApiResponse.ok(
        scheduledTaskStore.executions(task.scheduleId()).stream()
            .map(ScheduleExecutionView::from)
            .toList());
  }

  @PostMapping("/{key}/run")
  public ApiResponse<ScheduleExecutionView> run(@PathVariable String key) {
    ScheduledTaskView task = resolve(key);
    return ApiResponse.ok(
        agentScheduler
            .runNow(task.scheduleId())
            .map(ScheduleExecutionView::from)
            .orElseThrow(() -> new ConflictException("上一次执行尚未结束，请稍后重试")));
  }

  @PutMapping("/{key}")
  public ApiResponse<ScheduleView> update(
      @PathVariable String key, @RequestBody UpdateScheduleRequest request) {
    if (request == null || request.enabled() == null) {
      throw new IllegalArgumentException("enabled 不能为空");
    }
    ScheduledTaskView task = resolve(key);
    scheduledTaskStore.setEnabled(task.scheduleId(), request.enabled());
    return ApiResponse.ok(
        ScheduleView.from(
            scheduledTaskStore
                .find(task.scheduleId())
                .orElseThrow(() -> new ResourceNotFoundException("定时任务不存在: " + key))));
  }

  private ScheduledTaskView resolve(String key) {
    List<ScheduledTaskView> matches =
        scheduledTaskStore.list().stream().filter(view -> view.scheduleKey().equals(key)).toList();
    if (matches.isEmpty()) {
      throw new ResourceNotFoundException("定时任务不存在: " + key);
    }
    if (matches.size() > 1) {
      throw new ConflictException(
          "任务键在多个 Agent 下重复: " + key + "，请改用 /api/v2/schedules/{scheduleId}");
    }
    return matches.get(0);
  }
}
