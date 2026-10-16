package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.tool.interaction.ConsoleUserInteraction;
import com.sableos.tool.interaction.UnsupportedUserInteraction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** InteractionTools（ask_user）的回归测试：终端档拿到回答；无人值守档大声失败。 */
class InteractionToolsTest {

  @Test
  @DisplayName("ask_user_终端档应把用户回答带回来")
  void askUser_returnsConsoleAnswer() {
    ByteArrayInputStream in = new ByteArrayInputStream("确认删除\n".getBytes(StandardCharsets.UTF_8));
    PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    InteractionTools tools =
        new InteractionTools(new ConsoleUserInteraction(in, out, StandardCharsets.UTF_8));

    String answer = tools.askUser("要删除这个文件吗？");

    assertEquals("确认删除", answer);
  }

  @Test
  @DisplayName("ask_user_无人值守渠道应明确报不支持（绝不静默卡住）")
  void askUser_unattendedChannelFailsLoud() {
    InteractionTools tools = new InteractionTools(new UnsupportedUserInteraction());

    UnsupportedOperationException ex =
        assertThrows(UnsupportedOperationException.class, () -> tools.askUser("在吗？"));

    assertTrue(ex.getMessage().contains("不支持"), ex.getMessage());
  }
}
