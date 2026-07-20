"""Offline correctness tests against real captured handshake bytes.

These frames are taken verbatim from btcsnoop_hci_full_session.log (handle 0x0023,
the internal/link channel) so we can validate the codec/protobuf/crypto layers
without any hardware.
"""
from myvu import crypto, linkproto, packets
from cryptography.hazmat.primitives.serialization import load_der_public_key

# --- captured frames (hex) ----------------------------------------------
F479 = bytes.fromhex("000006110100")                       # phone: FAST_CTR init
F480 = bytes.fromhex(                                        # phone: version JSON
    "01007b2269223a22376361333735643039346631222c2276223a332c2265223a"
    "352c226d223a3531322c2262223a322c2263223a2239393939227d")
F483 = bytes.fromhex(                                        # glasses: version JSON
    "000009117b2269223a22324336463445303044433437222c2276223a342c2265"
    "223a312c226d223a3531322c2262223a327d")
F484 = bytes.fromhex(                                        # phone: WRITE_SWITCH_KEY
    "000002100a060e6b2f8a5c83100b1a650a5b3059301306072a8648ce3d020106"
    "082a8648ce3d0301070342000440620bda2512a57f5716887ed299beea0f02c3"
    "9675cd831d64ceb27dab9ae52eaea1b6c4dc7999906767a68ffe3b6d9eb95244"
    "48053341f62f7e9ede5458a2b812067ca375d094f1")

ok = 0


def check(name, cond):
    global ok
    assert cond, f"FAILED: {name}"
    ok += 1
    print(f"  ok  {name}")


# 1. dealDeviceId matches the wire exactly
check("dealDeviceId(phone MAC) == device_id on wire",
      linkproto.deal_device_id(bytes.fromhex("7ca375d094f1"))
      == bytes.fromhex("0e6b2f8a5c83"))

# 2. FAST_CTR negotiation packet parses as expected
p479 = packets.parse(F479)
check("F479 is FAST_CTR", p479.type == packets.TYPE_FAST_CTR)
check("F479 pkgType == STARRY_DATA_INIT(17)", p479.pkg_type == 17)
check("F479 frameCount == 1", p479.frame_count == 1)

# 3. version JSON reassembles from the data fragment
p480 = packets.parse(F480)
check("F480 is data fragment seq 1", p480.is_data and p480.sn == 1)
import json
own = json.loads(p480.value.decode())
check("phone version i == 7ca375d094f1", own["i"] == "7ca375d094f1")
check("phone advertises e==5", own["e"] == 5)

# 4. glasses reply is SINGLE_NO_ACK carrying the negotiated encrypt mode
p483 = packets.parse(F483)
check("F483 is SINGLE_NO_ACK", p483.type == packets.TYPE_SINGLE_CMD_NO_ACK)
peer = json.loads(p483.value.decode())
check("glasses negotiate e==1 (AES/CBC)", peer["e"] == 1)

# 5. WRITE_SWITCH_KEY frame: SinglePacket -> LinkProtocol -> WriteSwitchKey
p484 = packets.parse(F484)
check("F484 is SinglePacket", p484.type == packets.TYPE_SINGLE_CMD)
check("F484 pkgType == STARRY_DATA(16)", p484.pkg_type == 16)
lp = linkproto.parse_link_protocol(p484.value)
check("F484 device_id matches dealDeviceId(MAC)",
      lp.device_id == bytes.fromhex("0e6b2f8a5c83"))
check("F484 cmd == WRITE_SWITCH_KEY(11)", lp.cmd == linkproto.CMD_WRITE_SWITCH_KEY)
key, info = linkproto.parse_write_switch_key(lp.data)
check("F484 WriteSwitchKey.info == phone MAC", info == bytes.fromhex("7ca375d094f1"))
check("F484 key is 91-byte P-256 SPKI DER", len(key) == 91 and key[:2] == b"\x30\x59")

# 6. the captured public key really loads as an EC P-256 key
pub = load_der_public_key(key)
check("captured pubkey loads as EC key", pub.curve.name == "secp256r1")

# 7. re-encoding reproduces the captured frame byte-for-byte
rebuilt = packets.single_packet(
    16,
    linkproto.link_protocol(
        bytes.fromhex("7ca375d094f1"),
        linkproto.CMD_WRITE_SWITCH_KEY,
        linkproto.write_switch_key(key, bytes.fromhex("7ca375d094f1"))))
check("re-encoded WRITE_SWITCH_KEY == captured F484 bytes", rebuilt == F484)

# 8. ECDH + AES round-trips in every negotiated mode
a, b = crypto.generate_ec_keypair(), crypto.generate_ec_keypair()
s1 = crypto.ecdh_shared_secret(b.public_spki_der, a)
s2 = crypto.ecdh_shared_secret(a.public_spki_der, b)
check("ECDH shared secrets agree", s1 == s2 and len(s1) == 32)
iv = crypto.generate_iv()
check("generateIV is 16 bytes", len(iv) == 16)
for mode, label in [(1, "CBC"), (2, "CTR"), (3, "GCM")]:
    msg = b"the quick brown fox jumps over 13 lazy dogs!!"
    ct = crypto.encrypt(msg, s1, iv, mode)
    pt = crypto.decrypt(ct, s2, iv, mode)
    check(f"AES {label} round-trip", pt == msg)

print(f"\nALL {ok} CHECKS PASSED")
