"""Check the production AGSL rim expression against the boundary-light invariant.

Runs without Android. --source also accepts a previous GlassDrawable.java revision.
This checks scalar math, not GPU compilation or final OEM rendering.
"""
import argparse
from pathlib import Path
import re
import unittest


def smoothstep(low, high, value):
    t = max(0.0, min(1.0, (value - low) / (high - low)))
    return t * t * (3.0 - 2.0 * t)


class RimInvariant(unittest.TestCase):
    def weight(self, distance, width):
        return eval(EXPRESSION, {"__builtins__": {}},
                    {"smoothstep": smoothstep, "sd": distance, "rimWidth": width})

    def test_flat_interior_has_no_directional_light(self):
        for width in (1.5, 6.0, 12.0, 18.0):
            for distance in (-width, -width * 2, -500):
                self.assertEqual(self.weight(distance, width), 0.0)

    def test_light_is_strongest_at_boundary(self):
        for width in (1.5, 6.0, 12.0, 18.0):
            self.assertEqual(self.weight(0, width), 1.0)
            weights = [self.weight(-width + width * i / 100, width) for i in range(101)]
            self.assertEqual(weights, sorted(weights))

    def test_medial_axis_normal_switch_cannot_change_flat_face(self):
        # A rectangle's closest edge switches across its internal diagonal. Two limiting
        # normals may therefore produce different lighting, but neither may shade its flat face.
        for normal in ((1.0, 0.0), (0.0, 1.0), (-1.0, 0.0), (0.0, -1.0)):
            dot = normal[0] * -.72 + normal[1] * -.70
            reflection = max(-dot, 0) ** 3
            shade = max(dot, 0) ** 2
            rim = self.weight(-100, 18)
            output = .5 * (1 - shade * rim * .10) + rim * (.025 + reflection * .14)
            self.assertEqual(output, .5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2] /
                        "app/src/main/java/net/z841973620/colorosliquidglass/glass/GlassDrawable.java")
    args, remaining = parser.parse_known_args()
    source = args.source.read_text(encoding="utf-8")
    EXPRESSION = re.search(r"float rim = ([^;]+);", source).group(1)
    unittest.main(argv=[__file__, *remaining])
