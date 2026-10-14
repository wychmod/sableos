package com.sableos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.agent.AgentLoader;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.LlmCallAuditor;
import com.sableos.core.provider.ProviderRequest;
import com.sableos.core.provider.ProviderService;
import com.sableos.provider.ProviderNotFoundException;
import com.sableos.provider.ProvidersProperties;
import com.sableos.provider.SpringAiProviderServiceImpl;
import com.sableos.provider.ToolSchemaAdapter;
import com.sableos.storage.JpaLlmCallAuditor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 装配层验收（零 key）：空 providers 清单下上下文可升起、六个 bean 齐备、ProfileRegistry 为空、 未登记 provider 调用抛点名异常。数据源指向临时
 * SQLite，避免污染仓库（真 Flyway 建表随之验证）。
 */
@SpringBootTest
class ProviderAssemblyTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-assembly-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private ProvidersProperties providersProperties;
  @Autowired private ToolSchemaAdapter toolSchemaAdapter;
  @Autowired private LlmCallAuditor llmCallAuditor;
  @Autowired private ProviderService providerService;
  @Autowired private AgentLoader agentLoader;
  @Autowired private ProfileRegistry profileRegistry;

  @Test
  @DisplayName("装配_空清单可启动且六个bean齐备")
  void contextLoads_withEmptyProviderList() {
    assertThat(providersProperties.providers()).isEmpty();
    assertThat(toolSchemaAdapter).isNotNull();
    assertThat(llmCallAuditor).isInstanceOf(JpaLlmCallAuditor.class);
    assertThat(providerService).isInstanceOf(SpringAiProviderServiceImpl.class);
    assertThat(agentLoader).isNotNull();
    assertThat(profileRegistry.all()).isEmpty();
  }

  @Test
  @DisplayName("装配_未登记provider调用抛点名异常")
  void chat_withUnknownProvider_throwsNamed() {
    Profile profile =
        new Profile(
            "smoke",
            null,
            null,
            new Profile.ProviderRef("nope", "nope-model", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);

    assertThatThrownBy(
            () -> providerService.chat("s-1", profile, new ProviderRequest("你好", List.of())))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining("nope");
  }
}
