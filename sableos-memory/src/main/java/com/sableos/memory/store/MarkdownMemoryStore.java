package com.sableos.memory.store;

import com.sableos.core.agent.ToolExecutionContext;
import com.sableos.memory.LongTermMemoryStore;
import com.sableos.memory.MemoryScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 默认长期记忆后端：按 Profile 分目录的 {@code MEMORY.md}，按 "## 核心记忆" / "## 归档记忆" 分区。
 *
 * <p>第 30 节起，有 Agent 执行上下文时记忆落到 {@code agents/<name>/MEMORY.md}（这个 Agent 自己的成长记录，跟它的 {@code
 * AGENT.md} / {@code skills/} 同目录）；无上下文时回退按 Profile 分目录的全局路径，保持既有行为。
 *
 * <p>每次重新读文件（契约一）；截断只裁归档区字符串（契约二）；recall 只搜归档区（契约四）。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_CHARS = 4000;

  /** Agent 名必须是安全目录段；「../」「a/b」等一律回退全局路径。 */
  private static final Pattern SAFE_AGENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

  private final Path workspaceRoot;

  public MarkdownMemoryStore(Path workspaceRoot) {
    this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空");
  }

  @Override
  public void append(String content, MemoryScope scope, String profileName) {
    Objects.requireNonNull(content, "content 不能为空");
    Objects.requireNonNull(scope, "scope 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    Path file = memoryFile(profileName);
    ensureFile(file);
    Document doc = readDocument(file);
    String line = "- [" + LocalDate.now() + "] " + content;
    if (scope == MemoryScope.CORE) {
      doc.core.add(line);
    } else {
      doc.archive.add(line);
    }
    writeDocument(file, doc);
  }

  @Override
  public String load(String profileName) {
    Objects.requireNonNull(profileName, "profileName 不能为空");

    Path file = memoryFile(profileName);
    if (!Files.exists(file)) {
      return "";
    }
    Document doc = readDocument(file);
    String core = String.join("\n", doc.core);
    String archive = truncateIfNeeded(String.join("\n", doc.archive));
    return (core + "\n" + archive).strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword, String profileName) {
    Objects.requireNonNull(keyword, "keyword 不能为空");
    Objects.requireNonNull(profileName, "profileName 不能为空");

    Path file = memoryFile(profileName);
    if (!Files.exists(file)) {
      return List.of();
    }
    Document doc = readDocument(file);
    return doc.archive.stream().filter(line -> line.contains(keyword)).toList();
  }

  /**
   * 该读写哪个 {@code MEMORY.md}（第 30 节）：有合法 Agent 上下文 → 该 Agent 目录下的专属记忆； 否则回退到按 Profile 分目录的全局路径（非
   * Agent 触发的直接调用、单测，行为与改动前一致）。
   *
   * <p>Agent 名做安全校验：非法段一律回退全局，防目录穿越。
   */
  private Path memoryFile(String profileName) {
    String agent = ToolExecutionContext.agentName();
    if (agent != null && SAFE_AGENT.matcher(agent).matches()) {
      return workspaceRoot.resolve("agents").resolve(agent).resolve("MEMORY.md");
    }
    return workspaceRoot.resolve("memory").resolve(profileName).resolve("MEMORY.md");
  }

  private void ensureFile(Path file) {
    Path parent = file.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("MEMORY.md 路径必须有父目录: " + file);
    }
    try {
      Files.createDirectories(parent);
      if (!Files.exists(file)) {
        Files.writeString(
            file, CORE_HEADER + "\n\n" + ARCHIVE_HEADER + "\n", StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法初始化 MEMORY.md: " + file, e);
    }
  }

  private Document readDocument(Path file) {
    String raw = readString(file);
    Document doc = new Document();
    int coreStart = raw.indexOf(CORE_HEADER);
    int archiveStart = raw.indexOf(ARCHIVE_HEADER);
    if (coreStart >= 0 && archiveStart >= 0) {
      if (coreStart < archiveStart) {
        doc.core.addAll(parseLines(raw.substring(coreStart + CORE_HEADER.length(), archiveStart)));
        doc.archive.addAll(parseLines(raw.substring(archiveStart + ARCHIVE_HEADER.length())));
      } else {
        doc.archive.addAll(
            parseLines(raw.substring(archiveStart + ARCHIVE_HEADER.length(), coreStart)));
        doc.core.addAll(parseLines(raw.substring(coreStart + CORE_HEADER.length())));
      }
    } else if (coreStart >= 0) {
      doc.core.addAll(parseLines(raw.substring(coreStart + CORE_HEADER.length())));
    } else if (archiveStart >= 0) {
      doc.archive.addAll(parseLines(raw.substring(archiveStart + ARCHIVE_HEADER.length())));
    }
    return doc;
  }

  private List<String> parseLines(String section) {
    List<String> lines = new ArrayList<>();
    for (String line : section.lines().toList()) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    return lines;
  }

  private void writeDocument(Path file, Document doc) {
    StringBuilder sb = new StringBuilder();
    sb.append(CORE_HEADER).append("\n");
    for (String line : doc.core) {
      sb.append(line).append("\n");
    }
    sb.append(ARCHIVE_HEADER).append("\n");
    for (String line : doc.archive) {
      sb.append(line).append("\n");
    }
    try {
      Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 MEMORY.md: " + file, e);
    }
  }

  private String truncateIfNeeded(String archive) {
    if (archive.length() <= MAX_ARCHIVE_CHARS) {
      return archive;
    }
    return archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
  }

  private String readString(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 MEMORY.md: " + file, e);
    }
  }

  private static final class Document {
    final List<String> core = new ArrayList<>();
    final List<String> archive = new ArrayList<>();
  }
}
