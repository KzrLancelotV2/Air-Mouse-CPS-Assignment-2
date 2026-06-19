# AirMouse Laptop UDP Server

This server receives UDP JSON packets from the Android app and controls the laptop mouse with PyAutoGUI.

## Install

```bash
pip install pyautogui
```

## Run

```bash
python air_mouse_server.py --port 5000
```

## Dry Run

Use dry-run first to verify that packets and ACKs work without moving the cursor:

```bash
python air_mouse_server.py --dry-run
```

## Network Notes

Find the laptop IP on Windows:

```powershell
ipconfig
```

Enter the laptop IPv4 address in the Android app. The phone and laptop must be on the same Wi-Fi/network. Windows Firewall may ask for permission to allow Python to receive UDP traffic; allow it for the active network.

## Useful Options

```bash
python air_mouse_server.py --host 0.0.0.0 --port 5000 --move-scale 1.0 --scroll-scale 5
```
