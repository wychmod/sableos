# Data Model: Sandbox 白名单管理接口

**Feature**: `008-sandbox-whitelist-admin` | **Date**: 2026-10-29

## 1. 迁移脚本

版本 **V5**，SQLite 与 PostgreSQL 双轨，版本号一一对应。文件名 `V5__sandbox_whitelist.sql`。

### 1.1 SQLite

```sql
CREATE TABLE IF NOT EXISTS sandbox_whitelist_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        VARCHAR(32)   NOT NULL,
    value       VARCHAR(1024) NOT NULL,
    description VARCHAR(255),
    created_at  INTEGER       NOT NULL,
    updated_at  INTEGER       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sandbox_whitelist_type_value
    ON sandbox_whitelist_entries (type, value);

CREATE TABLE IF NOT EXISTS sandbox_whitelist_seed (
    id          INTEGER  PRIMARY KEY CHECK (id = 1),
    seeded_at   INTEGER  NOT NULL,
    entry_count INTEGER  NOT NULL
);
```

### 1.2 PostgreSQL

```sql
CREATE TABLE IF NOT EXISTS sandbox_whitelist_entries (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(32)   NOT NULL,
    value       VARCHAR(1024) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sandbox_whitelist_type_value
    ON sandbox_whitelist_entries (type, value);

CREATE TABLE IF NOT EXISTS sandbox_whitelist_seed (
    id          SMALLINT  PRIMARY KEY CHECK (id = 1),
    seeded_at   TIMESTAMP NOT NULL,
    entry_count INTEGER   NOT NULL
);
```

> **时间列类型**：PG 轨用 `TIMESTAMP`（对齐最近的 V4，而非 V1~V3 的 `TIMESTAMPTZ`）。项目内 V1~V3 与 V4 本就不一致，本表选择与最新的 V4 对齐，减少新增分歧。

## 2. 表字段

### 2.1 `sandbox_whitelist_entries`

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | 自增主键 | NOT NULL | API 路径参数 |
| `type` | VARCHAR(32) | NOT NULL | `FILE_PATH` / `SHELL_COMMAND` / `HTTP_DOMAIN` |
| `value` | VARCHAR(1024) | NOT NULL | **归一后**的值；不存原始输入 |
| `description` | VARCHAR(255) | 可空 | 运营方说明；不进日志 |
| `created_at` | INTEGER / TIMESTAMP | NOT NULL | `Instant` |
| `updated_at` | INTEGER / TIMESTAMP | NOT NULL | `Instant`，更新时刷新 |

唯一约束：`UNIQUE(type, value)`。

### 2.2 `sandbox_whitelist_seed`

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | SMALLINT/INTEGER | PK + `CHECK (id = 1)` | 物理上只允许一行 |
| `seeded_at` | INTEGER / TIMESTAMP | NOT NULL | 导入完成时刻 |
| `entry_count` | INTEGER | NOT NULL | 本次导入条数，**允许为 0** |

**语义**：行存在 = 已导入过。与条目表是否为空无关。

## 3. JPA 实体映射

### 3.1 `SandboxWhitelistEntryEntity`

包：`com.sableos.storage.sandbox`

```java
@Entity
@Table(name = "sandbox_whitelist_entries")
public class SandboxWhitelistEntryEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "type", nullable = false, length = 32)
  private String type;
  @Column(name = "value", nullable = false, length = 1024)
  private String value;
  @Column(name = "description", length = 255)
  private String description;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected SandboxWhitelistEntryEntity() {}
  // @PrePersist 填 createdAt/updatedAt；@PreUpdate 刷新 updatedAt
}
```

日期映射对齐既有 `MemoryEntryEntity`。`type` 存枚举名（`WhitelistType.name()`），不存 ordinal——避免枚举顺序变化导致历史数据语义漂移。

### 3.2 `SandboxWhitelistSeedEntity`

```java
@Entity
@Table(name = "sandbox_whitelist_seed")
public class SandboxWhitelistSeedEntity {
  @Id @Column(name = "id") private Short id;          // 恒为 1
  @Column(name = "seeded_at", nullable = false) private Instant seededAt;
  @Column(name = "entry_count", nullable = false) private Integer entryCount;
}
```

无 `@GeneratedValue`（id 由业务固定为 1）。

## 4. 仓库接口

```java
interface SandboxWhitelistEntryRepository
    extends JpaRepository<SandboxWhitelistEntryEntity, Long> {
  Optional<SandboxWhitelistEntryEntity> findByTypeAndValue(String type, String value);
  List<SandboxWhitelistEntryEntity> findAllByOrderByTypeAscValueAsc();
  List<SandboxWhitelistEntryEntity> findAllByTypeOrderByValueAsc(String type);
  long countByType(String type);
}

interface SandboxWhitelistSeedRepository
    extends JpaRepository<SandboxWhitelistSeedEntity, Short> {}
```

## 5. core 领域模型

```java
enum WhitelistType { FILE_PATH, SHELL_COMMAND, HTTP_DOMAIN }

record WhitelistEntry(
    long id,
    WhitelistType type,
    String value,          // 归一后
    String description,    // 可空
    Instant createdAt,
    Instant updatedAt) {}

record WhitelistCommand(
    WhitelistType type,
    String value,          // 原始输入，由 admin 层归一
    String description) {}
```

## 6. 运行时策略快照（tool 层内部类型）

```java
record WhitelistPolicy(
    List<Path> allowedRoots,          // 已 toAbsolutePath().normalize()
    Set<String> allowedCommands,
    List<String> allowedDomainPatterns) {
  // 构造器内全部 List.copyOf / Set.copyOf
}
```

从条目重建规则：
- `FILE_PATH` → `Path.of(value).toAbsolutePath().normalize()`（与 24 节现行逻辑一致）
- `SHELL_COMMAND` → 原样字符串
- `HTTP_DOMAIN` → 原样字符串（入库时已小写）

## 7. 状态转换

```text
[空库 + 无标记]
    │ 首次启动
    ▼
[条目 = YAML 导入结果] + [标记 id=1]
    │ CRUD
    ▼
[条目 = YAML 结果 ± 运营方变更]  ← 重启不变，不重播 YAML
    │ 删光条目
    ▼
[条目 = 空] + [标记 id=1]      ← 重启仍为空，不重播
```

关键不变量：**标记行一旦写入永不删除**（除非显式重置，本期不做）。
