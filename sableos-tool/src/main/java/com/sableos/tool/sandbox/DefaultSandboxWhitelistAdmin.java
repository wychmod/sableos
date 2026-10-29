package com.sableos.tool.sandbox;

import com.sableos.core.sandbox.SandboxWhitelistAdmin;
import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistCommand;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistEntryNotFoundException;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SandboxWhitelistAdmin} 的实现：归一 → 校验 → 判重 → 落库 → 重建快照并发布。
 *
 * <p><b>为什么写完库才换快照</b>：库是运行时事实源。若先换快照再写库，写库失败就会留下"内存已放行、库里没有"的窗口——重启后行为突变，且期间的安全策略不可审计。因此顺序固定为"库成功
 * → 换快照"；失败路径直接抛出，快照不动。
 *
 * <p><b>为什么每次重建整份快照</b>：条目是低基数配置（每类上限 1000），重建成本可忽略；换来的是快照与库永远等价，不需要维护增量更新的正确性。
 */
public class DefaultSandboxWhitelistAdmin implements SandboxWhitelistAdmin {

  /** 每类条目上限。超出按参数错误拒绝，不提供分页。 */
  public static final int MAX_ENTRIES_PER_TYPE = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSandboxWhitelistAdmin.class);

  private final SandboxWhitelistStore store;
  private final WhitelistSandbox sandbox;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：store 与 sandbox 都是容器内共享单例，构造注入共享同一引用正是依赖注入的本意（本类不返回、不修改它们的内部状态）。"
              + "撤销条件：一旦本类新增暴露这两个字段的 getter，或对它们做原地可变配置，须重新评估并移除本抑制。")
  public DefaultSandboxWhitelistAdmin(SandboxWhitelistStore store, WhitelistSandbox sandbox) {
    this.store = store;
    this.sandbox = sandbox;
  }

  @Override
  public List<WhitelistEntry> list(WhitelistType type) {
    return store.findAll(type);
  }

  @Override
  public WhitelistEntry get(long id) {
    return store.findById(id).orElseThrow(() -> new WhitelistEntryNotFoundException(id));
  }

  @Override
  public WhitelistEntry add(WhitelistCommand command) {
    String normalized = WhitelistRules.normalize(command.type(), command.value());
    requireCapacity(command.type());
    WhitelistEntry created = store.insert(command.type(), normalized, command.description());
    refreshPolicy();
    logChange("create", created);
    return created;
  }

  @Override
  public WhitelistEntry update(long id, String value, String description) {
    WhitelistEntry existing = get(id);
    String normalized = WhitelistRules.normalize(existing.type(), value);
    WhitelistEntry updated = store.update(id, normalized, description);
    refreshPolicy();
    logChange("update", updated);
    return updated;
  }

  @Override
  public void delete(long id) {
    WhitelistEntry existing = get(id);
    store.delete(id);
    refreshPolicy();
    logChange("delete", existing);
  }

  private void requireCapacity(WhitelistType type) {
    if (store.countByType(type) >= MAX_ENTRIES_PER_TYPE) {
      throw new WhitelistValidationException(type + " 类型条目数已达上限 " + MAX_ENTRIES_PER_TYPE);
    }
  }

  /** 从库重建整份策略并发布；失败即抛出，旧快照保持不变。 */
  private void refreshPolicy() {
    List<WhitelistEntry> all = store.findAll(null);

    List<Path> roots =
        all.stream()
            .filter(e -> e.type() == WhitelistType.FILE_PATH)
            .map(e -> Path.of(e.value()).toAbsolutePath().normalize())
            .toList();
    Set<String> commands = new LinkedHashSet<>();
    List<String> domains = new java.util.ArrayList<>();
    for (WhitelistEntry entry : all) {
      if (entry.type() == WhitelistType.SHELL_COMMAND) {
        commands.add(entry.value());
      } else if (entry.type() == WhitelistType.HTTP_DOMAIN) {
        domains.add(entry.value());
      }
    }
    sandbox.refresh(new WhitelistPolicy(roots, commands, domains));
  }

  /**
   * 记录一次增删改。
   *
   * <p>findsecbugs 的 {@code CRLF_INJECTION_LOGS} 无法追踪"参数已在 {@link #sanitize} 中净化"，对任何带动态参数的日志调用都报
   * Low； 本方法内每个可变参数要么是枚举/数值，要么已经过 {@code replaceAll("[\\r\\n]", "_")}，故做定点抑制。撤销条件：改为直接拼接未净化的用户输入。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "确认误报：本方法内进入日志的动态值只有 type（枚举）、id（数值）与 sanitize(value) 的结果；"
              + "sanitize 用 replaceAll(\"[\\\\r\\\\n]\", \"_\") 移除 CR/LF，不留换行注入面。")
  private static void logChange(String operation, WhitelistEntry entry) {
    LOG.info("sandbox whitelist {}: type={}, id={}", operation, entry.type(), entry.id());
    LOG.info("sandbox whitelist value: {}", sanitize(entry.value()));
  }

  /** 用户可控字符串入日志前净化 CR/LF，防止伪造日志行（findsecbugs 只认 replaceAll 形态）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n]", "_");
  }
}
