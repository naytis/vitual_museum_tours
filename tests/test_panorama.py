import json
import shutil
import uuid
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image
from panorama import run


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.root = Path.cwd() / (".test-" + uuid.uuid4().hex)
        self.root.mkdir()
        self.addCleanup(shutil.rmtree, self.root)
        self.src = self.root / "input"
        self.src.mkdir()
        self.out = self.root / "output"

    def picture(self, name="hall.png", size=(80, 40), mode="RGB"):
        path = self.src / name
        Image.new(mode, size).save(path)
        return path

    def test_resize_and_json(self):
        file = self.picture()
        original = file.read_bytes()
        entries = run(self.src, self.out, 41)
        data = json.loads((self.out / "manifest.json").read_text("utf-8"))
        self.assertEqual(data, {"schemaVersion": 1, "files": entries})
        with Image.open(self.out / entries[0]["output"]) as jpeg:
            self.assertEqual((jpeg.format, jpeg.size), ("JPEG", (40, 20)))
        self.assertEqual(file.read_bytes(), original)

    def test_no_upscale_and_alpha(self):
        entry, = run(self.picture(mode="RGBA"), self.out)
        with Image.open(self.out / entry["output"]) as result:
            self.assertEqual(result.size, (80, 40))
            self.assertEqual(result.getpixel((0, 0)), (255, 255, 255))

    def test_mixed_batch(self):
        self.picture("square.png", (32,32))
        self.picture("ok.jpg")
        (self.src / "bad.png").write_bytes(b"invalid")
        entries = run(self.src, self.out)
        self.assertEqual([e["valid"] for e in entries], [False,True,False])
        self.assertIn("2:1", entries[-1]["error"])

    def test_unsupported(self):
        self.assertFalse(run(self.picture("hall.bmp"), self.out)[0]["valid"])

    def test_missing(self):
        self.assertIn("error", run(self.src / "absent.jpg", self.out)[0])

    def test_decoding_truncated_jpeg(self):
        path = self.picture("cut.jpg")
        path.write_bytes(path.read_bytes()[:-18])
        self.assertFalse(run(path, self.out)[0]["valid"])

    def test_input_protected(self):
        with self.assertRaises(ValueError):
            run(self.src, self.src)

    def test_nonempty_destination(self):
        self.out.mkdir()
        keep = self.out / "keep"
        keep.touch()
        with self.assertRaises(ValueError):
            run(self.src, self.out)
        self.assertTrue(keep.exists())

    def test_bad_settings(self):
        for width, quality in [(1,85),(80,0),(80,96)]:
            with self.subTest(width=width, quality=quality), self.assertRaises(ValueError):
                run(self.src, self.out, width, quality)

    def test_save_error(self):
        path = self.picture()
        with patch("panorama.Image.Image.save", side_effect=OSError("disk full")):
            entry, = run(path, self.out)
        self.assertTrue(entry["valid"])
        self.assertEqual(entry["status"], "error")
        self.assertNotIn("output", entry)
        self.assertEqual(list(self.out.iterdir()), [self.out / "manifest.json"])

    def test_empty_directory(self):
        self.assertEqual(run(self.src, self.out), [])
        self.assertTrue((self.out / "manifest.json").exists())

    def test_same_stem(self):
        self.picture("hall.png")
        self.picture("hall.jpg")
        entries = run(self.src, self.out)
        self.assertEqual(len({e["output"] for e in entries}), 2)
