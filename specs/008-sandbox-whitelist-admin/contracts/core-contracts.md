# core 契约

**Feature**: `008-sandbox-whitelist-admin` | 包：`com.sableos.core.sandbox`

## 1. 枚举

```java
public enum WhitelistType {
  FILE_PATH, SHELL_COMMAND, HTTP_DOMAIN
}
```

不含 `SMTP_ENDPOINT`（扩展阶段）。

## 2. 数据记录

```java
public record WhitelistEntry(
    long id,
    WhitelistType type,
    String value,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
```

`value` 恒为归一后形态；`description` 可为 `null`。

```java
public record WhitelistCommand(
    WhitelistType type,
    String value,
    String description) {}
```

`value` 是原始输入，归一由 `SandboxWhitelistAdmin` 实现层负责。

## 3. 管理服务契约

```java
public interface SandboxWhitelistAdmin {

  /** type 为 null 表示全部；按 type, value 排序。 */
  List<WhitelistEntry> list(WhitelistType type);

  WhitelistEntry get(long id);

  /** 归一 → 校验 → 判重 → 落库 → 刷新快照。 */
  WhitelistEntry add(WhitelistCommand command);

  /** 归一 → 校验 → 判重（排除自身）→ 落库 → 刷新快照。type 不可变。 */
  WhitelistEntry update(long id, String value, String description);

  /** 删除 → 刷新快照。 */
  void delete(long id);
}
```

**契约不变量**：

- 任何写方法成功后，运行时策略快照 MUST 已反映该变更（调用返回时生效）。
- 任何写方法失败时，策略快照 MUST 保持不变。
- `list` / `get` 只读库，不读快照（管理面要看到库的真实状态）。

## 4. 存储契约

```java
public interface SandboxWhitelistStore {

  List<WhitelistEntry> findAll(WhitelistType type);      // null = 全部

  Optional<WhitelistEntry> findById(long id);

  Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String normalizedValue);

  WhitelistEntry insert(WhitelistType type, String normalizedValue, String description);

  WhitelistEntry update(long id, String normalizedValue, String description);

  void delete(long id);

  boolean isSeeded();

  void markSeeded(int entryCount);

  long countByType(WhitelistType type);
}
```

**约定**：

- 入参 `normalizedValue` 已归一，存储层不重复校验。
- `insert` 遇唯一约束冲突 MUST 抛 `WhitelistDuplicateException`（存储层翻译 DB 异常）。
- 删除不存在的 id 由 `SandboxWhitelistAdmin` 先 `findById` 判定，存储层 `delete` 直接执行。

## 5. 领域异常

```java
public class WhitelistValidationException extends RuntimeException
public class WhitelistEntryNotFoundException extends RuntimeException
public class WhitelistDuplicateException extends RuntimeException
```

均为 `RuntimeException` 直接子类，**不继承** `IllegalArgumentException`（理由见 research.md D-05）。

构造器只接受已构造好的 message（不回显原始输入）。

## 6. 与既有类型的关系

| 既有类型 | 关系 |
| --- | --- |
| `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` | 逐字不改 |
| `WhitelistSandbox` | 字段由三个不可变集合改为 `AtomicReference<WhitelistPolicy>`；新增包内 `refresh` |
| `FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` | 保留，仍用于构造初始策略 |
