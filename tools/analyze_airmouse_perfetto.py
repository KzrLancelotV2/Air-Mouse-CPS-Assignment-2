#!/usr/bin/env python3
"""Summarize AirMouse Perfetto traces.

Requires the Perfetto TraceProcessor Python API when available:
    pip install perfetto

The script intentionally reports trace-slice wall durations. It does not invent
CPU time from wall slices when scheduler-derived CPU accounting is unavailable.
"""

from __future__ import annotations

import argparse
import math
import statistics
import sys
from pathlib import Path
from typing import Any, Iterable


AIR_MOUSE_SLICES = [
    "AirMouse_SensorEvent_Copy",
    "AirMouse_Calibration_Apply",
    "AirMouse_GyroCalibration",
    "AirMouse_AccelCalibration",
    "AirMouse_MagCalibration",
    "AirMouse_GravityFilter",
    "AirMouse_Madgwick_Update",
    "AirMouse_Complementary_Update",
    "AirMouse_MotionProcessor",
    "AirMouse_CursorDelta",
    "AirMouse_ClickDetect",
    "AirMouse_ScrollDetect",
    "AirMouse_PacketCreate",
    "AirMouse_UDPSend",
    "AirMouse_UDP_AckReceive",
    "AirMouse_UDP_Retry",
    "AirMouse_UI_StatusUpdate",
    "AirMouse_UI_PreviewUpdate",
]

AIR_MOUSE_COUNTERS = [
    "AirMouse_GyroDtMs",
    "AirMouse_AccelDtMs",
    "AirMouse_MagDtMs",
    "AirMouse_MotionPacketSeq",
    "AirMouse_PendingReliablePackets",
    "AirMouse_FinalDeltaX",
    "AirMouse_FinalDeltaY",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze AirMouse Perfetto trace markers.")
    parser.add_argument("trace", type=Path, help="Path to .perfetto-trace file")
    return parser.parse_args()


def load_trace_processor(trace_path: Path):
    try:
        from perfetto.trace_processor import TraceProcessor  # type: ignore
    except Exception as exc:
        print(
            "WARNING: Perfetto TraceProcessor Python API is unavailable.\n"
            "Install it with: pip install perfetto\n"
            f"Import error: {exc}",
            file=sys.stderr,
        )
        return None

    try:
        return TraceProcessor(trace=str(trace_path))
    except Exception as exc:
        print(f"ERROR: could not open trace with TraceProcessor: {exc}", file=sys.stderr)
        return None


def row_to_dict(row: Any, columns: Iterable[str] | None = None) -> dict[str, Any]:
    if isinstance(row, dict):
        return row
    if hasattr(row, "_asdict"):
        return dict(row._asdict())
    if columns:
        return {column: getattr(row, column, None) for column in columns}
    if hasattr(row, "__dict__"):
        return dict(row.__dict__)
    return {}


def query(tp: Any, sql: str) -> list[dict[str, Any]]:
    try:
        result = tp.query(sql)
        columns = getattr(result, "columns", None)
        return [row_to_dict(row, columns) for row in result]
    except Exception as exc:
        compact = " ".join(sql.strip().split())
        print(f"WARNING: query failed: {compact}\n  {exc}")
        return []


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * pct / 100.0
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return ordered[int(index)]
    fraction = index - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def fmt_ms(value_ns: float | None) -> str:
    if value_ns is None:
        return "n/a"
    return f"{value_ns / 1_000_000.0:.3f} ms"


def print_slice_summary(tp: Any) -> None:
    print("\n== AirMouse Trace Slice Durations ==")
    for name in AIR_MOUSE_SLICES:
        rows = query(tp, f"select dur from slice where name = '{name}' and dur >= 0")
        durations = [float(row.get("dur", 0) or 0) for row in rows]
        if not durations:
            print(f"{name}: missing")
            continue
        print(
            f"{name}: count={len(durations)} "
            f"avg={fmt_ms(statistics.mean(durations))} "
            f"min={fmt_ms(min(durations))} "
            f"max={fmt_ms(max(durations))} "
            f"p95={fmt_ms(percentile(durations, 95))}"
        )

    print("\nNOTE: These are trace-slice wall durations. CPU time requires scheduler analysis; do not treat wall duration as exact CPU time.")


def print_sensor_event_summary(tp: Any) -> None:
    print("\n== Sensor Event Copy Slices ==")
    rows = query(
        tp,
        """
        select name, count(*) as count, avg(dur) as avg_dur, min(dur) as min_dur, max(dur) as max_dur
        from slice
        where name glob 'AirMouse_SensorEvent_*'
        group by name
        order by name
        """,
    )
    if not rows:
        print("No AirMouse_SensorEvent_* slices found.")
        return
    for row in rows:
        print(
            f"{row.get('name')}: count={row.get('count')} "
            f"avg={fmt_ms(float(row.get('avg_dur') or 0))} "
            f"min={fmt_ms(float(row.get('min_dur') or 0))} "
            f"max={fmt_ms(float(row.get('max_dur') or 0))}"
        )


def print_counter_summary(tp: Any) -> None:
    print("\n== AirMouse Counters ==")
    for name in AIR_MOUSE_COUNTERS:
        rows = query(
            tp,
            f"""
            select c.value as value
            from counter c
            join counter_track t on c.track_id = t.id
            where t.name = '{name}'
            order by c.ts
            """,
        )
        values = [float(row.get("value", 0) or 0) for row in rows]
        if not values:
            print(f"{name}: missing")
            continue
        suffix = ""
        if name in ("AirMouse_FinalDeltaX", "AirMouse_FinalDeltaY"):
            suffix = " (values are delta * 1000)"
        print(
            f"{name}: count={len(values)} "
            f"avg={statistics.mean(values):.3f} "
            f"min={min(values):.3f} "
            f"max={max(values):.3f}{suffix}"
        )


def print_thread_summary(tp: Any) -> None:
    print("\n== AirMouse Threads ==")
    rows = query(
        tp,
        """
        select distinct name
        from thread
        where name glob 'AirMouse*'
        order by name
        """,
    )
    if not rows:
        print("No AirMouse thread names found. Check that the trace includes process/thread metadata.")
    else:
        for row in rows:
            print(f"- {row.get('name')}")


def print_sched_summary(tp: Any) -> None:
    print("\n== Basic Scheduling Summary ==")
    rows = query(
        tp,
        """
        select thread.name as thread_name, thread_state.state as state, count(*) as count,
               sum(thread_state.dur) as total_dur
        from thread_state
        join thread using(utid)
        where thread.name glob 'AirMouse*'
        group by thread.name, thread_state.state
        order by thread.name, total_dur desc
        """,
    )
    if not rows:
        print("No thread_state summary available. Include sched/sched_switch and sched/sched_waking in the trace.")
        return
    for row in rows:
        print(
            f"{row.get('thread_name')} state={row.get('state')} "
            f"count={row.get('count')} total={fmt_ms(float(row.get('total_dur') or 0))}"
        )


def main() -> int:
    args = parse_args()
    if not args.trace.exists():
        print(f"ERROR: trace file not found: {args.trace}", file=sys.stderr)
        return 2

    tp = load_trace_processor(args.trace)
    if tp is None:
        return 2

    print(f"Trace: {args.trace}")
    print_slice_summary(tp)
    print_sensor_event_summary(tp)
    print_counter_summary(tp)
    print_thread_summary(tp)
    print_sched_summary(tp)
    print("\nFor full PC cursor latency, combine this Android trace with laptop_server --measure-timing logs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
