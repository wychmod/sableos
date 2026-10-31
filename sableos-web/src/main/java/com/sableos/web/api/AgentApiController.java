package com.sableos.web.api;

import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.web.api.dto.InvokeRequest;
import com.sableos.web.api.dto.ReplyView;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 无状态调用端点（26 节）：一次请求跑完一次完整处理，用完即弃。
 *
 * <p>“一次性会话”仍走 {@link SessionManager#getOrCreate}：用随机用户标识构成新的三元组，既保证会话身份只由 SessionManager 拼接，
 * 又让每次调用留下可追溯的会话与审计记录，且不会复用上一条历史。
 */
@RestController
@RequestMapping("/api/v1/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class AgentApiController {

  private static final String INVOKE_USER_PREFIX = "invoke-";

  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentCallExecutor agentCallExecutor;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ProfileRegistry 自第 29 节起支持运行期 remove，SpotBugs 因而把它判为可变类型；"
              + "本类只把它当依赖注入的协作者查询使用，无返回它的 getter，也不暴露其内部集合。"
              + "撤销条件：一旦新增暴露该字段的 getter 或 public 字段，须重新评估。")
  public AgentApiController(
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentCallExecutor agentCallExecutor) {
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.agentCallExecutor = agentCallExecutor;
  }

  /** 无状态调用：Agent 未注册 404；消息空白或超 32KB 400；超过 60 秒 504。 */
  @PostMapping("/{name}/invoke")
  public ApiResponse<ReplyView> invoke(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    if (!profileRegistry.exists(name)) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
    String message =
        ApiConstraints.requireMessage(request == null ? null : request.message(), "消息");
    String userId = INVOKE_USER_PREFIX + UUID.randomUUID();
    Session session = sessionManager.getOrCreate(ApiConstraints.CHANNEL_WEB, userId, name);
    return ApiResponse.ok(new ReplyView(agentCallExecutor.process(session, message)));
  }
}
