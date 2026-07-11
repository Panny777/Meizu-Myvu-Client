"""Entry point: scan, connect, pair, and stream from the MYVU glasses.

Usage:
  python run.py                 # scan and list MYVU/StarryNet devices
  python run.py <ADDRESS>       # connect + pair + listen
  python run.py <ADDRESS> --mac 7C:A3:75:D0:94:F1   # spoof a specific identifier

On Windows <ADDRESS> is the BLE MAC (as shown by the scan).
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os

from myvu.client import MyvuClient

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "myvu.log")


def configure_logging(log_file: str = LOG_FILE) -> logging.Handler:
    """Two-tier logging: the console shows milestones and errors only; the
    full blow-by-blow (every packet, every ACK, every telemetry message) goes
    to `log_file`. Returns the console handler so callers can raise its level
    (e.g. --debug). """
    file_handler = logging.FileHandler(log_file, mode="a", encoding="utf-8")
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"))

    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)-5s %(message)s", datefmt="%H:%M:%S"))

    logging.basicConfig(level=logging.WARNING, handlers=[file_handler, console_handler])
    logging.getLogger("myvu").setLevel(logging.DEBUG)  # full detail -> file only
    logging.getLogger("myvu").info("Full detail is being logged to %s", log_file)
    return console_handler


async def do_scan() -> None:
    print("scanning for StarryNet devices (8s)...")
    devices = await MyvuClient.scan()
    if not devices:
        print("  none found. Make sure the glasses are on and advertising.")
        return
    for d in devices:
        print(f"  {d.address}  rssi={getattr(d, 'rssi', '?')}  name={d.name!r}")


HELP = """
commands:
  notify <text>              push a notification card to the lens
  notify <title> | <body>    notification with a separate title
  tici <text>                open the teleprompter with this text
  hl <index>                 scroll/highlight teleprompter to paragraph <index>
  vol <0-15>                 set the glasses' volume
  bright <0-10>              set the glasses' screen brightness
  raw <json>                 send a raw app-action JSON (to the launcher)
  help                       show this help
  quit / q                   disconnect and exit
"""


async def repl(client) -> None:
    loop = asyncio.get_event_loop()
    print(HELP)
    while True:
        if not client.is_connected:
            print("!! link is down, exiting REPL")
            return
        try:
            line = (await loop.run_in_executor(None, input, "myvu> ")).strip()
        except (EOFError, KeyboardInterrupt):
            return
        if not line:
            continue
        cmd, _, arg = line.partition(" ")
        cmd = cmd.lower()
        arg = arg.strip()
        try:
            if cmd in ("quit", "q", "exit"):
                return
            elif cmd == "help":
                print(HELP)
            elif cmd == "notify":
                if "|" in arg:
                    title, _, body = arg.partition("|")
                    await client.push_notification(title.strip(), body.strip())
                else:
                    await client.push_notification("Notification", arg)
            elif cmd == "tici":
                await client.open_teleprompter(arg or "Hello from Python!")
            elif cmd == "hl":
                await client.teleprompter_highlight(int(arg))
            elif cmd == "vol":
                await client.set_volume(int(arg))
            elif cmd == "bright":
                await client.set_brightness(int(arg))
            elif cmd == "raw":
                await client.send_action(arg)
            else:
                print(f"unknown command: {cmd!r} (try 'help')")
        except Exception as e:  # noqa: BLE001
            print(f"error: {e}")


async def do_run(address: str, own_mac: str) -> None:
    client = MyvuClient(address, own_mac=own_mac)
    try:
        await client.connect()
        await client.pair()
        await client.establish_session()
        client.start_drains()          # print glasses responses live
        await client.send_init_burst()
        await asyncio.sleep(1.5)
        await repl(client)
    finally:
        await client.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("address", nargs="?", help="BLE address of the glasses")
    ap.add_argument("--mac", default="aa:bb:cc:dd:ee:ff",
                    help="identifier/MAC to present to the glasses")
    ap.add_argument("--debug", action="store_true",
                    help="also show full packet-level detail on the console "
                         "(normally only in myvu.log)")
    ap.add_argument("--log-file", default=LOG_FILE,
                    help="where to write the full-detail log (default: myvu.log)")
    args = ap.parse_args()

    console_handler = configure_logging(args.log_file)
    if args.debug:
        console_handler.setLevel(logging.DEBUG)

    if not args.address:
        asyncio.run(do_scan())
    else:
        asyncio.run(do_run(args.address, args.mac))


if __name__ == "__main__":
    main()
