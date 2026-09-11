"""Render fictional, simplified product-flow GIFs; never reads device/user data.

Requires Pillow. Run from any directory with --font pointing to a CJK font.
These are explanatory diagrams, not screenshots or claims of actual AI output.
"""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "guide" / "media"
W, H = 1000, 640
PAPER, INK, SAGE = "#F7F4EC", "#17211B", "#6A806E"
SOFT, MUTED, LINE, WHITE, CLAY = "#E2EAE0", "#5F6B63", "#DDE2DA", "#FFFDF8", "#C66A4A"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--font", default="C:/Windows/Fonts/msyh.ttc")
args = parser.parse_args()
if not Path(args.font).is_file():
    parser.error("Supply a local CJK font using --font; no font is downloaded.")
fonts = {size: ImageFont.truetype(args.font, size) for size in (14, 16, 18, 20, 22, 24, 28, 32, 38, 42)}


def txt(d, x, y, value, size=20, fill=INK):
    d.text((x, y), value, font=fonts[size], fill=fill)


def box(d, xy, fill=WHITE, radius=18, outline=None):
    d.rounded_rectangle(xy, radius=radius, fill=fill, outline=outline, width=1)


def lines(d, x, y, text, size=20, gap=32, fill=INK):
    for i, line in enumerate(text.split("\n")):
        txt(d, x, y + i * gap, line, size, fill)


def pill(d, x, y, label, active=True):
    width = int(d.textlength(label, font=fonts[16])) + 28
    box(d, (x, y, x + width, y + 34), SOFT if active else WHITE, 17, None if active else LINE)
    txt(d, x + 14, y + 5, label, 16)


def button(d, y, label, primary=True):
    box(d, (617, y, 917, y + 48), INK if primary else SOFT, 15)
    tw = d.textlength(label, font=fonts[18])
    txt(d, 767 - tw / 2, y + 11, label, 18, WHITE if primary else INK)


def base(series, title, desc, steps, active):
    im = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(im)
    box(d, (38, 33, 84, 79), INK, 15)
    d.rounded_rectangle((51, 46, 57, 66), radius=2, fill=PAPER)
    d.rounded_rectangle((65, 46, 71, 66), radius=2, fill=PAPER)
    txt(d, 99, 38, "停一下 / PauseCN", 24)
    txt(d, 40, 114, series, 16, SAGE)
    lines(d, 38, 150, title, 38, 52)
    lines(d, 40, 276, desc, 20, 33, MUTED)
    for i, label in enumerate(steps):
        yy = 410 + i * 47
        d.ellipse((40, yy, 68, yy + 28), fill=INK if i == active else SOFT)
        txt(d, 49, yy + 3, str(i + 1), 14, WHITE if i == active else MUTED)
        txt(d, 82, yy, label, 20, INK if i == active else MUTED)
    d.line((40, 592, 960, 592), fill=LINE, width=1)
    txt(d, 40, 606, "交互流程示意 · 虚构数据 · 非实机录屏", 16, MUTED)
    txt(d, 738, 606, "alpha40-dev / 可选 AI", 16, MUTED)
    box(d, (587, 24, 947, 577), INK, 38)
    box(d, (596, 33, 938, 568), WHITE, 31)
    txt(d, 616, 48, "9:41", 14)
    box(d, (732, 46, 802, 58), INK, 6)
    d.rounded_rectangle((896, 49, 918, 59), radius=2, outline=INK, width=1)
    d.rectangle((899, 52, 914, 56), fill=INK)
    d.rounded_rectangle((724, 554, 810, 558), radius=2, fill=INK)
    return im, d


def reminder_frame(phase, tick=0):
    im, d = base("01 / 个性化提醒", "让每次停顿，\n更贴近你的想法。", "风格、背景与已确认记忆，\n按授权为下次提醒提供上下文。\n有效短句提前准备，打开时直接显示。",
                 ["保存自己的背景与偏好", "按授权预生成下一次提醒", "停几秒，再自己决定"], phase)
    if phase == 0:
        txt(d, 617, 84, "我的背景与偏好", 24)
        pill(d, 617, 125, "保存在本机")
        txt(d, 617, 178, "我想调整的习惯", 16, MUTED)
        box(d, (617, 207, 917, 292), PAPER, 14)
        lines(d, 632, 221, "睡前少刷一会儿，\n查资料时仍然可以继续。", 20, 29)
        txt(d, 617, 310, "提醒的口吻", 16, MUTED)
        box(d, (617, 339, 917, 394), PAPER, 14)
        txt(d, 632, 354, "温和一点，也可以幽默。", 20)
        box(d, (617, 415, 917, 476), SOFT, 14)
        txt(d, 632, 422, "已确认的记忆 · 示例", 16, SAGE)
        txt(d, 632, 448, "查完攻略后，我想早点收工。", 18)
        button(d, 492, "保存背景")
    elif phase == 1:
        txt(d, 617, 84, "准备下次提醒", 24)
        pill(d, 617, 128, "小红书")
        txt(d, 617, 190, "本次授权的背景", 18, MUTED)
        for i, label in enumerate(["提醒风格", "我的背景与偏好", "相关的已确认记忆"]):
            yy = 228 + 46 * i
            d.ellipse((619, yy + 3, 639, yy + 23), fill=SOFT)
            txt(d, 648, yy, label, 20)
        box(d, (617, 377, 917, 463), SOFT, 16)
        txt(d, 634, 391, "已准备好 · 示例", 22)
        txt(d, 634, 427, "有效新句可直接用于下次停顿", 16, MUTED)
        lines(d, 617, 481, "生成可能收费；按功能授权发送。\n停顿本身不等待联网。", 16, 27, MUTED)
    else:
        pill(d, 617, 92, "即将打开 · 小红书")
        txt(d, 617, 151, "先停一下", 28)
        lines(d, 617, 215, "这次还要找攻略吗？\n找到就收工。", 24, 38)
        cx, cy, r = 767, 375, 47
        d.ellipse((cx-r, cy-r, cx+r, cy+r), outline=SOFT, width=5)
        if tick < 3:
            progress = (tick + 1) / 3
            d.arc((cx-r, cy-r, cx+r, cy+r), -90, -90 + progress * 360, fill=SAGE, width=5)
            txt(d, cx - 11, cy - 23, str(3 - tick), 32)
            txt(d, 717, 433, "慢慢呼吸", 20, MUTED)
            button(d, 486, "离开这个应用", primary=False)
        else:
            txt(d, 723, 353, "想好了", 22)
            button(d, 438, "选择理由，继续")
            button(d, 493, "离开这个应用", primary=False)
    return im


def heatmap(d, x, y, reveal=1.0, cell=15, gap=4):
    palette = ["#E8EDE4", "#C7D5C3", "#A1B69D", "#7D977A", "#526D53"]
    for col in range(14):
        for row in range(7):
            level = ((col * 17 + row * 11 + col * row) % 9) % 5 if col < 14 * reveal else 0
            box(d, (x+col*(cell+gap), y+row*(cell+gap), x+col*(cell+gap)+cell, y+row*(cell+gap)+cell), palette[level], 3)


def report_frame(phase, tick=0):
    im, d = base("02 / 记录与 AI 复盘", "看见使用节奏，\n也聊聊为什么。", "从本地记录出发，按授权生成解读。\n建议先看再决定，已有解读\n也能放进分享卡片。",
                 ["热力图里看见本地记录", "AI 解读，理解自己的选择", "选好模板，预览后分享"], phase)
    if phase == 0:
        txt(d, 617, 84, "记录", 28)
        pill(d, 617, 130, "停顿次数")
        pill(d, 742, 130, "全部目标", False)
        txt(d, 617, 188, "使用节奏 · 示例", 20)
        heatmap(d, 620, 231, min(1, (tick+1)/3))
        txt(d, 620, 370, "少", 14, MUTED)
        for i, col in enumerate(["#E8EDE4", "#C7D5C3", "#A1B69D", "#7D977A", "#526D53"]):
            box(d, (651+i*23, 373, 666+i*23, 388), col, 3)
        txt(d, 773, 370, "多", 14, MUTED)
        box(d, (617, 419, 917, 480), PAPER, 15)
        txt(d, 632, 437, "本周 24 次停顿 · 7 次离开", 18)
        txt(d, 618, 503, "停顿次数不等于全部打开次数", 16, MUTED)
    elif phase == 1:
        txt(d, 617, 84, "AI 使用解读", 24)
        pill(d, 617, 130, "本周 · 示例")
        box(d, (617, 188, 917, 390), PAPER, 16)
        txt(d, 634, 207, "给这一周的一点观察", 20)
        observation = ["记录了 24 次停顿，", "其中 7 次选择了离开。", "继续不代表失败，", "也可以是有目的的选择。"]
        for i, line in enumerate(observation[:min(4, tick+1)]):
            txt(d, 634, 252+i*30, line, 18)
        box(d, (617, 408, 917, 479), SOFT, 15)
        lines(d, 632, 420, "下次可以试试：\n打开前，先说清这次想做什么。", 18, 27)
        txt(d, 617, 500, "AI 可能误解记录，建议由你判断。", 16, MUTED)
    else:
        txt(d, 617, 84, "分享本周", 24)
        pill(d, 617, 129, "加入已有 AI 解读")
        box(d, (626, 180, 908, 472), PAPER, 18, LINE)
        txt(d, 645, 198, "停一下 / 我的这一周", 18, SAGE)
        txt(d, 645, 245, "24 次停顿", 28)
        txt(d, 645, 291, "7 次选择离开", 20)
        heatmap(d, 645, 328, cell=9, gap=3)
        # Compact excerpt below the heatmap, with no private details.
        lines(d, 645, 420, "继续不代表失败，\n也可以是有目的的选择。", 16, 23)
        button(d, 486, "检查预览，再选择接收应用")
    return im


def save_demo(name, frames, durations, poster):
    OUT.mkdir(parents=True, exist_ok=True)
    # Shared palette avoids color flicker; Pillow merges identical holds.
    palette = frames[poster].quantize(colors=128)
    indexed = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames]
    path = OUT / f"{name}.gif"
    indexed[0].save(path, save_all=True, append_images=indexed[1:], duration=durations,
                    loop=0, optimize=True, disposal=1)
    frames[poster].save(OUT / f"{name}.png", optimize=True)
    with Image.open(path) as check:
        assert check.n_frames > 1 and check.size == (W, H)
        assert sum(check.seek(i) or check.info["duration"] for i in range(check.n_frames)) == sum(durations)
    print(f"{path.relative_to(ROOT)}: {path.stat().st_size:,} bytes, {sum(durations)/1000:g}s")


save_demo("ai-reminder", [reminder_frame(0), reminder_frame(1)] + [reminder_frame(2, i) for i in range(4)],
          [2600, 2600, 1000, 1000, 1000, 2800], 5)
save_demo("ai-report", [report_frame(0, i) for i in range(3)] + [report_frame(1, i) for i in range(4)] + [report_frame(2)],
          [400, 400, 2000, 700, 700, 700, 2500, 3000], 7)
