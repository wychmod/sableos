package com.sableos.core.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** core 沙箱白名单契约的守点：领域异常不得继承 {@code IllegalArgumentException}（见 specs/008 research D-05）。 */
class SandboxWhitelistContractTest {

  @Test
  @DisplayName("三个领域异常都不继承 IllegalArgumentException_避免 400 原样回显用户输入")
  void domainExceptions_doNotExtendIllegalArgumentException() {
    assertThat(IllegalArgumentException.class.isAssignableFrom(WhitelistValidationException.class))
        .isFalse();
    assertThat(
            IllegalArgumentException.class.isAssignableFrom(WhitelistEntryNotFoundException.class))
        .isFalse();
    assertThat(IllegalArgumentException.class.isAssignableFrom(WhitelistDuplicateException.class))
        .isFalse();
  }

  @Test
  @DisplayName("白名单类型只有三类_不含 SMTP")
  void whitelistType_hasExactlyThreeValues() {
    assertThat(WhitelistType.values())
        .containsExactly(
            WhitelistType.FILE_PATH, WhitelistType.SHELL_COMMAND, WhitelistType.HTTP_DOMAIN);
  }

  @Test
  @DisplayName("条目不存在的异常消息只含 id")
  void notFoundException_carriesOnlyId() {
    assertThat(new WhitelistEntryNotFoundException(42L)).hasMessage("白名单条目不存在: 42");
  }

  @Test
  @DisplayName("重复异常消息用归一后的值")
  void duplicateException_carriesTypeAndNormalizedValue() {
    assertThat(new WhitelistDuplicateException(WhitelistType.HTTP_DOMAIN, "api.example.com"))
        .hasMessage("白名单条目已存在: HTTP_DOMAIN api.example.com");
  }
}
