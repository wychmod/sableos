package com.sableos.core.provider;

import com.sableos.core.profile.Profile;

/**
 * 模型调用的统一入口（核心能力一；契约见 specs/001-lesson16-provider/contracts/provider-service.md）。
 *
 * <p>薄封装一次调用：按 {@code profile.provider()} 显式路由到对应模型 → 翻译工具说明（只翻译不执行）→ 调用一次 → 成败均落 {@code llm_calls}
 * 审计 → 结果原样返回。
 *
 * <p>provider 未登记时抛点名异常（ProviderNotFoundException，位于 provider 实现模块），不降级、不换家；
 * 供应商调用失败时审计后原异常继续上抛，由调用方决定如何应对。
 */
public interface ProviderService {

  /**
   * 发起一次模型调用。
   *
   * @param sessionId 会话标识（审计落 llm_calls.session_id）
   * @param profile 本次调用的 Agent 运行配置（provider.name 决定路由落点）
   * @param request 用户输入 + 本次可用工具说明
   * @return 模型文本与/或工具调用请求（工具请求原样交回，本层不执行）
   */
  ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}
