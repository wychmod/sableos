package com.sableos.storage.sandbox;

import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistDuplicateException;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistEntryNotFoundException;
import com.sableos.core.sandbox.WhitelistType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SandboxWhitelistStore} 的 JPA 实现：sandbox_whitelist_entries 表 ↔ {@link WhitelistEntry} 互转。
 *
 * <p>把数据库层的唯一约束冲突翻译成领域异常，避免 {@code JpaSystemException} 一类存储细节泄漏到 web 层。
 *
 * <p><b>为什么按消息特征判定唯一冲突</b>：SQLite 方言不把唯一索引冲突翻译成 {@code DataIntegrityViolationException}，而是抛 {@code
 * JpaSystemException}（实测，见 specs/008 的 research D-02 与 仓库测试）。只按异常类型判断会在 SQLite 上漏判、在 PostgreSQL
 * 上误判，故以"底层驱动消息含唯一约束特征"为准， 类型只作为前置过滤。
 */
public class JpaSandboxWhitelistStore implements SandboxWhitelistStore {

  /** SQLite / PostgreSQL 唯一约束冲突的常见消息片段（小写比对）。 */
  private static final List<String> UNIQUE_VIOLATION_MARKERS =
      List.of("unique constraint", "duplicate key", "唯一");

  private final SandboxWhitelistEntryRepository entryRepository;
  private final SandboxWhitelistSeedRepository seedRepository;

  public JpaSandboxWhitelistStore(
      SandboxWhitelistEntryRepository entryRepository,
      SandboxWhitelistSeedRepository seedRepository) {
    this.entryRepository = entryRepository;
    this.seedRepository = seedRepository;
  }

  @Override
  public List<WhitelistEntry> findAll(WhitelistType type) {
    List<SandboxWhitelistEntryEntity> rows =
        (type == null)
            ? entryRepository.findAllByOrderByTypeAscValueAsc()
            : entryRepository.findAllByTypeOrderByValueAsc(type.name());
    return rows.stream().map(JpaSandboxWhitelistStore::toEntry).toList();
  }

  @Override
  public Optional<WhitelistEntry> findById(long id) {
    return entryRepository.findById(id).map(JpaSandboxWhitelistStore::toEntry);
  }

  @Override
  public Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String normalizedValue) {
    return entryRepository
        .findByTypeAndValue(type.name(), normalizedValue)
        .map(JpaSandboxWhitelistStore::toEntry);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public WhitelistEntry insert(WhitelistType type, String normalizedValue, String description) {
    try {
      SandboxWhitelistEntryEntity saved =
          entryRepository.saveAndFlush(
              new SandboxWhitelistEntryEntity(type.name(), normalizedValue, description));
      return toEntry(saved);
    } catch (DataAccessException e) {
      throw translateDuplicate(e, type, normalizedValue);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public WhitelistEntry update(long id, String normalizedValue, String description) {
    SandboxWhitelistEntryEntity entity =
        entryRepository.findById(id).orElseThrow(() -> new WhitelistEntryNotFoundException(id));
    // 同一类型下撞到别的条目（排除自身）才算重复；改回自己的原值不算
    entryRepository
        .findByTypeAndValue(entity.getType(), normalizedValue)
        .filter(other -> !other.getId().equals(id))
        .ifPresent(
            other -> {
              throw new WhitelistDuplicateException(
                  WhitelistType.valueOf(entity.getType()), normalizedValue);
            });
    entity.setValue(normalizedValue);
    entity.setDescription(description);
    try {
      return toEntry(entryRepository.saveAndFlush(entity));
    } catch (DataAccessException e) {
      throw translateDuplicate(e, WhitelistType.valueOf(entity.getType()), normalizedValue);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void delete(long id) {
    if (!entryRepository.existsById(id)) {
      throw new WhitelistEntryNotFoundException(id);
    }
    entryRepository.deleteById(id);
  }

  @Override
  public boolean isSeeded() {
    return seedRepository.existsById(SandboxWhitelistSeedEntity.SINGLETON_ID);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int seedIfNeeded(List<SeedEntry> entries) {
    if (isSeeded()) {
      return 0;
    }
    int inserted = 0;
    if (entries != null) {
      for (SeedEntry entry : entries) {
        if (entryRepository
            .findByTypeAndValue(entry.type().name(), entry.normalizedValue())
            .isPresent()) {
          continue;
        }
        entryRepository.save(
            new SandboxWhitelistEntryEntity(
                entry.type().name(), entry.normalizedValue(), entry.description()));
        inserted++;
      }
    }
    // 与条目写入同一事务：flush 后若标记插入失败，条目也会一起回滚
    entryRepository.flush();
    seedRepository.saveAndFlush(new SandboxWhitelistSeedEntity(inserted));
    return inserted;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void markSeeded(int entryCount) {
    seedRepository.saveAndFlush(new SandboxWhitelistSeedEntity(entryCount));
  }

  @Override
  public long countByType(WhitelistType type) {
    return entryRepository.countByType(type.name());
  }

  private static WhitelistEntry toEntry(SandboxWhitelistEntryEntity entity) {
    return new WhitelistEntry(
        entity.getId(),
        WhitelistType.valueOf(entity.getType()),
        entity.getValue(),
        entity.getDescription(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static RuntimeException translateDuplicate(
      DataAccessException e, WhitelistType type, String normalizedValue) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    boolean uniqueViolation =
        UNIQUE_VIOLATION_MARKERS.stream().anyMatch(marker -> message.contains(marker));
    if (uniqueViolation) {
      return new WhitelistDuplicateException(type, normalizedValue);
    }
    // 不是唯一冲突就原样上抛：并发写竞争（如 SQLITE_BUSY）属资源问题，不能错报成数据冲突
    return e;
  }
}
