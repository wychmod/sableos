package com.sableos.web.api;

import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.session.SessionMessage;
import com.sableos.web.api.dto.CreateSessionRequest;
import com.sableos.web.api.dto.MessageView;
import com.sableos.web.api.dto.ReplyView;
import com.sableos.web.api.dto.SendMessageRequest;
import com.sableos.web.api.dto.SessionSummaryView;
import com.sableos.web.api.dto.SessionView;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点（26 节，契约见 {@code specs/010-lesson26-web-service/contracts/rest-api.md}）。
 *
 * <p>薄门面：只做参数校验、响应包装与错误抛出；真正的处理全部委托给既有 {@link AgentService}（经 {@link AgentCallExecutor}），与 CLI
 * 共用同一条处理链。会话身份只经 {@link SessionManager} 生成，Controller 不拼 {@code session_id}。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class SessionApiController {

  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentCallExecutor agentCallExecutor;

  public SessionApiController(
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentCallExecutor agentCallExecutor) {
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.agentCallExecutor = agentCallExecutor;
  }

  /** 建会话：同三元组幂等，返回既有会话与历史。 */
  @PostMapping
  public ApiResponse<SessionView> create(@RequestBody CreateSessionRequest request) {
    if (request == null || request.profile() == null || request.profile().isBlank()) {
      throw new IllegalArgumentException("profile 不能为空");
    }
    String profile = request.profile();
    if (!profileRegistry.exists(profile)) {
      throw new ResourceNotFoundException("Agent 不存在: " + profile);
    }
    String userId =
        (request.userId() == null || request.userId().isBlank())
            ? ApiConstraints.DEFAULT_USER
            : request.userId();
    return ApiResponse.ok(
        toView(sessionManager.getOrCreate(ApiConstraints.CHANNEL_WEB, userId, profile)));
  }

  /** 发消息：校验 32KB 上限与会话存在性，处理权交给与 CLI 相同的入口。 */
  @PostMapping("/{id}/messages")
  public ApiResponse<ReplyView> send(
      @PathVariable String id, @RequestBody SendMessageRequest request) {
    String content =
        ApiConstraints.requireMessage(request == null ? null : request.content(), "消息");
    Session session =
        sessionManager.get(id).orElseThrow(() -> new ResourceNotFoundException("会话不存在: " + id));
    return ApiResponse.ok(new ReplyView(agentCallExecutor.process(session, content)));
  }

  /** 列出会话（27 节）：按最后活跃时间倒序，默认最近 100 条摘要；{@code ?status=active} 可选过滤。 */
  @GetMapping
  public ApiResponse<List<SessionSummaryView>> list(
      @RequestParam(name = "status", required = false) String status) {
    String wanted = (status == null || status.isBlank()) ? null : status.trim();
    return ApiResponse.ok(
        sessionManager.listRecent(ApiConstraints.MAX_SESSION_LIST).stream()
            .filter(session -> wanted == null || wanted.equals(session.status()))
            .map(SessionApiController::toSummary)
            .toList());
  }

  /** 查历史：最近最多 100 条，时间正序；归档会话同样可查。 */
  @GetMapping("/{id}")
  public ApiResponse<SessionView> get(@PathVariable String id) {
    Session session =
        sessionManager.get(id).orElseThrow(() -> new ResourceNotFoundException("会话不存在: " + id));
    return ApiResponse.ok(toView(session));
  }

  /** 归档：软归档，行保留；不存在 404，重复归档幂等成功。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> archive(@PathVariable String id) {
    if (!sessionManager.archive(id)) {
      throw new ResourceNotFoundException("会话不存在: " + id);
    }
    return ApiResponse.ok(null);
  }

  private static SessionView toView(Session session) {
    List<SessionMessage> messages = session.messages();
    int from = Math.max(0, messages.size() - ApiConstraints.MAX_HISTORY_MESSAGES);
    List<MessageView> views =
        messages.subList(from, messages.size()).stream().map(SessionApiController::toView).toList();
    return new SessionView(
        session.id(),
        session.profileName(),
        session.channel(),
        session.userId(),
        session.status(),
        session.createdAt().toString(),
        session.lastActiveAt().toString(),
        views);
  }

  private static SessionSummaryView toSummary(Session session) {
    return new SessionSummaryView(
        session.id(),
        session.profileName(),
        session.channel(),
        session.userId(),
        session.status(),
        session.createdAt().toString(),
        session.lastActiveAt().toString(),
        session.messages().size());
  }

  private static MessageView toView(SessionMessage message) {
    return new MessageView(
        message.role().name(), message.content(), message.toolName(), message.success());
  }
}
