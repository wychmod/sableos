package com.sableos.web.api;

import com.sableos.core.agent.AgentLifecycleService;
import com.sableos.core.agent.AgentStore;
import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.web.api.dto.AgentDetailView;
import com.sableos.web.api.dto.AgentView;
import com.sableos.web.api.dto.CreateAgentRequest;
import com.sableos.web.api.dto.FileNode;
import com.sableos.web.api.dto.GenerateFilesRequest;
import com.sableos.web.api.dto.GeneratedFilesView;
import com.sableos.web.api.dto.InvokeRequest;
import com.sableos.web.api.dto.MemoryView;
import com.sableos.web.api.dto.ReplyView;
import com.sableos.web.api.dto.SaveFilesRequest;
import com.sableos.web.api.dto.ScheduleConfigView;
import com.sableos.web.api.dto.SendMessageRequest;
import com.sableos.web.api.dto.SessionView;
import com.sableos.web.api.dto.UpdateAgentRequest;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 管理与调用端点（26 节 + 第 30 节）：无状态调用、增删改查、生成草稿、固定管理台会话、专属记忆。
 *
 * <p>薄门面：只做参数校验、响应包装与错误抛出；生命周期编排全部委托 {@link AgentLifecycleService}，「文件存哪」由 {@link AgentStore}
 * 决定，会话身份只经 {@link SessionManager} 生成（Controller 不拼 {@code session_id}）。
 */
@RestController
@RequestMapping("/api/v1/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class AgentApiController {

  private static final String INVOKE_USER_PREFIX = "invoke-";
  private static final String AGENT_FILE = "AGENT.md";
  private static final String PATH_SEPARATOR = "/";
  private static final String BACKSLASH = "\\";

  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentLifecycleService lifecycle;
  private final AgentStore agentStore;
  private final AgentCallExecutor agentCallExecutor;
  private final MemoryProvider memoryProvider;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ProfileRegistry 自第 29 节起支持运行期 remove，SpotBugs 因而把它判为可变类型；"
              + "本类只把它当依赖注入的协作者查询使用，无返回它的 getter，也不暴露其内部集合。"
              + "撤销条件：一旦新增暴露该字段的 getter 或 public 字段，须重新评估。")
  public AgentApiController(
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentLifecycleService lifecycle,
      AgentStore agentStore,
      AgentCallExecutor agentCallExecutor,
      MemoryProvider memoryProvider) {
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.lifecycle = lifecycle;
    this.agentStore = agentStore;
    this.agentCallExecutor = agentCallExecutor;
    this.memoryProvider = memoryProvider;
  }

  // ---------------------------------------------------------------- 增删改查

  /** 列表：按名排序。 */
  @GetMapping
  public ApiResponse<List<AgentView>> list() {
    return ApiResponse.ok(lifecycle.list().stream().map(AgentApiController::toView).toList());
  }

  /** 单个详情：含该 Agent 目录的文件树。 */
  @GetMapping("/{name}")
  public ApiResponse<AgentDetailView> get(@PathVariable String name) {
    Profile profile = requireRegistered(name);
    return ApiResponse.ok(new AgentDetailView(toView(profile), fileTree(name)));
  }

  /** 创建：只有名字与描述，其余按模板脚手架生成；同名 400 且零文件新增。 */
  @PostMapping
  public ApiResponse<AgentDetailView> create(@RequestBody CreateAgentRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    String name = request.name() == null ? null : request.name().trim();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不能为空");
    }
    String description = (request.description() == null) ? "" : request.description().strip();
    Profile profile = lifecycle.create(name, description);
    return ApiResponse.ok(new AgentDetailView(toView(profile), fileTree(name)));
  }

  /** 更新：整份 AGENT.md 覆写，校验通过即生效（定时变更先注销旧句柄）。 */
  @PutMapping("/{name}")
  public ApiResponse<AgentDetailView> update(
      @PathVariable String name, @RequestBody UpdateAgentRequest request) {
    requireRegistered(name);
    String content = request == null ? null : request.content();
    Profile profile = lifecycle.update(name, content);
    return ApiResponse.ok(new AgentDetailView(toView(profile), fileTree(name)));
  }

  /** 删除：注销定时 → 移出索引 → 目录归档（不物理删）。 */
  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    requireRegistered(name);
    lifecycle.delete(name);
    return ApiResponse.ok(null);
  }

  // ---------------------------------------------------------------- 调用 / 会话

  /** 无状态调用：Agent 未注册 404；消息空白或超 32KB 400；超过 60 秒 504。 */
  @PostMapping("/{name}/invoke")
  public ApiResponse<ReplyView> invoke(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    if (!profileRegistry.exists(name)) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
    String message =
        ApiConstraints.requireMessage(request == null ? null : request.message(), "消息");
    String userId = INVOKE_USER_PREFIX + UUID.randomUUID();
    Session session = sessionManager.getOrCreate(ApiConstraints.CHANNEL_WEB, userId, name);
    return ApiResponse.ok(new ReplyView(agentCallExecutor.process(session, message)));
  }

  /** 固定管理台会话：同三元组幂等，恒为同一条（第 30 节）。 */
  @GetMapping("/{name}/session")
  public ApiResponse<SessionView> session(@PathVariable String name) {
    requireRegistered(name);
    Session session =
        sessionManager.getOrCreate(ApiConstraints.ADMIN_CHANNEL, ApiConstraints.ADMIN_USER, name);
    return ApiResponse.ok(SessionApiController.toView(session));
  }

  /** 往固定会话发一条消息并触发处理（60 秒上限）。 */
  @PostMapping("/{name}/session/messages")
  public ApiResponse<ReplyView> sendSessionMessage(
      @PathVariable String name, @RequestBody SendMessageRequest request) {
    requireRegistered(name);
    String content =
        ApiConstraints.requireMessage(request == null ? null : request.content(), "消息");
    Session session =
        sessionManager.getOrCreate(ApiConstraints.ADMIN_CHANNEL, ApiConstraints.ADMIN_USER, name);
    return ApiResponse.ok(new ReplyView(agentCallExecutor.process(session, content)));
  }

  // ---------------------------------------------------------------- 记忆

  /** 该 Agent 的专属长期记忆（{@code agents/<name>/MEMORY.md}）；从未写过时返回空串而非 404。 */
  @GetMapping("/{name}/memory")
  public ApiResponse<MemoryView> memory(@PathVariable String name) {
    requireRegistered(name);
    return ApiResponse.ok(new MemoryView(name, memoryText(name)));
  }

  // ---------------------------------------------------------------- 生成 / 保存

  /** 一段需求 → 模型生成的文件草稿（不落盘、不注册）。 */
  @PostMapping("/{name}/generate-files")
  public ApiResponse<GeneratedFilesView> generateFiles(
      @PathVariable String name, @RequestBody GenerateFilesRequest request) {
    String description =
        ApiConstraints.requireMessage(request == null ? null : request.description(), "需求描述");
    return ApiResponse.ok(new GeneratedFilesView(lifecycle.generateFiles(name, description)));
  }

  /** 保存一组文件并即时生效（必须含 AGENT.md，先校验再落盘）。 */
  @PostMapping("/{name}/files")
  public ApiResponse<AgentDetailView> saveFiles(
      @PathVariable String name, @RequestBody SaveFilesRequest request) {
    Map<String, String> files = request == null ? null : request.files();
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("files 不能为空");
    }
    Profile profile = lifecycle.saveFiles(name, files);
    return ApiResponse.ok(new AgentDetailView(toView(profile), fileTree(name)));
  }

  // ---------------------------------------------------------------- 内部

  private Profile requireRegistered(String name) {
    Profile profile = profileRegistry.get(name);
    if (profile == null) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
    return profile;
  }

  private String memoryText(String name) {
    Profile profile = profileRegistry.get(name);
    String text = memoryProvider.memorySection(profile);
    return text == null ? "" : text;
  }

  private List<FileNode> fileTree(String name) {
    Path dir = agentStore.agentsDir().resolve(name).normalize();
    return listChildren(dir, "agents/" + name);
  }

  private static List<FileNode> listChildren(Path dir, String relativePath) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<FileNode> children = new ArrayList<>();
    try (Stream<Path> stream = Files.list(dir)) {
      List<Path> entries = stream.sorted(Comparator.comparing(Path::getFileName)).toList();
      for (Path entry : entries) {
        String fileName = entry.getFileName().toString();
        String path = relativePath + PATH_SEPARATOR + fileName;
        if (Files.isDirectory(entry)) {
          children.add(new FileNode(fileName, path, true, listChildren(entry, path)));
        } else if (Files.isRegularFile(entry)) {
          children.add(new FileNode(fileName, path, false, List.of()));
        }
      }
      return children;
    } catch (IOException e) {
      throw new UncheckedIOException("列出 Agent 目录失败：" + relativePath, e);
    }
  }

  private static AgentView toView(Profile profile) {
    Profile.ProviderRef provider = profile.provider();
    List<ScheduleConfigView> schedules =
        profile.schedules().stream()
            .map(s -> new ScheduleConfigView(s.id(), s.cron(), s.zone(), s.message()))
            .toList();
    return new AgentView(
        profile.name(),
        profile.description(),
        provider == null ? null : provider.name(),
        provider == null ? null : provider.model(),
        profile.tools(),
        profile.channels(),
        schedules);
  }

  /** 供文件浏览器复用：相对工作区根的路径统一用 {@code /} 分隔（Windows 上也要一致）。 */
  static String toRelativePath(Path base, Path file) {
    return base.relativize(file).toString().replace(BACKSLASH, PATH_SEPARATOR);
  }

  /** 该 Agent 是否有主定义文件（详情页展示用；缺文件时前端应提示）。 */
  static boolean hasDefinition(Path agentDir) {
    return Files.isRegularFile(agentDir.resolve(AGENT_FILE));
  }
}
