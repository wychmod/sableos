package com.sableos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@code @Tool} 注解管道的机制本体：把 Spring AI 扫描出的 {@link ToolCallback}（schema 已自动生成）包装成 {@link
 * SableTool}——内置工具与业务方方式三共用这条管道（技术方案 §6.5/§6.6）。
 *
 * <p>宪法 III：这里只借 Spring AI 的 schema 生成与方法反射调用；执行的发起方是 ToolExecutor， 不存在框架自动执行路径。异常不捕获——ToolExecutor
 * 统一转失败结果并落审计。
 */
public class AnnotatedToolAdapter implements SableTool {

  private final ToolCallback callback;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ToolCallback 是 Spring AI 提供的无状态调用句柄（接口），构造注入即依赖注入本就必须持有协作者引用，"
              + "并非把可变内部表示暴露出去（本类无返回该字段的 getter，execute 只在方法栈内使用）。"
              + "撤销条件：一旦本类新增暴露 callback 的 getter，或对其做原地可变配置，须重新评估并移除本抑制。")
  public AnnotatedToolAdapter(ToolCallback callback) {
    this.callback = callback;
  }

  @Override
  public String getName() {
    return callback.getToolDefinition().name();
  }

  @Override
  public String getDescription() {
    return callback.getToolDefinition().description();
  }

  @Override
  public String getInputSchema() {
    return callback.getToolDefinition().inputSchema();
  }

  @Override
  public ToolResult execute(JsonNode input) {
    String output = callback.call(input == null ? "{}" : input.toString());
    return ToolResult.success(output);
  }
}
