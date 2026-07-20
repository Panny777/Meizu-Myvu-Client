"""INVESTIGATION + DIAGNOSTICS for classic-BT pairing of the MYVU glasses.
This is NOT the setup path -- see below.

>>> HOW TO ACTUALLY PAIR (the settled, working answer): use Windows
    Settings > Add device > Bluetooth to pair "MYVU DC47" as an AUDIO device,
    then REMOVE it from the Settings "Other devices" section (leave the
    "Audio devices" entry). Then run:  python run_glasses.py <MAC> --no-hfp

Why not this script: programmatic audio pairing was investigated thoroughly
and DOES NOT WORK on this device -- confirmed empirically, not assumed:

  * Pairing by raw MAC (pair_glasses.py) -> generic classic bond in
    "Other devices" (no audio profiles) -> the glasses REBOOT/crash.
  * This script's approach -- discover over BLE (the only transport the
    glasses advertise on; they don't answer a classic inquiry) and pair the
    discovered device -> a BLE-ONLY bond, still in "Other devices", still not
    the audio pairing.
  * The --probe diagnostic (below) enumerated every unpaired thing Windows can
    see for the glasses -- classic endpoint, BLE endpoint, device container --
    over a fair scan: ONLY the BLE endpoint is reachable. There is no classic
    endpoint or container for app-level WinRT to pair. The Settings "Add
    device" wizard succeeds only because it runs with system-level access that
    bridges BLE discovery to a classic audio pairing; the public WinRT pairing
    API cannot.

So the glasses' firmware wants a phone-shaped AUDIO connection (HFP/A2DP), and
only Windows' own wizard can establish that. This file is kept for the finding
and for two genuinely useful, safe subcommands:

  --probe    enumerate every unpaired endpoint/container Windows sees for the
             glasses (no pairing attempted). The diagnostic that proved the
             above. Needs the glasses awake and unpaired.
  --unpair   programmatically remove an ACTIVE bond (BLE and/or classic) via
             unpair_async -- handy right after a wrong bond gets created.
             (Cannot purge a stale cached "Other devices" entry that isn't an
             active pairing; use Settings > Remove device for that.)

Running with no subcommand still attempts the (known-not-to-produce-audio)
discover-and-pair, for the record / for anyone wanting to re-verify.

Usage:
  python pair_glasses_audio.py 2C:6F:4E:00:DC:47 --probe     # safe diagnostic
  python pair_glasses_audio.py 2C:6F:4E:00:DC:47 --unpair    # remove active bond
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from myvu.rfcomm_pair import discover_and_pair_as_audio, probe_endpoints, unpair


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
    ap.add_argument("--unpair", action="store_true",
                    help="programmatically remove an ACTIVE pairing (BLE and/or classic) "
                         "for the glasses via unpair_async. Useful to tear down a wrong "
                         "BLE bond this script created. Does NOT purge a stale cached "
                         "entry in Settings > Other devices (use Settings for that).")
    ap.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")

    if args.unpair:
        ok = asyncio.run(unpair(args.address))
        if ok:
            print("\nNo active pairing remains for the glasses.")
        else:
            print("\nAn endpoint is still paired -- see log output above.")
            sys.exit(1)
        return

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
