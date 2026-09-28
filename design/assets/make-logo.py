# -*- coding: utf-8 -*-
"""
SableOS 标识资产生成器
=====================
从一份参数化几何生成全套标识资产，保证各尺寸 / 各变体的形状完全一致。
改参数即可整体微调，不需要手动改每个文件。

设计于 64x64 坐标系，其他尺寸通过 viewBox + transform 等比复用同一路径。
"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)))
CONCEPTS = os.path.join(OUT, "logo-concepts")

# ---------------------------------------------------------------- 几何参数
SW = 5.0          # 笔画宽度
ARM_DX = 4.8      # 单枚 chevron 的水平跨度
ARM_DY = 4.25     # 单枚 chevron 的半高
PITCH_X = 5.8     # 相邻 chevron 的水平步距
PITCH_Y = 4.0     # 相邻 chevron 的垂直步距
GAP = 4.4         # 末枚 chevron 与收束短横之间的间隙
BAR_LEN = 7.0     # 收束短横长度
RAMP = [0.42, 0.68, 1.0]   # 三枚 chevron 的透明度阶梯（前 → 后递进）
BAR_OPACITY = 0.95


def build_geometry(n_chevrons=3, sw=SW, arm_dx=ARM_DX, arm_dy=ARM_DY,
                   pitch_x=PITCH_X, pitch_y=PITCH_Y, gap=GAP, bar_len=BAR_LEN):
    """返回 (paths, bounds)；paths 为 (d, opacity, kind) 列表，已居中于 64 画布。"""
    # 以末枚 chevron 的顶点的 y 为基准，反推首枚位置，使整体垂直居中
    total_h = (n_chevrons - 1) * pitch_y + 2 * arm_dy
    vy0 = (64 - total_h) / 2 + arm_dy          # 首枚顶点 y
    vx0 = 0.0                                   # 先放在 0，稍后整体平移

    chevrons = []
    for i in range(n_chevrons):
        vx = vx0 + i * pitch_x
        vy = vy0 + i * pitch_y
        d = "M{:.2f} {:.2f} L{:.2f} {:.2f} L{:.2f} {:.2f}".format(
            vx - arm_dx, vy - arm_dy, vx, vy, vx - arm_dx, vy + arm_dy)
        chevrons.append((d, vx, vy))

    last_vx, last_vy = chevrons[-1][1], chevrons[-1][2]
    bar_x1 = last_vx + gap
    bar_x2 = bar_x1 + bar_len
    bar_d = "M{:.2f} {:.2f} H{:.2f}".format(bar_x1, last_vy, bar_x2)

    # 计算包围盒（含圆帽 / 圆角接头的半径外扩）
    half = sw / 2
    xs = [c[1] - arm_dx - half for c in chevrons] + [bar_x2 + half]
    ys = [vy0 - arm_dy - half, chevrons[-1][2] + arm_dy + half]
    x_min, x_max = min(xs), max(xs)
    y_min, y_max = min(ys), max(ys)
    dx = (64 - (x_max - x_min)) / 2 - x_min
    dy = (64 - (y_max - y_min)) / 2 - y_min

    def shift(d):
        out = []
        for tok in d.replace("M", " M ").replace("L", " L ").replace("H", " H ").split():
            out.append(tok)
        # 用正则更稳妥地平移坐标
        import re
        def repl(m):
            cmd, nums = m.group(1), m.group(2)
            n = [float(v) for v in nums.replace(",", " ").split()]
            if cmd == "H":
                return "H{:.2f}".format(n[0] + dx)
            res = []
            for k in range(0, len(n), 2):
                res.append("{:.2f}".format(n[k] + dx))
                if k + 1 < len(n):
                    res.append("{:.2f}".format(n[k + 1] + dy))
            return "{}{}".format(cmd, " ".join(res))
        return re.sub(r"([MLH])([-\d\.,\s]+?)(?=[MLH]|$)", repl, d).strip()

    paths = []
    for i, (d, _, _) in enumerate(chevrons):
        op = RAMP[min(i, len(RAMP) - 1)] if n_chevrons == 3 else (0.5, 1.0)[i]
        paths.append((shift(d), op, "chevron"))
    paths.append((shift(bar_d), BAR_OPACITY, "bar"))
    return paths, (x_min + dx, y_min + dy, x_max + dx, y_max + dy)


def mark_group(paths, sw=SW, stroke="url(#forge)"):
    """把几何转成 <g> 片段（不含 defs）。"""
    out = ['  <g fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round" stroke-linejoin="round">'.format(stroke, sw)]
    for d, op, kind in paths:
        op_attr = "" if op >= 1 else ' stroke-opacity="{}"'.format(op)
        out.append('    <path d="{}"{}/>'.format(d, op_attr))
    out.append("  </g>")
    return "\n".join(out)


FORGE_GRAD = """    <linearGradient id="forge" x1="0.10" y1="0" x2="0.90" y2="1">
      <stop offset="0" stop-color="#FADFA6"/>
      <stop offset="0.45" stop-color="#F2B441"/>
      <stop offset="1" stop-color="#CE8B12"/>
    </linearGradient>"""

TILE_GRAD = """    <linearGradient id="tile" x1="0" y1="0" x2="0.35" y2="1">
      <stop offset="0" stop-color="#1E1915"/>
      <stop offset="1" stop-color="#0D0B0A"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.5" cy="0.44" r="0.5">
      <stop offset="0" stop-color="#F2B441" stop-opacity="0.16"/>
      <stop offset="1" stop-color="#F2B441" stop-opacity="0"/>
    </radialGradient>"""


def write(name, content, folder=OUT):
    path = os.path.join(folder, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("  {:<34} {:>6} B".format(name, len(content.encode("utf-8"))))


def svg_open(w, h, title, desc):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        'viewBox="0 0 {w} {h}" role="img" aria-labelledby="t d">\n'
        '  <title id="t">{title}</title>\n'
        '  <desc id="d">{desc}</desc>\n  <defs>\n'
    ).format(w=w, h=h, title=title, desc=desc)


# ============================================================ 生成
print("生成 SableOS 标识资产\n" + "-" * 56)

paths3, bounds = build_geometry(3)
paths2, bounds2 = build_geometry(2)

# 1) 主标识：深色底方形应用图标（512）
svg = svg_open(512, 512, "SableOS", "疾行 Prompt：三枚级联箭头表示一群 Agent 并行推进，收束短横表示交付一个结果。")
svg += TILE_GRAD + "\n" + FORGE_GRAD + "\n  </defs>\n"
svg += '  <rect width="512" height="512" rx="112" fill="url(#tile)"/>\n'
svg += '  <rect x="1" y="1" width="510" height="510" rx="111" fill="none" stroke="#FFFFFF" stroke-opacity="0.07" stroke-width="2"/>\n'
svg += '  <circle cx="256" cy="238" r="180" fill="url(#glow)"/>\n'
svg += '  <g transform="scale(8)">\n' + mark_group(paths3) + "\n  </g>\n</svg>\n"
write("logo.svg", svg)

# 2) 主标识（无底，透明背景，供深色界面 / README 用）
svg = svg_open(64, 64, "SableOS 标识", "三枚级联箭头加收束短横。")
svg += FORGE_GRAD + "\n  </defs>\n" + mark_group(paths3) + "\n</svg>\n"
write("logo-mark.svg", svg)
write("logo-concepts/logo-mark.svg", svg, OUT)

# 3) 单色版（继承 currentColor，用于任意底色的界面）
svg = svg_open(64, 64, "SableOS 标识（单色）", "单色版标识，颜色由外层 currentColor 决定。")
svg += "  </defs>\n" + mark_group(paths3, stroke="currentColor") + "\n</svg>\n"
write("logo-mark-mono.svg", svg)

# 4) favicon：两枚 chevron 加粗，保证 16px 下仍可辨识
paths_f, _ = build_geometry(2, sw=6.4, arm_dy=4.6, arm_dx=5.2, pitch_x=6.6, pitch_y=0.0, gap=4.6, bar_len=7.4)
svg = svg_open(64, 64, "SableOS", "favicon：简化两枚箭头加收束短横。")
svg += FORGE_GRAD + "\n  </defs>\n" + mark_group(paths_f, sw=6.4) + "\n</svg>\n"
write("favicon.svg", svg)

# 5) 横向组合（标识 + 字标），供 og:image / 社交卡片 / 白皮书封面
def lockup(bg_dark, name):
    fg = "#F6F2ED" if bg_dark else "#131110"
    sub = "#A8A099" if bg_dark else "#5A524B"
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="160" '
        'viewBox="0 0 640 160" role="img" aria-labelledby="t d">\n'
        '  <title id="t">SableOS 横向组合标识</title>\n'
        '  <desc id="d">标识与字标水平排列，字标下方为英文定位语。</desc>\n  <defs>\n'
    )
    svg += FORGE_GRAD + "\n  </defs>\n"
    svg += '  <g transform="translate(28 30) scale(1.55)">\n' + mark_group(paths3) + "\n  </g>\n"
    svg += (
        '  <g font-family="Inter, \'HarmonyOS Sans SC\', system-ui, \'Segoe UI\', sans-serif">\n'
        '    <text x="148" y="74" font-size="46" font-weight="600" letter-spacing="-1.6" fill="{}">'
        'SableOS</text>\n'
        '    <text x="150" y="102" font-size="14" letter-spacing="2.4" fill="{}">'
        'AGENT HARNESS OS for JAVA</text>\n'
        "  </g>\n</svg>\n"
    ).format(fg, sub)
    write(name, svg)

lockup(True, "logo-horizontal-dark.svg")
lockup(False, "logo-horizontal-light.svg")

# 6) 概念对照：把候选并排渲染成一张评审图
def concept_card(x, y, label, note, geometry, sw, accent_note=""):
    g = ['  <g transform="translate({} {}) scale(1.5)">'.format(x, y)]
    g.append(mark_group(geometry, sw=sw))
    g.append("  </g>")
    g.append('  <text x="{}" y="{}" font-family="Inter, system-ui, sans-serif" font-size="17" font-weight="600" fill="#F6F2ED">{}</text>'.format(x + 10, y + 130, label))
    g.append('  <text x="{}" y="{}" font-family="Inter, system-ui, sans-serif" font-size="13" fill="#A8A099">{}</text>'.format(x + 10, y + 152, note))
    if accent_note:
        g.append('  <text x="{}" y="{}" font-family="Inter, system-ui, sans-serif" font-size="13" fill="#F2B441">{}</text>'.format(x + 10, y + 173, accent_note))
    return "\n".join(g)

cards = []
slots = [(48, 40), (356, 40), (664, 40), (972, 40)]
meta = [
    ("现状（仓库 logo.svg）", "S 无法读出语义，字怀太小", "建议替换"),
    ("概念 A · 疾行 Prompt", "双箭头：一群 Agent 并行", ""),
    ("概念 B · 底座与 Agent", "平台线 + 箭头，主体偏左", ""),
    ("概念 C · 目录与派生", "方块语义 = 通用 App", ""),
]
for (x, y), (label, note, acc) in zip(slots, meta):
    geo = paths3 if "A" in label else (paths2 if "B" in label else paths3)
    cards.append(concept_card(x, y, label, note, geo, SW, acc))
write("logo-concepts/_placeholder.svg", "<svg/>", OUT)  # 占位，实际对照图见下

# 最终选定的 3 个精修候选并排图
def refine_card(x, label, note, paths, sw, opacity_ramp=True):
    g = ['  <g transform="translate({} 34) scale(1.62)">'.format(x)]
    g.append(mark_group(paths, sw=sw))
    g.append("  </g>")
    g.append('  <text x="{}" y="158" font-family="Inter, system-ui, sans-serif" font-size="16" font-weight="600" fill="#F6F2ED">{}</text>'.format(x + 8, label))
    g.append('  <text x="{}" y="180" font-family="Inter, system-ui, sans-serif" font-size="13" fill="#A8A099">{}</text>'.format(x + 8, note))
    return "\n".join(g)

shapes = []
shapes.append(refine_card(60, "R1 · 三枚级联", "推荐：一群 Agent 推进 → 交付", paths3, SW))
p3, _ = build_geometry(3, sw=5.4, arm_dy=4.6, pitch_x=6.4, pitch_y=4.4, gap=5.0, bar_len=8.0)
shapes.append(refine_card(360, "R3 · 级联（更舒展）", "间距加大，小尺寸更清晰", p3, 5.4))
p4, _ = build_geometry(3, sw=4.2, arm_dy=4.9, arm_dx=5.6, pitch_x=7.4, pitch_y=4.9, gap=5.6, bar_len=9.0)
shapes.append(refine_card(660, "R4 · 级联（更细）", "描边更细，气质更书卷", p4, 4.2))

svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="240" '
    'viewBox="0 0 960 240" role="img" aria-labelledby="t d">\n'
    '  <title id="t">SableOS 标识精修候选 R1 / R3 / R4</title>\n'
    '  <desc id="d">三枚级联箭头方案的三种描边与间距参数对比。</desc>\n  <defs>\n'
    + FORGE_GRAD + "\n  </defs>\n"
    '  <rect width="960" height="240" rx="20" fill="#131110"/>\n'
    + "\n".join(shapes) + "\n</svg>\n"
)
write("_compare-marks.svg", svg)
os.remove(os.path.join(OUT, "logo-concepts", "_placeholder.svg"))

print("-" * 56)
print("包围盒（64 坐标）：x {:.2f}..{:.2f}  y {:.2f}..{:.2f}".format(*bounds))
print("宽高比：{:.3f}（正方形画布内留白均衡）".format((bounds[2] - bounds[0]) / (bounds[3] - bounds[1])))
