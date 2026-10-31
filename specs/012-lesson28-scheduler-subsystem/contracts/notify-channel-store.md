# Contract: `NotifyChannelStore`（core）

**Package**: `com.sableos.core.notify` | **实现**: `com.sableos.storage.notify.JpaNotifyChannelStore`

## 接口

```java
public interface NotifyChannelStore {

  List<NotifyChannelView> list();

  Optional<NotifyChannelView> find(String name);

  void save(NotifyChannelView channel);

  boolean delete(String name);
}
```

## 语义

### `list`

- 返回全部渠道，按 `createdAt ASC, name ASC` 排序。
- 第一条即"缺省渠道"；`NotifyTools` 在 channel 缺省/空白/`default` 时取它。

### `find`

- 按全局唯一名称查找；不存在返回 `Optional.empty()`。

### `save`

- Upsert：按 `name` 查找。
  - 未命中：`createdAt = now`、`updatedAt = now`，插入。
  - 命中：保留 `createdAt`，更新 `type / url / description / config`、`updatedAt = now`。
- `name` / `type` / `url` 非空校验在调用方（Web 控制器）完成；store 仍应对空白值快速失败（`IllegalArgumentException`）。
- `config` 为 null 时存 `{}`；序列化为 JSON 对象。

### `delete`

- 命中：删除并返回 true。
- 未命中：返回 false（Web 映射 404）。

## 校验口径（Web 层）

| 字段 | 规则 | 失败 |
| --- | --- | --- |
| `name` | 非空、≤128、`^[A-Za-z0-9._-]+$` | 400 |
| `type` | 非空、大小写不敏感等于 `webhook` | 400 |
| `url` | 非空、URI 可解析、scheme ∈ {http, https} | 400 |
| `description` | 可空；≤1000 字符 | 400 |
| `config` | 可空；键值均为字符串；null 视为 `{}` | 400 |

重名 → 409；更新/删除不存在 → 404。

## 与 `NotifyTools` 的契约

- `NotifyTools` 每次执行调用 `list()` 或 `find(name)`，不缓存启动快照。
- `NotifyChannelView` → `NotifyTarget` 的映射：`config` 复制后写入 `url` 键；`channelType = view.type()`。
- 无渠道 / 渠道名不存在 → `ToolResult.failure(..., false)`，零请求。
