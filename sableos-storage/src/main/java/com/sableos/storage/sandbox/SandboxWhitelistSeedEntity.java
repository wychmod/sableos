package com.sableos.storage.sandbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * sandbox_whitelist_seed 表的 JPA 映射：单行标记，行存在即"已从配置文件导入过"。
 *
 * <p>判据刻意独立于条目表：管理员删光所有条目后，条目表与"从未导入"完全同构；若用条目表判空，下次启动会把配置文件再插一遍，推翻"删除不复活"的承诺。
 *
 * <p>{@code id} 不由数据库生成，恒为 1（迁移脚本带 {@code CHECK (id = 1)}），主键冲突即为并发启动的第二道防线。
 */
@Entity
@Table(name = "sandbox_whitelist_seed")
public class SandboxWhitelistSeedEntity {

  /** 标记行唯一允许的主键值。 */
  public static final short SINGLETON_ID = 1;

  @Id
  @Column(name = "id")
  private Short id;

  @Column(name = "seeded_at", nullable = false)
  private Instant seededAt;

  @Column(name = "entry_count", nullable = false)
  private Integer entryCount;

  /** JPA 要求的无参构造。 */
  protected SandboxWhitelistSeedEntity() {}

  public SandboxWhitelistSeedEntity(int entryCount) {
    this.id = SINGLETON_ID;
    this.seededAt = Instant.now();
    this.entryCount = entryCount;
  }

  public Short getId() {
    return id;
  }

  public Instant getSeededAt() {
    return seededAt;
  }

  public Integer getEntryCount() {
    return entryCount;
  }
}
