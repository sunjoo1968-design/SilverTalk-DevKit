"""Render the r1.0 assembly and make a full-scale front-panel template."""

from pathlib import Path
from xml.sax.saxutils import escape

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from generate_enclosure import (
    BUTTONS, COSINE, DOCK_Y, FACE_HOLES, HERE, LEDS, LID_TOP,
    OLED, SINE, angled_piece, box, build, front_frame, front_hole, front_round,
    mesh, pcb, phone, text_solid,
)


BACKGROUND = "#edf1f5"
BODY = (104, 127, 149)
FRONT = (218, 226, 232)
DOCK = (87, 115, 131)
HARDWARE = (53, 63, 75)
FONT_DIR = Path("C:/Windows/Fonts")
INK = "#122d42"
SECONDARY = "#405f71"


def view(items, direction, dimensions):
    camera = np.array(direction) / np.linalg.norm(direction)
    right = np.cross((0, 0, 1), camera)
    right /= np.linalg.norm(right)
    up = np.cross(camera, right)
    matrix = np.stack([right, -up, camera], axis=1)
    projections = [(mesh(item), color) for item, color in items]
    clouds = [obj.vertices @ matrix for obj, _ in projections]
    all_points = np.concatenate(clouds)
    outline = all_points[:, :2]
    low, high = outline.min(axis=0), outline.max(axis=0)
    width, height = dimensions
    scale = np.min((np.array(dimensions) - 24) / (high - low))
    origin = np.array(dimensions) / 2 - (low + high) / 2 * scale
    image = np.broadcast_to(np.array([255, 255, 255, 0], dtype=np.uint8),
                            (height, width, 4)).copy()
    depth = np.full((height, width), -np.inf)
    light = np.array([-0.38, -0.48, 0.79])
    for (geometry, color), points in zip(projections, clouds):
        points[:, :2] = points[:, :2] * scale + origin
        for triangle, normal in zip(points[geometry.faces], geometry.face_normals):
            if normal @ camera <= 1e-8:
                continue
            x0, y0 = np.maximum(np.floor(triangle[:, :2].min(axis=0)).astype(int), 0)
            x1, y1 = np.minimum(np.ceil(triangle[:, :2].max(axis=0)).astype(int) + 1,
                                (width, height))
            pixel_x, pixel_y = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
            a, b, c = triangle
            v, w = b - a, c - a
            factor = v[0] * w[1] - v[1] * w[0]
            if abs(factor) < 1e-10:
                continue
            rel_x, rel_y = pixel_x - a[0], pixel_y - a[1]
            u = (rel_x * w[1] - rel_y * w[0]) / factor
            v_weight = (v[0] * rel_y - v[1] * rel_x) / factor
            z = a[2] + u * v[2] + v_weight * w[2]
            crop = depth[y0:y1, x0:x1]
            selected = (u >= 0) & (v_weight >= 0) & (u + v_weight <= 1) & (z > crop)
            crop[selected] = z[selected]
            brightness = 0.67 + 0.33 * max(0, light @ normal)
            rgb = np.clip(np.array(color) * brightness, 0, 255).astype(np.uint8)
            image[y0:y1, x0:x1][selected] = (*rgb, 255)
    return Image.fromarray(image)


def phone_mockup(width, height):
    screen = angled_piece(47 - width / 2 + 2, width - 4, -8.2, -7.5, 2.8, height - 2.8)
    # The lettering follows the screen, with its extrusion toward the viewer.
    clock = text_solid("10:28", 75, height * 0.54, min(19, width * 0.17), 0.16)
    clock = clock.transform(((1, 0, 0, 0),
                             (0, SINE, -COSINE, DOCK_Y + 20 - 8.23 * COSINE),
                             (0, COSINE, SINE, LID_TOP + 31 + 8.23 * SINE)))
    return [(phone(width, height, 7.8), HARDWARE),
            (screen, (42, 103, 126)), (clock, (230, 241, 245))]


def panel_mockup():
    screen_x, screen_z, _, _ = OLED
    items = [
        (front_round(screen_x - 12.7, screen_z - 7.0, 25.4, 14, 1, 2.5, 0.8),
         (16, 25, 32)),
        (front_frame(text_solid("READY", screen_x, screen_z - 1.1, 3.0, 0.15), 1.65),
         (104, 215, 239)),
    ]
    for x, z, hole, label in BUTTONS:
        color = (37, 124, 142) if label == "TALK" else HARDWARE
        items.append((front_hole(x, z, hole + 2.2, 0.5, 3.05), color))
    for (x, z), color in zip(LEDS, [(101, 192, 232), (247, 112, 125), (117, 216, 194),
                                    (119, 222, 164), (244, 194, 101)]):
        items.append((front_hole(x, z, 5.0, 0.4, 1.5), color))
    return items


def typeface(size, bold=False):
    family = "malgunbd.ttf" if bold else "malgun.ttf"
    return ImageFont.truetype(str(FONT_DIR / family), size)


def preview(shapes):
    body, panel, lid, dock = shapes.values()
    assembly = [(body, BODY), (panel, FRONT), (lid, FRONT), (dock, DOCK)]
    electronic_parts = [(pcb(), (54, 145, 112)),
                        (box(61, 39, 17, 28, 55, 2), (34, 65, 68)),
                        (box(64, 43, 19, 22, 24, 4.5), (179, 194, 204)),
                        (box(71, 88, 19, 8, 6.5, 3), (210, 213, 216))]
    image = Image.new("RGB", (1600, 1140), BACKGROUND)
    draw = ImageDraw.Draw(image)
    draw.text((55, 34), "SilverTalk DevKit 케이스 r1.0", font=typeface(46, True), fill=INK)
    draw.text((58, 103), "84 × 74 mm 캐리어 PCB  /  SunjooAn", font=typeface(23), fill=SECONDARY)

    def card(bounds, title, subtitle, art):
        x0, y0, x1, y1 = bounds
        draw.rounded_rectangle(bounds, radius=24, fill="white")
        draw.text((x0 + 26, y0 + 19), title, font=typeface(27, True), fill=INK)
        draw.text((x0 + 26, y0 + 59), subtitle, font=typeface(20), fill=SECONDARY)
        image.paste(art, (x0 + (x1 - x0 - art.width) // 2,
                          y0 + 90 + (y1 - y0 - 90 - art.height) // 2), art)

    portrait = view(assembly + panel_mockup() + phone_mockup(74.7, 158.4),
                    (0.57, -1, 0.43), (746, 810))
    card((52, 158, 818, 1090), "세로 거치 · 전면 조작",
         "왼쪽부터 OLED, 대기, 말하기, 연속 대화, 서비스 A/B", portrait)
    landscape = view(assembly + panel_mockup() + phone_mockup(158.4, 74.7),
                     (0.70, 1, 0.65), (685, 340))
    card((843, 158, 1548, 614), "가로 거치 · 후면",
         "ESP32 USB-C 케이블 개구 28 × 42 mm와 통풍 슬롯", landscape)
    inside = view([(body, BODY)] + electronic_parts, (0.6, -0.63, 1), (656, 355))
    card((843, 637, 1548, 1090), "상판을 연 상태",
         "PCB 지지대 4곳 · M3 · 홀 중심 간격 75 × 65 mm", inside)
    draw.text((58, 1105), "STL 형상에 휴대폰과 전자 부품을 예시로 합성했습니다.",
              font=typeface(17), fill=SECONDARY)
    image.save(HERE / "case-preview.png")


def template():
    left, top = 24, 39
    artwork = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="190mm" height="118mm" '
        'viewBox="0 0 190 118">',
        '<style>text{font-family:Arial,sans-serif;fill:#122d42}'
        'path,circle,rect,line{stroke:#122d42;stroke-width:.25;fill:none}</style>',
        '<text x="24" y="13" font-size="5" font-weight="bold">SilverTalk DevKit · Case r1.0</text>',
        '<text x="24" y="21" font-size="3.4">SunjooAn · 1:1 front panel · Print at 100%</text>',
        f'<rect x="{left}" y="{top}" width="141.1" height="46.3" rx="1.7"/>',
    ]

    def x_pos(x):
        return left + x - 4.45

    def y_pos(z):
        return top + 50.45 - z

    def labeled_hole(x, z, diameter):
        return f'<circle cx="{x_pos(x):.3f}" cy="{y_pos(z):.3f}" r="{diameter / 2}"/>'

    x, z, width, height = OLED
    artwork += [
        f'<rect x="{x_pos(x - width / 2):.3f}" y="{y_pos(z + height / 2):.3f}" '
        f'width="{width}" height="{height}" rx="1"/>',
        f'<text x="{x_pos(x)}" y="{y_pos(z) + 1}" text-anchor="middle" font-size="2.5">'
        '24.8 × 14.0</text>',
    ]
    for x, z, diameter, label in BUTTONS:
        artwork.append(labeled_hole(x, z, diameter))
        artwork.append(f'<text x="{x_pos(x)}" y="{y_pos(z) + 1}" font-size="2.25" '
                       f'text-anchor="middle">{diameter}</text>')
        artwork.append(f'<text x="{x_pos(x)}" y="79.4" font-size="2.8" '
                       f'text-anchor="middle">{escape(label)}</text>')
    artwork.extend(labeled_hole(x, z, 5.6) for x, z in LEDS)
    artwork.extend(labeled_hole(x, z, 3.9) for x, z in FACE_HOLES)
    artwork.extend([
        '<line x1="24" y1="99" x2="74" y2="99"/>',
        '<line x1="24" y1="97" x2="24" y2="101"/>',
        '<line x1="74" y1="97" x2="74" y2="101"/>',
        '<text x="49" y="106" text-anchor="middle" font-size="3.2">50 mm</text>',
        '<text x="85" y="99" font-size="3.0">LED Ø5.6 · panel screws Ø3.9 · mm</text>',
        '</svg>',
    ])
    (HERE / "front-panel-1to1.svg").write_text("\n".join(artwork) + "\n", encoding="utf-8")


if __name__ == "__main__":
    preview(build())
    template()
