package com.sableos.core.agent;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import com.sableos.core.trace.TraceContext;

/**
 * 统一处理入口（FR-011）：三种触发源（CLI / Web / 定时）复用同一入口。
 *
 * <p>职责：取当前 Agent 运行配置 → 放入线程级上下文（供工具执行按当前 Agent 取值）→ 跑循环 → 保存会话 → {@code finally} 清理
 * 线程级上下文（异常路径也必须清，SC-005）。
 *
 * <p>同时用 {@link TraceContext} 把 sessionId/profileName/channel 投影进 MDC 业务键：{@code openIfAbsent()}
 * 保证「外层入口已开 链路则复用、否则新开根链路」（research D-06）。两个作用域与 {@code ProfileContext} 收口于同一处，异常也清。
 */
public final class AgentService {

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionStore sessionStore;

  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionStore sessionStore) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionStore = sessionStore;
  }

  /**
   * 处理一条用户消息。
   *
   * @param session 本次会话（{@code profileName()} 决定用哪个 Agent 的运行配置）
   * @param userMessage 本条用户消息
   * @return 最终答复文本（透传 {@link ReActLoop#run}）
   * @throws IllegalArgumentException 会话声明的 Agent 未在注册表登记（点名 profile 名）
   */
  public String process(Session session, String userMessage) {
    Profile profile = profileRegistry.get(session.profileName());
    if (profile == null) {
      throw new IllegalArgumentException("未注册的 Agent：" + session.profileName());
    }

    ProfileContext.set(profile);
    try (TraceContext.Scope ignored = TraceContext.openIfAbsent();
        TraceContext.Scope business =
            TraceContext.attachBusiness(session.id(), profile.name(), session.channel())) {
      String reply = reActLoop.run(session, userMessage, profile);
      sessionStore.save(session);
      return reply;
    } finally {
      ProfileContext.clear();
    }
  }
}
