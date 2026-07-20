package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.myvu.client.crypto.EcKeyPair;
import com.myvu.client.crypto.StarryCrypto;
import com.myvu.client.protocol.link.LinkProtocol;
import com.myvu.client.transport.ble.BlePackets;
import com.myvu.client.transport.ble.BleParsedPacket;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;

/**
 * Mirrors myvu_client/selftest.py checks 6 and 8.
 *
 * These are the highest-value offline tests in the project: an ECDH or AES bug
 * is otherwise only observable as "the glasses silently ignore us", with no
 * error to diagnose.
 */
public class CryptoTest {

    private static final byte[] MESSAGE =
            "the quick brown fox jumps over 13 lazy dogs!!".getBytes(StandardCharsets.UTF_8);

    @Test
    public void capturedPublicKeyLoadsAsP256() throws Exception {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F484);
        byte[] key = LinkProtocol.parseWriteSwitchKey(LinkProtocol.parse(p.value).data)[0];

        PublicKey pub = EcKeyPair.parsePublicSpkiDer(key);
        assertNotNull(pub);
        assertEquals("EC", pub.getAlgorithm());
        // 256-bit field size == P-256/secp256r1.
        assertEquals(256, ((ECPublicKey) pub).getParams().getCurve().getField().getFieldSize());
    }

    @Test
    public void generatedPublicKeyIsWireCompatible() throws Exception {
        // Our own key must serialise to the same 91-byte SPKI DER shape the
        // glasses sent, or WRITE_SWITCH_KEY will be rejected.
        byte[] spki = EcKeyPair.generate().publicSpkiDer();
        assertEquals(91, spki.length);
        assertEquals((byte) 0x30, spki[0]);
        assertEquals((byte) 0x59, spki[1]);
    }

    @Test
    public void ecdhSharedSecretsAgree() throws Exception {
        EcKeyPair a = EcKeyPair.generate();
        EcKeyPair b = EcKeyPair.generate();

        byte[] s1 = a.sharedSecret(b.publicSpkiDer());
        byte[] s2 = b.sharedSecret(a.publicSpkiDer());

        assertArrayEquals(s1, s2);
        // Raw X coordinate, used directly as the AES-256 key with no KDF.
        assertEquals(32, s1.length);
    }

    @Test
    public void generateIvIs16Bytes() {
        byte[] iv = StarryCrypto.generateIv();
        assertEquals(16, iv.length);
        // ASCII hex characters, not raw entropy -- matches EncryptionUtil.
        for (byte b : iv) {
            char c = (char) (b & 0xFF);
            assertEquals("IV must be ASCII hex, got '" + c + "'",
                    true, (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        }
    }

    @Test
    public void aesRoundTripsInEveryNegotiatedMode() throws Exception {
        EcKeyPair a = EcKeyPair.generate();
        EcKeyPair b = EcKeyPair.generate();
        byte[] key = a.sharedSecret(b.publicSpkiDer());
        byte[] iv = StarryCrypto.generateIv();

        int[] modes = {
                StarryCrypto.SYMMETRIC_V1_CBC,
                StarryCrypto.SYMMETRIC_V2_CTR,
                StarryCrypto.SYMMETRIC_V3_GCM,
        };
        for (int mode : modes) {
            byte[] ct = StarryCrypto.encrypt(MESSAGE, key, iv, mode);
            byte[] pt = StarryCrypto.decrypt(ct, key, iv, mode);
            assertArrayEquals("mode " + mode + " round-trip", MESSAGE, pt);
        }
    }

    @Test
    public void cbcPadsToBlockSize() throws Exception {
        // e=1 (CBC) is what a real captured session negotiated, so verify its
        // shape specifically: PKCS5 always pads, so ciphertext is a strict
        // multiple of 16 and strictly longer than a 44-byte plaintext.
        byte[] key = EcKeyPair.generate().sharedSecret(EcKeyPair.generate().publicSpkiDer());
        byte[] iv = StarryCrypto.generateIv();

        byte[] ct = StarryCrypto.encrypt(MESSAGE, key, iv, StarryCrypto.SYMMETRIC_V1_CBC);
        assertEquals(0, ct.length % 16);
        assertFalse(ct.length == MESSAGE.length);
    }

    @Test
    public void ctrDoesNotChangeLength() throws Exception {
        byte[] key = EcKeyPair.generate().sharedSecret(EcKeyPair.generate().publicSpkiDer());
        byte[] iv = StarryCrypto.generateIv();

        byte[] ct = StarryCrypto.encrypt(MESSAGE, key, iv, StarryCrypto.SYMMETRIC_V2_CTR);
        assertEquals(MESSAGE.length, ct.length);
    }

    @Test
    public void gcmAppendsA16ByteTag() throws Exception {
        byte[] key = EcKeyPair.generate().sharedSecret(EcKeyPair.generate().publicSpkiDer());
        byte[] iv = StarryCrypto.generateIv();

        byte[] ct = StarryCrypto.encrypt(MESSAGE, key, iv, StarryCrypto.SYMMETRIC_V3_GCM);
        assertEquals(MESSAGE.length + 16, ct.length);
    }
}
