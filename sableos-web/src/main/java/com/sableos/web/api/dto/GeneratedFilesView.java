package com.sableos.web.api.dto;

import java.util.Map;

/**
 * 生成草稿的响应体（第 30 节）：{@code 相对路径 → 内容}。
 *
 * <p>单独一个 record 而不是直接返回 {@code Map}：留出将来加 {@code suggestions}/{@code warnings} 的扩展位，且 OpenAPI
 * 上有具名模型。
 */
public record GeneratedFilesView(Map<String, String> files) {

  public GeneratedFilesView {
    files = (files == null) ? Map.of() : Map.copyOf(files);
  }
}
