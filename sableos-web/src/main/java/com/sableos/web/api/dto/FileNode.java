package com.sableos.web.api.dto;

import java.util.List;

/**
 * 目录树节点（第 30 节工作区浏览器）。
 *
 * @param name 显示名（文件/目录的末段）
 * @param path 相对工作区根的路径（{@code /} 分隔，可直接回传给文件读/写接口）
 * @param directory 是否目录
 * @param children 子节点；文件恒为空表
 */
public record FileNode(String name, String path, boolean directory, List<FileNode> children) {

  public FileNode {
    children = (children == null) ? List.of() : List.copyOf(children);
  }
}
