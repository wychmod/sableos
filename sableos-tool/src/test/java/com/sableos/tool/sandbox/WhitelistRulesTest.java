package com.sableos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 三类值的校验与归一（specs/008 §6.3）：归一幂等、等价写法收敛、非 ASCII 域名拒绝、非法值不留原始输入。 */
class WhitelistRulesTest {

  @Nested
  @DisplayName("FILE_PATH")
  class FilePath {

    @ParameterizedTest
    @ValueSource(
        strings = {
          ".sableos/agents",
          ".sableos/agents/",
          ".sableos//agents",
          ".sableos/./agents",
          ".sableos/a/../agents",
          "  .sableos/agents  "
        })
    @DisplayName("五种等价写法归一到同一形态")
    void equivalentForms_converge(String input) {
      assertThat(WhitelistRules.normalize(WhitelistType.FILE_PATH, input))
          .isEqualTo(".sableos/agents");
    }

    @Test
    @DisplayName("归一幂等")
    void normalize_isIdempotent() {
      String once = WhitelistRules.normalize(WhitelistType.FILE_PATH, ".sableos//a/../agents/");
      String twice = WhitelistRules.normalize(WhitelistType.FILE_PATH, once);
      assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("入库形态分隔符统一为正斜杠")
    void separator_isNormalizedToForwardSlash() {
      assertThat(WhitelistRules.normalize(WhitelistType.FILE_PATH, "a\\b")).doesNotContain("\\");
    }

    @Test
    @DisplayName("绝对路径保留绝对形式_不转相对也不改写盘符大小写")
    void absolutePath_isPreserved() {
      assertThat(WhitelistRules.normalize(WhitelistType.FILE_PATH, "/usr/bin")).startsWith("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "a\0b", "a\rb", "a\nb"})
    @DisplayName("空值_仅点号_控制字符被拒")
    void invalidInputs_areRejected(String input) {
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.FILE_PATH, input))
          .isInstanceOf(WhitelistValidationException.class);
    }

    @Test
    @DisplayName("超长被拒")
    void tooLong_isRejected() {
      String longValue = "a".repeat(WhitelistRules.MAX_VALUE_LENGTH + 1);
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.FILE_PATH, longValue))
          .isInstanceOf(WhitelistValidationException.class);
    }
  }

  @Nested
  @DisplayName("SHELL_COMMAND")
  class ShellCommand {

    @Test
    @DisplayName("单 token 原样保留_大小写敏感")
    void singleToken_isPreserved() {
      assertThat(WhitelistRules.normalize(WhitelistType.SHELL_COMMAND, "Python"))
          .isEqualTo("Python");
      assertThat(WhitelistRules.normalize(WhitelistType.SHELL_COMMAND, " python "))
          .isEqualTo("python");
    }

    @Test
    @DisplayName("绝对路径可执行文件允许")
    void absoluteExecutable_isAllowed() {
      assertThat(WhitelistRules.normalize(WhitelistType.SHELL_COMMAND, "/usr/bin/python3"))
          .isEqualTo("/usr/bin/python3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "python -m http.server", "ls\t-la", "a\rb"})
    @DisplayName("含空白或控制字符被拒")
    void whitespaceOrControl_isRejected(String input) {
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.SHELL_COMMAND, input))
          .isInstanceOf(WhitelistValidationException.class);
    }
  }

  @Nested
  @DisplayName("HTTP_DOMAIN")
  class HttpDomain {

    @Test
    @DisplayName("普通主机名统一小写")
    void host_isLowercased() {
      assertThat(WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, "API.Example.COM"))
          .isEqualTo("api.example.com");
    }

    @Test
    @DisplayName("最左通配保留")
    void wildcard_isPreserved() {
      assertThat(WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, "*.Example.com"))
          .isEqualTo("*.example.com");
    }

    @Test
    @DisplayName("IP 字面量允许")
    void ipLiteral_isAllowed() {
      assertThat(WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, "127.0.0.1"))
          .isEqualTo("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "http://example.com",
          "https://example.com/x",
          "example.com:8080",
          "example.com/x",
          "example.com?q=1",
          "example.com..evil",
          ".example.com",
          "example.com.",
          "a.*.example.com",
          "*.",
          "exa mple.com"
        })
    @DisplayName("带协议_端口_路径_查询串_错位通配的输入被拒")
    void malformedInputs_areRejected(String input) {
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, input))
          .isInstanceOf(WhitelistValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文.example.com", "münchen.de", "api．example.com"})
    @DisplayName("非 ASCII 域名被拒_并提示改用 punycode")
    void nonAscii_isRejectedWithPunycodeHint(String input) {
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, input))
          .isInstanceOf(WhitelistValidationException.class)
          .hasMessageContaining("punycode");
    }

    @Test
    @DisplayName("错误消息不回显原始输入")
    void errorMessage_doesNotEchoInput() {
      String payload = "http://evil.example.com/<script>";
      assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.HTTP_DOMAIN, payload))
          .isInstanceOf(WhitelistValidationException.class)
          .hasMessageNotContaining("script")
          .hasMessageNotContaining("evil");
    }
  }

  @Test
  @DisplayName("type 为空或 value 为空被拒")
  void nullInputs_areRejected() {
    assertThatThrownBy(() -> WhitelistRules.normalize(null, "x"))
        .isInstanceOf(WhitelistValidationException.class);
    assertThatThrownBy(() -> WhitelistRules.normalize(WhitelistType.FILE_PATH, null))
        .isInstanceOf(WhitelistValidationException.class);
  }
}
