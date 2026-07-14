"""Entry point: the FULL, working glasses connection -- BLE handshake, wait
for the glasses' per-session classic-BT relay UUID, open that relay channel
via WinRT SDP resolution, connect HFP (Hands-Free) so the glasses consider
a phone "properly" connected, then drive everything through the classic-BT
relay client's REPL. This is what actually gets the teleprompter (and
presumably anything else gated the same way) working -- see README.md's
"Classic-Bluetooth (RFCOMM)" section for the full story of how this was
reverse-engineered.

Requires: the glasses must already be BR/EDR-bonded to this PC (see
pair_glasses.py). Windows only (uses winsdk for WinRT SDP-by-UUID RFCOMM
and HFP connections -- raw socket.AF_BLUETOOTH can't do either).

Usage:
  python run_glasses.py 2C:6F:4E:00:DC:47
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os

from myvu.client import MyvuClient
from myvu.hfp import HfpAgResponder
from myvu.rfcomm_client import MyvuRfcommClient
from run import configure_logging, repl

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "myvu_glasses.log")


async def do_run(address: str, own_mac: str, uuid_wait: float, use_hfp: bool) -> None:
    ble = MyvuClient(address, own_mac=own_mac)
    rf: MyvuRfcommClient | None = None
    hfp: HfpAgResponder | None = None
    try:
        await ble.connect()
        await ble.pair()
        await ble.establish_session()
        ble.start_drains()
        await ble.send_init_burst()

        logging.getLogger("myvu").info("waiting for the glasses to sync the "
                                       "classic-BT relay UUID over BLE...")
        for _ in range(int(uuid_wait)):
            await asyncio.sleep(1.0)
            if ble.spp_uuid:
                break
        if not ble.spp_uuid:
            raise RuntimeError(
                f"never received SPP_SERVER_UUID_SYNC within {uuid_wait:.0f}s -- "
                "the glasses may need to be re-woken, or BLE init didn't "
                "fully complete")

        rf = MyvuRfcommClient(address, own_mac=own_mac, service_uuid=ble.spp_uuid)
        await rf.connect()
        await rf.establish_session()
        rf.start_drains()
        await rf.send_init_burst()

        # Let capture_mic()/AI-button handling on the REPL client (rf) also arm
        # the BLE client -- the glasses may stream mic audio (or send the button
        # press) over either channel, so both need to be listening.
        rf._sibling = ble

        if use_hfp:
            hfp = HfpAgResponder(address)
            await hfp.connect()
            try:
                await asyncio.wait_for(hfp.handshake_done.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                logging.getLogger("myvu").warning(
                    "HFP handshake didn't complete in 10s -- continuing anyway, "
                    "but features gated on 'phone connected' may not clear")
        else:
            logging.getLogger("myvu").info(
                "skipping in-app HFP (--no-hfp): relying on Windows' native "
                "Hands-Free connection from pairing the glasses as an AUDIO "
                "device. This is the stable path -- see README.")

        await asyncio.sleep(1.0)
        await rf.sync_time()  # match the glasses' clock to this PC on connect
        await rf.set_wear_detection(True)  # default wear detection on (app default)
        await rf.set_zen_mode(False)       # default do-not-disturb off
        await rf.set_screen_off_time(10)   # default display auto-off to 10s
        await repl(rf)
    finally:
        if hfp:
            await hfp.close()
        if rf:
            await rf.close()
        await ble.close()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("address", help="classic-Bluetooth / BLE MAC of the glasses "
                                    "(same address space on this device)")
    ap.add_argument("--mac", default="aa:bb:cc:dd:ee:ff",
                    help="identifier/MAC to present to the glasses")
    ap.add_argument("--uuid-wait", type=float, default=20.0,
                    help="seconds to wait for the glasses to sync the classic-BT "
                         "relay UUID over BLE before giving up (default 20)")
    ap.add_argument("--no-hfp", action="store_true",
                    help="skip the in-app HFP handshake. Use this when the glasses "
                         "are paired to Windows as an AUDIO device (Settings > Add "
                         "device), so Windows natively holds the Hands-Free/A2DP "
                         "connection -- the stable setup. Only use the in-app HFP "
                         "(default) if the glasses are NOT paired as an audio device.")
    ap.add_argument("--debug", action="store_true",
                    help="also show full packet-level detail on the console")
    ap.add_argument("--log-file", default=LOG_FILE,
                    help="where to write the full-detail log (default: myvu_glasses.log)")
    args = ap.parse_args()

    console_handler = configure_logging(args.log_file)
    if args.debug:
        console_handler.setLevel(logging.DEBUG)

    asyncio.run(do_run(args.address, args.mac, args.uuid_wait, use_hfp=not args.no_hfp))


if __name__ == "__main__":
    main()
