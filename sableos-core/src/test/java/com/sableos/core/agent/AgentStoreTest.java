package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 30 节 harness：AgentStore 的边界——写入防穿越、归档绝不覆盖、回滚删除幂等、名字校验。 */
class AgentStoreTest {

  @TempDir Path root;

  private AgentStore store() {
    return new AgentStore(root);
  }

  @Test
  @DisplayName("writeAll 写出整组文件，相对路径按目录结构落盘")
  void writeAll_writesNestedFiles() throws IOException {
    Path dir =
        store().writeAll("demo", java.util.Map.of("AGENT.md", "x", "scripts/a.py", "print(1)"));

    assertThat(dir).isEqualTo(root.resolve("agents").resolve("demo"));
    assertThat(Files.readString(dir.resolve("AGENT.md"))).isEqualTo("x");
    assertThat(Files.readString(dir.resolve("scripts/a.py"))).isEqualTo("print(1)");
  }

  @Test
  @DisplayName("writeAll 拒绝跳出 Agent 目录的相对路径")
  void writeAll_rejectsTraversal() {
    assertThatThrownBy(() -> store().writeAll("demo", java.util.Map.of("../escape.md", "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("越出 Agent 目录");
  }

  @Test
  @DisplayName("writeAll 拒绝绝对路径")
  void writeAll_rejectsAbsolutePath() {
    assertThatThrownBy(() -> store().writeAll("demo", java.util.Map.of("/tmp/x.md", "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("相对路径");
  }

  @Test
  @DisplayName("archive 同名不覆盖：追加时间戳后缀")
  void archive_neverOverwrites() throws IOException {
    AgentStore store = store();
    store.writeAll("demo", java.util.Map.of("AGENT.md", "first"));
    Path first = store.archive("demo");

    store.writeAll("demo", java.util.Map.of("AGENT.md", "second"));
    Path second = store.archive("demo");

    assertThat(first).isEqualTo(root.resolve("archive").resolve("demo"));
    assertThat(second).isNotEqualTo(first);
    assertThat(Files.readString(first.resolve("AGENT.md"))).isEqualTo("first");
    assertThat(Files.readString(second.resolve("AGENT.md"))).isEqualTo("second");
  }

  @Test
  @DisplayName("delete 幂等：不存在时静默")
  void delete_isIdempotent() {
    AgentStore store = store();
    store.delete(root.resolve("agents").resolve("ghost"));
    store.delete(null);
  }

  @Test
  @DisplayName("delete 拒绝删除 agents/ 之外的路径")
  void delete_rejectsOutsideAgentsDir() {
    assertThatThrownBy(() -> store().delete(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出 Agent 目录范围");
  }

  @Test
  @DisplayName("isValidName 只放行安全目录段")
  void isValidName_boundaries() {
    assertThat(AgentStore.isValidName("demo")).isTrue();
    assertThat(AgentStore.isValidName("ops_1")).isTrue();
    assertThat(AgentStore.isValidName("a-b.c")).isTrue();
    assertThat(AgentStore.isValidName("..")).isFalse();
    assertThat(AgentStore.isValidName(".")).isFalse();
    assertThat(AgentStore.isValidName("../evil")).isFalse();
    assertThat(AgentStore.isValidName("a/b")).isFalse();
    assertThat(AgentStore.isValidName("_leading")).isFalse();
    assertThat(AgentStore.isValidName("")).isFalse();
    assertThat(AgentStore.isValidName(null)).isFalse();
    assertThat(AgentStore.isValidName("a".repeat(65))).isFalse();
  }

  @Test
  @DisplayName("resolveWithin 只放行 agents/ 与 archive/ 下的路径")
  void resolveWithin_boundaries() {
    AgentStore store = store();

    assertThat(store.resolveWithin("agents/demo/AGENT.md"))
        .isEqualTo(root.resolve("agents").resolve("demo").resolve("AGENT.md"));
    assertThat(store.resolveWithin("archive/demo"))
        .isEqualTo(root.resolve("archive").resolve("demo"));

    assertThatThrownBy(() -> store.resolveWithin("../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.resolveWithin("/etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("相对");
    assertThatThrownBy(() -> store.resolveWithin("sableos.db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只允许浏览");
    assertThatThrownBy(() -> store.resolveWithin("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("readAll 返回目录内全部文本文件，跳过数据库文件")
  void readAll_skipsBinary() throws IOException {
    AgentStore store = store();
    store.writeAll("demo", java.util.Map.of("AGENT.md", "body", "scripts/a.py", "code"));
    Files.writeString(root.resolve("agents/demo/sableos.db"), "binary");

    assertThat(store.readAll("demo"))
        .containsEntry("AGENT.md", "body")
        .containsEntry("scripts/a.py", "code")
        .doesNotContainKey("sableos.db");
  }
}
