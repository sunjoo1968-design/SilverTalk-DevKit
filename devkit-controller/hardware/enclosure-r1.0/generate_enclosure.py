"""SilverTalk DevKit case r1.0, by SunjooAn. Dimensions in millimetres."""

import json
from itertools import combinations
from pathlib import Path

import manifold3d as md
import numpy as np
import trimesh
from matplotlib.font_manager import FontProperties
from matplotlib.textpath import TextPath


HERE = Path(__file__).resolve().parent
STL = HERE / "STL"
WIDTH, DEPTH, HEIGHT = 150, 126, 54
LID_TOP = 58
PCB_X, PCB_Y, PCB_Z = 33, 35, 10.7
PCB_OUTLINE = [(0, 0), (24, 0), (24, 12), (60, 12), (60, 0), (84, 0),
               (84, 74), (49, 74), (49, 62), (35, 62), (35, 74), (0, 74)]
PCB_HOLES = [(PCB_X + x, PCB_Y + y) for x in (4.5, 79.5) for y in (4.5, 69.5)]
FACE_HOLES = [(x, z) for x in (9.5, 140.5) for z in (11, 43)]
LID_HOLES = [(x, y) for x in (8, 142) for y in (34, 113)]
DOCK_X, DOCK_Y = 28, 64
DOCK_HOLES = [(DOCK_X + x, DOCK_Y + y) for x in (7, 87) for y in (7, 43)]
OLED = (30, 33, 24.8, 14.0)
BUTTONS = [(60, 23, 12.7, "WAKE"), (87, 23, 16.7, "TALK"),
           (114, 23, 12.7, "LIVE"), (133, 23, 6.7, "A/B")]
LEDS = [(60, 36.5), (87, 38), (114, 36.5), (126.5, 35.5), (136, 35.5)]
FONT = FontProperties(family="DejaVu Sans", weight="bold")
ANGLE = np.radians(18)
SINE, COSINE = np.sin(ANGLE), np.cos(ANGLE)


def box(x, y, z, dx, dy, dz):
    return md.Manifold.cube((dx, dy, dz)).translate((x, y, z))


def disk(x, y, z, diameter, height):
    return md.Manifold.cylinder(height, diameter / 2, circular_segments=72).translate((x, y, z))


def round_box(x, y, z, dx, dy, dz, radius):
    core = md.CrossSection.square((dx - 2 * radius, dy - 2 * radius))
    return core.offset(radius, md.JoinType.Round, circular_segments=40).extrude(dz).translate(
        (x + radius, y + radius, z))


def front_frame(shape, y):
    # Convert XY artwork to the front plane, with the extrusion pointing out.
    return shape.transform(((1, 0, 0, 0), (0, 0, -1, y), (0, 1, 0, 0)))


def front_hole(x, z, diameter, y_back, depth):
    return front_frame(disk(x, z, 0, diameter, depth), y_back)


def front_round(x, z, width, height, radius, y_back, depth):
    return front_frame(round_box(x, z, 0, width, height, depth, radius), y_back)


def text_solid(label, x, y, height, depth):
    path = TextPath((0, 0), label, size=9, prop=FONT)
    bounds = path.get_extents()
    size = height / bounds.height
    polygons = [(polygon - (bounds.x0 + bounds.width / 2, bounds.y0)) * size + (x, y)
                for polygon in path.to_polygons()]
    return md.CrossSection(polygons, md.FillRule.EvenOdd).extrude(depth)


def case_body():
    body = round_box(0, 0, 0, WIDTH, DEPTH, HEIGHT, 4)
    body -= round_box(3.2, 6.6, 3.6, 143.6, 116.2, 51, 2.0)
    body -= front_round(3.7, 3.6, 142.6, 47.4, 2.0, 3.7, 4.7)
    body -= front_round(15.0, 8, 120, 40, 2.0, 7, 8)
    body -= front_round(122, 15, 20.5, 25.4, 1.2, 7, 8)

    for x, y in PCB_HOLES:
        body += disk(x, y, 3.35, 11.4, PCB_Z - 3.35)
        body -= disk(x, y, -1, 3.9, PCB_Z + 2)
        body -= box(x - 3.15, y - 3.15, -1, 6.3, 6.3, 3.65)

    for x, y in LID_HOLES:
        body += disk(x, y, 3.35, 11.7, HEIGHT - 3.35)
        body -= disk(x, y, 45, 3.9, 10)
        x_start = x - 3.2 if x < WIDTH / 2 else x - 6.6
        body -= box(x_start, y - 3.2, 46.9, 9.8, 6.4, 2.8)

    for x, z in FACE_HOLES:
        body -= front_hole(x, z, 3.9, 7.1, 4.0)

    # Open rear slot allows unplugging the lid without threading the cable.
    body -= front_round(61, 12, 28, 46, 2.5, 127, 5.2)
    for x in (23, 100):
        for z in (17, 26, 35):
            body -= front_round(x, z, 27, 4.2, 1.95, 127, 5.2)
    return body


def front_panel():
    panel = front_round(4.45, 4.15, 141.1, 46.3, 1.7, 3.1, 3.1)
    x, z, width, height = OLED
    panel -= front_round(x - width / 2, z - height / 2, width, height, 1, 4, 5)
    for x, z, diameter, label in BUTTONS:
        panel -= front_hole(x, z, diameter, 4, 5)
        panel -= front_frame(text_solid(label, x, 9.4, 3.8, 0.8), 0.57)
    for x, z in LEDS:
        panel -= front_hole(x, z, 5.6, 4, 5)
    for x, z in FACE_HOLES:
        panel -= front_hole(x, z, 3.9, 4, 5)
    for label, x in (("A", 126.5), ("B", 136)):
        panel -= front_frame(text_solid(label, x, 40.4, 3.2, 0.8), 0.57)
    panel -= front_frame(text_solid("SilverTalk", 31, 11.2, 4.1, 0.8), 0.57)
    return panel


def lid():
    cover = round_box(0, 0, HEIGHT, WIDTH, DEPTH, LID_TOP - HEIGHT, 4)
    for x, y in LID_HOLES + DOCK_HOLES:
        cover -= disk(x, y, HEIGHT - 1, 4.0, LID_TOP - HEIGHT + 2)
    mark = text_solid("SunjooAn  |  Case r1.0", 75, 25, 5.2, 1)
    return cover - mark.translate((0, 0, 57.35))


def extrude_side(polygon, x, width):
    side = md.CrossSection([polygon], md.FillRule.EvenOdd).extrude(width)
    return side.transform(((0, 0, 1, x), (1, 0, 0, DOCK_Y), (0, 1, 0, LID_TOP)))


def holder_point(thickness, along):
    return 20 + thickness * COSINE + along * SINE, 31 - thickness * SINE + along * COSINE


def angled_piece(x, width, t_min, t_max, a_min, a_max):
    return extrude_side([holder_point(t, a) for t, a in
                         ((t_min, a_min), (t_max, a_min), (t_max, a_max), (t_min, a_max))],
                        DOCK_X + x, width)


def phone_dock():
    dock = round_box(DOCK_X, DOCK_Y, LID_TOP, 94, 50, 5, 3)
    dock -= box(DOCK_X + 27, DOCK_Y - 1, LID_TOP - 1, 40, 28, 7)
    for x in (14, 67):
        dock += angled_piece(x, 13, -18.5, 4.4, -4.6, 0.2)
        dock += angled_piece(x + 0.35, 12.3, -18.3, -14.5, -4.0, 6.5)
        dock += angled_piece(x, 13, -0.10, 4.4, -2, 56)
        dock += extrude_side(
            [(3.5, 4.2), (47, 4.2), (47, 13), holder_point(3.2, 55),
             holder_point(3.2, -1.7), holder_point(-17.6, -3.7)],
            DOCK_X + x + 0.7, 11.6)
        # Leave a path under the phone's bottom speaker and microphone.
        dock -= angled_piece(x + 3.5, 6, -20, 0.65, -2.1, 7)
    dock += angled_piece(16, 62, -0.12, 4.1, 32, 55)
    for x, y in DOCK_HOLES:
        dock -= disk(x, y, 57, 4.0, 7)
    return dock


def pcb():
    board = md.CrossSection([PCB_OUTLINE], md.FillRule.EvenOdd).extrude(1.6)
    board = board.translate((PCB_X, PCB_Y, PCB_Z))
    for x, y in PCB_HOLES:
        board -= disk(x, y, PCB_Z - 1, 3.2, 4)
    return board


def phone(width, height, thickness):
    return angled_piece(47 - width / 2, width, -thickness, -0.16, 0.3, height + 0.3)


def mesh(shape):
    vertices = shape.to_mesh64()
    return trimesh.Trimesh(vertices=vertices.vert_properties[:, :3], faces=vertices.tri_verts,
                           process=False)


def check_gap(solid, clearance, description):
    overlap = (solid ^ clearance).volume()
    if overlap > 0.002:
        raise ValueError(f"{description}: overlap {overlap:.3f} mm³")


def validate_assembly(parts):
    body, panel, cover, dock = parts.values()
    for (name_a, part_a), (name_b, part_b) in combinations(parts.items(), 2):
        if (part_a ^ part_b).volume() > 0.002:
            raise ValueError(f"Assembly collision: {name_a}, {name_b}")
    for name, part in parts.items():
        check_gap(part, pcb(), f"{name} / PCB")
        check_gap(part, box(57.5, 36, 13, 35, 61, 19), f"{name} / ESP32 envelope")
    check_gap(body, box(65, 91, 15.0, 20, 36, 14), "Rear USB-C plug envelope")
    check_gap(body, box(15.6, 3.2, 17.5, 28.8, 16.8, 29.5), "OLED and wire envelope")
    for x, z, diameter, label in BUTTONS:
        check_gap(body, box(x - diameter / 2 - 1, 3.2, z - diameter / 2 - 1,
                            diameter + 2, 24.5, diameter + 2), label)
    for width, height in [(74.7, 158.4), (158.4, 74.7), (80, 165), (165, 80)]:
        check_gap(dock, phone(width, height, 13.5), f"Phone {width} × {height}")
    # Portrait connector and strain relief fit into the gap between ledges.
    check_gap(dock, angled_piece(35, 24, -13, -1, -22, 0), "Galaxy charging plug")
    for x, y in PCB_HOLES:
        check_gap(body, disk(x, y, 0, 3.4, 13), "PCB screw path")
        check_gap(body, box(x - 2.8, y - 2.8, 0, 5.6, 5.6, 2.35), "PCB square nut")
    for x, y in LID_HOLES:
        check_gap(body, disk(x, y, 49.1, 3.4, 9), "Lid screw path")
        check_gap(body, box(x - 2.8, y - 2.8, 47.2, 5.6, 5.6, 2.15), "Lid square nut")
    for x, z in FACE_HOLES:
        check_gap(body + panel, front_hole(x, z, 3.4, 8, 9), "Panel screw path")
    for x, y in DOCK_HOLES:
        check_gap(dock + cover, disk(x, y, 53.9, 3.4, 10), "Dock screw path")
    for x, z in LEDS:
        check_gap(panel, front_hole(x, z, 5.2, 4.0, 5.0), "5 mm LED aperture")
        check_gap(body, front_hole(x, z, 6.2, 15, 11.8), "5 mm LED holder and leads")


def build():
    shapes = {"01-Body": case_body(), "02-Front": front_panel(), "03-Lid": lid(),
              "04-Galaxy-Dock": phone_dock()}
    validate_assembly(shapes)
    STL.mkdir(exist_ok=True)
    report = []
    for name, shape in shapes.items():
        output = mesh(shape.simplify(0.008))
        if name == "02-Front":
            output.apply_transform(trimesh.transformations.rotation_matrix(-np.pi / 2, [1, 0, 0]))
        output.apply_translation(-output.bounds[0])
        path = STL / f"SilverTalk-r1.0-{name}.stl"
        output.export(path)
        exported = trimesh.load_mesh(path)
        if not (exported.is_volume and exported.is_watertight and exported.is_winding_consistent
                and len(exported.split(only_watertight=False)) == 1):
            raise ValueError(f"Invalid STL: {path.name}")
        report.append({"file": path.name, "mm": exported.extents.round(2).tolist(),
                       "triangles": len(exported.faces), "cm3": round(exported.volume / 1000, 2),
                       "watertight": True, "connected_shells": 1})
    (HERE / "mesh-check.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return shapes


if __name__ == "__main__":
    build()
