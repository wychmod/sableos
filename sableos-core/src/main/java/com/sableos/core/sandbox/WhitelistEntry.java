package com.sableos.core.sandbox;

import java.time.Instant;

/**
 * 一条已生效的白名单条目（只读）。
 *
 * <p>{@code value} 恒为**归一后**的形态——它同时是存储形态、唯一性判重形态与 API 返回形态。原始输入不保留，避免"库里有两种写法、匹配只用一种"的漂移。
 *
 * @param id 条目标识（API 路径参数）
 * @param type 类型；创建后不可变
 * @param value 归一后的值
 * @param description 运营方说明，可为 null
 * @param createdAt 创建时刻
 * @param updatedAt 最近更新时刻
 */
public record WhitelistEntry(
    long id,
    WhitelistType type,
    String value,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
