"""Entry point: connect to the MYVU glasses over classic Bluetooth (RFCOMM/SPP).

Prerequisite: the glasses must already be BR/EDR-bonded to this PC via
Windows Settings > Bluetooth (pair them like any other Bluetooth device).
Do NOT have run.py's BLE session open at the same time as attempting that
pairing -- a concurrent connection has been observed to reset the glasses.

Usage:
  python run_rfcomm.py <BT-ADDRESS>
  python run_rfcomm.py <BT-ADDRESS> --mac 7C:A3:75:D0:94:F1 --channel 13

<BT-ADDRESS> is the classic-Bluetooth MAC (same address space as the BLE MAC
for this device, per the capture, e.g. 2C:6F:4E:00:DC:47).
"""
from __future__ import annotations

import argparse
import asyncio
import logging

from myvu.rfcomm import DEFAULT_CHANNEL
from myvu.rfcomm_client import MyvuRfcommClient
from run import repl


async def do_run(address: str, own_mac: str, channel: int) -> None:
    client = MyvuRfcommClient(address, own_mac=own_mac, channel=channel)
    try:
        await client.connect()
        await client.establish_session()
        client.start_drains()
        await asyncio.sleep(1.0)
        await repl(client)
    finally:
        await client.close()


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    ap = argparse.ArgumentParser()
    ap.add_argument("address", help="classic-Bluetooth MAC of the glasses")
    ap.add_argument("--mac", default="aa:bb:cc:dd:ee:ff",
                    help="identifier/MAC to present to the glasses")
    ap.add_argument("--channel", type=int, default=DEFAULT_CHANNEL,
                    help="RFCOMM channel (default 13, matches the capture)")
    args = ap.parse_args()
    asyncio.run(do_run(args.address, args.mac, args.channel))


if __name__ == "__main__":
    main()
