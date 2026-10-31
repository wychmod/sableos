package com.sableos.storage.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.notify.NotifyChannelStore;
import com.sableos.core.notify.NotifyChannelView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotifyChannelStore} 的 JPA 实现（技术方案 §6.8；第 28 节补齐）。
 *
 * <p>{@code config_json} 存类型相关多字段；{@code url} 是 webhook 的权威地址。更新时保留 {@code createdAt}，
 * 使"第一个渠道"的顺序稳定。
 */
public class JpaNotifyChannelStore implements NotifyChannelStore {

  private static final Logger LOG = LoggerFactory.getLogger(JpaNotifyChannelStore.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final TypeReference<Map<String, String>> CONFIG_TYPE =
      new TypeReference<Map<String, String>>() {};

  private final NotifyChannelRepository repository;

  public JpaNotifyChannelStore(NotifyChannelRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotifyChannelView> list() {
    return repository.findAllByOrderByCreatedAtAscNameAsc().stream()
        .map(JpaNotifyChannelStore::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<NotifyChannelView> find(String name) {
    return repository.findById(name).map(JpaNotifyChannelStore::toView);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void save(NotifyChannelView channel) {
    String name = requireText(channel.name(), "name");
    Instant now = Instant.now();
    NotifyChannelEntity entity =
        repository
            .findById(name)
            .orElseGet(
                () -> {
                  NotifyChannelEntity created = new NotifyChannelEntity();
                  created.setName(name);
                  created.setCreatedAt(channel.createdAt() == null ? now : channel.createdAt());
                  return created;
                });
    entity.setType(requireText(channel.type(), "type").toLowerCase(java.util.Locale.ROOT));
    entity.setUrl(requireText(channel.url(), "url"));
    entity.setDescription(channel.description());
    entity.setConfigJson(writeConfig(channel.config()));
    entity.setUpdatedAt(now);
    repository.save(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean delete(String name) {
    if (!repository.existsById(name)) {
      return false;
    }
    repository.deleteById(name);
    return true;
  }

  private static NotifyChannelView toView(NotifyChannelEntity entity) {
    return new NotifyChannelView(
        entity.getName(),
        entity.getType(),
        entity.getUrl(),
        entity.getDescription(),
        readConfig(entity.getConfigJson()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static String writeConfig(Map<String, String> config) {
    try {
      return MAPPER.writeValueAsString(config == null ? Map.of() : config);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("通知渠道 config 序列化失败", e);
    }
  }

  private static Map<String, String> readConfig(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, CONFIG_TYPE);
    } catch (JsonProcessingException e) {
      // 数据由本类写入；解析失败说明库被外部改坏。保留渠道可用性、降级为空配置并留日志。
      // 不把解析器原始消息拼进日志：它可能含库内被篡改的 CR/LF，且对排障价值有限。
      LOG.warn("通知渠道 config_json 解析失败，按空配置处理");
      return Map.of();
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value;
  }
}
