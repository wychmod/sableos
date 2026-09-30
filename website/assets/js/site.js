/* ==========================================================================
   SableOS · Site behaviour
   --------------------------------------------------------------------------
   只做这几件小事：主题记忆、中英切换、导航滚动态、锚点高亮、进场动画 + 代码复制 + Tabs。
   全部为渐进增强：JS 不可用时页面依然完整可读（中文原样展示）。
   ========================================================================== */
(function () {
  "use strict";

  /* ======================================================================
     站点配置 —— 迭代时只改这里，全站外链自动生效。
     仓库改名 / 换组织 / 迁移到 sableos.dev 都只需要动这一段。
     ====================================================================== */
  var SITE = {
    repo: "https://github.com/wychmod/sableos",
    branch: "main",
    license: "https://www.apache.org/licenses/LICENSE-2.0",
  };

  function repoBlob(relPath) {
    return SITE.repo + "/blob/" + SITE.branch + "/" + relPath.replace(/^\/+/, "");
  }

  function resolveLinks() {
    document.querySelectorAll("[data-repo]").forEach(function (el) {
      el.setAttribute("href", SITE.repo);
      el.setAttribute("rel", "noopener");
    });
    document.querySelectorAll("[data-issues]").forEach(function (el) {
      el.setAttribute("href", SITE.repo + "/issues");
      el.setAttribute("rel", "noopener");
    });
    document.querySelectorAll("[data-license]").forEach(function (el) {
      el.setAttribute("href", SITE.license);
      el.setAttribute("rel", "noopener");
    });
    document.querySelectorAll("[data-doc]").forEach(function (el) {
      var p = el.getAttribute("data-doc");
      if (!p) return;
      el.setAttribute(
        "href",
        /^https?:/.test(p) ? p : repoBlob(p)
      );
      el.setAttribute("rel", "noopener");
    });
    // 站外链接统一补 target / rel，避免逐个手写遗漏
    document
      .querySelectorAll('a[href^="http"]')
      .forEach(function (a) {
        a.setAttribute("rel", "noopener noreferrer");
        if (!a.hasAttribute("target")) a.setAttribute("target", "_blank");
      });
  }

  var root = document.documentElement;
  root.classList.add("js");

  var body = document.body;
  var THEME_KEY = "sableos-theme";
  var LANG_KEY = "sableos-lang";

  // 语言状态要在主题模块之前定好：主题按钮的 aria-label 跟随当前语言。
  // ?lang=en|zh 与 ?theme= 同一套规则：URL 优先级最高、只作用于本次打开、不写回偏好。
  var lang = "zh-CN";
  var langLockedByUrl = false;
  try {
    var lm = /[?&]lang=(en|zh)(?:&|$)/.exec(window.location.search);
    if (lm) {
      lang = lm[1] === "en" ? "en" : "zh-CN";
      langLockedByUrl = true;
    }
  } catch (e) {
    /* 忽略 */
  }
  if (!langLockedByUrl) {
    try {
      var savedLang = localStorage.getItem(LANG_KEY);
      if (savedLang === "en" || savedLang === "zh-CN") lang = savedLang;
    } catch (e) {
      /* 隐私模式下忽略 */
    }
  }

  /* ---------- 1. 外链与文档路径 ---------- */
  resolveLinks();

  /* ---------- 2. 主题 ---------- */
  function applyTheme(theme) {
    body.setAttribute("data-theme", theme);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) {
      meta.setAttribute("content", theme === "dark" ? "#131110" : "#1D1815");
    }
    document.querySelectorAll("[data-theme-toggle]").forEach(function (btn) {
      var label =
        lang === "en"
          ? "Switch to " + (theme === "dark" ? "light" : "dark") + " mode"
          : "切换到" + (theme === "dark" ? "浅色" : "深色") + "模式";
      btn.setAttribute("aria-label", label);
      btn.setAttribute("title", label);
    });
  }

  var stored = null;
  // URL 上的 ?theme=light|dark 优先级最高：用于评审台内嵌预览、分享链接、截图脚本。
  // 只作用于本次打开，不写回 localStorage —— 避免"点开一个 dark 链接就把偏好改掉"。
  try {
    var m = /[?&]theme=(light|dark)(?:&|$)/.exec(window.location.search);
    if (m) stored = m[1];
  } catch (e) {
    stored = null;
  }
  if (!stored) {
    try {
      stored = localStorage.getItem(THEME_KEY);
    } catch (e) {
      stored = null;
    }
  }
  if (!stored) {
    stored =
      window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
  }
  applyTheme(stored);

  document.querySelectorAll("[data-theme-toggle]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var next = body.getAttribute("data-theme") === "dark" ? "light" : "dark";
      applyTheme(next);
      try {
        localStorage.setItem(THEME_KEY, next);
      } catch (e) {
        /* 隐私模式下忽略 */
      }
    });
  });

  /* ---------- 3. 导航滚动态 ---------- */
  var nav = document.querySelector(".sd-nav");
  function onScroll() {
    if (!nav) return;
    nav.setAttribute("data-scrolled", window.scrollY > 8 ? "true" : "false");
  }
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  /* ---------- 4. 进场动画 ---------- */
  var reveals = Array.prototype.slice.call(
    document.querySelectorAll(".sd-reveal")
  );
  var reduceMotion =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if (reduceMotion || !("IntersectionObserver" in window)) {
    reveals.forEach(function (el) {
      el.setAttribute("data-visible", "true");
    });
  } else {
    var io = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          var el = entry.target;
          var group = el.parentElement
            ? Array.prototype.indexOf.call(
                el.parentElement.children,
                el
              )
            : 0;
          el.style.setProperty(
            "--reveal-delay",
            Math.min(group, 5) * 70 + "ms"
          );
          el.setAttribute("data-visible", "true");
          io.unobserve(el);
        });
      },
      { rootMargin: "0px 0px -12% 0px", threshold: 0.08 }
    );
    reveals.forEach(function (el) {
      io.observe(el);
    });
  }

  /* ---------- 5. 锚点高亮 ---------- */
  var navLinks = Array.prototype.slice.call(
    document.querySelectorAll('.sd-nav__link[href^="#"]')
  );
  var sections = navLinks
    .map(function (link) {
      var id = link.getAttribute("href").slice(1);
      return id ? document.getElementById(id) : null;
    })
    .filter(Boolean);

  if (sections.length && "IntersectionObserver" in window) {
    var spy = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          var id = entry.target.id;
          navLinks.forEach(function (link) {
            var active = link.getAttribute("href") === "#" + id;
            if (active) {
              link.setAttribute("aria-current", "true");
            } else {
              link.removeAttribute("aria-current");
            }
          });
        });
      },
      { rootMargin: "-72px 0px -62% 0px", threshold: 0 }
    );
    sections.forEach(function (sec) {
      spy.observe(sec);
    });
  }

  /* ---------- 6. 代码复制 ---------- */
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var wrap = btn.closest(".sd-code");
      var code = wrap ? wrap.querySelector("pre code") : null;
      if (!code) return;
      var text = code.innerText;
      var done = function () {
        btn.setAttribute("data-copied", "true");
        var label = btn.querySelector("[data-copy-label]");
        if (label) {
          var original = label.textContent;
          label.textContent = lang === "en" ? "Copied" : "已复制";
          window.setTimeout(function () {
            label.textContent = original;
            btn.removeAttribute("data-copied");
          }, 1600);
        }
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () {});
      } else {
        var ta = document.createElement("textarea");
        ta.value = text;
        ta.setAttribute("readonly", "");
        ta.style.position = "absolute";
        ta.style.left = "-9999px";
        document.body.appendChild(ta);
        ta.select();
        try {
          document.execCommand("copy");
          done();
        } catch (e) {
          /* 忽略 */
        }
        document.body.removeChild(ta);
      }
    });
  });

  /* ---------- 7. Tabs（可选，仅在存在 .sd-tabs 时生效） ---------- */
  document.querySelectorAll(".sd-tabs").forEach(function (tabs) {
    var tabList = tabs.querySelector('[role="tablist"]');
    var panelWrap = tabs.querySelector(".sd-tabs__panels");
    if (!tabList || !panelWrap) return;

    var tabEls = Array.prototype.slice.call(
      tabList.querySelectorAll('[role="tab"]')
    );
    var panels = Array.prototype.slice.call(
      panelWrap.querySelectorAll('[role="tabpanel"]')
    );
    if (!tabEls.length || tabEls.length !== panels.length) return;

    panels.forEach(function (panel, i) {
      panel.hidden = i !== 0;
    });

    function select(index) {
      tabEls.forEach(function (tab, i) {
        tab.setAttribute("aria-selected", i === index ? "true" : "false");
        tab.setAttribute("tabindex", i === index ? "0" : "-1");
      });
      panels.forEach(function (panel, i) {
        panel.hidden = i !== index;
      });
    }

    tabEls.forEach(function (tab, i) {
      tab.addEventListener("click", function () {
        select(i);
      });
      tab.addEventListener("keydown", function (e) {
        var dir = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
        if (!dir) return;
        e.preventDefault();
        var next = (i + dir + tabEls.length) % tabEls.length;
        select(next);
        tabEls[next].focus();
      });
    });

    select(0);
  });

  /* ---------- 8. 年份 ---------- */
  document.querySelectorAll("[data-year]").forEach(function (el) {
    el.textContent = String(new Date().getFullYear());
  });

  /* ---------- 9. 语言（中/英切换） ----------
     中文是源语言：HTML 里的原文即中文文案，不进词典，切回中文走快照恢复。
     标记三种：data-i18n（纯文本）、data-i18n-html（含 <code>/<a>/<strong> 等
     自有静态标记的条目，值由本文件维护、无注入面）、data-i18n-attr="属性:键"。
     维护规则：index.html 改文案/增删元素时同步维护 I18N_EN 的键；
     缺键时该条保留中文并 console.warn 一次，方便发现漏翻。 */
  var I18N_EN = {
    "meta.title": "SableOS · The Java-native, privately auditable Agent OS",
    "meta.desc":
      "SableOS is an Agent OS that enterprises fully control — Java-native and privately auditable. One directory defines an Agent; one runtime runs a fleet of Agents.",
    "meta.ogDesc":
      "One directory defines an Agent, one runtime runs a fleet of Agents. Deployed on your own infrastructure — your data never leaves your domain.",

    "a11y.skip": "Skip to main content",
    "a11y.brandHome": "SableOS home",
    "a11y.pageSections": "Page sections",
    "a11y.termDemo": "SableOS command-line demo",
    "a11y.agentmdTabs": "AGENT.md example",

    "nav.why": "Why",
    "nav.positioning": "Positioning",
    "nav.core": "Core capabilities",
    "nav.architecture": "Architecture",
    "nav.quickstart": "Quick start",
    "nav.roadmap": "Roadmap",
    "nav.docs": "Docs",

    "hero.badge.doc": "Docs-first · preview",
    "hero.title":
      "Let every enterprise run its own <em>Agents</em> in natural language",
    "hero.slogan":
      "One directory defines an Agent. One runtime runs a fleet of Agents.",
    "hero.lead":
      "SableOS is a <strong>fully enterprise-controlled, Java-native, privately auditable Agent OS</strong>. It installs on your own K8s clusters, servers or bare metal and runs business Agents as a unified foundation — sharing channel access, model routing, memory, tool calling and security auditing. Business teams write no Agent backend code; they configure Agents and write Tools.",
    "hero.tag.nl": "Natural language (md)",
    "hero.tag.knowledge": "Knowledge base",
    "hero.tag.formula": "= an Agent",
    "hero.action.architecture": "See the architecture",
    "hero.stat.core.unit": " ",
    "hero.stat.core.label": "Core capabilities",
    "hero.stat.modules.unit": " ",
    "hero.stat.modules.label": "Maven modules",
    "hero.stat.jar.unit": " ",
    "hero.stat.jar.label": "Executable JAR",
    "hero.stat.code.unit": "lines",
    "hero.stat.code.label": "Agent backend code",

    "term.init": "Workspace initialized: .sableos/",
    "term.generated": "Created .sableos/agents/ops-bot/AGENT.md",
    "term.ask": "› Check the production services for me",
    "term.toolCall": "· Calling tool http_get …",
    "term.result":
      "✓ 1 of 3 services timed out; inspection report generated",
    "hero.note.title": "Docs-first stage",
    "hero.note.text":
      'The commands above follow the Technical Solution and become available once the core phase ships. The repo currently contains a 9-module skeleton; <code class="sd-inline-code">mvn clean package</code> passes.',

    "why.eyebrow": "Why SableOS",
    "why.title": "Most Agents stall at demos, stuck on four thresholds",
    "why.lead":
      "SableOS removes all four at once. The deeper judgment: the bottleneck for reliable Agents in production is usually not the model itself, but the environment the Agent runs in.",
    "why.c1.num": "Threshold 01",
    "why.c1.title": "Defining an Agent requires code",
    "why.c1.bad":
      "The people who understand the business best can't build it — separated by a development schedule",
    "why.c1.good":
      "A directory plus an AGENT.md file is an Agent — no code required",
    "why.c2.num": "Threshold 02",
    "why.c2.title": "Your data ends up on a cloud platform",
    "why.c2.bad": "Compliance says no; sensitive workloads simply can't ship",
    "why.c2.good":
      "Private deployment, data never leaves your domain, no cloud lock-in",
    "why.c3.num": "Threshold 03",
    "why.c3.title": "Execution is a black box",
    "why.c3.bad":
      "No audit, no whitelist, no approval — enterprises dare not go to production",
    "why.c3.good":
      "Enforced sandboxing plus end-to-end auditing — security is in the architecture from day one",
    "why.c4.num": "Threshold 04",
    "why.c4.title": "Running one is easy; running a fleet is hard",
    "why.c4.bad": "A missing layer: an operating system for a fleet of Agents",
    "why.c4.good": "Lifecycle and governance built for an entire fleet of Agents",

    "pos.eyebrow": "Ecosystem layers",
    "pos.title": "SableOS holds the runtime layer",
    "pos.lead":
      "Frameworks give you code and leave the runtime to you; orchestration platforms give you flows that run on top of a runtime; SableOS is the runtime itself.",
    "pos.c1.title": "Framework layer",
    "pos.c1.text":
      "Spring AI, LangChain4j, etc. Code-level building blocks: Provider abstraction, Tool schema generation.",
    "pos.c1.gap":
      "Runtime environment, reasoning loop, tool governance and auditing remain DIY",
    "pos.c2.title": "Orchestration layer",
    "pos.c2.text":
      "Dify, Coze, etc. Visual flow orchestration, running on top of some runtime.",
    "pos.c2.gap":
      "Limited private deployment and compliance; limited code-level extensibility",
    "pos.c3.title": "Runtime layer · SableOS",
    "pos.c3.text":
      "The foundation that keeps Agents running persistently, governed and auditable, with native support for MCP, A2A and other open protocols.",
    "pos.c3.gap": "You only write business Tools — the foundation does the rest",
    "pos.note.title": "Why it must be Java",
    "pos.note.text":
      "The two proven open-source Agent OS projects — OpenClaw (Node.js) and Hermes Agent (Python) — are not Java. Yet Java is the de facto standard for enterprise backends: Spring Boot plugs directly into Nacos, Sentinel, SkyWalking, Prometheus and the rest of the ops stack; Tools can call existing enterprise Java services directly; and private deployment with compliance auditing in strictly regulated industries can follow existing processes.",

    "core.eyebrow": "Runtime kernel",
    "core.title": "Five core capabilities",
    "core.lead":
      "Engine and capabilities, capabilities and the outside world — all decoupled through abstract interfaces. New channels, providers and tools in the extension phase plug in at the edges; the core engine stays untouched.",
    "core.c1.title": "LLM access",
    "core.c1.text":
      "A Provider abstraction connects mainstream LLMs and local inference (Ollama, vLLM, etc.). Multiple providers coexist via explicit mapping — switch at runtime, no lock-in.",
    "core.c2.title": "ReAct loop",
    "core.c2.text":
      "A self-implemented reasoning engine: the LLM decides whether and which tool to call; results feed back into the loop until a final answer or the iteration cap (default 10).",
    "core.c3.title": "Memory",
    "core.c3.text":
      'One facade for session and long-term memory. Long-term memory persists to <code class="sd-inline-code">.sableos/memory/MEMORY.md</code> by default, with pluggable backends (Markdown / SQLite / self-hosted Mem0).',
    "core.c4.title": "Tool system",
    "core.c4.text":
      'Nine built-in Tools plus three tiers of Plugin Tool integration; an MCP client connects external tools. File, shell, HTTP and notification calls pass <code class="sd-inline-code">Sandbox</code> whitelist checks, and every call lands in the <code class="sd-inline-code">tool_invocations</code> audit trail.',
    "core.c5.title": "Web Service",
    "core.c5.text":
      "Every capability is exposed over REST API — 10 endpoints in the core phase. Business systems integrate over HTTP in any language.",
    "core.c6.title": "Three evolution tracks",
    "core.c6.text":
      "Single-node runtime kernel → distributed foundation → cross-node Agent collaboration. Horizontal capabilities (multi-tenancy, SSO, full audit queries, observability) land alongside each phase.",

    "feat.eyebrow": "Key features",
    "feat.title": "Nine product principles",
    "feat.lead":
      "From definition to deployment to security boundaries — each maps to a design principle.",
    "feat.c1.title": "A directory is an Agent",
    "feat.c1.text":
      'A directory containing <code class="sd-inline-code">AGENT.md</code> defines an Agent — no code — and multiple Agents coexist in one instance.',
    "feat.c2.title": "Java-native",
    "feat.c2.text":
      "Built on JDK 21 and Spring Boot 3.x, deployed as a single executable JAR, reusing your existing Java ops toolchain.",
    "feat.c3.title": "Private and controlled",
    "feat.c3.text":
      "Installs on your own K8s, VMs or bare metal. Data stays in your domain; no cloud lock-in.",
    "feat.c4.title": "Security isolation",
    "feat.c4.text":
      "Tool calls pass file, command and network whitelist checks with enforced sandboxing; credentials stay in your enterprise secret store; everything is auditable.",
    "feat.c5.title": "Self-implemented ReAct",
    "feat.c5.text":
      "The core reasoning loop is implemented in-house — no external Agent framework, mechanics fully under control.",
    "feat.c6.title": "Open standards",
    "feat.c6.text":
      "MCP for tools, A2A for Agent collaboration, Anthropic Agent Skills as the Agent directory format — interoperating with the ecosystem rather than inventing protocols.",
    "feat.c7.title": "Three-tier tool extension",
    "feat.c7.text":
      "Zero-code Agent directory with MCP reuse, light-code custom MCP servers, or heavy-code native Java methods — choose by threshold.",
    "feat.c8.title": "Cross-session memory",
    "feat.c8.text":
      "Session plus long-term memory in two layers, with pluggable long-term backends, so Agents remember preferences and key facts.",
    "feat.c9.title": "Stateless and scalable",
    "feat.c9.text":
      "Stateless runtime instances with externalized state — the architecture leaves room for distributed evolution from the start.",

    "arch.eyebrow": "Architecture",
    "arch.title": "One message, processed in four layers",
    "arch.lead":
      "The stack in one line: JDK 21 + Spring Boot 3.x + Spring AI Alibaba + a self-implemented ReAct loop + SQLite + Picocli.",
    "arch.l1": "Access layer",
    "arch.l2": "Engine layer",
    "arch.l3": "Capability layer",
    "arch.l4": "Foundation layer",
    "arch.chip.scheduler": "AgentScheduler (cron triggers)",
    "arch.chip.ws": "Web Service (REST API)",
    "arch.chip.agentService": "AgentService (unified entry)",
    "arch.chip.reactLoop": "ReActLoop (self-implemented)",
    "arch.chip.sqlite": "SQLite (Session / tool_invocations / llm_calls)",
    "arch.chip.fs": ".sableos/ filesystem",
    "arch.mavenTitle": "Project structure · Maven multi-module",
    "arch.tbl.caption":
      "Per chapter 10 of the Technical Solution: 14 modules targeted; 9 in place today.",
    "arch.tbl.thModule": "Module",
    "arch.tbl.thRole": "Responsibility",
    "arch.tbl.thStatus": "Status",
    "arch.status.now": "Skeleton",
    "arch.status.planned": "Planned",
    "arch.r.core":
      "Core abstractions and interfaces: SableTool, Profile, ContextLoader, ReActLoop, PromptBuilder, ToolExecutor, AgentService, AgentScheduler",
    "arch.r.provider":
      "Provider service, Function Calling adapter, explicit provider-name-to-ChatModel mapping",
    "arch.r.memory": "Memory facade, long-term memory, save_memory / recall_memory tools",
    "arch.r.tool":
      "Built-in Tools, MCP client, ToolRegistry, Sandbox and notify adapters (3-in-1)",
    "arch.r.storage": "Persistence: Session, tool-call and LLM-call audit records",
    "arch.r.web": "Web server, six ApiControllers, OpenAPI docs",
    "arch.r.cli": "CLI entry: Picocli, ConfigLoader",
    "arch.r.channelCli": "CLI channel: interactive sableos chat",
    "arch.r.boot":
      "Spring Boot launcher: main class, auto-configuration, dependency aggregation",
    "arch.r.persona": "Persona library: built-in presets plus custom persona CRUD",
    "arch.r.knowledge":
      "Knowledge base: parse / chunk / embed pipeline, hybrid retrieval with RRF fusion",
    "arch.r.feishu":
      "Feishu inbound channel: long-connection events, sandboxed outbound, auto-reconnect",
    "arch.r.wecom": "WeCom inbound channel (mirrors Feishu)",
    "arch.r.dingtalk": "DingTalk inbound channel (mirrors Feishu / WeCom)",
    "arch.note.title": "Modules decouple through interfaces",
    "arch.note.text":
      "In the extension phase, a new channel or tool implementation adds a new module — core stays untouched.",

    "qs.eyebrow": "Quick start",
    "qs.title": "Four commands from zero to a conversational Agent",
    "qs.lead":
      "You need JDK 21 or later, Maven, and one LLM provider — a cloud API, or an OpenAI-compatible local service such as Ollama / vLLM.",
    "qs.s1.title": "Build",
    "qs.s1.text": "Produces the executable fat JAR. Verifiable at the skeleton stage.",
    "qs.s2.title": "Initialize the workspace",
    "qs.s2.text":
      'Creates the <code class="sd-inline-code">.sableos/</code> directory structure.',
    "qs.s3.title": "Generate an Agent directory",
    "qs.s3.text":
      'You get <code class="sd-inline-code">.sableos/agents/weather-bot/AGENT.md</code>.',
    "qs.s4.title": "Chat, or serve",
    "qs.s4.text":
      "Interactive CLI, or start the REST API with Swagger UI (default port 8080).",
    "qs.cmd.c1": "# 1. Build: produce the executable fat JAR",
    "qs.cmd.c2": "# 2. Initialize the workspace",
    "qs.cmd.c3": "# 3. Generate an Agent directory",
    "qs.cmd.c4": "# 4. Interactive chat",
    "qs.cmd.c5": "# 5. Start the Web Service: REST API with Swagger UI",
    "ui.copy": "Copy",
    "qs.agentmdTitle": "A minimal AGENT.md",
    "qs.tab.define": "Definition",
    "qs.tab.tree": "Directory tree",
    "qs.sample.desc": "Report the weather every morning with outfit advice",
    "qs.sample.agentName": "Weather Buddy",
    "qs.sample.prompt":
      "You are a weather reporter focused on the weather in the user's city",
    "qs.panelExplain":
      'The body holds the task instructions; the frontmatter is the Agent\'s runtime configuration (derived into a <code class="sd-inline-code">Profile</code>). Skill bindings are expressed as relative symlinks under the Agent\'s <code class="sd-inline-code">skills/</code> directory, never in the frontmatter.',
    "qs.tree.c1": "# Workspace",
    "qs.tree.c2": "# runtime config + task instructions",
    "qs.tree.c3": "# optional: reference material",
    "qs.tree.c4": "# relative symlinks select visible Skills",
    "qs.tree.c5": "# optional: scripts",
    "qs.tree.c6": "# shared Skill library",
    "qs.tree.c7": "# custom personas",
    "qs.tree.c8": "# long-term memory",
    "qs.modesTitle": "Three run modes",
    "qs.modes.thCmd": "Command",
    "qs.modes.thMode": "Mode",
    "qs.modes.thDesc": "Description",
    "qs.modes.r1.mode": "Interactive chat",
    "qs.modes.r1.desc": "Local CLI interaction",
    "qs.modes.r2.desc":
      "Starts the REST API service; scheduled jobs run persistently with it",
    "qs.modes.r3.mode": "Daemon",
    "qs.modes.r3.desc": "Mounts multiple channels at once",

    "prin.eyebrow": "Design principles",
    "prin.title": "Seven non-negotiable constraints",
    "prin.lead":
      "These principles are written into the project constitution — and they are the yardstick for every architecture upgrade review.",
    "prin.p1.title": "The foundation outranks any single Agent",
    "prin.p1.text":
      "The most important deliverable is not one powerful Agent, but an environment where any Agent can run reliably.",
    "prin.p2.title": "Configuration is the Agent",
    "prin.p2.text": "An Agent is defined by a piece of configuration, not written in code.",
    "prin.p3.title": "Self-implemented core, control first",
    "prin.p3.text":
      "The core reasoning loop is implemented in-house; model protocol adaptation reuses mature libraries — no reinvented wheels.",
    "prin.p4.title": "Open standards",
    "prin.p4.text":
      "MCP for tools, A2A for collaboration, open formats for skills — align with the ecosystem.",
    "prin.p5.title": "Stateless instances, externalized state",
    "prin.p5.text": "The prerequisite for a smooth path from single node to distributed.",
    "prin.p6.title": "Security is the foundation, not a patch",
    "prin.p6.text":
      "Controlled tool provenance, least privilege, enforced sandboxing, no local credentials, end-to-end auditability.",
    "prin.p7.title": "Restrained phasing",
    "prin.p7.text":
      "Ship the minimal complete set of the runtime kernel first; every architecture upgrade must be justified by real usage data.",

    "road.eyebrow": "Roadmap",
    "road.title": "Slow is smooth, smooth is fast",
    "road.lead":
      "Make the single-node runtime kernel solid first — running and managing a fleet of Agents on one node that genuinely works — then grow distributed capabilities on top of it.",
    "road.label.now": "Now",
    "road.label.planned": "Planned",
    "road.label.vision": "Vision",
    "road.ph1.title": "Phase 1 · Single-node runtime kernel",
    "road.ph1.i1": "All five core capabilities working end to end",
    "road.ph1.i2": "Configuration-as-Agent, multiple Agents coexisting",
    "road.ph1.i3": "REST API access, MCP integration",
    "road.ph1.i4": "Single-node running and managing a fleet of Agents, production-usable",
    "road.ph2.title": "Phase 2 · Distributed foundation",
    "road.ph2.i1": "Stateless nodes",
    "road.ph2.i2": "Externalized state, multi-replica deployment",
    "road.ph2.i3": "Support for larger scale and high availability",
    "road.ph3.title": "Phase 3 · Cross-node Agent collaboration",
    "road.ph3.i1": "Introduce an Agent communication foundation",
    "road.ph3.i2": "A2A protocol integration",
    "road.ph3.i3":
      "Cross-node discovery, delegation and reliable async collaboration for Agents across nodes",
    "road.note.title": "Horizontal capabilities · landing alongside each phase",
    "road.note.text":
      "Multi-tenancy, SSO, full audit queries, tool governance, observability and a web admin console.",

    "eco.eyebrow": "Ecosystem & standards",
    "eco.title": "Interoperate with the ecosystem — don't invent protocols",
    "eco.lead":
      "Reuse every protocol that can be reused; spend effort only on the layer nobody else has built.",
    "eco.mcp.desc":
      "Model Context Protocol: the open protocol connecting LLMs with external tools and data sources — the de facto standard of the tool ecosystem.",
    "eco.role.tool": "Tool integration layer",
    "eco.skills.desc":
      "The open directory format for Skills; SableOS's Agent directory follows its shape.",
    "eco.role.form": "Agent definition format",
    "eco.springai.desc":
      "LLM Provider abstraction with connectors for mainstream models — SableOS's underlying LLM access layer.",
    "eco.role.model": "Model access layer",
    "eco.alibaba.desc":
      "A Spring AI implementation for the Chinese model ecosystem, with connectors for mainstream LLMs.",
    "eco.a2a.name": "A2A (Agent2Agent)",
    "eco.a2a.desc":
      "An open protocol for Agent interoperability, used for cross-node Agent collaboration.",
    "eco.role.collab": "Phase 3 · Collaboration layer",

    "docs.eyebrow": "Docs",
    "docs.title": "Six documents, one source of truth",
    "docs.lead":
      "When documents drift out of sync, the Technical Solution is authoritative.",
    "docs.d1.name": "Industry Research",
    "docs.d1.desc": "The Agent OS landscape, Java's absence, and where SableOS fits",
    "docs.d2.name": "Demand Analysis",
    "docs.d2.desc": "Requirements, data model, milestones and acceptance criteria",
    "docs.d3.name": "Technical Solution",
    "docs.badge.authority": "Authority",
    "docs.d3.desc": "Architecture, modules and interfaces — the authority when coding",
    "docs.d4.name": "AI Programming Guide",
    "docs.d4.desc": "The Spec-Kit development process and AI-coding collaboration",
    "docs.d5.name": "Project Brief",
    "docs.d5.desc": "The project's public introduction (narrative)",
    "docs.d6.name": "Community",
    "docs.d6.desc": "Introducing the sable-labs community (narrative)",

    "cta.title": "Help us make this Agent foundation solid",
    "cta.text":
      "SableOS is initiated by the sable-labs community — an AI-exploration community driven by AI coding. We are docs-first today; join the discussion and co-build via Issues and PRs.",
    "cta.issues": "Open an Issue",
    "cta.readSolution": "Read the Technical Solution",

    "foot.desc":
      "The Agent OS that enterprises fully control — Java-native and privately auditable. One directory defines an Agent; one runtime runs a fleet of Agents.",
    "foot.col.pages": "Pages",
    "foot.col.start": "Get started",
    "foot.col.community": "Community",
    "foot.link.repo": "GitHub repository",
    "foot.link.issues": "Issues / PR",
    "foot.mission": "Long-term goal: become an Apache Foundation project",
    "foot.badge.doc": "Docs-first",
  };

  // 快照原文：切回中文时逐元素恢复，避免维护一份中文词典
  var langNodes = [];
  document
    .querySelectorAll("[data-i18n],[data-i18n-html],[data-i18n-attr]")
    .forEach(function (el) {
      var node = { el: el, attrs: [] };
      if (el.hasAttribute("data-i18n")) {
        node.type = "text";
        node.key = el.getAttribute("data-i18n");
        node.text = el.textContent;
      } else if (el.hasAttribute("data-i18n-html")) {
        node.type = "html";
        node.key = el.getAttribute("data-i18n-html");
        node.html = el.innerHTML;
      } else {
        node.type = "attr";
        el.getAttribute("data-i18n-attr")
          .split(";")
          .forEach(function (pair) {
            var parts = pair.split(":");
            if (parts.length !== 2) return;
            var name = parts[0].trim();
            node.attrs.push({
              name: name,
              key: parts[1].trim(),
              original: el.getAttribute(name) || "",
            });
          });
      }
      langNodes.push(node);
    });

  var warnedKeys = {};
  function lookup(key) {
    var v = I18N_EN[key];
    if (v == null && !warnedKeys[key]) {
      warnedKeys[key] = true;
      if (window.console && console.warn) {
        console.warn("[i18n] 词典缺键，该条保留中文:", key);
      }
    }
    return v;
  }

  function applyLang(next) {
    lang = next;
    var en = lang === "en";
    root.setAttribute("lang", en ? "en" : "zh-CN");
    langNodes.forEach(function (node) {
      var el = node.el;
      if (node.type === "text") {
        el.textContent = en ? lookup(node.key) || node.text : node.text;
      } else if (node.type === "html") {
        el.innerHTML = en ? lookup(node.key) || node.html : node.html;
      } else {
        node.attrs.forEach(function (a) {
          var v = en ? lookup(a.key) || a.original : a.original;
          el.setAttribute(a.name, v);
        });
      }
    });
    document.querySelectorAll("[data-lang-toggle]").forEach(function (btn) {
      // 按钮显示"目标语言"：中文界面显示 EN，英文界面显示 中
      btn.textContent = en ? "中" : "EN";
      var label = en ? "切换到中文" : "切换到英文";
      btn.setAttribute("aria-label", label);
      btn.setAttribute("title", label);
    });
    // 主题按钮的 aria-label 跟随语言重写（applyTheme 内部读取当前 lang）
    applyTheme(body.getAttribute("data-theme") || "light");
  }

  document.querySelectorAll("[data-lang-toggle]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var next = lang === "en" ? "zh-CN" : "en";
      applyLang(next);
      if (!langLockedByUrl) {
        try {
          localStorage.setItem(LANG_KEY, next);
        } catch (e) {
          /* 隐私模式下忽略 */
        }
      }
    });
  });

  applyLang(lang);
})();
