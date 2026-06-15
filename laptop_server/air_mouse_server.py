import argparse
import json
import socket
from typing import Any, Dict


def load_pyautogui(dry_run: bool):
    if dry_run:
        return None
    import pyautogui

    pyautogui.FAILSAFE = True
    return pyautogui


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Receive AirMouse UDP packets and control the laptop mouse.")
    parser.add_argument("--host", default="0.0.0.0", help="UDP bind host. Default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=5000, help="UDP bind port. Default: 5000")
    parser.add_argument("--move-scale", type=float, default=1.0, help="Multiplier for cursor movement deltas.")
    parser.add_argument("--scroll-scale", type=int, default=5, help="Multiplier for scroll packets.")
    parser.add_argument("--dry-run", action="store_true", help="Print packets and ACKs without moving the mouse.")
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


def handle_packet(packet: Dict[str, Any], pyautogui, move_scale: float, scroll_scale: int, dry_run: bool) -> None:
    delta_x = as_float(packet, "deltaX") * move_scale
    delta_y = as_float(packet, "deltaY") * move_scale
    click = bool(packet.get("click", False))
    scroll = as_int(packet, "scroll")

    if dry_run:
        return

    if delta_x or delta_y:
        pyautogui.moveRel(delta_x, delta_y, duration=0)
    if click:
        pyautogui.click(button="left")
    if scroll:
        pyautogui.scroll(scroll * scroll_scale)


def main() -> int:
    args = parse_args()
    pyautogui = load_pyautogui(args.dry_run)

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        server.bind((args.host, args.port))
        print(f"AirMouse UDP server listening on {args.host}:{args.port} dry_run={args.dry_run}")

        while True:
            data, address = server.recvfrom(4096)
            try:
                text = data.decode("utf-8")
                packet = json.loads(text)
                if not isinstance(packet, dict):
                    raise ValueError("packet JSON is not an object")
            except Exception as exc:
                print(f"Bad packet from {address}: {exc}")
                continue

            seq = packet.get("seq")
            delta_x = as_float(packet, "deltaX")
            delta_y = as_float(packet, "deltaY")
            click = bool(packet.get("click", False))
            scroll = as_int(packet, "scroll")
            requires_ack = bool(packet.get("requiresAck", False))
            print(
                f"packet seq={seq} dx={delta_x:.2f} dy={delta_y:.2f} "
                f"click={click} scroll={scroll} ack={requires_ack} from={address}"
            )

            try:
                handle_packet(packet, pyautogui, args.move_scale, args.scroll_scale, args.dry_run)
            except Exception as exc:
                print(f"Mouse action failed for seq={seq}: {exc}")

            if requires_ack and seq is not None:
                ack = json.dumps({"ack": seq}).encode("utf-8")
                server.sendto(ack, address)
                print(f"ack seq={seq} to={address}")


if __name__ == "__main__":
    raise SystemExit(main())
