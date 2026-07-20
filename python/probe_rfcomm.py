"""Standalone RFCOMM probe: does classic-Bluetooth SPP work on this machine?

Tries to open a raw RFCOMM socket to the glasses on the channel seen in the
capture (13, with 3 as a fallback) using Python 3.13's native
socket.AF_BLUETOOTH / socket.BTPROTO_RFCOMM support on Windows. No BLE, no
StarryNet protocol yet -- this only proves the transport itself works.

Usage:
    python probe_rfcomm.py <BT-ADDRESS> [channel]

<BT-ADDRESS> is the classic-Bluetooth MAC, e.g. 2C:6F:4E:00:DC:47 (same
address space as the BLE address for this device based on the capture).
"""
from __future__ import annotations

import socket
import sys
import time


def try_channel(addr: str, channel: int, timeout: float = 8.0) -> None:
    print(f"\n--- trying RFCOMM channel {channel} ---")
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    s.settimeout(timeout)
    try:
        s.connect((addr, channel))
        print(f"CONNECTED to {addr} channel {channel}")
    except OSError as e:
        print(f"connect failed: {e}")
        s.close()
        return

    s.settimeout(5.0)
    try:
        for _ in range(10):
            data = s.recv(4096)
            if not data:
                print("peer closed the connection")
                break
            print(f"<- {len(data)} bytes: {data.hex()}")
            if data[:4] == b"\xea\xca\x93\x53":
                print("   (matches the eaca9353 StarryNet framing magic!)")
    except socket.timeout:
        print("(no data received within 5s -- link is open but silent)")
    except OSError as e:
        print(f"recv error: {e}")
    finally:
        s.close()
        print("socket closed")


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    addr = sys.argv[1]
    channels = [int(sys.argv[2])] if len(sys.argv) > 2 else [13, 3]
    for ch in channels:
        try_channel(addr, ch)
        time.sleep(1)


if __name__ == "__main__":
    main()
