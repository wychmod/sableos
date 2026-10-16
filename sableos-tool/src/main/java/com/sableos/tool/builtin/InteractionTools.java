package com.sableos.tool.builtin;

import com.sableos.tool.interaction.UserInteraction;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置交互工具：ask_user（human-in-the-loop 的落点）。
 *
 * <p>工具自身只把问题递给 {@link UserInteraction}、把回答带回来——不吞异常、不设校验位（提问不涉外，无需过 Sandbox）。渠道无法交互时由交互抽象大声失败
 * （{@link com.sableos.tool.interaction.UnsupportedUserInteraction}），绝不静默卡住（FR-009）。
 */
public class InteractionTools {

  private final UserInteraction interaction;

  public InteractionTools(UserInteraction interaction) {
    this.interaction = interaction;
  }

  @Tool(name = "ask_user", description = "向当前用户提出一个问题并等待其回答（用于澄清需求或确认危险操作）")
  public String askUser(@ToolParam(description = "要问用户的问题") String question) {
    return interaction.ask(question);
  }
}
