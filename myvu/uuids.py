"""GATT UUIDs for the MYVU / StarryNet BLE protocol.

All values are reverse-engineered from the decompiled app:
  com.upuphone.starrynet.core.ble.BluetoothConstants
  com.upuphone.starrynet.core.ble.utils.UUIDUtils

UUIDUtils.makeUUID(i) -> "0000{i:04x}-0000-1000-8000-00805f9b34fb"
"""


def make_uuid(i: int) -> str:
    return f"0000{i:04x}-0000-1000-8000-00805f9b34fb"


# --- Service ------------------------------------------------------------
# BluetoothConstants.STARRY_NET_SERVICE_UUID = makeUUID(3025)
SERVICE_UUID = make_uuid(3025)  # 00000bd1-...

# --- "Air" glasses characteristics (model XGA010C / MYVU) ----------------
# These are the two channels seen in the btsnoop capture (handles 0x0023 / 0x0026).
#   INTERNAL  = link/pairing channel  (LinkProtocol: version negotiation + ECDH handshake)
#   EXTERNAL  = application data channel (JSON {"action": ...} messages)
#   URGENT    = high priority external messages
AIR_INTERNAL_UUID = make_uuid(8224)  # 0x2020  STARRY_NET_AIR_INTERNAL_MESSAGE_UUID
AIR_EXTERNAL_UUID = make_uuid(8225)  # 0x2021  STARRY_NET_AIR_EXTERNAL_MESSAGE_UUID
AIR_URGENT_UUID = make_uuid(8226)    # 0x2022  STARRY_NET_AIR_URGENT_EXTERNAL_MESSAGE_UUID
GLASS_WRITE_UUID = make_uuid(8227)   # 0x2023  STARRY_NET_GLASS_WRITE_UUID

# --- "V2" characteristics (other device types; some units advertise these) -
V2_INTERNAL_UUID = make_uuid(8208)   # 0x2010
V2_EXTERNAL_UUID = make_uuid(8209)   # 0x2011
V2_URGENT_UUID = make_uuid(8210)     # 0x2012

# --- Legacy / misc write + config characteristics ------------------------
MULTI_WRITE_UUID = make_uuid(8193)   # 0x2001  STARRY_NET_MULTI_WRITE_UUID
WRITE_MESSAGE_UUID = make_uuid(8194)  # 0x2002  STARRY_NET_WRITE_MESSAGE_UUID
READ_UUID = make_uuid(4096)          # 0x1000
WRITE_UUID = make_uuid(8192)         # 0x2000

CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

# Preferred (internal, external) channel pairs, in priority order. The client
# probes the connected device and uses the first pair whose characteristics
# are both present.
CHANNEL_PAIRS = [
    (AIR_INTERNAL_UUID, AIR_EXTERNAL_UUID),
    (V2_INTERNAL_UUID, V2_EXTERNAL_UUID),
]
