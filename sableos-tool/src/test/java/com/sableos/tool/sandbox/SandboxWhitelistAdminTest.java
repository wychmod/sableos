package com.sableos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistCommand;
import com.sableos.core.sandbox.WhitelistDuplicateException;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistEntryNotFoundException;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 管理服务的守点：CRUD、409/404、更新后新旧值切换、删除后立即拒绝、上限、快照一致性。 */
class SandboxWhitelistAdminTest {

  private InMemoryStore store;
  private WhitelistSandbox sandbox;
  private DefaultSandboxWhitelistAdmin admin;

  @BeforeEach
  void setUp() {
    store = new InMemoryStore();
    sandbox = new WhitelistSandbox(WhitelistPolicy.empty());
    admin = new DefaultSandboxWhitelistAdmin(store, sandbox);
  }

  @Test
  @DisplayName("新增后立即放行_删除后立即拒绝")
  void addThenDelete_takesEffectImmediately(@TempDir Path dir) {
    WhitelistEntry entry =
        admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, dir.toString(), "test"));

    assertThat(entry.id()).isPositive();
    assertDoesNotThrow(
        () ->
            sandbox.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("note.txt").toString())));

    admin.delete(entry.id());

    assertThrows(
        SandboxViolationException.class,
        () ->
            sandbox.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("note.txt").toString())));
  }

  @Test
  @DisplayName("重复新增返回冲突_大小写不同的域名也算重复")
  void duplicateAdd_isConflict() {
    admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "api.example.com", null));

    assertThatThrownBy(
            () ->
                admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "API.EXAMPLE.COM", null)))
        .isInstanceOf(WhitelistDuplicateException.class);
  }

  @Test
  @DisplayName("等价路径写法被判重_第二条起冲突")
  void equivalentPathForms_areDuplicates(@TempDir Path dir) {
    String base = dir.toString();
    admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, base, null));

    for (String variant : List.of(base + "/", base + "//", base + "/./", base + "/a/../")) {
      assertThatThrownBy(
              () -> admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, variant, null)))
          .as("variant: %s", variant)
          .isInstanceOf(WhitelistDuplicateException.class);
    }
  }

  @Test
  @DisplayName("查询不存在的 id 返回未找到")
  void get_missingId_throwsNotFound() {
    assertThatThrownBy(() -> admin.get(9999L)).isInstanceOf(WhitelistEntryNotFoundException.class);
  }

  @Test
  @DisplayName("更新后新值立即生效_旧值立即失效")
  void update_swapsOldValueForNew(@TempDir Path oldDir, @TempDir Path newDir) {
    WhitelistEntry created =
        admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, oldDir.toString(), null));

    admin.update(created.id(), newDir.toString(), "moved");

    assertThrows(
        SandboxViolationException.class,
        () ->
            sandbox.enforce(
                new SandboxAction(ActionType.FILE_READ, oldDir.resolve("x").toString())));
    assertDoesNotThrow(
        () ->
            sandbox.enforce(
                new SandboxAction(ActionType.FILE_READ, newDir.resolve("x").toString())));
  }

  @Test
  @DisplayName("更新撞到同类型另一条_返回冲突且原值不变")
  void update_collision_keepsOriginalValue(@TempDir Path a, @TempDir Path b) {
    WhitelistEntry first =
        admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, a.toString(), null));
    admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, b.toString(), null));

    assertThatThrownBy(() -> admin.update(first.id(), b.toString(), null))
        .isInstanceOf(WhitelistDuplicateException.class);

    assertThat(admin.get(first.id()).value())
        .isEqualTo(WhitelistRules.normalize(WhitelistType.FILE_PATH, a.toString()));
  }

  @Test
  @DisplayName("非法值被拒_且不落库不换快照")
  void invalidValue_isRejectedAndNothingChanges() {
    int before = admin.list(null).size();

    assertThatThrownBy(
            () -> admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "http://x.com", null)))
        .isInstanceOf(WhitelistValidationException.class);

    assertThat(admin.list(null)).hasSize(before);
  }

  @Test
  @DisplayName("每类达到上限后新增被拒")
  void perTypeLimit_isEnforced() {
    for (int i = 0; i < DefaultSandboxWhitelistAdmin.MAX_ENTRIES_PER_TYPE; i++) {
      admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "cmd" + i, null));
    }

    assertThatThrownBy(
            () -> admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "one-more", null)))
        .isInstanceOf(WhitelistValidationException.class)
        .hasMessageContaining("1000");

    // 上限按类型独立：别的类型仍可新增
    assertDoesNotThrow(
        () -> admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "api.example.com", null)));
  }

  @Test
  @DisplayName("列表可按类型过滤_空参数返回全部")
  void list_filtersByType() {
    admin.add(new WhitelistCommand(WhitelistType.FILE_PATH, "a", null));
    admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "ls", null));

    assertThat(admin.list(null)).hasSize(2);
    assertThat(admin.list(WhitelistType.FILE_PATH)).hasSize(1);
    assertThat(admin.list(WhitelistType.HTTP_DOMAIN)).isEmpty();
  }

  @Test
  @DisplayName("失败新增不改变运行时策略")
  void failedAdd_doesNotChangePolicy() {
    admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "ls", null));

    assertThatThrownBy(
            () -> admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "ls", null)))
        .isInstanceOf(WhitelistDuplicateException.class);

    assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    assertThrows(
        SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm")));
  }

  /** 纯内存替身：只实现契约，不牵扯 DB。 */
  static final class InMemoryStore implements SandboxWhitelistStore {

    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, WhitelistEntry> rows = new LinkedHashMap<>();

    @Override
    public List<WhitelistEntry> findAll(WhitelistType type) {
      return rows.values().stream()
          .filter(e -> type == null || e.type() == type)
          .sorted(
              (a, b) -> {
                int byType = a.type().compareTo(b.type());
                return byType != 0 ? byType : a.value().compareTo(b.value());
              })
          .toList();
    }

    @Override
    public Optional<WhitelistEntry> findById(long id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String value) {
      return rows.values().stream()
          .filter(e -> e.type() == type && e.value().equals(value))
          .findFirst();
    }

    @Override
    public WhitelistEntry insert(WhitelistType type, String value, String description) {
      if (findByTypeAndValue(type, value).isPresent()) {
        throw new WhitelistDuplicateException(type, value);
      }
      long id = seq.incrementAndGet();
      WhitelistEntry entry =
          new WhitelistEntry(id, type, value, description, Instant.now(), Instant.now());
      rows.put(id, entry);
      return entry;
    }

    @Override
    public WhitelistEntry update(long id, String value, String description) {
      WhitelistEntry existing = rows.get(id);
      if (existing == null) {
        throw new WhitelistEntryNotFoundException(id);
      }
      findByTypeAndValue(existing.type(), value)
          .filter(other -> other.id() != id)
          .ifPresent(
              other -> {
                throw new WhitelistDuplicateException(existing.type(), value);
              });
      WhitelistEntry updated =
          new WhitelistEntry(
              id, existing.type(), value, description, existing.createdAt(), Instant.now());
      rows.put(id, updated);
      return updated;
    }

    @Override
    public void delete(long id) {
      if (rows.remove(id) == null) {
        throw new WhitelistEntryNotFoundException(id);
      }
    }

    @Override
    public boolean isSeeded() {
      return false;
    }

    @Override
    public int seedIfNeeded(List<SeedEntry> entries) {
      // 测试替身不模拟首次导入
      return 0;
    }

    @Override
    public void markSeeded(int entryCount) {
      // 测试替身不落标记
    }

    @Override
    public long countByType(WhitelistType type) {
      return rows.values().stream().filter(e -> e.type() == type).count();
    }

    List<WhitelistEntry> all() {
      return new ArrayList<>(rows.values());
    }
  }
}
