package com.sableos.web.api;

import com.sableos.core.scheduler.AgentScheduler;
import com.sableos.core.scheduler.ScheduledTaskStore;
import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.web.api.dto.ScheduleExecutionView;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ConflictException;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按 Agent + 任务键精确定位的立即执行端点（第 28 节，技术方案 §8.5）。
 *
 * <p>多个 Agent 可以各自声明同名任务键；本端点用 {@code profileName + key} 消除歧义，是 v2 的推荐补跑入口。
 */
@RestController
@RequestMapping("/api/v2/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class ScheduleAgentRunController {

  private final ScheduledTaskStore scheduledTaskStore;
  private final AgentScheduler agentScheduler;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：构造器注入的两个协作者都是依赖注入引用，本类无返回它们的 getter，不构成暴露内部表示；"
              + "撤销条件：一旦新增暴露这些字段的 getter 或 public 字段，须重新评估。")
  public ScheduleAgentRunController(
      ScheduledTaskStore scheduledTaskStore, AgentScheduler agentScheduler) {
    this.scheduledTaskStore = scheduledTaskStore;
    this.agentScheduler = agentScheduler;
  }

  @PostMapping("/{profileName}/schedules/{key}/run")
  public ApiResponse<ScheduleExecutionView> run(
      @PathVariable String profileName, @PathVariable String key) {
    ScheduledTaskView task =
        scheduledTaskStore.list().stream()
            .filter(
                view -> view.profileName().equals(profileName) && view.scheduleKey().equals(key))
            .findFirst()
            .orElseThrow(
                () -> new ResourceNotFoundException("定时任务不存在: " + profileName + "/" + key));
    return ApiResponse.ok(
        agentScheduler
            .runNow(task.scheduleId())
            .map(ScheduleExecutionView::from)
            .orElseThrow(() -> new ConflictException("上一次执行尚未结束，请稍后重试")));
  }
}
