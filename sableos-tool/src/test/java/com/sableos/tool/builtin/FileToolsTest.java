package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import com.sableos.tool.sandbox.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FileTools 的回归测试：正常路径 + 白名单拦截 + 唯一编辑点 + grep/glob 上限与跳过语义。 */
class FileToolsTest {

  private final FileTools tools = new FileTools(new PermissiveSandbox());

  @Test
  @DisplayName("read_file 返回文件全文")
  void readFile_returnsFileContent(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("note.txt");
    Files.writeString(file, "你好，SableOS");

    assertEquals("你好，SableOS", tools.readFile(file.toString()));
  }

  @Test
  @DisplayName("write_file 覆盖写入内容")
  void writeFile_writesContent(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("out.txt");

    String result = tools.writeFile(file.toString(), "written");

    assertEquals("已写入: " + file, result);
    assertEquals("written", Files.readString(file));
  }

  @Test
  @DisplayName("list_dir 按名称排序、换行连接")
  void listDir_listsSortedNames(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("c.txt"), "c");
    Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(dir.resolve("b.txt"), "b");

    assertEquals("a.txt\nb.txt\nc.txt", tools.listDir(dir.toString()));
  }

  @Test
  @DisplayName("read_file 命中白名单外路径应被拦下（文件零接触）")
  void readFile_outsideWhitelistIsBlocked(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("secret.txt");
    Files.writeString(file, "top-secret");
    AtomicReference<SandboxAction> seen = new AtomicReference<>();
    Sandbox denying =
        action -> {
          seen.set(action);
          throw new SandboxViolationException("路径不在白名单");
        };
    FileTools guarded = new FileTools(denying);

    assertThrows(SandboxViolationException.class, () -> guarded.readFile(file.toString()));
    assertEquals(ActionType.FILE_READ, seen.get().type(), "校验动作应为 FILE_READ");
    assertEquals(file.toString(), seen.get().target());
    assertEquals("top-secret", Files.readString(file), "拦截后文件不得被碰");
  }

  @Test
  @DisplayName("edit_file 唯一匹配点被替换")
  void editFile_replacesUniqueOccurrence(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("app.yaml");
    Files.writeString(file, "name: old\nport: 8080\n");

    String result = tools.editFile(file.toString(), "old", "new");

    assertEquals("已编辑: " + file, result);
    assertEquals("name: new\nport: 8080\n", Files.readString(file));
  }

  @Test
  @DisplayName("edit_file 原文本未找到_报错且文件零字节改动")
  void editFile_oldStringNotFoundFailsWithoutWriting(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("app.yaml");
    Files.writeString(file, "name: old\n");

    assertThrows(
        IllegalArgumentException.class, () -> tools.editFile(file.toString(), "missing", "new"));

    assertEquals("name: old\n", Files.readString(file));
  }

  @Test
  @DisplayName("edit_file 原文本多处出现_报错且文件零字节改动")
  void editFile_multipleOccurrencesFailWithoutWriting(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("app.yaml");
    Files.writeString(file, "key: v\nkey: v\n");

    assertThrows(
        IllegalArgumentException.class, () -> tools.editFile(file.toString(), "key: v", "key: w"));

    assertEquals("key: v\nkey: v\n", Files.readString(file));
  }

  @Test
  @DisplayName("grep 命中返回 文件:行号:内容")
  void grep_returnsPathLineContent(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, "alpha\nneedle here\nomega\n");

    String result = tools.grep("needle", dir.toString());

    assertEquals(file + ":2:needle here", result);
  }

  @Test
  @DisplayName("grep 超过 200 条_截断并标注")
  void grep_truncatesAtTwoHundredWithNote(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("big.log");
    Files.writeString(
        file, IntStream.range(0, 250).mapToObj(i -> "hit " + i + "\n").reduce("", String::concat));

    String result = tools.grep("hit", dir.toString());

    assertTrue(result.contains("已达 200 条上限"));
    assertEquals(201, result.split("\n").length, "200 条命中 + 1 条截断标注");
  }

  @Test
  @DisplayName("grep 二进制/非 UTF-8 文件跳过不中断")
  void grep_skipsBinaryAndNonUtf8Files(@TempDir Path dir) throws IOException {
    Path good = dir.resolve("good.txt");
    Files.writeString(good, "needle\n");
    Path binary = dir.resolve("binary.bin");
    byte[] invalidUtf8 =
        new byte[] {'n', 'e', 'e', 'd', 'l', 'e', '\n', (byte) 0xC3, 0x28, (byte) 0xFF};
    Files.write(binary, invalidUtf8);

    String result = tools.grep("needle", dir.toString());

    assertTrue(result.contains(good.toString()), "正常文件仍被搜到");
    assertFalse(result.contains(binary.toString()), "二进制文件应被跳过");
  }

  @Test
  @DisplayName("glob 递归模式命中多层文件（含根下单段补匹配）")
  void glob_matchesRecursivePattern(@TempDir Path dir) throws IOException {
    Path top = dir.resolve("top.txt");
    Files.writeString(top, "top");
    Path nested = dir.resolve("sub").resolve("inner.txt");
    Files.createDirectories(nested.getParent());
    Files.writeString(nested, "inner");
    Files.writeString(dir.resolve("skip.md"), "skip");

    String result = tools.glob("**/*.txt", dir.toString());

    assertTrue(result.contains(top.toString()), "根下单段文件需补匹配");
    assertTrue(result.contains(nested.toString()));
    assertFalse(result.contains("skip.md"));
  }

  @Test
  @DisplayName("glob 超过 200 条_截断并标注")
  void glob_truncatesAtTwoHundredWithNote(@TempDir Path dir) throws IOException {
    for (int i = 0; i < 250; i++) {
      Files.writeString(dir.resolve("f" + i + ".txt"), "x");
    }

    String result = tools.glob("*.txt", dir.toString());

    assertTrue(result.contains("已达 200 条上限"));
    assertEquals(201, result.split("\n").length, "200 条命中 + 1 条截断标注");
  }

  @Test
  @DisplayName("grep 无命中_返回占位文案")
  void grep_noMatchReturnsPlaceholder(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("a.txt"), "aaa");

    assertEquals("（无匹配）", tools.grep("zzz", dir.toString()));
  }

  @Test
  @DisplayName("write_file 父目录缺失_失败点名路径（不自动建目录）")
  void writeFile_missingParentFailsLoud(@TempDir Path dir) {
    Path target = dir.resolve("no-such-dir").resolve("out.txt");

    assertThrows(RuntimeException.class, () -> tools.writeFile(target.toString(), "x"));
    assertFalse(Files.exists(target));
  }
}
