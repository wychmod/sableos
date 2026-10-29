package com.sableos.web.sandbox.dto;

import com.sableos.core.sandbox.WhitelistType;

/**
 * 新增白名单条目的请求体。
 *
 * @param type 类型（必填）
 * @param value 原始输入值（必填，由服务层归一）
 * @param description 可选说明
 */
public record WhitelistEntryRequest(WhitelistType type, String value, String description) {}
