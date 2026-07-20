package com.myvu.client.nav;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps an OSRM maneuver to the glasses' {@code ic} arrow-icon value.
 *
 * THESE VALUES ARE PROVISIONAL AND LARGELY UNVERIFIED. The glasses' int -> arrow
 * mapping is a HERE ManeuverAction-style enum that is not documented anywhere in
 * the decompiled app, and the Python client says the same. Only by putting each
 * value on the lens and looking at it can they be confirmed -- that is what
 * IcCalibrationActivity is for. Treat a wrong arrow as expected, not as a bug in
 * the routing code.
 */
public final class IcMap {
    private IcMap() {}

    /** Keyed by OSRM maneuver.modifier. */
    private static final Map<String, Integer> BY_MODIFIER = new HashMap<>();
    /** Keyed by maneuver.type; takes priority over the modifier when present. */
    private static final Map<String, Integer> BY_TYPE = new HashMap<>();

    public static final int DEFAULT_IC = 1; // straight ahead

    static {
        BY_MODIFIER.put("straight", 1);
        BY_MODIFIER.put("right", 2);
        BY_MODIFIER.put("left", 3);
        BY_MODIFIER.put("slight right", 4);
        BY_MODIFIER.put("slight left", 5);
        BY_MODIFIER.put("sharp right", 6);
        BY_MODIFIER.put("sharp left", 7);
        BY_MODIFIER.put("uturn", 8);

        BY_TYPE.put("roundabout", 9);
        BY_TYPE.put("rotary", 9);
        BY_TYPE.put("roundabout turn", 9);
        BY_TYPE.put("merge", 10);
        BY_TYPE.put("on ramp", 11);
        BY_TYPE.put("off ramp", 12);
        BY_TYPE.put("fork", 13);
        BY_TYPE.put("end of road", 14);
        BY_TYPE.put("arrive", 15);
        BY_TYPE.put("depart", 1);
    }

    public static int forManeuver(String type, String modifier) {
        Integer byType = type != null ? BY_TYPE.get(type) : null;
        if (byType != null) return byType;
        Integer byModifier = modifier != null ? BY_MODIFIER.get(modifier) : null;
        return byModifier != null ? byModifier : DEFAULT_IC;
    }
}
