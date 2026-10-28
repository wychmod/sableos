package com.sableos.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模块级架构约束：把《技术方案》第 13 章的分层口径钉成 CI 红线。
 *
 * <p>为什么放在 {@code sableos-boot}：ArchUnit 只能看到<strong>当前模块 classpath 上的类</strong>。boot 是唯一依赖全部 8
 * 个业务模块的汇编模块，只有它的测试类路径同时包含 core / provider / storage / memory / tool / web / cli /
 * channel-cli，跨模块的依赖方向才能被观察到。放在 core 或 tool 里都只能看到半个仓库，规则会静默空转。
 *
 * <p>{@code @AnalyzeClasses} 负责"导一次、缓存、供多条规则复用"：{@code packages} 圈定 {@code com.sableos}， {@code
 * importOptions} 排除 {@code target/test-classes}（同包测试类不该被当成被约束对象）。
 *
 * <p><strong>新增模块时请同步本类</strong>：新模块若不在 {@link #NON_CORE_MODULES} 里，"core 不反向依赖"这条规则对它
 * 无效；切片规则会自动覆盖到它，但切片名可能与预期不符（如 {@code com.sableos.channel.cli} 的切片名是 {@code channel}）。
 */
@AnalyzeClasses(packages = "com.sableos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** core 之外的 8 个模块包。新增模块必须补进本数组，否则规则对它不生效。 */
  private static final String[] NON_CORE_MODULES = {
    "com.sableos.provider..",
    "com.sableos.storage..",
    "com.sableos.memory..",
    "com.sableos.tool..",
    "com.sableos.web..",
    "com.sableos.cli..",
    "com.sableos.channel.cli..",
    "com.sableos.boot.."
  };

  @ArchTest
  static final ArchRule CORE_MUST_NOT_DEPEND_ON_OTHER_MODULES =
      noClasses()
          .that()
          .resideInAPackage("com.sableos.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_CORE_MODULES)
          .because("core 是运行时内核，被所有能力模块依赖；一旦反向依赖就会立刻形成环，" + "内核也无法脱离具体能力单独演进（技术方案 §13 的分层口径）");

  @ArchTest
  static final ArchRule MODULES_MUST_BE_FREE_OF_CYCLES =
      SlicesRuleDefinition.slices()
          .matching("com.sableos.(*)..")
          .should()
          .beFreeOfCycles()
          .because("模块间循环依赖会让独立演进、单独测试与按需裁剪全部失效；" + "当前形状应是 core 为根、各能力模块单向依赖、boot 只向下汇编");

  @ArchTest
  static final ArchRule MUST_NOT_USE_JAVA_UTIL_LOGGING =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because(
          "项目统一 logback + SLF4J（AGENTS §5.3）；直接使用 JUL 会绕开结构化日志与 traceId 上下文");

  /** 9 个模块的根包。新增模块时补进来，守卫方法会检查每个包都真的被扫到了。 */
  private static final String[] MODULE_PACKAGES = {
    "com.sableos.core",
    "com.sableos.provider",
    "com.sableos.storage",
    "com.sableos.memory",
    "com.sableos.tool",
    "com.sableos.web",
    "com.sableos.cli",
    "com.sableos.channel.cli",
    "com.sableos.boot"
  };

  /**
   * 防空转守卫：ArchUnit 的规则在"没有被约束的目标"时会静默通过。若某个模块改了包名、或 boot 的依赖被误删， 上面三条规则会变成一句废话却依然绿灯。这里显式要求 9
   * 个模块都至少有一个类被导入。
   */
  @ArchTest
  static void IMPORTED_SCOPE_MUST_COVER_ALL_MODULES(JavaClasses classes) {
    Set<String> classNames = classes.stream().map(JavaClass::getName).collect(Collectors.toSet());
    List<String> missing =
        Arrays.stream(MODULE_PACKAGES)
            .filter(pkg -> classNames.stream().noneMatch(name -> name.startsWith(pkg + ".")))
            .toList();

    assertThat(missing).as("以下模块没有任何类被导入——包名可能已变更或该模块已不在 boot 依赖里，架构规则会静默失效").isEmpty();
  }
}
