package com.sableos.core.profile;

/**
 * 线程级「当前 Agent 运行配置」上下文（FR-012）：供工具执行时按当前 Agent 取值。
 *
 * <p>底层 {@code ThreadLocal}（JDK 21 下 {@code ScopedValue} 仍是 preview、开 {@code --enable-preview}
 * 会挂构建）； <b>不继承</b>（非 {@code InheritableThreadLocal}，AGENTS.md §5.2 禁）——跨线程需显式传递。
 *
 * <p>有效期为一次处理：{@code AgentService} 入口 {@code set}、{@code finally} {@code
 * clear}（异常路径也清）。每请求独占一虚拟线程，天然不串台。
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  /** 放入当前处理所属的 Agent 运行配置。 */
  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 当前 Agent 运行配置；无上下文返回 null。 */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 清理当前线程的上下文（必须与 {@link #set} 配对，含异常路径）。 */
  public static void clear() {
    CURRENT.remove();
  }
}
