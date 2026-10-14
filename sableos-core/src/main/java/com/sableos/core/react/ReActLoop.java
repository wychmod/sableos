package com.sableos.core.react;

import com.sableos.core.profile.Profile;
import com.sableos.core.prompt.PromptBuilder;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.session.Session;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolResult;

/**
 * 自实现的 ReAct 循环引擎（宪法原则 II）：反复「组装上下文 → 调 Provider → 判断有无工具调用」，有则逐个执行并回填，无则以该轮
 * 输出收尾；转满最大轮数强制收尾并返回固定终止文案。
 *
 * <p>迭代上限取 {@code profile.settings().maxIterations()}（默认 10）；工具调用的执行权唯一在 {@link ToolExecutor}；本循环不做
 * 上下文压缩（仅靠 {@code PromptBuilder} 的「最近 N 条」截断）、不做并行工具调用、不做流式输出。
 *
 * <p>循环本身不开链路作用域、无 {@code synchronized}（阻塞调用不得进锁，AGENTS.md §5.2）；traceId 由 {@code AgentService}
 * 或更外层入口统一开。
 */
public final class ReActLoop {

  /** 转满最大轮数后的固定终止文案（FR-004；不拼接最后一条模型输出，research D-14）。 */
  static final String MAX_ITERATIONS_REPLY = "达到最大轮数，已停止";

  private final ProviderService providerService;
  private final PromptBuilder promptBuilder;
  private final ToolExecutor toolExecutor;

  public ReActLoop(
      ProviderService providerService, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
    this.providerService = providerService;
    this.promptBuilder = promptBuilder;
    this.toolExecutor = toolExecutor;
  }

  /**
   * 跑一轮完整的处理。
   *
   * @param session 本次会话（每轮响应与工具结果就地累积进它）
   * @param userMessage 本条用户消息（进入循环前先 append 进会话）
   * @param profile 本次处理的 Agent 运行配置（提供迭代上限）
   * @return 最终答复文本，或转满上限的固定终止文案
   */
  public String run(Session session, String userMessage, Profile profile) {
    session.appendUserMessage(userMessage);
    int maxIterations = profile.settings().maxIterations();
    for (int i = 0; i < maxIterations; i++) {
      ProviderRequest request = promptBuilder.build(session, profile);
      ProviderResponse response = providerService.chat(session.id(), profile, request);
      session.appendAssistantMessage(response);
      if (response.toolCalls().isEmpty()) {
        return (response.content() == null) ? "" : response.content();
      }
      for (ToolCallRequest call : response.toolCalls()) {
        ToolResult result = toolExecutor.execute(session.id(), call);
        session.appendToolResult(call.name(), result);
      }
    }
    return MAX_ITERATIONS_REPLY;
  }
}
