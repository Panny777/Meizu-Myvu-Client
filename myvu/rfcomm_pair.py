"""Programmatic Windows classic-BT (BR/EDR) pairing for the MYVU glasses.

WARNING -- read before running anything in this file.

Attempting classic-BT pairing with these glasses from Windows has previously
caused repeated spontaneous reboots (a crash loop) when done through Windows'
own Settings > Bluetooth UI. See the README's "Classic-Bluetooth (RFCOMM)
investigation -- PARKED" section for the full incident and the capture
analysis behind this module. The root cause was never isolated -- it was
*not* simply "Windows picked the wrong SSP method" (a fresh capture of the
real phone pairing confirmed the real phone also uses Numeric Comparison /
DisplayYesNo / MITM-required, same as Windows).

This module drives pairing through WinRT's DeviceInformationCustomPairing API
instead of the Settings UI, so we can specify the exact parameters confirmed
from that capture -- DevicePairingKinds.CONFIRM_PIN_MATCH (Numeric
Comparison) and DevicePairingProtectionLevel.ENCRYPTION_AND_AUTHENTICATION
(MITM-required) -- and auto-accept the comparison the same way the real
phone app evidently does (no pairing dialog reappears on its later
reconnects, so it must call the equivalent of setPairingConfirmation()
itself rather than showing system UI). This is an experiment to see whether
a scripted pairing behaves differently from the Settings UI, not a
confirmed-safe path. It may still crash the glasses.

Requires: `pip install winsdk pywin32` (Python projection of the Windows
Runtime, plus pywin32 for explicit COM apartment control -- see below).
Windows only.

Known gotcha (confirmed against Microsoft's own DevicePairingResultStatus
docs and prior reports of the same symptom): DeviceInformationCustomPairing's
PairingRequested event is UI-thread-affine. Historically it was only ever
exercised from UWP apps with a real message loop; from a plain console/
asyncio script the event can simply never be delivered, and PairAsync either
times out generically (AuthenticationTimeout) or short-circuits immediately
with RequiredHandlerNotRegistered ("Either the event handler wasn't
registered or a required DevicePairingKinds was not supported") even though
the handler *was* registered -- both were observed here across back-to-back
runs. The fix is to explicitly initialize this thread as a Single-Threaded
Apartment (pythoncom.CoInitialize(), which defaults to STA) and pump Windows
messages (pythoncom.PumpWaitingMessages()) alongside the asyncio loop while
the pairing operation is in flight, so the OS has a message queue to deliver
the callback through. This is a best-effort fix based on well-documented
WinRT/COM behaviour, not something confirmed against this specific device --
it may still fail, just hopefully with the handler actually invoked this
time (watch for the "PairingRequested: kind=... auto-accepting" log line).
"""
from __future__ import annotations

import asyncio
import logging

log = logging.getLogger("myvu.rfcomm_pair")


def _mac_to_int(mac: str) -> int:
    return int(mac.replace(":", "").replace("-", ""), 16)


async def _pump_com_messages(interval: float = 0.02) -> None:
    """Keep this STA thread's Windows message queue draining so WinRT can
    deliver the PairingRequested callback across the apartment boundary."""
    import pythoncom
    while True:
        pythoncom.PumpWaitingMessages()
        await asyncio.sleep(interval)


async def probe_endpoints(mac: str, timeout: float = 95.0) -> list:
    """SAFE diagnostic: enumerate every unpaired Bluetooth thing Windows can see
    for the glasses -- classic endpoint, BLE endpoint, and the device container
    -- and log each with its kind, id, name, and whether it reports can_pair.
    NO pairing is attempted. Use this (with the glasses UNPAIRED and awake) to
    find out what's actually available to pair before choosing a target, since
    pairing the BLE endpoint gave a BLE-only bond (wrong) and we want to see if
    a classic endpoint or the container is reachable (which might bring up the
    audio profiles).

    Returns a list of dicts: {kind, id, name, can_pair, is_paired}.
    """
    import pythoncom
    pythoncom.CoInitialize()
    import winsdk.windows.devices.bluetooth as bt
    import winsdk.windows.devices.enumeration as de

    target_hex = mac.replace(":", "").replace("-", "").lower()
    loop = asyncio.get_event_loop()
    results: list = []
    seen_ids: set = set()

    # (label, selector, kind)
    probes = [
        ("classic-endpoint",
         bt.BluetoothDevice.get_device_selector_from_pairing_state(False),
         de.DeviceInformationKind.ASSOCIATION_ENDPOINT),
        ("ble-endpoint",
         bt.BluetoothLEDevice.get_device_selector_from_pairing_state(False),
         de.DeviceInformationKind.ASSOCIATION_ENDPOINT),
        ("classic-container",
         bt.BluetoothDevice.get_device_selector_from_pairing_state(False),
         de.DeviceInformationKind.ASSOCIATION_ENDPOINT_CONTAINER),
    ]

    def _matches(info) -> bool:
        idl = (info.id or "").lower().replace(":", "").replace("-", "")
        return target_hex in idl or (info.name or "") == "MYVU DC47"

    for label, selector, kind in probes:
        done = loop.create_future()

        def on_added(sender, info, _label=label, _done=done):
            if not _matches(info):
                return
            if info.id in seen_ids:
                return
            seen_ids.add(info.id)
            try:
                pr = info.pairing
                cp, ip = pr.can_pair, pr.is_paired
            except Exception:  # noqa: BLE001
                cp = ip = None
            entry = {"kind": _label, "id": info.id, "name": info.name,
                     "can_pair": cp, "is_paired": ip}
            results.append(entry)
            log.info("PROBE [%s]: name=%r can_pair=%s is_paired=%s id=%s",
                     _label, info.name, cp, ip, info.id)

        watcher = de.DeviceInformation.create_watcher(selector, [], kind)
        token = watcher.add_added(on_added)
        watcher.start()
        log.info("probing %s for %.0fs...", label, timeout / len(probes))
        try:
            await asyncio.sleep(timeout / len(probes))
        finally:
            try:
                watcher.stop()
            except Exception:  # noqa: BLE001
                pass
            watcher.remove_added(token)

    if not results:
        log.warning("probe found NOTHING for the glasses -- make sure they're "
                    "awake, nearby, and fully unpaired.")
    return results


async def discover_and_pair_as_audio(mac: str, discover_timeout: float = 60.0,
                                     pair_timeout: float = 30.0,
                                     discover_only: bool = False) -> bool:
    """Pair the glasses AS AN AUDIO DEVICE by first discovering them (over BLE,
    which is the transport they actually advertise on -- a classic BR/EDR
    inquiry does NOT find them, confirmed 2026-07-13), then pairing the
    *discovered* DeviceInformation.

    If discover_only=True, return True as soon as the glasses are discovered
    and do NOT attempt pairing -- a safe way to validate the discovery selector
    without touching the pairing flow (and without needing to tear down a
    working pairing... except the device must be UNPAIRED to appear in an
    unpaired-only scan, so this still needs an unpaired device to prove out).

    Why this is different from (and safer than) ensure_paired(): ensure_paired
    resolves the device by raw MAC (BluetoothDevice.from_bluetooth_address_async)
    with NO discovery, so Windows never learns the device's Class-of-Device and
    pairs it as a bare/generic data device (the "Other devices" entry) -- which
    on this hardware caused spontaneous reboots. Manually pairing via Windows
    Settings > Add device instead does a real inquiry SCAN first, so Windows
    learns the glasses are an audio device (HFP + A2DP) and brings up those
    profiles -- the *stable* configuration (confirmed: teleprompter works and
    the glasses stay stable when paired this way, with run_glasses.py --no-hfp).

    This function replicates the Settings > Add device path programmatically:
    a DeviceWatcher over the UNPAIRED-classic-BT selector triggers the inquiry;
    once "MYVU DC47" is discovered (Windows now knows its audio CoD), we pair
    that discovered object, which should bring up the audio profiles.

    !! UNTESTED against the glasses hardware as of writing !! Built from the
    WinRT DeviceWatcher + pairing API (names verified by introspection, but the
    end-to-end behaviour -- that pairing a discovered device yields the audio
    pairing rather than the generic one -- is a hypothesis, not yet confirmed.
    It re-enters the pairing flow, so treat it as a careful experiment. If it
    works it produces the *stable* audio pairing, so it should be lower-risk
    than ensure_paired's generic pairing was -- but that's the thing under test.
    """
    import pythoncom
    pythoncom.CoInitialize()  # STA
    import winsdk.windows.devices.bluetooth as bt
    import winsdk.windows.devices.enumeration as de

    target_hex = mac.replace(":", "").replace("-", "").lower()
    loop = asyncio.get_event_loop()
    found: "asyncio.Future" = loop.create_future()

    # Selector for UNPAIRED devices. Use the BLE selector, NOT the classic
    # BR/EDR one: these glasses advertise over BLE and do NOT answer a classic
    # inquiry scan (confirmed 2026-07-13 -- a classic-inquiry watcher timed out
    # even with the glasses awake and fully unpaired). Pass all three args
    # (selector, requested-properties, kind) so pywinrt picks the
    # (String, IIterable, DeviceInformationKind) overload -- a lone string
    # ambiguously matches create_watcher(DeviceClass:int) and raises
    # "'str' object cannot be interpreted as an integer".
    selector = bt.BluetoothLEDevice.get_device_selector_from_pairing_state(False)
    watcher = de.DeviceInformation.create_watcher(
        selector, [], de.DeviceInformationKind.ASSOCIATION_ENDPOINT)

    def _matches(info) -> bool:
        idl = (info.id or "").lower().replace(":", "").replace("-", "")
        if target_hex in idl:
            return True
        return (info.name or "") == "MYVU DC47"

    def on_added(sender, info):
        log.debug("discovery: found id=%s name=%s", info.id, info.name)
        if _matches(info) and not found.done():
            log.info("discovered target as audio-capable device: name=%s id=%s",
                     info.name, info.id)
            loop.call_soon_threadsafe(found.set_result, info)

    token = watcher.add_added(on_added)
    watcher.start()
    log.info("scanning (inquiry) for the glasses so Windows learns their audio "
             "profile -- wake the glasses and keep them nearby...")
    try:
        info = await asyncio.wait_for(found, discover_timeout)
    except asyncio.TimeoutError:
        raise RuntimeError(
            f"did not discover the glasses within {discover_timeout:.0f}s -- make "
            "sure they're awake, in range, and not already paired (a device that's "
            "already paired won't appear in an unpaired-only scan)")
    finally:
        try:
            watcher.stop()
        except Exception:  # noqa: BLE001
            pass
        watcher.remove_added(token)

    if discover_only:
        log.info("discover_only: found the glasses (name=%s, id=%s) -- stopping "
                 "before pairing.", info.name, info.id)
        return True

    pairing = info.pairing
    if pairing.is_paired:
        log.info("already paired.")
        return True

    custom = pairing.custom

    def on_pairing_requested(sender, args):
        kind = args.pairing_kind
        pin = getattr(args, "pin", None)
        log.warning("PairingRequested (audio): kind=%s pin=%s -- auto-accepting",
                    kind, pin)
        args.accept()

    ptoken = custom.add_pairing_requested(on_pairing_requested)
    pump_task = asyncio.create_task(_pump_com_messages())
    try:
        kinds = de.DevicePairingKinds.CONFIRM_ONLY | de.DevicePairingKinds.CONFIRM_PIN_MATCH
        result = await asyncio.wait_for(
            custom.pair_async(kinds, de.DevicePairingProtectionLevel.ENCRYPTION_AND_AUTHENTICATION),
            timeout=pair_timeout)
    finally:
        pump_task.cancel()
        custom.remove_pairing_requested(ptoken)

    log.info("audio pairing result: status=%s", result.status)
    if result.status == de.DevicePairingResultStatus.PAIRED:
        return True
    fresh = await bt.BluetoothDevice.from_bluetooth_address_async(_mac_to_int(mac))
    return bool(fresh is not None and fresh.device_information.pairing.is_paired)




async def ensure_paired(mac: str, timeout: float = 30.0) -> bool:
    """Pair with `mac` (e.g. "2C:6F:4E:00:DC:47") if not already paired.

    Returns True if the device is paired (already was, or pairing just
    succeeded). Auto-accepts a Numeric Comparison ("confirm pin match")
    request without prompting -- mirroring the real phone's observed
    behaviour -- and logs the 6-digit code for visibility either way.
    """
    import pythoncom
    pythoncom.CoInitialize()  # STA -- must happen before any WinRT call on this thread

    import winsdk.windows.devices.bluetooth as bt
    import winsdk.windows.devices.enumeration as de

    device = await bt.BluetoothDevice.from_bluetooth_address_async(_mac_to_int(mac))
    if device is None:
        raise RuntimeError(f"no classic-BT device found for {mac} -- is it powered on "
                            f"and in range? (BLE-only visibility is not enough here)")

    info = device.device_information
    pairing = info.pairing
    log.info("device=%s paired=%s can_pair=%s", info.name, pairing.is_paired, pairing.can_pair)

    if pairing.is_paired:
        return True
    if not pairing.can_pair:
        log.warning("pairing.can_pair is False -- Windows may not consider this device "
                    "pairable in its current state")

    custom = pairing.custom

    def on_pairing_requested(sender, args):
        kind = args.pairing_kind
        pin = getattr(args, "pin", None)
        log.warning("PairingRequested: kind=%s pin=%s -- auto-accepting "
                    "(matches the real phone's observed silent-confirm behaviour)",
                    kind, pin)
        args.accept()

    token = custom.add_pairing_requested(on_pairing_requested)
    pump_task = asyncio.create_task(_pump_com_messages())
    try:
        result = await asyncio.wait_for(
            custom.pair_async(
                de.DevicePairingKinds.CONFIRM_PIN_MATCH,
                de.DevicePairingProtectionLevel.ENCRYPTION_AND_AUTHENTICATION,
            ),
            timeout=timeout,
        )
    finally:
        pump_task.cancel()
        custom.remove_pairing_requested(token)

    log.info("pairing result: status=%s", result.status)
    if result.status == de.DevicePairingResultStatus.PAIRED:
        return True
    # `pairing.is_paired` is a snapshot taken before pair_async() ran and
    # does not refresh in place -- re-fetch fresh state as a fallback rather
    # than trusting that stale object (this previously caused a false
    # "pairing did not complete" report on a run that actually succeeded).
    fresh = await bt.BluetoothDevice.from_bluetooth_address_async(_mac_to_int(mac))
    return bool(fresh is not None and fresh.device_information.pairing.is_paired)
