# Contract: ProfileRegistry（运行期注册）

**Feature**: `specs/013-lesson29-pluggable-agent` | 类：`com.sableos.core.profile.ProfileRegistry`

## 接口

```java
public class ProfileRegistry {
  public void register(Profile profile);   // 既有：登记或同名覆盖
  public Profile get(String name);         // 既有：未注册返回 null（语义冻结）
  public boolean exists(String name);      // 既有
  public List<Profile> all();              // 既有：快照，不暴露内部集合
  public boolean remove(String name);      // 本节新增
}
```

## 语义

| 方法 | 前置 | 后置 | 异常 |
| --- | --- | --- | --- |
| `register` | `profile != null` 且 `profile.name()` 非空 | 按名可见；同名覆盖 | 无（空值由调用方保证，见下） |
| `get` | — | 命中返回该实例；未命中返回 `null` | 无 |
| `exists` | — | 命中 `true` | 无 |
| `all` | — | 返回登记时刻的快照 | 无 |
| `remove` | — | 命中并移除返回 `true`；未命中返回 `false` | 无 |

**并发**：底层 `ConcurrentHashMap`，全部方法线程安全；`all()` 返回 `List.copyOf` 快照，调用方改动不影响注册表。

**校验边界**：本类**不做任何配置校验**（research D-07）。非法配置必须在 `AgentLoader.deriveProfile` 处被拒绝；`register` 收到什么就存什么。这条边界保证「启动扫描」与「运行期登记」的校验只有一处实现。

## 与启动扫描的关系

```text
启动：AgentLoader.loadAll()        -> registry.register(profile)   (每个合法目录一次)
运行：AgentLoader.deriveProfile(p) -> registry.register(profile)   (第 30 节 API 调用)
```

两条路径共用同一个 `register`，且校验都在 `deriveProfile` 内完成——**因此非法声明的异常类型与消息在两条路径上必然一致**（FR-003 / SC-008）。

## 不做的事

- 不提供 `clear()`（运行期整体清空会与进行中的请求竞争）。
- 不提供 `size()`（`all().size()` 已覆盖；新增方法属越界）。
- 不做持久化（Agent 定义的事实源是文件系统；注册表只是内存索引）。
