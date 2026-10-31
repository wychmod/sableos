package com.sableos.web.api.dto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存 Agent 一组文件（第 30 节）：{@code 相对路径 → 内容}，必须含 {@code AGENT.md}。
 *
 * <p>构造时做一次防御性拷贝并包成不可变视图：请求体来自 HTTP 反序列化，原 Map 在本类之外仍可达，直接持有会让 调用方在外侧继续改写已提交的落盘内容。拷贝用 {@link
 * LinkedHashMap} 保留提交顺序（前端按此顺序展示）。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "确认误报：紧凑构造器已把入参拷贝进 unmodifiableMap，字段对外只读；"
            + "SpotBugs 无法静态识别 Collections.unmodifiableMap 的不可变语义。"
            + "撤销条件：若改为直接持有入参 Map，须重新评估。")
public record SaveFilesRequest(Map<String, String> files) {

  public SaveFilesRequest {
    files = (files == null) ? null : Collections.unmodifiableMap(new LinkedHashMap<>(files));
  }
}
