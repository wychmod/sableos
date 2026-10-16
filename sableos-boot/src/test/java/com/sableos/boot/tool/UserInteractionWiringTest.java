package com.sableos.boot.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.sableos.tool.interaction.ConsoleUserInteraction;
import com.sableos.tool.interaction.UnsupportedUserInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** serve/gateway（Servlet web）vs chat（WebApplicationType.NONE）的 ask_user 交互装配选择。 */
class UserInteractionWiringTest {

  @Test
  @DisplayName("非 Servlet web（chat / WebApplicationType.NONE）→ ConsoleUserInteraction")
  void nonWebUsesConsole() {
    assertInstanceOf(ConsoleUserInteraction.class, ToolConfiguration.resolveUserInteraction(false));
  }

  @Test
  @DisplayName("Servlet web（serve/gateway）→ UnsupportedUserInteraction")
  void servletWebUsesUnsupported() {
    assertInstanceOf(
        UnsupportedUserInteraction.class, ToolConfiguration.resolveUserInteraction(true));
  }
}
