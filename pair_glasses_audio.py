"""Pair the MYVU glasses AS AN AUDIO DEVICE, programmatically, by replicating
what Windows Settings > Add device does: a real Bluetooth inquiry-scan
discovery first (so Windows learns the glasses are an audio device offering
HFP + A2DP), then pairing the discovered device -- which brings up those
audio profiles.

This is the STABLE pairing. Pairing by raw MAC (the old pair_glasses.py) made
a bare/generic "Other devices" data pairing with no audio profiles, which
caused the glasses to reboot/crash. Pairing as an audio device gives the
glasses the phone-shaped connection their firmware expects, and they stay
stable. Once paired this way, run:

    python run_glasses.py 2C:6F:4E:00:DC:47 --no-hfp

(--no-hfp because Windows now holds the Hands-Free connection natively.)

!! UNTESTED end-to-end against the glasses as of writing -- see the docstring
in myvu/rfcomm_pair.discover_and_pair_as_audio. It re-enters the pairing flow,
so run it carefully, once, with the glasses awake and nearby. If it produces
the audio pairing it should be the stable kind; if the glasses start rebooting
anyway, turn OFF Bluetooth immediately and let them settle.

Prerequisite: the glasses must NOT already be paired (an already-paired device
won't show up in the unpaired-only discovery scan). If they're already paired,
you don't need this -- just use run_glasses.py.

Usage:
  python pair_glasses_audio.py 2C:6F:4E:00:DC:47
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from myvu.rfcomm_pair import discover_and_pair_as_audio, probe_endpoints


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("address", help="classic-Bluetooth MAC of the glasses, e.g. 2C:6F:4E:00:DC:47")
    ap.add_argument("--discover-timeout", type=float, default=60.0,
                    help="seconds to scan for the glasses (default 60)")
    ap.add_argument("--discover-only", action="store_true",
                    help="just prove discovery works -- find the glasses and stop, "
                         "WITHOUT attempting to pair. Safe to run; needs the glasses "
                         "awake and NOT already paired to Windows.")
    ap.add_argument("--probe", action="store_true",
                    help="SAFE diagnostic: enumerate every unpaired endpoint/container "
                         "Windows can see for the glasses (classic, BLE, container) and "
                         "print what's pairable. No pairing attempted. Needs the glasses "
                         "awake and unpaired.")
    ap.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")

    if args.probe:
        results = asyncio.run(probe_endpoints(args.address))
        print("\n=== discoverable endpoints for the glasses ===")
        if not results:
            print("  (none -- glasses awake, nearby, and fully unpaired?)")
        for r in results:
            print(f"  [{r['kind']:18s}] can_pair={r['can_pair']} is_paired={r['is_paired']}")
            print(f"      id={r['id']}")
        return

    if args.discover_only:
        found = asyncio.run(discover_and_pair_as_audio(
            args.address, discover_timeout=args.discover_timeout, discover_only=True))
        if found:
            print("\nDiscovery works -- the glasses were found via BLE. No pairing "
                  "attempted (--discover-only). Re-run without --discover-only to "
                  "attempt the audio pairing.")
        else:
            print("\nDiscovery did not find the glasses -- see log output above.")
            sys.exit(1)
        return

    if not args.yes:
        print("This will scan for and pair the glasses as an AUDIO device.")
        print("Make sure they are AWAKE, nearby, and NOT already paired to Windows.")
        reply = input(f'Type "yes" to proceed with {args.address}: ')
        if reply.strip().lower() != "yes":
            print("Aborted.")
            sys.exit(1)

    paired = asyncio.run(discover_and_pair_as_audio(args.address,
                                                    discover_timeout=args.discover_timeout))
    if paired:
        print(f"\nPaired as audio device. Next: python run_glasses.py {args.address} --no-hfp")
    else:
        print("\nPairing did not complete -- see log output above.")
        sys.exit(1)


if __name__ == "__main__":
    main()
