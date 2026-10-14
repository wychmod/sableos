package com.sableos.core.memory;

import com.sableos.core.profile.Profile;

/**
 * 长期记忆注入的前向契约（FR-006「无则跳过该段」）：返回记忆段文本，无记忆返回 null。
 *
 * <p>真实现（{@code MemoryService} / {@code LongTermMemory}）归 22 节 {@code sableos-memory}；本节 boot 装配返回
 * null 的 lambda，{@code PromptBuilder} 在 null/空白时整段省略。
 */
public interface MemoryProvider {

  /** 本次 Profile 的长期记忆段文本；无记忆返回 null（或空白）。 */
  String memorySection(Profile profile);
}
