#!/usr/bin/env python3
"""k6 --summary-export JSON들을 모아 results.csv를 만들고 비교 그래프를 그린다.

사용법: python3 parse_results.py (benchmark/results 안에서, 또는 경로 자동 추정)
"""
import csv
import json
import re
from pathlib import Path

import matplotlib.pyplot as plt

RESULTS_DIR = Path(__file__).resolve().parent.parent / "results"
VU_LEVELS = [10, 50, 100, 200]
LOCK_TYPES = ["pessimistic", "optimistic"]


def load_summary(lock_type, vus):
    path = RESULTS_DIR / f"{lock_type}_vu{vus}.json"
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_verify(lock_type, vus):
    path = RESULTS_DIR / f"{lock_type}_vu{vus}.verify.txt"
    text = path.read_text(encoding="utf-8")
    return "OK" in text


def extract_row(lock_type, vus):
    summary = load_summary(lock_type, vus)
    metrics = summary["metrics"]

    http_reqs = metrics["http_reqs"]["count"]
    tps = metrics["http_reqs"]["rate"]
    duration_s = http_reqs / tps if tps else 0
    dur = metrics["http_req_duration"]
    p50 = dur.get("med", 0)
    p95 = dur.get("p(95)", 0)
    p99 = dur.get("p(99)", 0)
    error_rate = metrics["http_req_failed"]["value"]

    avg_attempts = ""
    retry_rate = ""
    if "optimistic_attempt_count" in metrics:
        attempt_metric = metrics["optimistic_attempt_count"]
        avg_attempts = attempt_metric.get("avg", 1)
        # k6 Trend엔 "1보다 큰 비율"이 없어서, checks 대신 avg로부터 근사한다:
        # avg_attempts == 1이면 재시도가 전혀 없었다는 뜻이고, 클수록 재시도가 잦았다는 뜻.
        # (avg - 1)을 "평균적으로 요청 하나당 몇 번의 추가 재시도가 있었는지"로 보여준다.
        retry_rate = avg_attempts - 1

    balance_ok = load_verify(lock_type, vus)

    return {
        "lock_type": lock_type,
        "vus": vus,
        "http_reqs": http_reqs,
        "duration_s": round(duration_s, 2),
        "tps": round(tps, 2),
        "p50_ms": round(p50, 2),
        "p95_ms": round(p95, 2),
        "p99_ms": round(p99, 2),
        "error_rate": round(error_rate, 4),
        "avg_attempts": round(avg_attempts, 3) if avg_attempts != "" else "",
        "retry_rate": round(retry_rate, 3) if retry_rate != "" else "",
        "balance_ok": balance_ok,
    }


def main():
    rows = []
    for lock_type in LOCK_TYPES:
        for vus in VU_LEVELS:
            rows.append(extract_row(lock_type, vus))

    csv_path = RESULTS_DIR / "results.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"CSV: {csv_path}")

    for row in rows:
        print(row)

    draw_charts(rows)


def draw_charts(rows):
    by_type = {lt: [r for r in rows if r["lock_type"] == lt] for lt in LOCK_TYPES}

    # 1) TPS
    plt.figure(figsize=(6, 4))
    for lt, marker in [("pessimistic", "o-"), ("optimistic", "s-")]:
        xs = [r["vus"] for r in by_type[lt]]
        ys = [r["tps"] for r in by_type[lt]]
        plt.plot(xs, ys, marker, label=lt)
    plt.xlabel("Virtual Users (VU)")
    plt.ylabel("Throughput (req/s)")
    plt.title("TPS: pessimistic vs optimistic")
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(RESULTS_DIR / "chart_tps.png", dpi=120)
    plt.close()

    # 2) p95 latency
    plt.figure(figsize=(6, 4))
    for lt, marker in [("pessimistic", "o-"), ("optimistic", "s-")]:
        xs = [r["vus"] for r in by_type[lt]]
        ys = [r["p95_ms"] for r in by_type[lt]]
        plt.plot(xs, ys, marker, label=lt)
    plt.xlabel("Virtual Users (VU)")
    plt.ylabel("p95 latency (ms)")
    plt.title("p95 Latency: pessimistic vs optimistic")
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(RESULTS_DIR / "chart_p95.png", dpi=120)
    plt.close()

    # 3) error rate
    plt.figure(figsize=(6, 4))
    for lt, marker in [("pessimistic", "o-"), ("optimistic", "s-")]:
        xs = [r["vus"] for r in by_type[lt]]
        ys = [r["error_rate"] * 100 for r in by_type[lt]]
        plt.plot(xs, ys, marker, label=lt)
    plt.xlabel("Virtual Users (VU)")
    plt.ylabel("Error rate (%)")
    plt.title("Error Rate: pessimistic vs optimistic")
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(RESULTS_DIR / "chart_error_rate.png", dpi=120)
    plt.close()

    # 4) optimistic retry rate
    plt.figure(figsize=(6, 4))
    xs = [r["vus"] for r in by_type["optimistic"]]
    ys = [r["avg_attempts"] for r in by_type["optimistic"]]
    plt.bar([str(x) for x in xs], ys, color="tab:orange")
    plt.xlabel("Virtual Users (VU)")
    plt.ylabel("Average attempts per request")
    plt.title("Optimistic lock: average attempts (1 = no retry)")
    plt.axhline(y=1, color="gray", linestyle="--", linewidth=1)
    plt.grid(True, axis="y", alpha=0.3)
    plt.tight_layout()
    plt.savefig(RESULTS_DIR / "chart_retry.png", dpi=120)
    plt.close()

    print(f"Charts saved to {RESULTS_DIR}")


if __name__ == "__main__":
    main()
