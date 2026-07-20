"""Routing for the AR nav HUD, via OSRM (free, no API key) + Nominatim geocode.

The glasses render the HUD from the structured `navi_info` frames we stream
(see applayer.send_navi_info). This module turns a from/to into an OSRM route
and exposes it as a list of maneuver steps we can "drive" along, mapping each
OSRM maneuver to the glasses' `ic` icon value.

No third-party deps -- plain urllib. OSRM's public demo server has no SLA/rate
guarantees; swap OSRM_BASE for a self-hosted instance (or Mapbox, whose
maneuver model is identical) for anything real. Nominatim asks for a
descriptive User-Agent and light usage.
"""
from __future__ import annotations

import json
import logging
import math
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

log = logging.getLogger("myvu.navigation")

OSRM_BASE = "https://router.project-osrm.org"
NOMINATIM_BASE = "https://nominatim.openstreetmap.org"
_UA = "myvu-client/1.0 (reverse-engineering hobby project)"

# OSRM maneuver -> glasses `ic` icon value. THE VALUES ARE PROVISIONAL: the
# glasses' int->arrow mapping (a HERE ManeuverAction enum) isn't documented, so
# calibrate with `nav info <ic> ...` and adjust here. Keyed by OSRM
# maneuver.modifier, with a couple of type-based overrides below.
IC_BY_MODIFIER = {
    "straight": 1,
    "right": 2,
    "left": 3,
    "slight right": 4,
    "slight left": 5,
    "sharp right": 6,
    "sharp left": 7,
    "uturn": 8,
}
IC_BY_TYPE = {          # take priority over modifier when present
    "roundabout": 9,
    "rotary": 9,
    "roundabout turn": 9,
    "merge": 10,
    "on ramp": 11,
    "off ramp": 12,
    "fork": 13,
    "end of road": 14,
    "arrive": 15,
    "depart": 1,
}


def maneuver_to_ic(m_type: str, modifier: str) -> int:
    """Map an OSRM maneuver (type + modifier) to a glasses `ic` icon value."""
    if m_type in IC_BY_TYPE:
        return IC_BY_TYPE[m_type]
    return IC_BY_MODIFIER.get(modifier, 1)


@dataclass
class Step:
    ic: int               # glasses maneuver icon
    road: str             # road name you travel AFTER this step's maneuver
    distance: int         # length of this step in metres
    duration: float       # seconds for this step
    m_type: str           # raw OSRM maneuver type (for debugging/calibration)
    modifier: str         # raw OSRM maneuver modifier
    at: float = 0.0       # cumulative distance (m) of this step's maneuver


@dataclass
class Route:
    steps: list           # list[Step]
    total_distance: int   # metres
    total_duration: float # seconds
    # route polyline as (lat, lon, cumulative_distance_m), for map-matching
    vertices: list = field(default_factory=list)


# how far off the polyline (m) before we consider the driver off-route
OFF_ROUTE_M = 45.0


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance between two lat/lon points, in metres."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


@dataclass
class TrackState:
    travelled: float      # metres progressed along the route
    remaining: float      # metres left to destination
    off_route: bool       # current pos is far from the polyline
    deviation: float      # metres from the nearest route vertex
    next_step: object     # the upcoming Step (maneuver ahead), or None
    dist_to_next: float   # metres to that maneuver


class RouteTracker:
    """Snaps a live GPS position onto a Route and reports progress + the next
    maneuver. Nearest-vertex snapping (coarse but robust); good enough for HUD
    turn-by-turn at typical vertex spacing."""

    def __init__(self, route: Route) -> None:
        self.route = route

    def update(self, lat: float, lon: float) -> TrackState:
        verts = self.route.vertices
        best_i, best_d = 0, float("inf")
        for i, (vlat, vlon, _cum) in enumerate(verts):
            d = haversine(lat, lon, vlat, vlon)
            if d < best_d:
                best_d, best_i = d, i
        travelled = verts[best_i][2] if verts else 0.0
        total = self.route.total_distance
        nxt, dist_to_next = None, 0.0
        for st in self.route.steps:
            if st.at > travelled + 5:      # first maneuver still ahead
                nxt = st
                dist_to_next = st.at - travelled
                break
        return TrackState(
            travelled=travelled, remaining=max(0.0, total - travelled),
            off_route=best_d > OFF_ROUTE_M, deviation=best_d,
            next_step=nxt, dist_to_next=dist_to_next)


def _get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def geocode(place: str) -> tuple[float, float]:
    """Resolve a place name/address to (lat, lon) via Nominatim. Raises on
    no result."""
    q = urllib.parse.urlencode({"q": place, "format": "json", "limit": 1})
    data = _get_json(f"{NOMINATIM_BASE}/search?{q}")
    if not data:
        raise RuntimeError(f"no geocode result for {place!r}")
    return float(data[0]["lat"]), float(data[0]["lon"])


def parse_point(s: str) -> tuple[float, float]:
    """Parse 'lat,lon' if it looks like coordinates, else geocode the string."""
    s = s.strip()
    parts = s.split(",")
    if len(parts) == 2:
        try:
            return float(parts[0]), float(parts[1])
        except ValueError:
            pass
    return geocode(s)


def _cumulative(coords: list) -> list:
    """coords: list of (lat, lon). Return (lat, lon, cumulative_metres)."""
    out, acc = [], 0.0
    prev = None
    for lat, lon in coords:
        if prev is not None:
            acc += haversine(prev[0], prev[1], lat, lon)
        out.append((lat, lon, acc))
        prev = (lat, lon)
    return out


def _nearest_cum(verts: list, lat: float, lon: float) -> float:
    """Cumulative distance of the route vertex nearest to (lat, lon)."""
    best_c, best_d = 0.0, float("inf")
    for vlat, vlon, cum in verts:
        d = haversine(lat, lon, vlat, vlon)
        if d < best_d:
            best_d, best_c = d, cum
    return best_c


def route(origin: tuple[float, float], dest: tuple[float, float],
          profile: str = "driving") -> Route:
    """Fetch a turn-by-turn route from OSRM (with geometry for map-matching).
    origin/dest are (lat, lon)."""
    (olat, olon), (dlat, dlon) = origin, dest
    coords = f"{olon},{olat};{dlon},{dlat}"  # OSRM is lon,lat
    q = urllib.parse.urlencode({
        "overview": "full", "geometries": "geojson", "steps": "true",
        "annotations": "false"})
    url = f"{OSRM_BASE}/route/v1/{profile}/{coords}?{q}"
    data = _get_json(url)
    if data.get("code") != "Ok" or not data.get("routes"):
        raise RuntimeError(f"OSRM returned {data.get('code')!r}: "
                           f"{data.get('message', 'no route')}")
    r = data["routes"][0]
    geo = [(c[1], c[0]) for c in r["geometry"]["coordinates"]]  # -> (lat, lon)
    verts = _cumulative(geo)
    steps: list = []
    for leg in r["legs"]:
        for st in leg["steps"]:
            man = st.get("maneuver", {})
            m_type = man.get("type", "")
            modifier = man.get("modifier", "")
            loc = man.get("location")  # [lon, lat]
            at = _nearest_cum(verts, loc[1], loc[0]) if loc else 0.0
            steps.append(Step(
                ic=maneuver_to_ic(m_type, modifier),
                road=st.get("name") or "",
                distance=int(st.get("distance", 0)),
                duration=float(st.get("duration", 0.0)),
                m_type=m_type, modifier=modifier, at=at))
    log.info("OSRM route: %d steps, %.0f m, %.0f s",
             len(steps), r["distance"], r["duration"])
    return Route(steps=steps, total_distance=int(r["distance"]),
                 total_duration=float(r["duration"]), vertices=verts)
