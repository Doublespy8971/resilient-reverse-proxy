import csv
import importlib.util
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("analyze", "loadtest/analyze.py")
analyze = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyze)


class AnalyzeTest(unittest.TestCase):
    def test_counts_only_http_request_failures(self):
        rows = [
            ["metric_name", "timestamp", "status"],
            ["http_reqs", "2026-01-01T00:00:00Z", "200"],
            ["http_req_duration", "2026-01-01T00:00:00Z", "503"],
            ["vus", "2026-01-01T00:00:01Z", ""],
            ["http_reqs", "2026-01-01T00:00:01Z", "503"],
        ]
        with tempfile.NamedTemporaryFile(mode="w", newline="", encoding="utf-8") as csv_file:
            csv.writer(csv_file).writerows(rows)
            csv_file.flush()
            buckets, _ = analyze.read_failures(csv_file.name)

        self.assertEqual(1, sum(buckets.values()))


if __name__ == "__main__":
    unittest.main()
