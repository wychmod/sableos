package com.sableos.provider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 全局清单启动期校验验收：任何违规直接点名报错，不存在「静默跳过某个 provider」。 */
class ProvidersPropertiesTest {

  private static ProvidersProperties.Provider provider(
      String name, String type, String apiKey, String baseUrl) {
    return new ProvidersProperties.Provider(name, type, apiKey, baseUrl);
  }

  @Test
  @DisplayName("空清单合法_零key环境可启动")
  void validate_emptyList_passes() {
    assertThatCode(() -> new ProvidersProperties(List.of()).validate()).doesNotThrowAnyException();
    assertThatCode(() -> new ProvidersProperties(null).validate()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("合法条目_缺省type为openai且dashscope无需base_url")
  void validate_legalEntries_pass() {
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(
                provider("deepseek", null, "test-key", "https://api.deepseek.com"),
                provider("qwen", "dashscope", "test-key-2", null)));

    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("名字空白_报错点名条目序号")
  void validate_blankName_reportsIndex() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(List.of(provider("  ", null, "test-key", "https://x.com")))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("providers[0]");
  }

  @Test
  @DisplayName("名字重复_报错点名provider")
  void validate_duplicateName_reportsProvider() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(
                        List.of(
                            provider("deepseek", null, "test-key", "https://a.com"),
                            provider("deepseek", null, "test-key-2", "https://b.com")))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepseek");
  }

  @Test
  @DisplayName("api_key空白_报错点名provider")
  void validate_blankApiKey_reportsProvider() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(List.of(provider("deepseek", null, "  ", "https://x.com")))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepseek");
  }

  @Test
  @DisplayName("api_key含未展开占位符_报错点名provider")
  void validate_unresolvedPlaceholder_reportsProvider() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(
                        List.of(
                            provider("deepseek", null, "${DEEPSEEK_API_KEY:}", "https://x.com")))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepseek");
  }

  @Test
  @DisplayName("mock型_无key无base_url也合法")
  void validate_mockProviderWithoutKeyOrBaseUrl_passes() {
    ProvidersProperties properties =
        new ProvidersProperties(List.of(provider("mock", "mock", null, null)));

    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("openai型缺base_url_报错点名provider")
  void validate_openAiMissingBaseUrl_reportsProvider() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(List.of(provider("deepseek", "openai", "test-key", null)))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepseek");
  }

  @Test
  @DisplayName("type非法_报错点名provider")
  void validate_illegalType_reportsProvider() {
    assertThatThrownBy(
            () ->
                new ProvidersProperties(
                        List.of(provider("deepseek", "anthropic", "test-key", "https://x.com")))
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepseek");
  }
}
