#!/usr/bin/env python3
"""Метрики performance-тестів k6 і JMeter в одному форматі та перевірка thresholds.

  python3 scripts/metrics.py results/k6-load-20261021-171500   -> метрики одного запуску
  python3 scripts/metrics.py                                    -> таблиця останніх запусків
"""
import json
import sys
from pathlib import Path

P95_LIMIT_MS = 500       # threshold: p95 < 500 мс
ERROR_LIMIT_PCT = 1.0    # threshold: error rate < 1 %
OPERATIONS = ["POST /rentals", "GET /rentals/{id}", "POST /rentals/{id}/return"]
PROFILES = {"baseline": 2, "load": 20, "stress": 100}   # VU у кожному профілі
RESULTS = Path(__file__).resolve().parent.parent / "results"


def k6_metrics(run: Path) -> dict:
    m = json.loads((run / "summary.json").read_text(encoding="utf-8"))["metrics"]
    duration = m["http_req_duration"]
    return {
        "requests": m["http_reqs"]["count"],
        "avg": duration["avg"],
        "p95": duration["p(95)"],
        "rps": m["http_reqs"]["rate"],
        "scenarios_ps": m.get("iterations", {}).get("rate", 0.0),
        "error_pct": m["http_req_failed"]["value"] * 100,
        "ops_p95": {op: m.get(f"http_req_duration{{name:{op}}}", {}).get("p(95)") for op in OPERATIONS},
    }


def jmeter_metrics(run: Path) -> dict:
    stats = json.loads((run / "report" / "statistics.json").read_text(encoding="utf-8"))
    total = stats["Total"]
    return {
        "requests": total["sampleCount"],
        "avg": total["meanResTime"],
        "p95": total["pct2ResTime"],
        "rps": total["throughput"],
        "scenarios_ps": stats.get("POST /rentals", {}).get("throughput", 0.0),
        "error_pct": total["errorPct"],
        "ops_p95": {op: stats.get(op, {}).get("pct2ResTime") for op in OPERATIONS},
    }


def read_run(run: Path) -> tuple[str, str, dict]:
    tool, profile, _ = run.name.split("-", 2)
    metrics = k6_metrics(run) if tool == "k6" else jmeter_metrics(run)
    return tool, profile, metrics


def verdict(m: dict) -> tuple[bool, bool]:
    return m["p95"] < P95_LIMIT_MS, m["error_pct"] < ERROR_LIMIT_PCT


def ok(flag: bool) -> str:
    return "PASS" if flag else "FAIL"


def print_run(run: Path) -> None:
    tool, profile, m = read_run(run)
    p95_ok, err_ok = verdict(m)
    print(f"\n=== {tool} / {profile} ({PROFILES.get(profile, '?')} VU) — {run.name}")
    print(f"  запитів:      {m['requests']}")
    print(f"  average:      {m['avg']:.1f} ms")
    print(f"  p95:          {m['p95']:.1f} ms")
    print(f"  throughput:   {m['rps']:.1f} req/s ({m['scenarios_ps']:.1f} сценаріїв/с)")
    print(f"  error rate:   {m['error_pct']:.2f} %")
    print("  p95 за операціями: " + ", ".join(
        f"{op} {v:.1f} ms" for op, v in m["ops_p95"].items() if v is not None))
    print(f"  thresholds:   p95 < {P95_LIMIT_MS} ms — {ok(p95_ok)}; error rate < {ERROR_LIMIT_PCT:g} % — {ok(err_ok)}\n")


def print_table() -> None:
    latest = {}
    for run in sorted(p for p in RESULTS.glob("*-*-*") if p.is_dir()):
        tool, profile, _ = run.name.split("-", 2)
        latest[(tool, profile)] = run          # сортування за часом -> лишається найновіший
    print("| Інструмент | Профіль | VU | Запитів | Average, ms | p95, ms | Throughput, req/s | Сценаріїв/с | Error rate, % | Thresholds |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for tool in ("k6", "jmeter"):
        for profile in PROFILES:
            run = latest.get((tool, profile))
            if run is None:
                continue
            try:
                _, _, m = read_run(run)
            except (FileNotFoundError, KeyError) as error:
                print(f"| {tool} | {profile} | — | немає даних ({error}) | | | | | | |")
                continue
            p95_ok, err_ok = verdict(m)
            print(f"| {'JMeter' if tool == 'jmeter' else 'k6'} | {profile} | {PROFILES[profile]} | {m['requests']} "
                  f"| {m['avg']:.1f} | {m['p95']:.1f} | {m['rps']:.1f} | {m['scenarios_ps']:.1f} "
                  f"| {m['error_pct']:.2f} | {'PASS' if p95_ok and err_ok else 'FAIL'} |")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        print_run(Path(sys.argv[1]))
    else:
        print_table()
