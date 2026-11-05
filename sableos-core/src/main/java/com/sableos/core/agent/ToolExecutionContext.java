package com.sableos.core.agent;

/**
 * 当前工具调用的执行上下文（第 30 节）：按线程携带「这一步是替哪个 Agent 跑的」。
 *
 * <p><b>为什么需要它</b>：{@link com.sableos.core.tool.SableTool#execute} 的签名只有入参 JSON，没有执行上下文；而 {@code
 * save_memory} / {@code recall_memory} 要落到「当前 Agent 自己的」{@code MEMORY.md}，就必须知道 Agent 名。
 *
 * <p><b>为什么用 {@code ThreadLocal}</b>：核心阶段是同步阻塞模型，一次 ReAct 循环整体跑在一条（虚拟）线程上，{@link
 * com.sableos.core.tool.ToolExecutor} 在执行工具前置入、执行后清除，天然按 Agent 隔离。刻意**不用** {@code
 * InheritableThreadLocal}（AGENTS.md §5.2）：跨线程需显式传递，隐式继承会让「父线程的 Agent 串到子线程」变成沉默故障。
 *
 * <p>若将来引入流式 / 异步（扩展阶段），此处的线程绑定语义需连同上下文透传一起重新审视。
 */
public final class ToolExecutionContext {

  private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

  private ToolExecutionContext() {}

  /** 置入当前 Agent 名；传 null 等价于清空。 */
  public static void setAgentName(String name) {
    if (name == null) {
      AGENT.remove();
    } else {
      AGENT.set(name);
    }
  }

  /** 当前 Agent 名；无上下文（非 Agent 触发的直接调用、单测）时为 {@code null}。 */
  public static String agentName() {
    return AGENT.get();
  }

  /** 清除（执行工具后调用，避免线程复用时串到下一个 Agent）。 */
  public static void clear() {
    AGENT.remove();
  }
}
