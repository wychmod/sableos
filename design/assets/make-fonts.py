# -*- coding: utf-8 -*-
"""
把 Inter / JetBrains Mono 的 latin + latin-ext 子集自托管到 assets/fonts/
---------------------------------------------------------------
为什么只取两个子集：
  页面正文是中文，中文由系统字体（PingFang SC / 微软雅黑 / HarmonyOS Sans SC）承担；
  这两款西文字体只需要覆盖 latin / latin-ext。
  Google 默认给的 cyrillic / greek / vietnamese 等子集对本项目是纯浪费。

产出：
  website/assets/fonts/fonts.css        本地 @font-face，替代 Google Fonts 的 <link>
  website/assets/fonts/*.woff2          实际字体文件
"""
import os
import re
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

HERE = os.path.dirname(os.path.abspath(__file__))
SITE_FONTS = os.path.normpath(os.path.join(HERE, "..", "..", "website", "assets", "fonts"))
CACHE = os.path.join(HERE, "_fontcache")

KEEP = ("latin", "latin-ext")

FAMILIES = [
    ("Inter", "css2?family=Inter:wght@400;500;600&display=swap", "inter"),
    ("JetBrains Mono", "css2?family=JetBrains+Mono:wght@400;500&display=swap", "jetbrainsmono"),
]


def fetch(url, binary=False):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    return data if binary else data.decode("utf-8")


def main():
    os.makedirs(SITE_FONTS, exist_ok=True)
    os.makedirs(CACHE, exist_ok=True)

    out_css = [
        "/* ==========================================================================",
        "   SableOS · 自托管西文字体",
        "   --------------------------------------------------------------------------",
        "   由 design/assets/make-fonts.py 生成，请勿手改。",
        "   只包含 Inter / JetBrains Mono 的 latin 与 latin-ext 子集；",
        "   中文由系统字体承担（见 tokens.css 的 --font-sans / --font-mono）。",
        "   自托管的原因：国内访问 fonts.gstatic.com 不稳定，且可去掉一次跨域往返。",
        "   ========================================================================== */",
        "",
    ]

    total = 0
    count = 0
    for family, query, slug in FAMILIES:
        url = "https://fonts.googleapis.com/" + query
        print("拉取 @font-face 声明: " + family)
        css = fetch(url)
        cached = os.path.join(CACHE, slug + ".css")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(css)

        # 按注释分块，只保留 latin / latin-ext
        blocks = re.split(r"/\*\s*([a-z-]+)\s*\*/", css)
        # blocks: [pre, subset, block, subset, block, ...]
        kept = 0
        for i in range(1, len(blocks), 2):
            subset = blocks[i]
            body = blocks[i + 1]
            if subset not in KEEP:
                continue
            m = re.search(r"url\((https://fonts\.gstatic\.com/[^)]+\.woff2)\)", body)
            if not m:
                continue
            remote = m.group(1)
            # 文件名：家族-字重-子集.woff2
            weight = re.search(r"font-weight:\s*(\d+)", body)
            weight = weight.group(1) if weight else "400"
            fname = "{}-{}-{}.woff2".format(slug, weight, subset)
            local = os.path.join(SITE_FONTS, fname)

            if not os.path.exists(local):
                data = fetch(remote, binary=True)
                with open(local, "wb") as f:
                    f.write(data)
            size = os.path.getsize(local)
            total += size
            count += 1
            print("  {:<30} {:>6} KB".format(fname, round(size / 1024)))

            block = body.replace(remote, "./" + fname).strip()
            out_css.append(block)
            out_css.append("")
            kept += 1
        print("  → 保留 {} 个子集（丢弃 cyrillic / greek / vietnamese 等）".format(kept))

    css_path = os.path.join(SITE_FONTS, "fonts.css")
    with open(css_path, "w", encoding="utf-8") as f:
        f.write("\n".join(out_css).rstrip() + "\n")

    print("\n生成 " + os.path.relpath(css_path, os.path.dirname(HERE)))
    print("字体文件 {} 个，合计 {} KB".format(count, round(total / 1024)))
    print("fonts.css {} 字节".format(os.path.getsize(css_path)))


if __name__ == "__main__":
    main()
