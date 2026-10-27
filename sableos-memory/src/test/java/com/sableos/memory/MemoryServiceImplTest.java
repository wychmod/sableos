package com.sableos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.profile.Profile;
import com.sableos.core.session.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryServiceImpl 测试：buildContext 返回长期记忆且核心记忆完整。 */
class MemoryServiceImplTest {

  private final Profile profile =
      new Profile(
          "p1", null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null);
  private final Session session =
      Session.rehydrate(
          "s1",
          "p1",
          "cli",
          "u1",
          "active",
          java.time.Instant.now(),
          java.time.Instant.now(),
          List.of());

  private LongTermMemoryStore store;
  private MemoryServiceImpl service;

  @BeforeEach
  void setUp() {
    store = mock(LongTermMemoryStore.class);
    service = new MemoryServiceImpl(store);
  }

  @Test
  @DisplayName("buildContext 按 session.profileName 加载")
  void buildContextLoadsByProfileName() {
    when(store.load("p1")).thenReturn("长期记忆内容");

    String context = service.buildContext(session);

    assertEquals("长期记忆内容", context);
  }

  @Test
  @DisplayName("memorySection 按 profile.name 加载")
  void memorySectionLoadsByProfileName() {
    when(store.load("p1")).thenReturn("profile 记忆");

    String section = service.memorySection(profile);

    assertEquals("profile 记忆", section);
  }

  @Test
  @DisplayName("remember 转发到 store.append")
  void rememberForwardsToStore() {
    service.remember("事实", MemoryScope.CORE, profile);

    verify(store).append("事实", MemoryScope.CORE, "p1");
  }

  @Test
  @DisplayName("recall 转发到 store.recallByKeyword")
  void recallForwardsToStore() {
    when(store.recallByKeyword("关键词", "p1")).thenReturn(List.of("命中"));

    List<String> result = service.recall("关键词", profile);

    assertEquals(List.of("命中"), result);
  }
}
