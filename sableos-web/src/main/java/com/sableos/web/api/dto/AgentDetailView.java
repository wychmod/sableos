package com.sableos.web.api.dto;

import java.util.List;

/**
 * Agent 详情视图（第 30 节）：在 {@link AgentView} 之上补该 Agent 目录的文件树。
 *
 * @param files 该 Agent 目录的文件树（根为该目录本身）
 */
public record AgentDetailView(AgentView agent, List<FileNode> files) {

  public AgentDetailView {
    files = (files == null) ? List.of() : List.copyOf(files);
  }
}
