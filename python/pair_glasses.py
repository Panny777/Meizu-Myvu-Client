"""Attempt classic-BT (BR/EDR) pairing with the MYVU glasses from Windows,
using WinRT's programmatic pairing API (myvu/rfcomm_pair.py) instead of the
Settings UI.

!! CLASSIC-BT PAIRING HAS A CRASH HISTORY VIA WINDOWS' SETTINGS UI !!
This script (WinRT-based, not the Settings UI) has since been used
successfully many times with no crashes -- see the README's
"Classic-Bluetooth (RFCOMM) -- how the teleprompter got working" section
for the full story. It can still be slow/flaky (AUTHENTICATION_TIMEOUT on
the first attempt is common) -- a concurrent BLE session open at the same
time (e.g. `python run.py <address>` in another terminal, left connected)
measurably improves reliability, matching the real phone's own capture
timing. Still, treat this as real-hardware pairing, not a zero-risk
operation.

If the glasses start rebooting repeatedly after running this: turn OFF
Bluetooth on this PC immediately (Settings > Bluetooth, or disable the
radio) to stop Windows auto-retrying, then leave the glasses untouched for
a few minutes to let them settle.

Usage:
  python pair_glasses.py 2C:6F:4E:00:DC:47
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from myvu.rfcomm_pair import ensure_paired

WARNING = """
================================================================================
 WARNING: classic-BT pairing with these glasses from Windows has previously
 caused a spontaneous-reboot crash loop. The cause was never isolated -- this
 script is an experiment, not a known-safe fix. See README.md, section
 "Classic-Bluetooth (RFCOMM) investigation -- PARKED".

 If the glasses start rebooting repeatedly: turn OFF Bluetooth on this PC
 right away, then leave the glasses alone for a few minutes.
================================================================================
"""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("address", help="classic-Bluetooth MAC of the glasses, e.g. 2C:6F:4E:00:DC:47")
    ap.add_argument("--yes-i-understand-the-risk", action="store_true",
                    help="skip the interactive confirmation prompt")
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")

    print(WARNING)
    if not args.yes_i_understand_the_risk:
        reply = input('Type "yes" to proceed with pairing ' + args.address + ": ")
        if reply.strip().lower() != "yes":
            print("Aborted.")
            sys.exit(1)

    paired = asyncio.run(ensure_paired(args.address))
    if paired:
        print(f"\nPaired. Next: python run_rfcomm.py {args.address}")
    else:
        print("\nPairing did not complete -- see log output above.")
        sys.exit(1)


if __name__ == "__main__":
    main()
