package com.sableos.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.core.agent.ToolExecutionContext;
import com.sableos.memory.MemoryScope;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Markdown 后端专属测试：字符串截断边界与 header 解析。 */
class MarkdownMemoryStoreTest {

  @TempDir Path tempDir;

  private MarkdownMemoryStore store;

  @BeforeEach
  void setUp() {
    store = new MarkdownMemoryStore(tempDir);
  }

  @Test
  @DisplayName("首次写入自动创建 MEMORY.md 与两分区")
  void firstAppendCreatesFileWithSections() {
    store.append("first", MemoryScope.ARCHIVAL, "p1");

    assertTrue(store.load("p1").contains("first"));
  }

  @Test
  @DisplayName("核心记忆写入核心分区，归档写入归档分区")
  void coreAndArchiveGoToSeparateSections() {
    store.append("core-val", MemoryScope.CORE, "p1");
    store.append("archive-val", MemoryScope.ARCHIVAL, "p1");

    String core = store.load("p1");
    assertTrue(core.contains("core-val"));
    assertTrue(core.contains("archive-val"));
    assertFalse(store.recallByKeyword("archive-val", "p1").isEmpty());
    assertTrue(store.recallByKeyword("core-val", "p1").isEmpty());
  }

  @Test
  @DisplayName("归档区超长后只保留尾部")
  void archiveTruncationKeepsTail() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      String text = "line-" + i + "-" + "x".repeat(20);
      sb.append(text).append("\n");
      store.append(text, MemoryScope.ARCHIVAL, "p1");
    }
    String coreFact = "核心事实必须保留";
    store.append(coreFact, MemoryScope.CORE, "p1");

    String loaded = store.load("p1");

    assertTrue(loaded.contains(coreFact));
    assertFalse(loaded.contains("line-0-"));
    assertTrue(loaded.contains("line-499-"));
  }

  @Test
  @DisplayName("不同 Profile 使用不同文件")
  void profilesAreIsolated() {
    store.append("p1-val", MemoryScope.ARCHIVAL, "p1");
    store.append("p2-val", MemoryScope.ARCHIVAL, "p2");

    assertTrue(store.load("p1").contains("p1-val"));
    assertFalse(store.load("p1").contains("p2-val"));
    assertTrue(store.load("p2").contains("p2-val"));
  }

  @Test
  @DisplayName("空归档 recall 返回空列表")
  void recallOnEmptyArchiveReturnsEmpty() {
    assertEquals(0, store.recallByKeyword("anything", "empty").size());
  }

  // ---------------------------------------------------------------- 第 30 节：per-agent 记忆

  @Test
  @DisplayName("有 Agent 上下文时落 agents/<name>/MEMORY.md")
  void agentContext_writesToAgentDirectory() throws Exception {
    ToolExecutionContext.setAgentName("weather-daily");
    try {
      store.append("天气偏好：怕冷", MemoryScope.CORE, "weather-daily");
    } finally {
      ToolExecutionContext.clear();
    }

    Path agentFile = tempDir.resolve("agents").resolve("weather-daily").resolve("MEMORY.md");
    assertTrue(java.nio.file.Files.isRegularFile(agentFile));
    assertTrue(java.nio.file.Files.readString(agentFile).contains("天气偏好：怕冷"));
    // 全局路径不应出现同名内容
    assertFalse(
        java.nio.file.Files.exists(
            tempDir.resolve("memory").resolve("weather-daily").resolve("MEMORY.md")));
  }

  @Test
  @DisplayName("无 Agent 上下文时回退全局路径，行为与改动前一致")
  void noAgentContext_fallsBackToGlobalPath() {
    store.append("全局记忆", MemoryScope.ARCHIVAL, "p1");

    assertTrue(store.load("p1").contains("全局记忆"));
    assertTrue(
        java.nio.file.Files.isRegularFile(
            tempDir.resolve("memory").resolve("p1").resolve("MEMORY.md")));
    assertFalse(
        java.nio.file.Files.exists(tempDir.resolve("agents").resolve("p1").resolve("MEMORY.md")));
  }

  @Test
  @DisplayName("两个 Agent 的记忆互不可见")
  void twoAgents_areIsolated() {
    ToolExecutionContext.setAgentName("agent-a");
    try {
      store.append("A 的偏好", MemoryScope.CORE, "agent-a");
    } finally {
      ToolExecutionContext.clear();
    }
    ToolExecutionContext.setAgentName("agent-b");
    try {
      store.append("B 的偏好", MemoryScope.CORE, "agent-b");
      assertTrue(store.load("agent-b").contains("B 的偏好"));
      assertFalse(store.load("agent-b").contains("A 的偏好"));
    } finally {
      ToolExecutionContext.clear();
    }
  }

  @Test
  @DisplayName("非法 Agent 名（含路径穿越）回退全局，防目录穿越")
  void illegalAgentName_fallsBackToGlobal() {
    ToolExecutionContext.setAgentName("../evil");
    try {
      store.append("穿越尝试", MemoryScope.ARCHIVAL, "p1");
    } finally {
      ToolExecutionContext.clear();
    }

    assertTrue(store.load("p1").contains("穿越尝试"));
    assertFalse(
        java.nio.file.Files.exists(tempDir.resolve("agents").resolve("..").resolve("evil")));
  }
}
