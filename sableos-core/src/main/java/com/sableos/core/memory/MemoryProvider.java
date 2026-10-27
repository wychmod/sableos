package com.sableos.core.memory;

import com.sableos.core.profile.Profile;

/**
 * 长期记忆注入的前向契约（FR-006「无则跳过该段」）：返回记忆段文本，无记忆返回 null。
 *
 * <p>真实现为 22 节 {@code sableos-memory} 的 {@code MemoryServiceImpl}（由 {@code MemoryConfiguration}
 * 装配）；{@code PromptBuilder} 在 null/空白时整段省略。全容器唯一 Provider 定义，装配层不得再定义第二个（按名解析会静默改变注入来源）。
 */
public interface MemoryProvider {

  /** 本次 Profile 的长期记忆段文本；无记忆返回 null（或空白）。 */
  String memorySection(Profile profile);
}
