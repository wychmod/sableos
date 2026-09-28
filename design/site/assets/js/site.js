/* ==========================================================================
   SableOS · Site behaviour
   --------------------------------------------------------------------------
   只做四件小事：主题记忆、导航滚动态、锚点高亮、进场动画 + 代码复制 + Tabs。
   全部为渐进增强：JS 不可用时页面依然完整可读。
   ========================================================================== */
(function () {
  "use strict";

  /* ======================================================================
     站点配置 —— 迭代时只改这里，全站外链自动生效。
     仓库改名 / 换组织 / 迁移到 sableos.dev 都只需要动这一段。
     ====================================================================== */
  var SITE = {
    repo: "https://github.com/sable-labs/sableos",
    branch: "main",
    license: "https://www.apache.org/licenses/LICENSE-2.0",
    org: "https://github.com/sable-labs",
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
    document.querySelectorAll("[data-org]").forEach(function (el) {
      el.setAttribute("href", SITE.org);
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
      var next = theme === "dark" ? "浅色" : "深色";
      btn.setAttribute("aria-label", "切换到" + next + "模式");
      btn.setAttribute("title", "切换到" + next + "模式");
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
          label.textContent = "已复制";
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
})();
