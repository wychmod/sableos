package com.sableos.boot;

import com.sableos.core.memory.MemoryProvider;
import com.sableos.memory.LongTermMemoryStore;
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
  public LongTermMemoryStore mem0MemoryStore(RestClient.Builder builder) {
    String apiKey = System.getenv("MEM0_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("memory.backend=mem0 需要环境变量 MEM0_API_KEY");
    }
    // Platform 默认地址；私有网关/代理可用 MEM0_BASE_URL 覆盖
    String baseUrl = System.getenv("MEM0_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.mem0.ai";
    }
    RestClient restClient =
        builder.baseUrl(baseUrl).defaultHeader("Authorization", "Token " + apiKey).build();
    return new Mem0MemoryStore(restClient, "sableos");
  }
}
