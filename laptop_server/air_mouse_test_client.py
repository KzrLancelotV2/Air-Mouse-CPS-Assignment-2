import argparse
import json
import socket
import time
from typing import Dict, Iterable, Iterator, Tuple


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send AirMouse-like UDP packets without an Android phone.")
    parser.add_argument("--host", default="127.0.0.1", help="Server host. Default: 127.0.0.1")
    parser.add_argument("--port", type=int, default=5000, help="Server UDP port. Default: 5000")
    parser.add_argument(
        "--scenario",
        choices=("movement", "click", "scroll-up", "scroll-down", "gestures", "all"),
        default="all",
        help="Packet scenario to send.",
    )
    parser.add_argument("--interval", type=float, default=0.02, help="Delay between packets in seconds.")
    parser.add_argument("--movement-scale", type=float, default=1.0, help="Multiplier for movement test deltas.")
    parser.add_argument("--wait-ack", action="store_true", help="Wait briefly for ACKs on click/scroll packets.")
    parser.add_argument("--once", action="store_true", help="Send the selected scenario once, then exit.")
    parser.add_argument("--pause-between-cycles", type=float, default=0.45, help="Delay between repeated scenarios.")
    return parser.parse_args()


def packet(seq: int, delta_x: float = 0.0, delta_y: float = 0.0, click: bool = False, scroll: int = 0) -> Dict[str, object]:
    return {
        "seq": seq,
        "timestamp": int(time.time() * 1000),
        "sensorTimestampNanos": time.monotonic_ns(),
        "deltaX": float(delta_x),
        "deltaY": float(delta_y),
        "click": click,
        "scroll": int(scroll),
        "requiresAck": bool(click or scroll),
    }


def movement_packets(start_seq: int, scale: float) -> Iterator[Tuple[str, Dict[str, object]]]:
    seq = start_seq
    # Deltas stay in the same rough per-packet range as the Android app, but are
    # large enough to visibly exercise the laptop receiver.
    path = [
        (8.0, 0.0),
        (10.0, 0.0),
        (12.0, 0.0),
        (8.0, 0.0),
        (0.0, 7.0),
        (0.0, 9.0),
        (0.0, 11.0),
        (0.0, 7.0),
        (-8.0, 0.0),
        (-10.0, 0.0),
        (-12.0, 0.0),
        (-8.0, 0.0),
        (0.0, -7.0),
        (0.0, -9.0),
        (0.0, -11.0),
        (0.0, -7.0),
        (0.0, 0.0),
    ]
    for delta_x, delta_y in path:
        yield "movement", packet(seq, delta_x * scale, delta_y * scale)
        seq += 1


def scenario_packets(name: str, movement_scale: float, start_seq: int) -> Iterable[Tuple[str, Dict[str, object]]]:
    seq = start_seq
    if name in ("movement", "all"):
        yield from movement_packets(seq, movement_scale)
        seq += 17

    if name in ("click", "gestures", "all"):
        yield "click", packet(seq, click=True)
        seq += 1

    if name in ("scroll-up", "gestures", "all"):
        yield "scroll-up", packet(seq, scroll=1)
        seq += 1

    if name in ("scroll-down", "gestures", "all"):
        yield "scroll-down", packet(seq, scroll=-1)


def send_packet(sock: socket.socket, address: Tuple[str, int], label: str, payload: Dict[str, object], wait_ack: bool) -> None:
    text = json.dumps(payload)
    sock.sendto(text.encode("utf-8"), address)
    print(f"sent {label}: {text}")

    if not wait_ack or not payload.get("requiresAck"):
        return

    sock.settimeout(0.4)
    try:
        data, _ = sock.recvfrom(512)
        print(f"received ACK: {data.decode('utf-8', errors='replace')}")
    except socket.timeout:
        print("received ACK: timeout")


def main() -> int:
    args = parse_args()
    address = (args.host, args.port)
    seq = 1

    print(
        f"Sending scenario={args.scenario} to {args.host}:{args.port}. "
        f"{'One cycle only.' if args.once else 'Press Ctrl+C to stop.'}"
    )

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            while True:
                for label, payload in scenario_packets(args.scenario, args.movement_scale, seq):
                    send_packet(sock, address, label, payload, args.wait_ack)
                    seq = int(payload["seq"]) + 1
                    time.sleep(args.interval)
                if args.once:
                    break
                time.sleep(args.pause_between_cycles)
    except KeyboardInterrupt:
        print("\nAirMouse test client stopped.")
        return 0

    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
