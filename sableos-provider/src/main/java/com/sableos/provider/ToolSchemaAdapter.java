package com.sableos.provider;

import com.sableos.core.tool.SableTool;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具说明翻译器：把 {@link SableTool} 转成 Spring AI 的 {@link ToolCallback}——只翻译，不执行。
 *
 * <p>产物的 {@code call(...)} 恒抛 {@link IllegalStateException}：即使本层防御失效被误执行， 也会立刻显性失败而非静默跑工具（工具执行权归上层
 * ToolExecutor，宪法原则 III）。
 */
public class ToolSchemaAdapter {

  /**
   * 翻译工具列表为 Spring AI 回调列表（name / description / inputSchema 逐字保真，不去重不排序不改名）。
   *
   * @param tools 可为 null 或空 → 返回空表
   */
  public List<ToolCallback> toSpringAiTools(List<SableTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    List<ToolCallback> callbacks = new ArrayList<>(tools.size());
    for (SableTool tool : tools) {
      callbacks.add(new TranslatingToolCallback(tool));
    }
    return List.copyOf(callbacks);
  }

  /** 只携带工具定义的翻译载体：对外提供 ToolDefinition，执行一律拒绝。 */
  private static final class TranslatingToolCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;

    private TranslatingToolCallback(SableTool tool) {
      this.toolDefinition =
          ToolDefinition.builder()
              .name(tool.getName())
              .description(tool.getDescription())
              .inputSchema(tool.getInputSchema())
              .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
      throw new IllegalStateException("Provider 只翻译工具、不执行工具");
    }
  }
}
