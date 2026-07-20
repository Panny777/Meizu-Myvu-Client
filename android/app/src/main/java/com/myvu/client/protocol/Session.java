package com.myvu.client.protocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of myvu_client/myvu/session.py (RunAsOne ability/AUTH
 * handshake). Over classic-BT/RFCOMM there is NO ECDH crypto step -- BR/EDR's
 * own link-layer encryption covers security, so this handshake is sent
 * directly (still wrapped in the eaca9353 relay framing, see Rfcomm).
 */
public final class Session {
    private Session() {}

    public static final int AUTH_CLASS_BYTE = 0x02;
    public static final int STREAM_AUTH = 0;
    public static final int STREAM_AUTH_SUCCESS = 12;

    private static final String DEFAULT_VERSION = "2.40.51";
    private static final int DEFAULT_WEIGHT = 233333;

    private static JSONObject abilityAttributesJson() throws JSONException {
        JSONObject relay = new JSONObject()
                .put("agreementType", 0)
                .put("json", new JSONObject()
                        .put("isSupportMapping", false)
                        .put("metaInfo", new JSONArray())
                        .put("metaMap", new JSONObject())
                        .toString())
                .put("supportTlv", true);
        JSONObject air = new JSONObject()
                .put("agreementType", 0)
                .put("json", new JSONObject()
                        .put("airMapping", new JSONObject()
                                .put("1", "com.upuphone.star.launcher")
                                .put("2", "com.upuphone.thanos.sdk_test"))
                        .toString())
                .put("supportTlv", true);
        return new JSONObject()
                .put("abilityRelay", relay.toString())
                .put("abilityAir", air.toString());
    }

    public static JSONObject buildAuthBean(String deviceIdHex, String deviceName, String session)
            throws JSONException {
        return buildAuthBean(deviceIdHex, deviceName, session, DEFAULT_VERSION, DEFAULT_WEIGHT);
    }

    public static JSONObject buildAuthBean(String deviceIdHex, String deviceName, String session,
                                           String version, int weight) throws JSONException {
        return new JSONObject()
                .put("ability", new JSONArray(Arrays.asList(
                        "abilityRelay", "abilityRelayBypass", "abilityAir", "abilityShare")))
                .put("abilityAttributes", new JSONObject().put("abilityAttributes", abilityAttributesJson()))
                .put("agreementType", 0)
                .put("deviceId", deviceIdHex)
                .put("deviceName", deviceName)
                .put("session", session)
                .put("supportTlv", true)
                .put("supportVirtual", false)
                .put("version", version)
                .put("weight", weight);
    }

    private static byte[] buildStreamReq(int streamType, String deviceIdHex, String deviceName,
                                         String session) throws JSONException {
        JSONObject bean = buildAuthBean(deviceIdHex, deviceName, session);
        byte[] beanJson = bean.toString().getBytes(StandardCharsets.UTF_8);
        long nowMs = System.currentTimeMillis();
        byte[] ts = ("timestamp-" + nowMs).getBytes(StandardCharsets.US_ASCII);

        byte[] body = new byte[0];
        if (streamType != 0) body = Pb.concat(body, Pb.varintField(1, streamType));
        body = Pb.concat(body, Pb.bytes(3, deviceIdHex.getBytes(StandardCharsets.US_ASCII)));
        body = Pb.concat(body, Pb.bytes(4, beanJson));
        body = Pb.concat(body, Pb.bytes(7, "1.2".getBytes(StandardCharsets.US_ASCII)));
        body = Pb.concat(body, Pb.bytes(9, ts));
        if (streamType == STREAM_AUTH_SUCCESS) body = Pb.concat(body, Pb.varintField(12, nowMs));
        return Pb.concat(new byte[] { (byte) AUTH_CLASS_BYTE }, body);
    }

    /** Phase 1: StreamReq type=AUTH (the initial ability handshake). */
    public static byte[] buildAbilityMessage(String deviceIdHex, String deviceName, String session)
            throws JSONException {
        return buildStreamReq(STREAM_AUTH, deviceIdHex, deviceName, session);
    }

    /** Phase 2: StreamReq type=AUTH_SUCCESS, sent after the glasses reply. */
    public static byte[] buildAuthSuccessMessage(String deviceIdHex, String deviceName, String session)
            throws JSONException {
        return buildStreamReq(STREAM_AUTH_SUCCESS, deviceIdHex, deviceName, session);
    }

    public static AbilityReply parseAbilityReply(byte[] payload) {
        byte[] body = (payload.length > 0 && (payload[0] & 0xFF) == AUTH_CLASS_BYTE)
                ? Arrays.copyOfRange(payload, 1, payload.length) : payload;
        Map<Integer, List<PbValue>> f = Pb.parse(body);
        return new AbilityReply(
                Pb.firstString(f, 3, ""),
                Pb.firstString(f, 4, null));
    }
}
