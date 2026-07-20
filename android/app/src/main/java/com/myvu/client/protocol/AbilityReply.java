package com.myvu.client.protocol;

/** Decoded ability-handshake reply (Kotlin data class -> plain immutable holder). */
public class AbilityReply {
    public final String deviceId;
    /** Null when the glasses omitted the auth bean. */
    public final String authBeanRaw;

    public AbilityReply(String deviceId, String authBeanRaw) {
        this.deviceId = deviceId;
        this.authBeanRaw = authBeanRaw;
    }
}
