package com.sableos.memory.store;

import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;

/**
 * 契约测试用的内存替身：行为满足四条长期记忆契约，用作 Markdown 之外的契约基准。
 *
 * <p><b>不代表真实 Mem0 实现</b>：真实 {@code Mem0MemoryStore} 当前不保证 recall 只搜归档、load 不截断
 * （见其类注释）。本替身只证明“契约本身可实现、断言有效”，不证明 Mem0 档已满足契约。
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
