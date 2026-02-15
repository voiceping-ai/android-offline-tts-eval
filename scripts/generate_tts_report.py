#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_PULLED_DIR = ROOT_DIR / "artifacts" / "pulled" / "tts_eval"
DEFAULT_REPORT_DIR = ROOT_DIR / "artifacts" / "report"


def _median(xs: List[float]) -> float:
    if not xs:
        return 0.0
    return float(statistics.median(xs))


def _p95(xs: List[float]) -> float:
    if not xs:
        return 0.0
    xs2 = sorted(xs)
    k = int(math.ceil(0.95 * len(xs2))) - 1
    k = max(0, min(k, len(xs2) - 1))
    return float(xs2[k])


def _read_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def _safe_get(d: Dict[str, Any], path: str, default: Any = None) -> Any:
    cur: Any = d
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


@dataclass(frozen=True)
class Row:
    model_id: str
    engine: str
    model_name: str
    prompt_set: str
    count: int
    median_load_ms: float
    median_synth_ms: float
    p95_synth_ms: float
    median_tok_per_s: float
    median_rtf: float
    status: str


def _group_by_model(results: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    out: Dict[str, List[Dict[str, Any]]] = {}
    for r in results:
        mid = _safe_get(r, "model.id", "") or ""
        if not mid:
            continue
        out.setdefault(mid, []).append(r)
    return out


def _summarize(results: List[Dict[str, Any]]) -> List[Row]:
    grouped = _group_by_model(results)
    rows: List[Row] = []
    for model_id, rs in grouped.items():
        engine = str(_safe_get(rs[0], "model.engine", "") or "")
        model_name = str(_safe_get(rs[0], "model.name", "") or model_id)

        load_ms = [float(_safe_get(r, "timing_ms.load", 0) or 0) for r in rs]
        load_nonzero = [x for x in load_ms if x > 0.0]
        synth_ms = [float(_safe_get(r, "timing_ms.synthesis", 0) or 0) for r in rs]
        tokps = [float(_safe_get(r, "metrics.tokens_per_second", 0.0) or 0.0) for r in rs]
        rtf_all = [float(_safe_get(r, "metrics.rtf", 0.0) or 0.0) for r in rs]
        rtf = [x for x in rtf_all if x > 0.0]

        rows.append(
            Row(
                model_id=model_id,
                engine=engine,
                model_name=model_name,
                prompt_set="en",
                count=len(rs),
                median_load_ms=_median(load_nonzero),
                median_synth_ms=_median(synth_ms),
                p95_synth_ms=_p95(synth_ms),
                median_tok_per_s=_median(tokps),
                median_rtf=_median(rtf),
                status="PASS" if len(rs) > 0 else "FAIL",
            )
        )

    # Lower RTF is better.
    rows.sort(key=lambda r: (r.median_rtf if r.median_rtf > 0 else 1e9, -r.median_tok_per_s))
    return rows


def _write_report(rows: List[Row], input_dir: Path, report_dir: Path) -> None:
    report_dir.mkdir(parents=True, exist_ok=True)

    out_json = [
        {
            "model_id": r.model_id,
            "engine": r.engine,
            "model_name": r.model_name,
            "prompt_set": r.prompt_set,
            "count": r.count,
            "median_load_ms": r.median_load_ms,
            "median_synth_ms": r.median_synth_ms,
            "p95_synth_ms": r.p95_synth_ms,
            "median_tok_per_s": r.median_tok_per_s,
            "median_rtf": r.median_rtf,
            "status": r.status,
        }
        for r in rows
    ]
    (report_dir / "tts_results.json").write_text(json.dumps(out_json, indent=2), encoding="utf-8")

    lines: List[str] = []
    lines.append("# Android Offline TTS Eval Report")
    lines.append("")
    lines.append(f"Input dir: `{input_dir}`")
    lines.append("")
    lines.append("| Model ID | Engine | Prompt set | Median load (ms) | Median synth (ms) | Median tok/s | Median RTF | Count | Status |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|---|")
    for r in rows:
        lines.append(
            f"| `{r.model_id}` | `{r.engine}` | {r.prompt_set} | "
            f"{r.median_load_ms:.0f} | {r.median_synth_ms:.0f} | {r.median_tok_per_s:.2f} | {r.median_rtf:.3f} | "
            f"{r.count} | {r.status} |"
        )
    (report_dir / "tts_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _svg_bar_chart(
    items: List[Tuple[str, float]],
    title: str,
    unit: str,
    out_path: Path,
    *,
    max_width: int = 780,
    bar_height: int = 18,
    gap: int = 8,
) -> None:
    if not items:
        out_path.write_text("", encoding="utf-8")
        return

    max_val = max(v for _, v in items) or 1.0
    left = 260
    top = 60
    width = left + max_width + 40
    height = top + len(items) * (bar_height + gap) + 40

    def esc(s: str) -> str:
        return (
            s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
            .replace("'", "&#39;")
        )

    parts: List[str] = []
    parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">')
    parts.append('<rect width="100%" height="100%" fill="#ffffff"/>')
    parts.append(f'<text x="20" y="30" font-family="ui-sans-serif, system-ui" font-size="18" fill="#111827">{esc(title)}</text>')
    parts.append(f'<text x="20" y="50" font-family="ui-sans-serif, system-ui" font-size="12" fill="#6b7280">Unit: {esc(unit)}</text>')

    y = top
    for label, val in items:
        frac = float(val) / float(max_val) if max_val > 0 else 0.0
        bar_w = int(max_width * frac)
        parts.append(f'<text x="20" y="{y + 13}" font-family="ui-monospace, SFMono-Regular, Menlo, monospace" font-size="12" fill="#111827">{esc(label)}</text>')
        parts.append(f'<rect x="{left}" y="{y}" width="{bar_w}" height="{bar_height}" rx="3" fill="#2563eb"/>')
        parts.append(f'<text x="{left + bar_w + 8}" y="{y + 13}" font-family="ui-sans-serif, system-ui" font-size="12" fill="#111827">{val:.3f}</text>')
        y += bar_height + gap

    parts.append("</svg>")
    out_path.write_text("\n".join(parts) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input_dir", default=str(DEFAULT_PULLED_DIR), help="Directory containing pulled tts_eval runs.")
    ap.add_argument("--run_id", default="", help="If set, only read this run_id subdirectory.")
    ap.add_argument("--output_dir", default=str(DEFAULT_REPORT_DIR), help="Output report directory.")
    args = ap.parse_args()

    input_dir = Path(args.input_dir)
    if not input_dir.exists():
        raise SystemExit(f"Missing pulled results dir: {input_dir}")

    if args.run_id.strip():
        input_dir = input_dir / args.run_id.strip()
        if not input_dir.exists():
            raise SystemExit(f"Missing run_id dir: {input_dir}")

    results = [_read_json(p) for p in sorted(input_dir.rglob("result.json"))]
    rows = _summarize(results)
    report_dir = Path(args.output_dir)
    _write_report(rows, input_dir=input_dir, report_dir=report_dir)

    # Charts
    tok_items = [(r.model_id, r.median_tok_per_s) for r in sorted(rows, key=lambda r: -r.median_tok_per_s)]
    rtf_items = [(r.model_id, r.median_rtf) for r in sorted(rows, key=lambda r: r.median_rtf if r.median_rtf > 0 else 1e9)]
    _svg_bar_chart(tok_items, "Median tok/s (tokens = words)", "words/sec", report_dir / "android_tts_tok_per_sec.svg")
    _svg_bar_chart(rtf_items, "Median RTF (lower is faster)", "rtf", report_dir / "android_tts_rtf.svg")

    print(f"Wrote: {report_dir / 'tts_report.md'}")
    print(f"Wrote: {report_dir / 'tts_results.json'}")
    print(f"Wrote: {report_dir / 'android_tts_tok_per_sec.svg'}")
    print(f"Wrote: {report_dir / 'android_tts_rtf.svg'}")


if __name__ == "__main__":
    main()
