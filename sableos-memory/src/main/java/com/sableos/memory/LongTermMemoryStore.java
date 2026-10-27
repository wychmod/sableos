package com.sableos.memory;

import java.util.List;

/**
 * 长期记忆的可插拔后端接口。
 *
 * <p>除 {@code Mem0MemoryStore} 的已知例外（见下）外，所有实现必须共同遵守四条行为契约：
 *
 * <ol>
 *   <li>不缓存：每次重新读文件 / 查库 / 调 API；
 *   <li>核心记忆区永不被截断，截断只作用于归档区；
 *   <li>写 core 还是 archival 由调用方显式 {@code scope} 指定，系统不猜测；
 *   <li>{@code recallByKeyword} 只搜归档区。
 * </ol>
 *
 * <p><b>Mem0 档已知例外</b>：{@code Mem0MemoryStore} 的 {@code load} 不在客户端做归档截断， {@code recallByKeyword}
 * 只把关键词转发给 Mem0 语义搜索、不按 {@code metadata.scope} 过滤，结果可能包含核心记忆。 因此 {@code load} 的“归档区经截断”与契约四在 Mem0
 * 档尚未兑现（核心区不截断这一点仍然成立）， 切换 Mem0 前须知晓；详见该类 Javadoc。
 *
 * <p>长期记忆按 {@code profileName} 隔离，不同 Profile 的记忆互不干扰。
 */
public interface LongTermMemoryStore {

  /** 追加一条记忆到指定 Profile 的指定作用域。 */
  void append(String content, MemoryScope scope, String profileName);

  /**
   * 返回指定 Profile 的核心记忆区全量 + 归档记忆区（经截断后）的内容。
   *
   * <p>Mem0 档当前不做客户端截断，归档区返回量取决于 Mem0 服务端（见类注释）。
   */
  String load(String profileName);

  /**
   * 按关键词检索指定 Profile 的归档记忆区。
   *
   * <p>匹配规则由后端实现决定（Markdown 为大小写敏感的子串匹配、SQLite 为 LIKE、Mem0 为语义检索）； Mem0 档当前不保证只返回归档记忆（见类注释）。
   */
  List<String> recallByKeyword(String keyword, String profileName);
}
