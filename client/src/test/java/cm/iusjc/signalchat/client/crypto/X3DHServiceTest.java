package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.model.X3DHResult;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour X3DHService.
 *
 * Validation centrale : Alice et Bob doivent calculer independamment
 * le MEME master secret a partir des cles publiques echangees.
 * C'est la propriete fondamentale de X3DH.
 */
class X3DHServiceTest {

    private static KeyManager keyManager;
    private static CryptoEngine cryptoEngine;
    private static X3DHService x3dhService;

    @BeforeAll
    static void setUpClass() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CryptoLogger logger = new CryptoLogger();
        keyManager   = new KeyManager(logger);
        cryptoEngine = new CryptoEngine(logger);
        x3dhService  = new X3DHService(keyManager, cryptoEngine, logger);
    }

    // =========================================================================
    // Donnees de test : Bob (le recepteur)
    // =========================================================================

    /** Cles de Bob : identite, SPK et OPK */
    private IdentityKeyPair bobIdentityKey;
    private byte[][] bobSPK;           // [0]=seed, [1]=publicKey
    private byte[]   bobSPKSignature;  // signature XEdDSA de bobSPK[1]
    private byte[][][] bobOPKs;        // One-Time Pre-Keys de Bob
    private KeyBundle  bobKeyBundle;   // ce que le serveur publie

    @BeforeEach
    void setUpBob() {
        // Bob genere ses cles
        bobIdentityKey  = keyManager.generateIdentityKeyPair();
        bobSPK          = keyManager.generateSignedPreKeyPair();
        bobSPKSignature = keyManager.signXEdDSA(bobIdentityKey.getSeed(), bobSPK[1]);
        bobOPKs         = keyManager.generateOneTimePreKeys(5);

        // Construction du KeyBundle (ce que le serveur stocke pour Bob)
        List<String> opkPublicKeys = new java.util.ArrayList<>();
        for (byte[][] opk : bobOPKs) {
            opkPublicKeys.add(encodeB64(opk[1]));
        }

        bobKeyBundle = KeyBundle.builder()
                .username("Bob")
                .identityKey(encodeB64(bobIdentityKey.getX25519PublicKey()))
                .identitySigningKey(encodeB64(bobIdentityKey.getEd25519PublicKey()))
                .signedPreKey(encodeB64(bobSPK[1]))
                .signedPreKeyId(1)
                .signedPreKeySignature(encodeB64(bobSPKSignature))
                .oneTimePreKeys(opkPublicKeys)
                .build();
    }

    // =========================================================================
    // Tests principaux
    // =========================================================================

    @Test
    @DisplayName("Alice et Bob calculent le meme master secret (avec OPK)")
    void x3dh_aliceAndBobDeriveSameMasterSecret_withOPK() {
        IdentityKeyPair aliceIdentityKey = keyManager.generateIdentityKeyPair();
        X3DHResult aliceResult = x3dhService.initiateHandshake(aliceIdentityKey, bobKeyBundle);

        int opkId = aliceResult.getUsedOneTimePreKeyId();
        byte[] bobOPKSeed = bobOPKs[opkId][0];

        byte[] bobMasterSecret = x3dhService.processHandshake(
                bobIdentityKey,
                bobSPK[0],
                bobOPKSeed,
                aliceIdentityKey.getX25519PublicKey(),
                aliceResult.getEphemeralPublicKey()
        );

        assertArrayEquals(aliceResult.getMasterSecret(), bobMasterSecret,
                "Alice et Bob doivent calculer le meme master secret X3DH");
    }

    @Test
    @DisplayName("Alice et Bob calculent le meme master secret (sans OPK)")
    void x3dh_aliceAndBobDeriveSameMasterSecret_withoutOPK() {
        KeyBundle bundleNoOPK = KeyBundle.builder()
                .username("Bob")
                .identityKey(encodeB64(bobIdentityKey.getX25519PublicKey()))
                .identitySigningKey(encodeB64(bobIdentityKey.getEd25519PublicKey()))
                .signedPreKey(encodeB64(bobSPK[1]))
                .signedPreKeyId(1)
                .signedPreKeySignature(encodeB64(bobSPKSignature))
                .oneTimePreKeys(List.of())
                .build();

        IdentityKeyPair aliceIdentityKey = keyManager.generateIdentityKeyPair();
        X3DHResult aliceResult = x3dhService.initiateHandshake(aliceIdentityKey, bundleNoOPK);

        assertNull(aliceResult.getUsedOneTimePreKeyId(), "Sans OPK, usedOneTimePreKeyId doit etre null");

        byte[] bobMasterSecret = x3dhService.processHandshake(
                bobIdentityKey,
                bobSPK[0],
                null,
                aliceIdentityKey.getX25519PublicKey(),
                aliceResult.getEphemeralPublicKey()
        );

        assertArrayEquals(aliceResult.getMasterSecret(), bobMasterSecret,
                "Alice et Bob doivent calculer le meme master secret X3DH (sans OPK)");
    }

    @Test
    @DisplayName("Le master secret fait 32 bytes")
    void x3dh_masterSecretIs32Bytes() {
        IdentityKeyPair aliceIdentityKey = keyManager.generateIdentityKeyPair();
        X3DHResult result = x3dhService.initiateHandshake(aliceIdentityKey, bobKeyBundle);
        assertEquals(32, result.getMasterSecret().length, "Le master secret doit faire 32 bytes");
    }

    @Test
    @DisplayName("La cle ephemere d Alice fait 32 bytes")
    void x3dh_ephemeralKeyIs32Bytes() {
        IdentityKeyPair aliceIdentityKey = keyManager.generateIdentityKeyPair();
        X3DHResult result = x3dhService.initiateHandshake(aliceIdentityKey, bobKeyBundle);
        assertEquals(32, result.getEphemeralPublicKey().length, "EK_A doit faire 32 bytes");
    }

    @Test
    @DisplayName("Des handshakes differents produisent des master secrets differents")
    void x3dh_differentHandshakesDifferentSecrets() {
        IdentityKeyPair alice1 = keyManager.generateIdentityKeyPair();
        IdentityKeyPair alice2 = keyManager.generateIdentityKeyPair();

        X3DHResult result1 = x3dhService.initiateHandshake(alice1, bobKeyBundle);
        X3DHResult result2 = x3dhService.initiateHandshake(alice2, bobKeyBundle);

        assertFalse(Arrays.equals(result1.getMasterSecret(), result2.getMasterSecret()),
                "Des handshakes differents doivent produire des secrets differents");
    }

    @Test
    @DisplayName("Signature invalide dans le KeyBundle est rejetee")
    void x3dh_invalidSignatureIsRejected() {
        byte[] badSignature = new byte[64];
        KeyBundle badBundle = KeyBundle.builder()
                .username("Bob")
                .identityKey(encodeB64(bobIdentityKey.getX25519PublicKey()))
                .identitySigningKey(encodeB64(bobIdentityKey.getEd25519PublicKey()))
                .signedPreKey(encodeB64(bobSPK[1]))
                .signedPreKeyId(1)
                .signedPreKeySignature(encodeB64(badSignature))
                .oneTimePreKeys(List.of())
                .build();

        IdentityKeyPair aliceIdentityKey = keyManager.generateIdentityKeyPair();

        assertThrows(SecurityException.class,
                () -> x3dhService.initiateHandshake(aliceIdentityKey, badBundle),
                "Une signature invalide doit lever une SecurityException");
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    private static String encodeB64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
