import importlib.util
from pathlib import Path
import unittest

module_path = Path(__file__).resolve().parents[1] / "rewrite-app-image.py"
spec = importlib.util.spec_from_file_location("rewrite_app_image", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OverrideTests(unittest.TestCase):
    def test_changes_only_application_image(self):
        original = "services:\n  app:\n    image: app:old\n    pull_policy: never\n  ocr:\n    image: ocr:stable\n    pull_policy: never\n"
        expected = original.replace("app:old", "app:new")
        self.assertEqual(module.rewrite(original, "app:new"), expected)

    def test_refuses_unknown_layout_or_multiple_images(self):
        for original in ["services: {}\n", "services:\n  app:\n    image: one\n    image: two\n", "services:\n  app:\n    build: .\n"]:
            with self.assertRaises(ValueError):
                module.rewrite(original, "app:new")


if __name__ == "__main__":
    unittest.main()
