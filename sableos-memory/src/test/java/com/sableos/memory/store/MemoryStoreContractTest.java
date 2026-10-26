package com.sableos.memory.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 长期记忆后端契约测试：同一套断言对 Markdown 档与内存替身（代表 Mem0 档语义）统一跑。
 *
 * <p>SQLite 档由于需要真实 JPA 仓库，其等价契约由 {@link SqliteMemoryStoreTest} 单独覆盖。
 * 任何一档后端破了四条行为契约（不缓存、核心不截断、scope 显式路由、recall 只搜归档），对应测试立即红。
 */
class MemoryStoreContractTest {

  static Stream<Arguments> allStores() {
    return Stream.of(
        Arguments.of(
            new MarkdownMemoryStore(
                Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "sableos-memory-contract-" + System.nanoTime())),
            "Markdown"),
        Arguments.of(new InMemoryMemoryStore(), "InMemory"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("allStores")
  @DisplayName("写入后立刻可读，不允许有缓存")
  void writeIsImmediatelyVisible(LongTermMemoryStore memory, String label) {
    memory.append("刚记的事", MemoryScope.ARCHIVAL, "default");

    assertTrue(memory.load("default").contains("刚记的事"));
    assertFalse(memory.recallByKeyword("刚记的事", "default").isEmpty());
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("allStores")
  @DisplayName("截断只裁归档区，核心记忆一字不能少")
  void truncationOnlyAffectsArchive(LongTermMemoryStore memory, String label) {
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE, "default");
    for (int i = 0; i < 500; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL, "default");
    }

    String loaded = memory.load("default");

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"));
    assertFalse(loaded.contains("归档流水 0"));
    assertTrue(loaded.contains("归档流水 499"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("allStores")
  @DisplayName("scope 路由到正确分区")
  void scopeRoutesToCorrectPartition(LongTermMemoryStore memory, String label) {
    memory.append("核心事实", MemoryScope.CORE, "default");
    memory.append("归档事实", MemoryScope.ARCHIVAL, "default");

    assertTrue(memory.load("default").contains("核心事实"));
    assertTrue(memory.load("default").contains("归档事实"));
    assertTrue(memory.recallByKeyword("核心事实", "default").isEmpty());
    assertFalse(memory.recallByKeyword("归档事实", "default").isEmpty());
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("allStores")
  @DisplayName("recall 只搜归档区")
  void recallSearchesOnlyArchive(LongTermMemoryStore memory, String label) {
    memory.append("只在核心", MemoryScope.CORE, "default");
    memory.append("只在归档", MemoryScope.ARCHIVAL, "default");

    assertFalse(memory.recallByKeyword("只在归档", "default").isEmpty());
    assertTrue(memory.recallByKeyword("只在核心", "default").isEmpty());
  }
}
