package com.sableos.boot;

import com.sableos.core.memory.MemoryProvider;
import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.Mem0Mode;
import com.sableos.memory.MemoryService;
import com.sableos.memory.MemoryServiceImpl;
import com.sableos.memory.store.MarkdownMemoryStore;
import com.sableos.memory.store.Mem0MemoryStore;
import com.sableos.memory.store.SqliteMemoryStore;
import com.sableos.memory.tool.MemoryTools;
import com.sableos.storage.memory.MemoryEntryRepository;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 长期记忆的装配配置：按 {@code memory.backend} 选择后端，把 {@link MemoryService} 注册为 {@link MemoryProvider}，并提供
 * {@link MemoryTools} bean 供 {@code ToolConfiguration} 注册。
 *
 * <p>{@code MemoryService} 是容器内唯一的 {@link MemoryProvider}——装配确定性靠「无第二处定义」，刻意不用
 * {@code @ConditionalOnMissingBean}（在常规 {@code @Configuration} 上条件求值依赖解析顺序，不可靠）。
 */
@Configuration
public class MemoryConfiguration {

  @Bean
  public MemoryService memoryService(LongTermMemoryStore store) {
    return new MemoryServiceImpl(store);
  }

  @Bean
  public MemoryTools memoryTools(MemoryService memoryService) {
    return new MemoryTools(memoryService);
  }

  @Bean
  @ConditionalOnProperty(name = "memory.backend", havingValue = "markdown", matchIfMissing = true)
  public LongTermMemoryStore markdownMemoryStore(
      @Value("${workspace.root:.sableos}") String workspaceRoot) {
    return new MarkdownMemoryStore(Path.of(workspaceRoot));
  }

  @Bean
  @ConditionalOnProperty(name = "memory.backend", havingValue = "sqlite")
  public LongTermMemoryStore sqliteMemoryStore(MemoryEntryRepository repository) {
    return new SqliteMemoryStore(repository);
  }

  @Bean
  @ConditionalOnProperty(name = "memory.backend", havingValue = "mem0")
  public LongTermMemoryStore mem0MemoryStore(
      RestClient.Builder builder,
      @Value("${memory.mem0.mode:platform}") String mode,
      @Value("${memory.mem0.base-url:}") String baseUrl,
      @Value("${memory.mem0.api-key:${MEM0_API_KEY:}}") String apiKey,
      @Value("${memory.mem0.user-prefix:sableos}") String userPrefix) {
    return mem0Store(builder, Mem0Mode.from(mode), baseUrl, apiKey, userPrefix);
  }

  /**
   * Mem0 装配的可测内核：按形态选默认地址与鉴权头。
   *
   * <p>Platform 走 {@code Authorization: Token}（缺 key 启动即失败）；OSS 走 {@code X-API-Key} （server
   * 关鉴权时允许留空）。
   */
  static LongTermMemoryStore mem0Store(
      RestClient.Builder builder, Mem0Mode mode, String baseUrl, String apiKey, String userPrefix) {
    String resolvedBaseUrl =
        baseUrl == null || baseUrl.isBlank()
            ? (mode == Mem0Mode.PLATFORM ? "https://api.mem0.ai" : "http://localhost:8888")
            : baseUrl;
    String key = apiKey == null ? "" : apiKey.trim();
    if (mode == Mem0Mode.PLATFORM && key.isEmpty()) {
      throw new IllegalStateException(
          "memory.backend=mem0 + memory.mem0.mode=platform 需要 memory.mem0.api-key（或环境变量 MEM0_API_KEY）");
    }

    RestClient.Builder configured = builder.baseUrl(resolvedBaseUrl);
    if (!key.isEmpty()) {
      configured =
          mode == Mem0Mode.PLATFORM
              ? configured.defaultHeader("Authorization", "Token " + key)
              : configured.defaultHeader("X-API-Key", key);
    }
    return new Mem0MemoryStore(configured.build(), userPrefix, mode);
  }
}
