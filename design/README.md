# SableOS 展示方案

> SableOS 开源项目官网的设计方案与站点源码。站点位于仓库根下的 `website/`，由 GitHub Actions 发布到 Pages。

站点是**纯静态、零构建、零外部请求**的：双击 `website/index.html` 即可打开，
也可以直接由 Pages 托管，不需要任何构建步骤。

## 目录分工

| 目录 | 作用 | 是否上线 |
| --- | --- | --- |
| `website/` | **线上站点本体**。发布到 GitHub Pages，是对外唯一入口 | ✅ 上线 |
| `design/` | 设计过程留档：评审台、标识源文件、生成脚本、校验数据 | ❌ 不上线 |

两者不重复：站点代码只存在 `website/` 一份，`design/` 只放"为什么这么设计"以及可重跑的生成/校验工具。

## 交付环节与产出物

| 环节 | 产出物 | 位置 |
| --- | --- | --- |
| ① 定位梳理与页面结构 | 11 个区块的叙事结构与信息层级 | `design/preview/index.html` 第 02 节 |
| ② 设计系统 | 设计变量层 + 组件层（22 组件 / 21 图标） | `website/assets/css/tokens.css`、`website/assets/css/main.css` |
| ③ 展示页面 | 首页（明暗双主题、响应式、渐进增强） | `website/index.html` |
| ④ 标识设计体系 | 7 个 SVG 资产 + 参数化生成器 + 3 套备选概念 | `design/assets/` |
| ⑤ 评审台 | 一屏完成评审的总览页（含实时内嵌预览） | `design/preview/index.html` |
| ⑥ 质量校验 | 自动校验原始结果 | `design/preview/audit.json` |
| ⑦ 部署 | Pages 工作流 + 站点健全性校验 | `.github/workflows/pages.yml` |

## 怎么看

| 想看的 | 打开 |
| --- | --- |
| 首页本身（就是线上内容） | `website/index.html` |
| 全部设计决策与校验结果 | `design/preview/index.html` |
| 标识各变体 | `design/assets/logo.svg`、`design/assets/logo-horizontal-dark.svg` 等 |
| 标识参数对比 | `design/assets/_compare-marks.svg` |

两个 HTML 都不需要构建、不需要起服务，双击即可。

## 部署

站点由 `.github/workflows/pages.yml` 发布：推送到 `main` 且改动 `website/**` 时自动部署，
也可在 Actions 页手动触发。

**首次启用需要仓库管理员手动做一次**（CI 无法代做）：

> Settings → Pages → Build and deployment → Source 选择 **GitHub Actions**

若仍停留在 "Deploy from a branch"，工作流不会被触发。

之所以用 Actions 而不是内置的 "Deploy from a branch"：后者只允许发布根目录 `/` 或 `/docs`，
不支持 `/website` 这样的子目录；用 Actions 才能保持 `website/` 这个更清晰的目录结构。

工作流里带一段廉价的健全性校验（入口文件与关键资源是否齐、页面是否仍保持零外部 CDN 依赖），
目的是避免把"资源漏提交"的版本发出去 —— 历史上曾漏提交过字体文件。

### 已知坑：`github-pages` 环境的分支策略会锁死部署

启用 Pages 时 GitHub 会自动创建名为 `github-pages` 的部署环境，并给它加一条**自定义分支策略**，
把允许部署的分支钉死在那**当时**的默认分支上。之后如果改了默认分支（或删掉旧分支），
部署任务会在**任何 step 执行之前**被直接拒绝 —— 表现为：

- job 秒级失败（约 2 秒），`runner_name` 为空，**没有任何 step 被执行**
- 构建任务（`build`）却完全正常，因为它在另一个 job 里、不涉及该环境

本仓库就踩过：`github-pages` 环境创建于默认分支还是 `master` 的时刻，
策略里只有 `master` 一条；默认分支改成 `main` 后，从 `main` 触发的部署全部被拒。

**修复**（二选一，都在 GitHub 网页上做）：

| 做法 | 路径 | 说明 |
| --- | --- | --- |
| 删除环境让 GitHub 重建（推荐） | Settings → Environments → `github-pages` → Delete environment | 下次运行会按当前默认分支重新创建，策略自然正确 |
| 直接改策略 | Settings → Environments → `github-pages` → Deployment branches and tags → 改为 `Protected branches` 或加入 `main` | 保留环境的历史记录 |

排查命令（公开仓库无需 token）：

```bash
curl -s https://api.github.com/repos/<owner>/<repo>/environments/github-pages/deployment-branch-policies
```

## 设计主张

**配色。** 取名而非编号：`sable`（黑貂墨）是项目名 sable 的本意，作主题底色，色相偏暖约 30°；
`ember`（燃金）继承仓库现有 logo 的 `#F2B441` 作强调色。不用纯黑（显廉价）、不用冷灰（像默认主题）。
同一品牌色在浅色主题取 ember-700（5.9:1）、深色主题取 ember-400（10.2:1），各自取到合规的那一档。

**层次靠留白，不靠分割线。** 区块纵向节奏 `clamp(4rem, 2.5rem + 6vw, 8rem)`，长页面靠大间距分节。

**字重只用三档（400 / 500 / 600）。** 中文在 600 以上容易糊，粗体上限压到 600。

**"文档先行"是设计过的一等状态。** 页面没有隐瞒项目还只有骨架，而是用徽标、提示条、模块表的状态列明确表达。  
项目每推进一步只需改文案与状态字段，版式不受影响。

## 标识方案

### 对现有 logo 的评估

仓库现有 `docs/images/logo.svg` 是一枚折线构成的「S」。评估结论：**建议替换**。

| 问题 | 说明 |
| --- | --- |
| 语义空缺 | 折线 S 不承载「Agent / 运行底座 / 交付」任何一层含义 |
| 小尺寸不成立 | 44px 描边下 S 字怀过窄，16–24px 时糊成折线 |
| 不可单色化 | 依赖深色底 + 金色描边，在浅色 README 里无法直接用 |
| 团聚感缺失 | SableOS 的核心主张是「一群 Agent」，单字形表达不了「一群」 |
| 配色可继承 | `#332C27` 暖灰底 + `#F2B441` 燃金是很好的资产，应保留并系统化 |

### 方案：疾行 Prompt

三枚级联 chevron + 右端收束短横。设计在 64×64 坐标系内，用 `assets/make-logo.py` 参数化生成。

- **chevron** 同时是 Prompt 提示符与「向前推进」，一个形状压两层语义
- **三枚级联**取代原来的一枚，把「一群 Agent 并行推进」画进标记本身
- **透明度阶梯**（0.42 / 0.68 / 1.00）制造纵深，暗示队列与并行
- **收束短横**对应北极星公式里「= 一个 Agent / 交付一个结果」，让标记有落点
- **纯几何 + 圆帽接头**，可无损转单色

已考虑并放弃的备选概念留在 `assets/logo-concepts/`：双箭头（一群 Agent）、平台线 + 箭头（底座与 Agent）、  
方块 + 脱出小方块（目录派生）。放弃原因是它们更像通用 SaaS 图标，方块方案尤其容易撞脸。

### 资产清单

| 文件 | 用途 | 尺寸 |
| --- | --- | --- |
| `assets/logo.svg` | 主标识，深色底应用图标 | 512 × 512 |
| `assets/logo-mark.svg` | 无底版，供深色界面 | 64 × 64 |
| `assets/logo-mark-mono.svg` | 单色版，继承 `currentColor` | 64 × 64 |
| `assets/favicon.svg` | favicon，两枚箭头加粗简化 | 64 × 64（16px 可辨） |
| `assets/logo-horizontal-dark.svg` | 横向组合 · 深底 | 640 × 160 |
| `assets/logo-horizontal-light.svg` | 横向组合 · 浅底 | 640 × 160 |
| `website/assets/img/og-cover.png` | 社交分享封面 | 1200 × 630 |

改标识只需改 `make-logo.py` 顶部的几何参数，重新运行即得全套资产：

```bash
python design/assets/make-logo.py
```

## 设计变量

`website/assets/css/tokens.css` 是唯一的变量来源。组件样式一律引用变量，不写死数值。

| 类别 | 内容 |
| --- | --- |
| 品牌色阶 | `--sable-050` → `--sable-950`（12 档）、`--ember-050` → `--ember-900`（9 档） |
| 语义变量 | `--bg` / `--text` / `--border` / `--accent` 等，在 `body[data-theme]` 下切换 |
| 深色表面前景 | `--hero-ink` 系列，专为渐变底验证过的取值 |
| 字号 | `--fs-display` → `--fs-mono`，大标题用 `clamp()` 流式 |
| 间距 | `--sp-1` → `--sp-32`（4px 基准）+ 区块节奏 `--sec-y` |
| 圆角 | `--r-xs` (6px) → `--r-2xl` (32px) |
| 动效 | `--dur-fast` / `--dur` / `--dur-slow` + 两条缓动曲线 |

## 质量校验

用真实浏览器（Chromium 无头，通过 Playwright）跑出的结果，原始数据见 `preview/audit.json`。

| 检查项 | 结果 |
| --- | --- |
| 控制台 / 资源加载 | 明暗主题均无错误 |
| 图标 sprite 引用 | 21 定义 / 31 引用，无悬空 |
| 死链 | 37 个 `<a>`，0 个指向 `#` |
| 站外链接安全 | 17 个统一补 `rel="noopener noreferrer"` |
| 对比度 · 浅色主题 | WCAG AA，36 项全通过 |
| 对比度 · 深色主题 | WCAG AA，36 项全通过 |
| 横向溢出 | 1440 / 768 / 390px 三档均 `scrollWidth === viewport` |
| 未使用样式 | 0 个冗余类 |
| 无障碍 | 跳转链接、`focus-visible`、`aria-label`、Tab 键盘操作、`reduced-motion` 已覆盖 |

### 评审台为什么不用截图

`preview/index.html` 里的页面对照用的是**同源 iframe 内嵌真实页面**，不是 PNG。原因：

- 截图会随改版过期，评审台看着看着就和实际页面不一致了
- 截图是二进制，每改一版都往 git 历史里塞几 MB，且无法 diff
- iframe 永远是最新的，且**不占任何仓库体积**

分区预览通过 `?theme=light|dark` 把主题固定下来（该参数只作用于本次打开，不会写回
localStorage 改掉访问者偏好），点击任一预览可在新窗口打开完整页面。

确实需要静态图（贴 Issue / PR / 文档）时再按需生成，生成物已加入 `.gitignore`：

| 想看的 | 生成后文件名 |
| --- | --- |
| 首屏 · 浅色 / 深色 | `hero-light.png` · `hero-dark.png` |
| 整页 · 浅色 / 深色 | `home-light.png` · `home-dark.png` |
| 响应式 · 768 / 390 | `home-768.png` · `home-390.png` |
| 各分区 · 明暗各一份 | `sec-{why,core,architecture,quickstart,roadmap,docs}-{light,dark}.png` |

### 字体策略：自托管，零外部请求

`Inter` 与 `JetBrains Mono` 的 **latin + latin-ext** 两个子集已自托管到
`website/assets/fonts/`（10 个 woff2，约 475 KB，按 `unicode-range` 命中，未用到的子集不下载）。
生成脚本：`assets/make-fonts.py`。

**中文不由这两款西文字体承担**——它们的 `unicode-range` 不含 CJK，浏览器会直接落到
`--font-sans` 里的 `HarmonyOS Sans SC` / `PingFang SC` / `Microsoft YaHei`。
因此字体加载失败也只有西文字形受影响，中文永远正常。

自托管的三个理由：

1. **去掉对 `fonts.gstatic.com` 的依赖** —— 国内访问不稳定（实测间歇 `ERR_CONNECTION_RESET`）
2. **评审台内嵌 6 个预览**，若走 CDN 会对同一个域名重复发起 7 次请求
3. 页面由此**完全自包含**：实测首页与评审台的外部 http(s) 请求数均为 **0**

两个实测结论：

| 场景 | 结果 |
| --- | --- |
| 拦截 Google Fonts（旧方案） | hero 区高 1194px、h1 盒子 514 × 287，与正常加载完全一致 → **零布局位移** |
| 现方案（自托管） | 首页加载 5/10 个字重（按需命中），h1 宽 514px，无任何外部请求 |

另外刻意**不用 `<link rel="preload" as="font">`** 预加载：字体预加载按规范必须带
`crossorigin`，而用 `file://` 直接打开时 `Origin` 为 `null`，预加载会被 CORS 拒绝并在控制台
刷 `ERR_FAILED`（`@font-face` 本身仍能加载成功，只是白白多出一堆报错）。
字体已开 `font-display: swap`，不预加载也不会出现文字不可见。

### 渐变表面的对比度怎么验证

Hero 与 CTA 用深色渐变做背景，而 `computedStyle.backgroundColor` 读不到渐变，常规检测会误判成白底。  
做法是先算渐变「最亮点」：渐变顶端 `#1D1815` → 叠加燃金光晕（13% 不透明）→ 再叠加网格线（2.8% 白）  
→ 合成约 `#3F3221`，再按这个最不利采样值验证所有前景色：

| 前景 | 对比度 |
| --- | --- |
| `--hero-ink-hero` `#F6F2ED`（主标题） | 11.15:1 |
| `--hero-ink` `#C9BFB3`（正文、标签、终端） | 6.86:1 |
| `--hero-accent-soft` `#F6C868`（标语） | 7.92:1 |
| `--hero-ink-3` `#A8A099`（统计标签） | 4.83:1 |

**改渐变参数时必须重跑这一步验证。**

## 文件结构

```text
sableos/
├── website/                       线上站点（发布到 GitHub Pages）
│   ├── index.html                 首页（11 个区块）
│   └── assets/
│       ├── css/tokens.css         设计变量
│       ├── css/main.css           组件与布局（19 节）
│       ├── js/site.js             渐进增强脚本（9 个模块，含中英切换）
│       ├── fonts/                 自托管字体（latin + latin-ext，10 个 woff2）
│       └── img/                   favicon / 图标位图 / 分享封面
│
├── design/                        设计过程留档（不上线）
│   ├── README.md                  本文件
│   ├── assets/                    标识资产与生成脚本
│   │   ├── make-logo.py           标识参数化生成器
│   │   ├── make-compare.py        标识对照图生成器
│   │   ├── make-fonts.py          字体子集下载与 @font-face 生成
│   │   ├── logo*.svg              各变体
│   │   ├── og-cover.html          分享封面源文件
│   │   ├── _compare-logos.svg     五方案对照图
│   │   ├── _compare-marks.svg     参数精修对比图
│   │   └── logo-concepts/         备选概念（留档）
│   └── preview/                   评审与校验
│       ├── index.html             评审台（页面对照用 iframe 内嵌，不放截图）
│       ├── audit.json             校验原始结果
│       └── *.png                  按需生成的分区截图，已 gitignore
│
└── .github/workflows/pages.yml    Pages 部署工作流
```

## 后续扩展方向

页面按「项目会持续长大」设计，以下扩展点均不需要改动现有版式：

| 扩展项 | 改动位置 | 是否需改设计系统 |
| --- | --- | --- |
| 新增内容区块（更新日志、示例集、FAQ、贡献者） | 复用 `.sd-sec` + 现有卡片组件 | 否 |
| 新增组件 | `main.css` 追加编号小节 | 否 |
| 换主色 / 品牌色 | 只改 `tokens.css` 色阶 | 否 |
| 新增子页面（文档站、changelog、examples） | 复用 `assets/` | 否 |
| 迁移到静态站点生成器（Astro 等） | 保留 `assets/`，拆分 `index.html` | 否 |
| 新增标识变体 | `make-logo.py` 加生成函数 | 否 |
| 中英双语 | ✅ 已实现（2026-09-30）：`data-i18n` / `data-i18n-html` / `data-i18n-attr` 属性 + `site.js` 英文词典；导航栏按钮切换，localStorage 记忆，`?lang=en` 参数与 `?theme=` 同规则（URL 优先、不写回）；中文为源语言，切回走快照恢复，缺键回退中文并 console.warn | 否 |

## 待确认事项

1. **标识方向**：接受「疾行 Prompt」，还是从备选概念里换一个
2. ~~**仓库地址**~~：已确认 `wychmod/sableos`，并修正了站点配置里原本错误的 `sable-labs/sableos`
3. ~~**目录归属**~~：已迁至 `website/`，由 `.github/workflows/pages.yml` 发布
4. **配色温度**：暖墨 + 燃金是否符合预期
5. **页面形态**：单页足够，还是需要多页文档站

> 注：站点正文里的 `sable-labs` 是**社区名**（源自 `docs/sable-labs.md` 的叙事），
> 与仓库地址无关，保持不变。

## 已知待办：12 处架构配图仍缺失

`docs/sableos.md` 与 `docs/TechnicalSolution.md` 共 **12 处**图片引用，现统一指向
**文档同目录下的 `docs/images/`**（与既有的 `docs/images/architecture.svg` 同一约定）。

原先它们写的是 `../website/public/images/*.svg` —— 那是按「另有一个网站仓库」的设想留的跨目录引用，
而 `public/` 是构建期产物目录的约定，本站点是**零构建纯静态**，没有这一步，所以已改为 docs 本地路径，
让文档不再依赖 `website/` 的目录形状。

当前状态：**1 处已补齐，11 处仍缺**。

| 目标文件（`docs/images/` 下） | 内容 | 状态 |
| --- | --- | --- |
| `logo.svg` | 文档侧标识 | ✅ 已替换为新标识（源自 `website/assets/img/logo.svg`） |
| `docs-architecture-light.svg` | 整体架构：接入层 → Agent 层 → 引擎层 → 能力层 → 基础层 | ⬜ 待绘制 |
| `docs-provider.svg` | Provider 架构：ReAct 循环 → ProviderService → 显式映射的 ChatModel → 各家 LLM API | ⬜ 待绘制 |
| `docs-react-loop-detail.svg` | ReAct 循环：Reason → Act → Observe，直到无工具调用或达最大轮数 | ⬜ 待绘制 |
| `docs-memory-service.svg` | Memory 架构：MemoryService 门面统一收口 SessionManager 与 LongTermMemory | ⬜ 待绘制 |
| `docs-memory-structure.svg` | MEMORY.md 内部结构：核心记忆区永远保留，截断与检索只作用于归档区 | ⬜ 待绘制 |
| `docs-tool-flow.svg` | Tool 调用流程：LLM 决定调用 → SableOS 执行 → 外部世界 → 结果回填 | ⬜ 待绘制 |
| `docs-plugin-tool-tiers.svg` | Plugin Tool 三档：零代码目录+MCP、轻代码自写 MCP server、重代码 @Tool | ⬜ 待绘制 |
| `docs-sandbox-flow.svg` | Sandbox 校验流程：File/Shell/HttpTools 调 WhitelistSandbox.enforce | ⬜ 待绘制 |
| `docs-notify.svg` | NotifyTools 设计：接口先行，核心阶段只实现 WebhookNotifyAdapter | ⬜ 待绘制 |
| `docs-scheduler.svg` | 定时任务是第三种触发源，与 CLI / Web Service 同调 AgentService | ⬜ 待绘制 |
| `docs-agent-lifecycle.svg` | 两条录入路径一段注册代码，免重启即上线 | ⬜ 待绘制 |

绘制时建议直接复用 `tokens.css` 的 sable / ember 色值，避免与站点风格脱节 ——
现有的 `docs/images/architecture.svg` 还是仓库早期的冷灰蓝配色，与现行品牌色不一致，
可在补图时一并重绘。
