package com.myvu.client.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;

/**
 * EC P-256 keypair + ECDH, ported from myvu_client/myvu/crypto.py, which in
 * turn ports com.upuphone.starrynet.strategy.encrypt.utils.EncryptionUtil.
 *
 * This is a port back to the language the protocol was designed in: the wire
 * format for a public key is precisely Java's {@code ECPublicKey.getEncoded()},
 * i.e. X.509 SubjectPublicKeyInfo DER (91 bytes for P-256).
 */
public final class EcKeyPair {

    private final KeyPair keyPair;

    private EcKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /** EncryptionUtil.generatorECKeyPair() -- KeyPairGenerator("EC", 256). */
    public static EcKeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return new EcKeyPair(g.generateKeyPair());
    }

    /** X.509 SubjectPublicKeyInfo DER -- what goes on the wire in WriteSwitchKey.key. */
    public byte[] publicSpkiDer() {
        return keyPair.getPublic().getEncoded();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    /** Loads a peer public key from the 91-byte SPKI DER blob on the wire. */
    public static PublicKey parsePublicSpkiDer(byte[] spkiDer) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spkiDer));
    }

    /**
     * EncryptionUtil.getSecretKey(peerPubDer, ownPrivDer).
     *
     * Returns the raw 32-byte ECDH shared secret (the X coordinate), used
     * DIRECTLY as the AES-256 key with no KDF. Note this must be
     * {@code generateSecret()} and not {@code generateSecret("AES")} -- the
     * latter would apply provider-specific derivation and produce a key the
     * glasses cannot match.
     */
    public byte[] sharedSecret(byte[] peerPubSpkiDer) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(keyPair.getPrivate());
        ka.doPhase(parsePublicSpkiDer(peerPubSpkiDer), true);
        return ka.generateSecret();
    }
}
