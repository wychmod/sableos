package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sableos.core.profile.Profile;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ProviderService;
import com.sableos.storage.LlmCall;
import com.sableos.storage.LlmCallRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成冒烟：真 key、真调一次、断言非空响应且 llm_calls 落了一条 success=true。
 *
 * <p>打 {@code @Tag("integration")}，CI 与日常构建默认跳过（外部 API 可用性不绑架流水线）； 手动跑：{@code DEEPSEEK_API_KEY=xxx
 * mvn -pl sableos-boot -am test -DexcludedGroups= -Dgroups=integration -Dtest=ProviderSmokeIT}
 */
@SpringBootTest
@Tag("integration")
class ProviderSmokeIT {

  private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");
  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add("sableos.providers[0].name", () -> "deepseek");
    registry.add("sableos.providers[0].type", () -> "openai");
    registry.add("sableos.providers[0].base-url", () -> "https://api.deepseek.com");
    registry.add(
        "sableos.providers[0].api-key",
        () -> (API_KEY == null || API_KEY.isBlank()) ? "placeholder-key" : API_KEY);
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-smoke-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private ProviderService providerService;
  @Autowired private LlmCallRepository llmCallRepository;

  @Test
  @DisplayName("冒烟_真调deepseek一次_非空文本且llm_calls落success=true")
  void realCall_deepseek_returnsTextAndAudits() {
    assumeTrue(API_KEY != null && !API_KEY.isBlank(), "未设置 DEEPSEEK_API_KEY，跳过真连线冒烟");

    Profile profile =
        new Profile(
            "smoke-agent",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", 0.0),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);

    ProviderResponse response =
        providerService.chat("smoke-s-1", profile, new ProviderRequest("请用一句话回答：你好", List.of()));

    assertThat(response.content()).isNotBlank();

    List<LlmCall> rows =
        llmCallRepository.findAll().stream()
            .filter(row -> "smoke-s-1".equals(row.getSessionId()))
            .toList();
    assertThat(rows).hasSize(1);
    LlmCall audit = rows.get(0);
    assertThat(audit.isSuccess()).isTrue();
    assertThat(audit.getProvider()).isEqualTo("deepseek");
    assertThat(audit.getModel()).isEqualTo("deepseek-chat");
    assertThat(audit.getTotalTokens()).isNotNull();
    assertThat(audit.getDurationMs()).isGreaterThanOrEqualTo(0L);
  }
}
