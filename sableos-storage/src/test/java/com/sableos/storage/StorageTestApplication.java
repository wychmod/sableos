package com.sableos.storage;

import com.sableos.storage.notify.JpaNotifyChannelStore;
import com.sableos.storage.notify.NotifyChannelRepository;
import com.sableos.storage.scheduler.JpaScheduledTaskStore;
import com.sableos.storage.scheduler.ScheduledTaskRepository;
import com.sableos.storage.scheduler.TaskExecutionRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** storage 模块测试引导类：真 JPA + 真 Flyway（本地默认档 SQLite），只为仓库层验收而存在。 */
@SpringBootApplication
public class StorageTestApplication {

  @Bean
  public JpaLlmCallAuditor jpaLlmCallAuditor(LlmCallRepository llmCallRepository) {
    return new JpaLlmCallAuditor(llmCallRepository);
  }

  @Bean
  public JpaToolInvocationAuditor jpaToolInvocationAuditor(
      ToolInvocationRepository toolInvocationRepository) {
    return new JpaToolInvocationAuditor(toolInvocationRepository);
  }

  @Bean
  public JpaSessionManager jpaSessionManager(SessionRepository sessionRepository) {
    return new JpaSessionManager(sessionRepository);
  }

  @Bean
  public com.sableos.storage.sandbox.JpaSandboxWhitelistStore sandboxWhitelistStore(
      com.sableos.storage.sandbox.SandboxWhitelistEntryRepository entryRepository,
      com.sableos.storage.sandbox.SandboxWhitelistSeedRepository seedRepository) {
    return new com.sableos.storage.sandbox.JpaSandboxWhitelistStore(
        entryRepository, seedRepository);
  }

  @Bean
  public JpaScheduledTaskStore scheduledTaskStore(
      ScheduledTaskRepository taskRepository, TaskExecutionRepository executionRepository) {
    return new JpaScheduledTaskStore(taskRepository, executionRepository);
  }

  @Bean
  public JpaNotifyChannelStore notifyChannelStore(NotifyChannelRepository repository) {
    return new JpaNotifyChannelStore(repository);
  }
}
