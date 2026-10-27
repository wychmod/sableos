package com.sableos.memory;

import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.session.Session;
import java.util.List;
import java.util.Objects;

/**
 * {@link MemoryService} 的默认实现，同时实现 {@link MemoryProvider} 供 {@code PromptBuilder} 消费。
 *
 * <p>所有长期记忆按 Profile 隔离：同一个 Profile 下的不同会话共享长期记忆，不同 Profile 之间互不干扰。
 */
public class MemoryServiceImpl implements MemoryService, MemoryProvider {

  private final LongTermMemoryStore store;

  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = Objects.requireNonNull(store, "store 不能为空");
  }

  @Override
  public String buildContext(Session session) {
    Objects.requireNonNull(session, "session 不能为空");
    return store.load(session.profileName());
  }

  @Override
  public String memorySection(Profile profile) {
    Objects.requireNonNull(profile, "profile 不能为空");
    return store.load(profile.name());
  }

  @Override
  public void remember(String content, MemoryScope scope, Profile profile) {
    Objects.requireNonNull(content, "content 不能为空");
    Objects.requireNonNull(scope, "scope 不能为空");
    Objects.requireNonNull(profile, "profile 不能为空");
    store.append(content, scope, profile.name());
  }

  @Override
  public List<String> recall(String keyword, Profile profile) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profile, "profile 不能为空");
    return store.recallByKeyword(keyword, profile.name());
  }
}
