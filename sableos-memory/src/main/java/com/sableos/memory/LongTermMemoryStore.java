package com.sableos.memory;

import java.util.List;

/**
 * 长期记忆的可插拔后端接口。
 *
 * <p>所有实现必须共同遵守四条行为契约：①不缓存（每次重新读文件/查库/调 API）；②核心记忆区永不被截断； ③写 core 还是 archival 由调用方显式 scope
 * 指定；④{@code recallByKeyword} 只搜归档区。
 *
 * <p>长期记忆按 {@code profileName} 隔离，不同 Profile 的记忆互不干扰。
 */
public interface LongTermMemoryStore {

  /** 追加一条记忆到指定 Profile 的指定作用域。 */
  void append(String content, MemoryScope scope, String profileName);

  /** 返回指定 Profile 的核心记忆区全量 + 归档记忆区（经截断后）的内容。 */
  String load(String profileName);

  /** 按关键词检索指定 Profile 的归档记忆区。 */
  List<String> recallByKeyword(String keyword, String profileName);
}
