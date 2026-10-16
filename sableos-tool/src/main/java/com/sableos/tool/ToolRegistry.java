package com.sableos.tool;

import com.sableos.core.profile.Profile;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具注册表：三条注册路径（直接实现 / {@code @Tool} 注解 / MCP 适配器）在此统一为 {@link SableTool}， ReAct 循环与 ToolExecutor
 * 由此对来源无感知（技术方案 §6.2/§6.6）。
 *
 * <p>重名拒绝而非静默覆盖——两个来源打架时必须可查（US1.4）。注册表按名查、按 Profile 声明过滤， 不做任何执行与校验（执行归 ToolExecutor，校验归 Sandbox）。
 */
public class ToolRegistry implements ToolCatalog {

  private final Map<String, SableTool> tools = new ConcurrentHashMap<>();

  /** 直接实现来源；重名 → {@link IllegalStateException} 点名，不覆盖。 */
  public void register(SableTool tool) {
    SableTool previous = tools.putIfAbsent(tool.getName(), tool);
    if (previous != null) {
      throw new IllegalStateException("工具重名，拒绝注册: " + tool.getName());
    }
  }

  /** {@code @Tool} 注解来源：schema 由 Spring AI 自动生成，逐方法包装为 SableTool 后注册。 */
  public void registerAnnotated(Object bean) {
    for (ToolCallback callback : ToolCallbacks.from(bean)) {
      register(new AnnotatedToolAdapter(callback));
    }
  }

  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  /** 只读快照（契约测试 MethodSource 遍历用）。 */
  public List<SableTool> all() {
    return List.copyOf(tools.values());
  }

  /** 按 Profile 的 tools 字段过滤：结果 = 声明列表中存在于注册表的项，不多不少；未知名跳过；顺序随声明。 */
  public List<SableTool> filterByNames(List<String> names) {
    List<SableTool> filtered = new ArrayList<>();
    for (String name : names) {
      SableTool tool = tools.get(name);
      if (tool != null) {
        filtered.add(tool);
      }
    }
    return filtered;
  }

  @Override
  public List<SableTool> toolsFor(Profile profile) {
    return filterByNames(profile.tools());
  }

  @Override
  public SableTool find(String name) {
    return tools.get(name);
  }
}
