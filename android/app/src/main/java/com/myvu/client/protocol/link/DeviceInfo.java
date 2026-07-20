package com.myvu.client.protocol.link;

import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DeviceInfo{1:btMac, 2:companyId, 3:categoryId, 4:modelId, 5:name, 6:battery,
 * 7:btStatus} -- the payload each side sends inside WRITE_SWITCH_INFO,
 * AES-encrypted with the ECDH-derived key.
 *
 * Successfully decoding the glasses' DeviceInfo is the end-to-end proof that
 * the whole BLE + protobuf + ECDH + AES stack is correct.
 */
public class DeviceInfo {
    public final String btMac;
    public final String companyId;
    public final String categoryId;
    public final String modelId;
    public final String name;
    public final int battery;
    public final int btStatus;

    public DeviceInfo(String btMac, String companyId, String categoryId, String modelId,
                      String name, int battery, int btStatus) {
        this.btMac = btMac;
        this.companyId = companyId;
        this.categoryId = categoryId;
        this.modelId = modelId;
        this.name = name;
        this.battery = battery;
        this.btStatus = btStatus;
    }

    /** Zero-valued battery/btStatus are omitted from the wire, matching Python. */
    public static byte[] build(String btMac, String companyId, String categoryId, String modelId,
                               String name, int battery, int btStatus) {
        byte[] out = Pb.string(1, btMac);
        out = Pb.concat(out, Pb.string(2, companyId));
        out = Pb.concat(out, Pb.string(3, categoryId));
        out = Pb.concat(out, Pb.string(4, modelId));
        out = Pb.concat(out, Pb.bytes(5, name.getBytes(StandardCharsets.UTF_8)));
        if (battery != 0) out = Pb.concat(out, Pb.varintField(6, battery));
        if (btStatus != 0) out = Pb.concat(out, Pb.varintField(7, btStatus));
        return out;
    }

    public static DeviceInfo parse(byte[] raw) {
        Map<Integer, List<PbValue>> f = Pb.parse(raw);
        return new DeviceInfo(
                Pb.firstString(f, 1, ""),
                Pb.firstString(f, 2, ""),
                Pb.firstString(f, 3, ""),
                Pb.firstString(f, 4, ""),
                Pb.firstString(f, 5, ""),
                (int) Pb.firstVarint(f, 6, 0),
                (int) Pb.firstVarint(f, 7, 0));
    }

    @Override
    public String toString() {
        return name + " (" + btMac + ", battery " + battery + "%, model " + modelId + ")";
    }
}
