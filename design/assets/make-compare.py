# -*- coding: utf-8 -*-
"""
生成标识评审对照图：现状 + 3 个备选概念 + 最终方案
所有标记在 96x96 的槽位内居中，并排对比。
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SLOT = 96
CARD_W = 244
CARD_H = 330
PAD = 26


def wrap(title, note, verdict, body, verdict_color):
    """把槽位内容包成一张卡。"""
    return body, title, note, verdict, verdict_color


# --- 各方案的标记路径（96 坐标系内手工定位，保持与各自 SVG 同形）---

CURRENT = '''    <g fill="none" stroke="#F2B441" stroke-width="8.2" stroke-linecap="round" stroke-linejoin="round">
      <polyline points="31,31 54,48 31,65"/>
      <line x1="60" y1="65" x2="72" y2="65"/>
    </g>'''

CONCEPT_A = '''    <g fill="none" stroke="#F2B441" stroke-width="10" stroke-linecap="round" stroke-linejoin="round">
      <path d="M27 27 L40 48 L27 69"/>
      <path d="M52 27 L65 48 L52 69" stroke-opacity="0.8"/>
    </g>'''

CONCEPT_B = '''    <g fill="none" stroke="#F2B441" stroke-width="9.4" stroke-linecap="round" stroke-linejoin="round">
      <path d="M26 24 L44 44 L26 64"/>
      <line x1="56" y1="30" x2="70" y2="30" stroke-width="6.4" stroke-opacity="0.9"/>
      <line x1="56" y1="44" x2="70" y2="44" stroke-width="6.4" stroke-opacity="0.58"/>
      <line x1="56" y1="58" x2="70" y2="58" stroke-width="6.4" stroke-opacity="0.3"/>
    </g>'''

CONCEPT_C = '''    <g fill="none" stroke="#F2B441" stroke-linecap="round" stroke-linejoin="round">
      <rect x="21" y="26" width="38" height="38" rx="9" stroke-width="8"/>
      <path d="M34 39 L41 45 L34 51" stroke-width="6.6"/>
      <rect x="53" y="55" width="17" height="17" rx="5" stroke-width="6.6" stroke-opacity="0.85"/>
      <rect x="59" y="62" width="17" height="17" rx="5" stroke-width="6.6" stroke-opacity="0.55"/>
    </g>'''

FINAL = '''    <g fill="none" stroke="#F2B441" stroke-width="7.4" stroke-linecap="round" stroke-linejoin="round">
      <path d="M27.15 35.02L34.25 41.00L27.15 46.98" stroke-opacity="0.42"/>
      <path d="M35.75 40.52L42.85 46.50L35.75 52.48" stroke-opacity="0.68"/>
      <path d="M44.35 46.02L51.45 52.00L44.35 57.98"/>
      <path d="M57.95 52.00H68.30" stroke-opacity="0.95"/>
    </g>'''


def card_body(x, mark, tile=True):
    """一张卡的图形区（含深色底板）。"""
    cx = x + PAD
    cy = PAD
    parts = []
    if tile:
        parts.append(
            '  <rect x="{}" y="{}" width="{}" height="{}" rx="22" fill="#0B0908" stroke="#2E2823"/>'.format(
                cx, cy, CARD_W - PAD * 2, CARD_W - PAD * 2))
    else:
        parts.append(
            '  <rect x="{}" y="{}" width="{}" height="{}" rx="22" fill="#F7F3ED" stroke="#E7E0D7"/>'.format(
                cx, cy, CARD_W - PAD * 2, CARD_W - PAD * 2))
    inner = CARD_W - PAD * 2
    ox = cx + (inner - SLOT) / 2
    oy = cy + (inner - SLOT) / 2
    parts.append('  <g transform="translate({:.1f} {:.1f})">'.format(ox, oy))
    parts.append(mark)
    parts.append("  </g>")
    return "\n".join(parts)


CARDS = [
    (CURRENT, "现状", "docs/images/logo.svg", "折线 S：语义空缺、小尺寸糊、不可单色化", "#E2685E", True),
    (CONCEPT_A, "概念 A", "双箭头 · 一群 Agent", "语义直白，但更像通用流程图标", "#A2968A", True),
    (CONCEPT_B, "概念 B", "平台线 + 箭头 · 底座与 Agent", "表意准确，主体偏左、重心失衡", "#A2968A", True),
    (CONCEPT_C, "概念 C", "方块 + 脱出 · 目录派生", "与通用 App 图标撞脸概率高", "#A2968A", True),
    (FINAL, "最终方案", "疾行 Prompt · 三枚级联 + 收束", "16px 可辨、可单色、语义有落点", "#F2B441", True),
]

W = PAD + len(CARDS) * CARD_W
H = CARD_H

out = [
    '<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-labelledby="t d">'.format(w=W, h=H),
    '  <title id="t">SableOS 标识方案对照：现状与三个备选概念，最后为选定方案</title>',
    '  <desc id="d">左侧为仓库现有折线 S 标识与三个备选概念，均标注不足之处；最右侧为最终选定的疾行 Prompt 方案。</desc>',
    '  <rect width="{w}" height="{h}" fill="none"/>'.format(w=W, h=H),
]

for i, (mark, title, note, verdict, vcolor, tile) in enumerate(CARDS):
    x = i * CARD_W
    out.append(card_body(x, mark, tile))
    is_final = title == "最终方案"
    # 标题
    out.append(
        '  <text x="{}" y="{}" font-family="Inter, \'PingFang SC\', \'Microsoft YaHei\', sans-serif" '
        'font-size="{}" font-weight="600" fill="{}">{}</text>'.format(
            x + PAD, PAD + CARD_W - PAD * 2 + 38, 18 if is_final else 16,
            "#F6F2ED" if is_final else "#C9BFB3", title))
    # 副标题（方案描述）
    out.append(
        '  <text x="{}" y="{}" font-family="Inter, \'PingFang SC\', \'Microsoft YaHei\', sans-serif" '
        'font-size="12.5" fill="#8A827A">{}</text>'.format(x + PAD, PAD + CARD_W - PAD * 2 + 62, note))
    # 结论
    out.append(
        '  <text x="{}" y="{}" font-family="Inter, \'PingFang SC\', \'Microsoft YaHei\', sans-serif" '
        'font-size="12.5" font-weight="500" fill="{}">{}</text>'.format(
            x + PAD, PAD + CARD_W - PAD * 2 + 86, vcolor, verdict))
    # 分隔线
    if i < len(CARDS) - 1:
        out.append(
            '  <line x1="{}" y1="{}" x2="{}" y2="{}" stroke="#2E2823"/>'.format(
                x + CARD_W - 1, PAD, x + CARD_W - 1, H - PAD))

out.append("</svg>")

content = "\n".join(out)
path = os.path.join(HERE, "_compare-logos.svg")
with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("已生成 _compare-logos.svg  {w}x{h}".format(w=W, h=H))
print("  画布 {} x {}，卡片 {} 张，每张宽 {}".format(W, H, len(CARDS), CARD_W))
