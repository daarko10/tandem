"""Draws the detector's test frames (needs Pillow; the PNGs are committed, so the tests do not).

    python3 scripts/bench/ttft/fixtures/make_fixtures.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT = "Reply with exactly the single word PONG and nothing else."
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
WIDTH, HEIGHT = 560, 220
FG, BG, BOX = (223, 225, 229), (30, 31, 34), (60, 63, 65)


def frame(name: str, lines: list, box_text: str = ""):
    """lines: (y, text). The prompt box is the band at the bottom (y 180 to 212), holding box_text."""
    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)
    for y, text in lines:
        draw.text((16, y), text, font=FONT, fill=FG)
    draw.rectangle((8, 180, WIDTH - 8, 212), outline=BOX, width=1)
    if box_text:
        draw.text((16, 188), box_text, font=FONT, fill=FG)
    image.save(os.path.join(HERE, name + ".png"), optimize=True)


frame("cold-before", [], box_text=PROMPT)
frame("cold-sent", [(16, PROMPT)])
frame("cold-reply", [(16, PROMPT), (56, "PONG")])
frame("warm-sent", [(16, PROMPT), (46, "PONG"), (86, PROMPT + " (2)")])
frame("warm-reply", [(16, PROMPT), (46, "PONG"), (86, PROMPT + " (2)"), (126, "PONG")])
frame("masked-pong", [(16, PROMPT)], box_text="PONG")
frame("tui-reply", [(16, "> " + PROMPT), (56, "● PONG")])
