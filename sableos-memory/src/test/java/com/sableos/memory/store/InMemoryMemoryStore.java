package com.sableos.memory.store;

import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;

/**
 * 契约测试用的内存替身：行为满足四条长期记忆契约，代表 Mem0 档的抽象语义。
 *
 * <p>核心区完整保留；归档区只保留最近 10 条；检索只搜归档区且不区分大小写。
 */
class InMemoryMemoryStore implements LongTermMemoryStore {

  private static final int MAX_ARCHIVE = 10;

  private final List<Entry> core = new ArrayList<>();
  private final List<Entry> archive = new ArrayList<>();

  @Override
  public void append(String content, MemoryScope scope, String profileName) {
    List<Entry> target = scope == MemoryScope.CORE ? core : archive;
    target.add(new Entry(content));
    if (scope == MemoryScope.ARCHIVAL && archive.size() > MAX_ARCHIVE) {
      archive.removeFirst();
    }
  }

  @Override
  public String load(String profileName) {
    StringBuilder sb = new StringBuilder();
    for (Entry e : core) {
      sb.append("- ").append(e.content).append("\n");
    }
    for (Entry e : archive) {
      sb.append("- ").append(e.content).append("\n");
    }
    return sb.toString().strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword, String profileName) {
    return archive.stream()
        .filter(e -> e.content.toLowerCase().contains(keyword.toLowerCase()))
        .map(e -> "- " + e.content)
        .toList();
  }

  record Entry(String content) {}
}
