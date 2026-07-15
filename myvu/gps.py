"""Live position from a serial NMEA GPS receiver (USB dongle on a COM port).

Parses the standard NMEA sentences a receiver emits and keeps the latest fix.
Uses pyserial. Run it in a background thread and poll `latest()` for the most
recent fix.

Only RMC (position + speed + course + valid flag) and GGA (position + fix
quality) are parsed -- enough to drive turn-by-turn. Talker id is ignored, so
GPS/GLONASS/GNSS ($GP.../$GN.../$GL...) all work.
"""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass

log = logging.getLogger("myvu.gps")

_KNOTS_TO_MPS = 0.514444


@dataclass
class Fix:
    lat: float
    lon: float
    speed_mps: float      # ground speed
    course: float         # heading, degrees (0 = north)
    valid: bool           # RMC 'A' / GGA fix quality > 0
    ts: float             # local time this fix was parsed


def _nmea_checksum_ok(line: str) -> bool:
    if "*" not in line:
        return False
    body, _, cs = line[1:].partition("*")
    try:
        want = int(cs[:2], 16)
    except ValueError:
        return False
    got = 0
    for ch in body:
        got ^= ord(ch)
    return got == want


def _dm_to_deg(dm: str, hemi: str) -> float | None:
    """NMEA ddmm.mmmm / dddmm.mmmm + hemisphere -> signed decimal degrees."""
    if not dm or "." not in dm:
        return None
    dot = dm.index(".")
    deg_len = dot - 2  # 2 digits of minutes before the dot
    if deg_len <= 0:
        return None
    deg = float(dm[:deg_len])
    minutes = float(dm[deg_len:])
    val = deg + minutes / 60.0
    if hemi in ("S", "W"):
        val = -val
    return val


def parse_line(line: str) -> Fix | None:
    """Parse one NMEA sentence into a Fix, or None if it isn't a usable
    position sentence."""
    line = line.strip()
    if not line.startswith("$") or not _nmea_checksum_ok(line):
        return None
    body = line[1:].split("*")[0]
    f = body.split(",")
    kind = f[0][2:] if len(f[0]) >= 5 else f[0]  # strip talker id
    try:
        if kind == "RMC" and len(f) >= 9:
            valid = f[2] == "A"
            lat = _dm_to_deg(f[3], f[4])
            lon = _dm_to_deg(f[5], f[6])
            if lat is None or lon is None:
                return None
            speed = float(f[7]) * _KNOTS_TO_MPS if f[7] else 0.0
            course = float(f[8]) if f[8] else 0.0
            return Fix(lat, lon, speed, course, valid, time.time())
        if kind == "GGA" and len(f) >= 7:
            quality = int(f[6]) if f[6] else 0
            lat = _dm_to_deg(f[2], f[3])
            lon = _dm_to_deg(f[4], f[5])
            if lat is None or lon is None:
                return None
            return Fix(lat, lon, 0.0, 0.0, quality > 0, time.time())
    except (ValueError, IndexError):
        return None
    return None


class SerialNmeaGps:
    """Background reader for a serial NMEA receiver. Open, then poll latest()."""

    def __init__(self, port: str, baud: int = 9600) -> None:
        self.port = port
        self.baud = baud
        self._serial = None
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._fix: Fix | None = None
        self._running = False

    def open(self) -> None:
        try:
            import serial  # pyserial
        except ImportError as e:
            raise RuntimeError("pyserial not installed -- pip install pyserial") from e
        self._serial = serial.Serial(self.port, self.baud, timeout=1)
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        log.info("GPS: reading %s @ %d baud", self.port, self.baud)

    def _loop(self) -> None:
        while self._running:
            try:
                raw = self._serial.readline()
            except Exception as e:  # noqa: BLE001
                log.warning("GPS serial read error: %s", e)
                break
            if not raw:
                continue
            fix = parse_line(raw.decode("ascii", "replace"))
            if fix is not None:
                with self._lock:
                    # keep a valid fix's speed/course if a GGA (no speed) follows
                    if not fix.valid and self._fix is not None and self._fix.valid:
                        pass
                    self._fix = fix

    def latest(self) -> Fix | None:
        with self._lock:
            return self._fix

    def wait_for_fix(self, timeout: float = 60.0) -> Fix | None:
        """Block until a valid fix is available (or timeout). Returns it/None."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            fix = self.latest()
            if fix is not None and fix.valid:
                return fix
            time.sleep(0.5)
        return None

    def close(self) -> None:
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2)
        if self._serial is not None:
            try:
                self._serial.close()
            except Exception:  # noqa: BLE001
                pass
