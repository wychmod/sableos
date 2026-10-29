package com.sableos.core.sandbox;

import java.util.List;
import java.util.Optional;

/**
 * 白名单的持久化契约（依赖倒置放 core）：{@code sableos-tool} 的运行时实现通过它读写库，{@code sableos-storage} 提供 JPA 实现，两者互不依赖。
 *
 * <p>契约里**不含校验规则**：入参 {@code normalizedValue} 由调用方（{@link SandboxWhitelistAdmin} 实现）归一好再传进来，
 * 存储层不重复实现"域名怎么算合法"这类知识——规则必须与运行时的匹配逻辑同源，否则两边漂移。
 *
 * <p>唯一性由 DB 的 {@code UNIQUE(type, value)} 兜底；{@link #insert} 撞唯一约束时 MUST 抛 {@link
 * WhitelistDuplicateException}，而不是把数据库异常泄漏出去。
 */
public interface SandboxWhitelistStore {

  /**
   * 列出条目。
   *
   * @param type {@code null} 表示全部；非 null 时只返回该类型
   * @return 按 {@code type, value} 排序的条目
   */
  List<WhitelistEntry> findAll(WhitelistType type);

  /** 按标识查单条；不存在返回空。 */
  Optional<WhitelistEntry> findById(long id);

  /** 按类型与归一后的值查重。 */
  Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String normalizedValue);

  /**
   * 新增一条。
   *
   * @throws WhitelistDuplicateException {@code (type, normalizedValue)} 已存在
   */
  WhitelistEntry insert(WhitelistType type, String normalizedValue, String description);

  /**
   * 更新一条的值与说明（类型不可变）。
   *
   * @throws WhitelistEntryNotFoundException id 不存在
   * @throws WhitelistDuplicateException 新值与该类型的另一条重复
   */
  WhitelistEntry update(long id, String normalizedValue, String description);

  /**
   * 删除一条。
   *
   * @throws WhitelistEntryNotFoundException id 不存在
   */
  void delete(long id);

  /** 是否已完成首次导入（配置文件 → 库）。判据独立于条目表，见 specs/008 §5.3。 */
  boolean isSeeded();

  /**
   * 首次导入：**判重、写入条目、打标记全部在同一个事务里**，返回实际新增条数。
   *
   * <p>为什么必须原子：判定与写入分离会产生两种坏状态——"条目写了一半、标记没写"导致下次启动重复导入；"标记写了、条目没写"让配置静默丢失。并发启动时后到者应直接返回
   * 0（已由先到者完成）。
   *
   * <p>入参已由调用方归一：本方法只做"已存在则跳过 + 落库 + 打标记"，不重复实现校验规则。
   *
   * @param entries 待导入的条目（类型 + 归一后的值 + 说明）
   * @return 实际新增条数；已导入过则返回 0
   */
  int seedIfNeeded(List<SeedEntry> entries);

  /**
   * 记录"已导入"，允许 {@code entryCount} 为 0（空配置也要落标记）。
   *
   * <p>仅用于测试与显式重置场景；正常导入路径请用 {@link #seedIfNeeded}，它自带原子性。
   */
  void markSeeded(int entryCount);

  /** 一条待导入的配置条目。 */
  record SeedEntry(WhitelistType type, String normalizedValue, String description) {}

  /** 某类型当前条目数，用于上限校验。 */
  long countByType(WhitelistType type);
}
