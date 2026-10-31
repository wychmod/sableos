package com.sableos.web.api;

import com.sableos.core.agent.AgentLifecycleService;
import com.sableos.core.agent.AgentStore;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.web.api.dto.FileNode;
import com.sableos.web.api.dto.WorkspaceWriteRequest;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区文件浏览器（第 30 节）：列 {@code agents/} 与 {@code archive/} 的目录树、读写文件文本。
 *
 * <p><b>只暴露两棵子树</b>：{@code .sableos/} 下还有 SQLite 库与 MCP 凭证配置，不在浏览范围内——路径校验由 {@link
 * AgentStore#resolveWithin} 单点完成，本类不另写一套规则。
 *
 * <p><b>写 {@code AGENT.md} 不做裸写盘</b>：走 {@link AgentLifecycleService#saveFiles} 的校验 + 重注册链路。理由是平台差异：
 * macOS 上 {@code WatchService} 不监听子目录内的文件变更，裸写会让「保存了但没生效」成为沉默故障。
 */
@RestController
@RequestMapping("/api/v1/workspace")
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "PATH_TRAVERSAL_IN", "PATH_TRAVERSAL_OUT"},
    justification =
        "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）。path 参数经 AgentStore.resolveWithin 做词法边界校验"
            + "（归一后必须落在工作区根的 agents/ 或 archive/ 子树内），越界一律 400；读前再解析一次真实路径，"
            + "防校验后被换成外向软链。")
public class WorkspaceApiController {

  private static final String AGENT_FILE = "AGENT.md";
  private static final String AGENTS_DIR = "agents";
  private static final String PATH_SEPARATOR = "/";
  private static final String BACKSLASH = "\\";

  /** 固定话术：不含调用方输入，可安全回显。 */
  private static final String PATH_OUT_OF_BOUNDS = "路径越界：只允许读写 agents/ 或 archive/ 下的文件";

  private final AgentStore agentStore;
  private final AgentLifecycleService lifecycle;
  private final ProfileRegistry profileRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：三个协作者都是 Spring 注入的共享单例；AgentStore 虽持有可变路径状态（root/agentsDir），"
              + "但本类只调用其查询与边界校验方法，不返回它、也不暴露其内部字段。撤销条件：新增暴露任一字段的 getter。")
  public WorkspaceApiController(
      AgentStore agentStore, AgentLifecycleService lifecycle, ProfileRegistry profileRegistry) {
    this.agentStore = agentStore;
    this.lifecycle = lifecycle;
    this.profileRegistry = profileRegistry;
  }

  /** 目录树：根为工作区目录名，children 为 {@code agents/} 与 {@code archive/} 两棵子树。 */
  @GetMapping("/tree")
  public ApiResponse<FileNode> tree() {
    Path root = agentStore.root();
    List<FileNode> roots = new ArrayList<>();
    roots.add(treeOf(root.resolve(AGENTS_DIR), AGENTS_DIR));
    roots.add(treeOf(root.resolve(archiveDirName()), archiveDirName()));
    Path rootName = root.getFileName();
    return ApiResponse.ok(
        new FileNode(rootName != null ? rootName.toString() : root.toString(), "", true, roots));
  }

  /** 读文件文本；越界 400、不存在 404。 */
  @GetMapping("/file")
  public ApiResponse<String> file(@RequestParam(name = "path") String path) {
    Path target = resolveForRead(path);
    if (!Files.isRegularFile(target)) {
      throw new ResourceNotFoundException("文件不存在: " + path);
    }
    try {
      // 读前复检：与写路径同款——防「校验通过后被换成外向软链」
      target = resolveForRead(path);
      return ApiResponse.ok(Files.readString(target, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  /** 写文件文本；{@code agents/<name>/AGENT.md} 走 Agent 更新链路（校验 + 重注册），其余直接写盘。 */
  @PostMapping("/file")
  public ApiResponse<Void> write(@RequestBody WorkspaceWriteRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
      throw new IllegalArgumentException("path 不能为空");
    }
    if (request.content() == null) {
      throw new IllegalArgumentException("content 不能为空");
    }
    Path target = agentStore.resolveWithin(request.path());
    String agentName = agentNameOfAgentDefinition(target);
    if (agentName != null) {
      lifecycle.saveFiles(agentName, Map.of(AGENT_FILE, request.content()));
      return ApiResponse.ok(null);
    }
    if (Files.isDirectory(target)) {
      throw new IllegalArgumentException("目标是目录，无法写入：" + request.path());
    }
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, request.content(), StandardCharsets.UTF_8);
      return ApiResponse.ok(null);
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败：" + request.path(), e);
    }
  }

  /**
   * 解析并校验路径；工作区外、非两棵子树、相对跳出统一抛「路径越界」的 {@link IllegalArgumentException}（400）。
   *
   * <p>message 固定、不含调用方输入：通用 400 处理器会原样回显 message，拼进用户输入就等于反射。
   */
  private Path resolveForRead(String path) {
    Path target;
    try {
      target = agentStore.resolveWithin(path);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(PATH_OUT_OF_BOUNDS);
    }
    try {
      // 存在目标解析自身真实路径再复检一次，防软链逃逸
      Path real = target.toRealPath();
      if (!real.startsWith(agentStore.root())) {
        throw new IllegalArgumentException(PATH_OUT_OF_BOUNDS);
      }
      return real;
    } catch (IOException e) {
      return target; // 不存在或不可解析：交给调用方判 404
    }
  }

  /** 若目标是 {@code agents/<name>/AGENT.md} 返回该 Agent 名；否则 null。 */
  private String agentNameOfAgentDefinition(Path target) {
    if (!AGENT_FILE.equalsIgnoreCase(String.valueOf(target.getFileName()))) {
      return null;
    }
    Path parent = target.getParent();
    if (parent == null || !parent.getParent().equals(agentStore.agentsDir())) {
      return null;
    }
    String name = String.valueOf(parent.getFileName());
    return profileRegistry.exists(name) ? name : null;
  }

  private static FileNode treeOf(Path dir, String relativePath) {
    String name = String.valueOf(dir.getFileName());
    if (!Files.isDirectory(dir)) {
      return new FileNode(name, relativePath, true, List.of());
    }
    List<FileNode> children = new ArrayList<>();
    try (Stream<Path> stream = Files.list(dir)) {
      List<Path> entries =
          stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
      for (Path entry : entries) {
        String fileName = entry.getFileName().toString();
        String childPath = relativePath + PATH_SEPARATOR + fileName;
        if (Files.isDirectory(entry)) {
          children.add(treeOf(entry, childPath));
        } else if (Files.isRegularFile(entry)) {
          children.add(new FileNode(fileName, childPath, false, List.of()));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("列出目录失败：" + relativePath, e);
    }
    return new FileNode(name, relativePath, true, children);
  }

  private static String archiveDirName() {
    return "archive";
  }
}
