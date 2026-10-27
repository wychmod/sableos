package com.sableos.memory.store;

import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import com.sableos.storage.memory.MemoryEntryEntity;
import com.sableos.storage.memory.MemoryEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SQLite 长期记忆后端：记忆按条入库，截断变成 {@code LIMIT}，检索变成 {@code LIKE}。
 *
 * <p>每次重新查库（契约一）；核心区用 {@code WHERE scope='CORE'} 全量取（契约二）； {@code recallByKeyword} 只搜归档区（契约四）。
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  private static final int MAX_ARCHIVE_ROWS = 100;

  private final MemoryEntryRepository repository;

  public SqliteMemoryStore(MemoryEntryRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository 不能为空");
  }

  @Override
  public void append(String content, MemoryScope scope, String profileName) {
    Objects.requireNonNull(content, "content 不能为空");
    Objects.requireNonNull(scope, "scope 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    repository.save(new MemoryEntryEntity(profileName, scope.name(), content, Instant.now()));
  }

  @Override
  public String load(String profileName) {
    Objects.requireNonNull(profileName, "profileName 不能为空");

    String core = render(repository.findCoreByProfile(profileName));
    String archive = render(repository.findRecentArchivalByProfile(profileName, MAX_ARCHIVE_ROWS));
    return (core + "\n" + archive).strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword, String profileName) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    return repository.searchArchivalByProfile(profileName, "%" + keyword + "%").stream()
        .map(MemoryEntryEntity::getContent)
        .collect(Collectors.toList());
  }

  private String render(List<MemoryEntryEntity> entries) {
    return entries.stream()
        .map(e -> "- [" + e.getCreatedAt() + "] " + e.getContent())
        .collect(Collectors.joining("\n"));
  }
}
