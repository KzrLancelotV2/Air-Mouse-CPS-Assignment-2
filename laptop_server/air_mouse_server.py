import argparse
import errno
import json
import socket
import time
from typing import Any, Dict


def load_pyautogui(dry_run: bool):
    if dry_run:
        return None
    import pyautogui

    pyautogui.FAILSAFE = True
    pyautogui.PAUSE = 0
    return pyautogui


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Receive AirMouse UDP packets and control the laptop mouse.")
    parser.add_argument("--host", default="0.0.0.0", help="UDP bind host. Default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=5000, help="UDP bind port. Default: 5000")
    parser.add_argument("--move-scale", type=float, default=10.0, help="Multiplier for relative cursor movement deltas.")
    parser.add_argument("--scroll-scale", type=int, default=180, help="Total scroll clicks per scroll packet.")
    parser.add_argument("--scroll-steps", type=int, default=10, help="Split each scroll packet into this many small steps.")
    parser.add_argument("--scroll-duration", type=float, default=0.22, help="Seconds over which to apply one scroll packet.")
    parser.add_argument("--dry-run", action="store_true", help="Print packets and ACKs without moving the mouse.")
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Debug gestures: apply movement, but only print click/scroll instead of applying them.",
    )
    parser.add_argument(
        "--gesture-dry-run",
        action="store_true",
        help="Same as --debug. Kept for compatibility.",
    )
    parser.add_argument(
        "--measure-timing",
        action="store_true",
        help="Print perf_counter_ns receive/action timestamps for end-to-end latency analysis.",
    )
    return parser.parse_args()


def as_float(packet: Dict[str, Any], key: str) -> float:
    try:
        return float(packet.get(key, 0.0))
    except (TypeError, ValueError):
        return 0.0


def as_int(packet: Dict[str, Any], key: str) -> int:
    try:
        return int(packet.get(key, 0))
    except (TypeError, ValueError):
        return 0


def local_ipv4_candidates() -> list[str]:
    addresses = set()
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            addresses.add(info[4][0])
    except OSError:
        pass

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            addresses.add(probe.getsockname()[0])
    except OSError:
        pass

    return sorted(address for address in addresses if not address.startswith("127."))


def handle_packet(
    packet: Dict[str, Any],
    pyautogui,
    move_scale: float,
    scroll_scale: int,
    scroll_steps: int,
    scroll_duration: float,
    dry_run: bool,
    gesture_dry_run: bool,
) -> tuple[int | None, int | None]:
    delta_x = as_float(packet, "deltaX") * move_scale
    delta_y = as_float(packet, "deltaY") * move_scale
    click = bool(packet.get("click", False))
    scroll = as_int(packet, "scroll")
    action_start_ns = None

    def mark_action_start() -> None:
        nonlocal action_start_ns
        if action_start_ns is None:
            action_start_ns = time.perf_counter_ns()

    if dry_run:
        return None, None

    if delta_x or delta_y:
        mark_action_start()
        pyautogui.moveRel(delta_x, delta_y, duration=0)
    if click:
        if gesture_dry_run:
            print("gesture-dry-run: LEFT CLICK detected, not applying to PC", flush=True)
        else:
            mark_action_start()
            pyautogui.click(button="left")
    if scroll:
        if gesture_dry_run:
            direction = "UP" if scroll > 0 else "DOWN"
            print(
                f"gesture-dry-run: SCROLL {direction} packet={scroll}, "
                f"would apply total={scroll * scroll_scale}",
                flush=True,
            )
        else:
            mark_action_start()
            apply_scroll(pyautogui, scroll * scroll_scale, scroll_steps, scroll_duration)
    action_end_ns = time.perf_counter_ns() if action_start_ns is not None else None
    return action_start_ns, action_end_ns


def apply_scroll(pyautogui, total_amount: int, steps: int, duration: float) -> None:
    steps = max(1, steps)
    if steps == 1:
        pyautogui.scroll(total_amount)
        return

    base = int(total_amount / steps)
    remainder = total_amount - base * steps
    delay = max(0.0, duration / steps)
    for index in range(steps):
        amount = base
        if remainder:
            amount += 1 if remainder > 0 else -1
            remainder -= 1 if remainder > 0 else -1
        if amount:
            pyautogui.scroll(amount)
        if index < steps - 1 and delay > 0:
            time.sleep(delay)


def main() -> int:
    args = parse_args()
    gesture_debug = args.debug or args.gesture_dry_run
    pyautogui = load_pyautogui(args.dry_run)

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        server.settimeout(0.25)
        try:
            server.bind((args.host, args.port))
        except OSError as exc:
            if exc.errno in (errno.EADDRINUSE, 10048):
                print(
                    f"Port {args.port} is already in use. Stop the existing AirMouse server "
                    f"or start this one with a different port, for example: --port {args.port + 1}"
                )
                return 2
            raise

        print(
            f"AirMouse UDP server listening on {args.host}:{args.port} "
            f"dry_run={args.dry_run} debug={gesture_debug}",
            flush=True,
        )
        candidates = local_ipv4_candidates()
        if candidates:
            print(
                "Use one of these PC IPv4 addresses in the Android app: "
                + ", ".join(candidates),
                flush=True,
            )
        print("Waiting for Android packets. Press Ctrl+C to stop.", flush=True)

        try:
            while True:
                try:
                    data, address = server.recvfrom(4096)
                except socket.timeout:
                    continue

                process_datagram(
                    server,
                    data,
                    address,
                    pyautogui,
                    args.move_scale,
                    args.scroll_scale,
                    args.scroll_steps,
                    args.scroll_duration,
                    args.dry_run,
                    gesture_debug,
                    args.measure_timing,
                )
        except KeyboardInterrupt:
            print("\nAirMouse UDP server stopped.", flush=True)
            return 0

    return 0


def process_datagram(
    server: socket.socket,
    data: bytes,
    address,
    pyautogui,
    move_scale: float,
    scroll_scale: int,
    scroll_steps: int,
    scroll_duration: float,
    dry_run: bool,
    gesture_debug: bool,
    measure_timing: bool,
) -> None:
    receive_perf_counter_ns = time.perf_counter_ns()
    try:
        text = data.decode("utf-8")
        packet = json.loads(text)
        if not isinstance(packet, dict):
            raise ValueError("packet JSON is not an object")
    except Exception as exc:
        print(f"Bad packet from {address}: {exc}", flush=True)
        return

    seq = packet.get("seq")
    delta_x = as_float(packet, "deltaX")
    delta_y = as_float(packet, "deltaY")
    click = bool(packet.get("click", False))
    scroll = as_int(packet, "scroll")
    requires_ack = bool(packet.get("requiresAck", False))
    sensor_timestamp_nanos = packet.get("sensorTimestampNanos")
    created_elapsed_nanos = packet.get("createdElapsedRealtimeNanos")
    sent_elapsed_nanos = packet.get("sentElapsedRealtimeNanos")
    print(
        f"packet seq={seq} dx={delta_x:.2f} dy={delta_y:.2f} "
        f"click={click} scroll={scroll} ack={requires_ack} from={address}",
        flush=True,
    )

    if requires_ack and seq is not None:
        ack = json.dumps({"ack": seq}).encode("utf-8")
        server.sendto(ack, address)
        print(f"ack seq={seq} to={address}", flush=True)

    try:
        action_start_ns, action_end_ns = handle_packet(
            packet,
            pyautogui,
            move_scale,
            scroll_scale,
            scroll_steps,
            scroll_duration,
            dry_run,
            gesture_debug,
        )
    except Exception as exc:
        print(f"Mouse action failed for seq={seq}: {exc}", flush=True)
    else:
        if measure_timing:
            action_duration_us = (
                None if action_start_ns is None or action_end_ns is None
                else (action_end_ns - action_start_ns) / 1000.0
            )
            print(
                "timing "
                f"seq={seq} recvPerfNs={receive_perf_counter_ns} "
                f"sensorTimestampNanos={sensor_timestamp_nanos} "
                f"createdElapsedRealtimeNanos={created_elapsed_nanos} "
                f"sentElapsedRealtimeNanos={sent_elapsed_nanos} "
                f"beforeActionPerfNs={action_start_ns} "
                f"afterActionPerfNs={action_end_ns} "
                f"actionDurationUs={action_duration_us}",
                flush=True,
            )


if __name__ == "__main__":
    raise SystemExit(main())
