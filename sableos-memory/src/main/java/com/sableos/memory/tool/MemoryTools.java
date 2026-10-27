package com.sableos.memory.tool;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.memory.MemoryScope;
import com.sableos.memory.MemoryService;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 长期记忆内置工具：把 {@code save_memory} / {@code recall_memory} 暴露给 Agent。
 *
 * <p>工具层只认 {@link MemoryService}，对具体后端无感知。当前 Profile 取自 {@link ProfileContext}。
 */
public class MemoryTools {

  private final MemoryService memoryService;

  public MemoryTools(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
  public String saveMemory(
      @ToolParam(required = true, description = "要记住的内容") String content,
      @ToolParam(description = "core 或 archival，不确定就填 archival") String scope) {
    Profile profile = currentProfile();
    MemoryScope memoryScope = parseScope(scope);
    memoryService.remember(content, memoryScope, profile);
    return "已记住";
  }

  @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
  public String recallMemory(@ToolParam(required = true, description = "检索关键词") String keyword) {
    Profile profile = currentProfile();
    List<String> hits = memoryService.recall(keyword, profile);
    return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
  }

  private Profile currentProfile() {
    Profile profile = ProfileContext.current();
    if (profile == null) {
      throw new IllegalStateException("当前无 Agent 上下文，无法操作长期记忆");
    }
    return profile;
  }

  private MemoryScope parseScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return MemoryScope.ARCHIVAL;
    }
    try {
      return MemoryScope.valueOf(scope.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("scope 必须是 core 或 archival，收到: " + scope, e);
    }
  }
}
