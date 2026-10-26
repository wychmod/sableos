package com.sableos.memory;

import com.sableos.core.profile.Profile;
import com.sableos.core.session.Session;
import java.util.List;

/**
 * 长期记忆统一门面：对上提供组装上下文、写入、检索三个能力。
 *
 * <p>实现类同时实现 {@link com.sableos.core.memory.MemoryProvider}，把 {@code memorySection(Profile)} 桥接到
 * 同一套长期记忆数据。
 */
public interface MemoryService {

  /** 为当前会话构建长期记忆段文本；无记忆返回空字符串。 */
  String buildContext(Session session);

  /** 记住一条内容，按 scope 路由到核心区或归档区。 */
  void remember(String content, MemoryScope scope, Profile profile);

  /** 按关键词检索归档记忆，返回匹配条目列表。 */
  List<String> recall(String keyword, Profile profile);
}
