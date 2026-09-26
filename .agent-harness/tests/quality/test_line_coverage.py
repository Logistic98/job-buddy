"""Coverage gate rejects rounded percentages and empty reports."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "line_coverage", Path(__file__).parents[2] / "scripts/check_line_coverage.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LineCoverageTest(unittest.TestCase):
    def test_exact_line_count_controls_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "coverage.json"
            for statements, missing, valid in [(100000, 20001, False), (0, 0, False), (10, 0, True), (10, 2, True), (10, 3, False)]:
                with self.subTest(statements=statements, missing=missing):
                    path.write_text(
                        json.dumps(
                            {
                                "files": {"app.py": {}},
                                "totals": {
                                    "num_statements": statements,
                                    "missing_lines": missing,
                                    "missing_branches": 3,
                                },
                            }
                        )
                    )
                    if valid:
                        module.check_report(path)
                    else:
                        with self.assertRaises(ValueError):
                            module.check_report(path)


if __name__ == "__main__":
    unittest.main()
