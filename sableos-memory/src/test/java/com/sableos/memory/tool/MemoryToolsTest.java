package com.sableos.memory.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.memory.MemoryScope;
import com.sableos.memory.MemoryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryTools 测试：scope 缺省写归档；关键词未命中返回"没有找到相关记忆"而不抛异常。 */
class MemoryToolsTest {

  private final Profile profile =
      new Profile(
          "test", null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null);
  private MemoryService memoryService;
  private MemoryTools memoryTools;

  @BeforeEach
  void setUp() {
    memoryService = mock(MemoryService.class);
    memoryTools = new MemoryTools(memoryService);
    ProfileContext.set(profile);
  }

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("save_memory 缺省 scope 写归档")
  void saveMemoryDefaultsToArchival() {
    memoryTools.saveMemory("要记住", null);

    verify(memoryService).remember("要记住", MemoryScope.ARCHIVAL, profile);
  }

  @Test
  @DisplayName("save_memory 显式 core 写核心")
  void saveMemoryCoreWritesCore() {
    memoryTools.saveMemory("核心内容", "core");

    verify(memoryService).remember("核心内容", MemoryScope.CORE, profile);
  }

  @Test
  @DisplayName("save_memory 非法 scope 抛清晰异常")
  void saveMemoryInvalidScopeThrowsClearly() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> memoryTools.saveMemory("x", "unknown"));
    assertTrue(ex.getMessage().contains("scope 必须是 core 或 archival"));
  }

  @Test
  @DisplayName("recall_memory 未命中返回中文提示")
  void recallMemoryNoHitReturnsChineseHint() {
    when(memoryService.recall(eq("不存在"), any(Profile.class))).thenReturn(List.of());

    String result = memoryTools.recallMemory("不存在");

    assertEquals("没有找到相关记忆", result);
  }

  @Test
  @DisplayName("recall_memory 命中返回拼接内容")
  void recallMemoryReturnsJoinedHits() {
    when(memoryService.recall(eq("key"), any(Profile.class))).thenReturn(List.of("a", "b"));

    String result = memoryTools.recallMemory("key");

    assertEquals("a\nb", result);
  }

  @Test
  @DisplayName("无 Agent 上下文时 save_memory 抛异常")
  void noProfileContextThrows() {
    ProfileContext.clear();
    assertThrows(IllegalStateException.class, () -> memoryTools.saveMemory("x", null));
  }
}
