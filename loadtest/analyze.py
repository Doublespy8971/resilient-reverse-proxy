#!/usr/bin/env python3

import argparse
import csv
from collections import Counter
from datetime import datetime
from math import floor


def parse_timestamp(value):
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        numeric = float(value)
        return numeric / 1_000_000_000 if numeric > 1_000_000_000_000 else numeric


def parse_status(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def read_failures(path):
    records = []
    with open(path, newline="", encoding="utf-8") as csv_file:
        for row in csv.DictReader(csv_file):
            if "timestamp" not in row or "status" not in row:
                continue
            records.append((parse_timestamp(row["timestamp"]), parse_status(row["status"])))

    if not records:
        raise ValueError("CSV contains no timestamped status records")

    first_timestamp = min(timestamp for timestamp, _ in records)
    buckets = Counter(
        (floor(timestamp - first_timestamp), status)
        for timestamp, status in records
        if status < 200 or status >= 300
    )
    return buckets


def main():
    parser = argparse.ArgumentParser(description="Summarize non-2xx k6 responses by elapsed second.")
    parser.add_argument("csv_file")
    parser.add_argument("--from", dest="start", type=int, default=0)
    parser.add_argument("--to", dest="end", type=int)
    args = parser.parse_args()

    buckets = read_failures(args.csv_file)
    end = args.end
    print("second,status,count")
    for (second, status), count in sorted(buckets.items()):
        print(f"{second},{status},{count}")

    failed_in_window = sum(
        count for (second, _), count in buckets.items()
        if second >= args.start and (end is None or second < end)
    )
    window_end = "end" if end is None else str(end)
    print(f"failed_requests_seconds_{args.start}_to_{window_end}={failed_in_window}")


if __name__ == "__main__":
    main()
