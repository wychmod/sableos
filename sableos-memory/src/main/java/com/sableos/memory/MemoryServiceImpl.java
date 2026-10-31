package com.sableos.memory;

import com.sableos.core.agent.ToolExecutionContext;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.session.Session;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link MemoryService} 的默认实现，同时实现 {@link MemoryProvider} 供 {@code PromptBuilder} 消费。
 *
 * <p>所有长期记忆按 Agent 隔离（第 30 节）：同一个 Agent 下的不同会话共享长期记忆，不同 Agent 之间互不干扰。隔离键由 {@link
 * ToolExecutionContext#agentName()} 承载——工具执行路径由 {@code ToolExecutor} 置入；本类负责把**读路径**也带上同一上下文，
 * 否则「工具写入 Agent A 的记忆、提示词却读到全局路径」这种半隔离最难查。
 */
public class MemoryServiceImpl implements MemoryService, MemoryProvider {

  private final LongTermMemoryStore store;

  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = Objects.requireNonNull(store, "store 不能为空");
  }

  @Override
  public String buildContext(Session session) {
    Objects.requireNonNull(session, "session 不能为空");
    return withAgent(session.profileName(), () -> store.load(session.profileName()));
  }

  @Override
  public String memorySection(Profile profile) {
    Objects.requireNonNull(profile, "profile 不能为空");
    return withAgent(profile.name(), () -> store.load(profile.name()));
  }

  @Override
  public void remember(String content, MemoryScope scope, Profile profile) {
    Objects.requireNonNull(content, "content 不能为空");
    Objects.requireNonNull(scope, "scope 不能为空");
    Objects.requireNonNull(profile, "profile 不能为空");
    withAgent(
        profile.name(),
        () -> {
          store.append(content, scope, profile.name());
          return null;
        });
  }

  @Override
  public List<String> recall(String keyword, Profile profile) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profile, "profile 不能为空");
    return withAgent(profile.name(), () -> store.recallByKeyword(keyword, profile.name()));
  }

  /** 在「当前 Agent」上下文里委托 store，结束后**复原**原值（不是清空）：若外层已置入别的 Agent（例如工具执行路径本身）， 清空会把外层上下文一起抹掉。 */
  private static <T> T withAgent(String agentName, Supplier<T> action) {
    String previous = ToolExecutionContext.agentName();
    ToolExecutionContext.setAgentName(agentName);
    try {
      return action.get();
    } finally {
      ToolExecutionContext.setAgentName(previous);
    }
  }
}
