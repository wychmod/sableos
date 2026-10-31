package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** mock provider 的脚本行为验收：第一轮请求 save_memory，第二轮见工具结果即收尾。 */
class MockChatModelTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MockChatModel model = new MockChatModel();

  @Test
  @DisplayName("第一轮_请求一次save_memory并带出用户事实")
  void firstCall_requestsSaveMemoryWithFact() throws Exception {
    ChatResponse response = model.call(new Prompt("User: 记住：我喜欢喝美式咖啡"));

    AssistantMessage message = response.getResult().getOutput();
    assertThat(message.getText()).isEmpty();
    assertThat(message.getToolCalls()).hasSize(1);
    AssistantMessage.ToolCall call = message.getToolCalls().get(0);
    assertThat(call.name()).isEqualTo(MockChatModel.TOOL_NAME);

    JsonNode arguments = MAPPER.readTree(call.arguments());
    assertThat(arguments.get("content").asText()).isEqualTo("我喜欢喝美式咖啡");
    assertThat(arguments.get("scope").asText()).isEqualTo("archival");
  }

  @Test
  @DisplayName("第二轮_看到工具结果后直接返回最终答复")
  void secondCall_withToolResult_returnsFinalReply() {
    ChatResponse response = model.call(new Prompt("User: 记住：我喜欢喝美式咖啡\nTool[save_memory]: 已记住"));

    AssistantMessage message = response.getResult().getOutput();
    assertThat(message.getToolCalls()).isEmpty();
    assertThat(message.getText()).isEqualTo(MockChatModel.FINAL_REPLY);
  }

  @Test
  @DisplayName("空白上下文_回落固定事实而不是报错")
  void blankPrompt_fallsBackToDefaultFact() throws Exception {
    ChatResponse response = model.call(new Prompt(""));

    AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
    JsonNode arguments = MAPPER.readTree(call.arguments());
    assertThat(arguments.get("content").asText()).isEqualTo(MockChatModel.DEFAULT_FACT);
  }

  @Test
  @DisplayName("中文与ASCII两种记住前缀都能剥掉")
  void extractFact_stripsBothRememberPrefixes() {
    assertThat(MockChatModel.extractFact("User: 记住：我在北京")).isEqualTo("我在北京");
    assertThat(MockChatModel.extractFact("User: 记住: 我在北京")).isEqualTo("我在北京");
    assertThat(MockChatModel.extractFact("User: 今天天气如何")).isEqualTo("今天天气如何");
  }
}
