"""Crypto layer.

Faithful port of
  com.upuphone.starrynet.strategy.encrypt.utils.EncryptionUtil

Key exchange : EC P-256 (secp256r1), raw ECDH shared secret used directly as
               the AES key (no KDF). Public keys are exchanged as X.509
               SubjectPublicKeyInfo DER (Java's ECPublicKey.getEncoded()).
Symmetric    : mode negotiated at runtime (ProtocolVersions "e"):
                 1 -> AES/CBC/PKCS5Padding
                 2 -> AES/CTR/NoPadding
                 else -> AES/GCM/NoPadding
IV           : 16 ASCII chars taken from a UUID4 with dashes stripped.

NOTE on the shared-secret length: Java KeyAgreement.generateSecret() for ECDH
returns the raw X coordinate (32 bytes for P-256). We use it directly as an
AES-256 key, exactly like the app.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
    load_der_public_key,
)

SYMMETRIC_V1_CBC = 1
SYMMETRIC_V2_CTR = 2
SYMMETRIC_V3_GCM = 3  # and any other value


@dataclass
class KeyPair:
    private: ec.EllipticCurvePrivateKey

    @property
    def public_spki_der(self) -> bytes:
        """Java ECPublicKey.getEncoded() == X.509 SubjectPublicKeyInfo DER."""
        return self.private.public_key().public_bytes(
            Encoding.DER, PublicFormat.SubjectPublicKeyInfo
        )


def generate_ec_keypair() -> KeyPair:
    """EncryptionUtil.generatorECKeyPair() -> KeyPairGenerator("EC", 256)."""
    return KeyPair(ec.generate_private_key(ec.SECP256R1()))


def ecdh_shared_secret(peer_pub_spki_der: bytes, own: KeyPair) -> bytes:
    """EncryptionUtil.getSecretKey(peerPubDer, ownPrivDer).

    Raw ECDH; result is the 32-byte X coordinate, used directly as AES key.
    """
    peer_pub = load_der_public_key(peer_pub_spki_der)
    return own.private.exchange(ec.ECDH(), peer_pub)


def generate_iv() -> bytes:
    """EncryptionUtil.generateIV(): first 16 chars of a dash-stripped UUID4,
    interpreted as ASCII bytes."""
    return uuid.uuid4().hex[:16].encode("ascii")


def _pkcs7_pad(data: bytes, block: int = 16) -> bytes:
    n = block - (len(data) % block)
    return data + bytes([n]) * n


def _pkcs7_unpad(data: bytes) -> bytes:
    if not data:
        return data
    n = data[-1]
    if 1 <= n <= 16 and data[-n:] == bytes([n]) * n:
        return data[:-n]
    return data  # tolerate unpadded / already-plain


def encrypt(plaintext: bytes, key: bytes, iv: bytes, mode: int) -> bytes:
    if mode == SYMMETRIC_V1_CBC:
        enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        return enc.update(_pkcs7_pad(plaintext)) + enc.finalize()
    if mode == SYMMETRIC_V2_CTR:
        enc = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        return enc.update(plaintext) + enc.finalize()
    # GCM: java AES/GCM/NoPadding with a 16-byte Iv and 128-bit tag appended.
    return AESGCM(key).encrypt(iv, plaintext, None)


def decrypt(ciphertext: bytes, key: bytes, iv: bytes, mode: int) -> bytes:
    if mode == SYMMETRIC_V1_CBC:
        dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        return _pkcs7_unpad(dec.update(ciphertext) + dec.finalize())
    if mode == SYMMETRIC_V2_CTR:
        dec = Cipher(algorithms.AES(key), modes.CTR(iv)).decryptor()
        return dec.update(ciphertext) + dec.finalize()
    return AESGCM(key).decrypt(iv, ciphertext, None)
