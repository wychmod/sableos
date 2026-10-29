package com.sableos.web.sandbox.dto;

import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistType;
import java.time.Instant;

/**
 * 白名单条目的响应体（对外契约）。
 *
 * <p>与 core 的 {@link WhitelistEntry} 分开，是为了让 API 的字段名与形状不随领域模型内部调整而漂移。
 *
 * @param id 条目标识
 * @param type 类型
 * @param value 归一后的值
 * @param description 说明（可空）
 * @param createdAt 创建时刻
 * @param updatedAt 最近更新时刻
 */
public record WhitelistEntryResponse(
    long id,
    WhitelistType type,
    String value,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public static WhitelistEntryResponse from(WhitelistEntry entry) {
    return new WhitelistEntryResponse(
        entry.id(),
        entry.type(),
        entry.value(),
        entry.description(),
        entry.createdAt(),
        entry.updatedAt());
  }
}
