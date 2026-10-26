package com.sableos.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
