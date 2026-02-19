package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour CryptoEngine.
 * Valide les primitives crypto : HKDF, AES-256-CBC, HMAC-SHA256.
 */
class CryptoEngineTest {

    private static CryptoEngine cryptoEngine;

    @BeforeAll
    static void setUp() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CryptoLogger logger = new CryptoLogger();
        cryptoEngine = new CryptoEngine(logger);
    }

    // =========================================================================
    // Tests HKDF
    // =========================================================================

    @Test
    @DisplayName("HKDF produit une sortie de la bonne taille")
    void hkdf_produceCorrectOutputLength() {
        byte[] ikm  = new byte[32];
        byte[] salt = new byte[32];
        byte[] info = "test".getBytes();

        byte[] output32 = cryptoEngine.hkdf(ikm, salt, info, 32);
        byte[] output64 = cryptoEngine.hkdf(ikm, salt, info, 64);

        assertEquals(32, output32.length, "HKDF doit produire 32 bytes");
        assertEquals(64, output64.length, "HKDF doit produire 64 bytes");
    }

    @Test
    @DisplayName("HKDF est deterministe : meme entree → meme sortie")
    void hkdf_isDeterministic() {
        byte[] ikm  = "inputKeyMaterial".getBytes();
        byte[] salt = "sel".getBytes();
        byte[] info = "SignalX3DH".getBytes();

        byte[] output1 = cryptoEngine.hkdf(ikm, salt, info, 32);
        byte[] output2 = cryptoEngine.hkdf(ikm, salt, info, 32);

        assertArrayEquals(output1, output2, "HKDF doit etre deterministe");
    }

    @Test
    @DisplayName("HKDF produit des sorties differentes avec des entrees differentes")
    void hkdf_differentInputsDifferentOutputs() {
        byte[] salt = new byte[32];
        byte[] info = "test".getBytes();

        byte[] out1 = cryptoEngine.hkdf("input1".getBytes(), salt, info, 32);
        byte[] out2 = cryptoEngine.hkdf("input2".getBytes(), salt, info, 32);

        assertFalse(Arrays.equals(out1, out2), "Des entrees differentes doivent donner des sorties differentes");
    }

    // =========================================================================
    // Tests AES-256-CBC
    // =========================================================================

    @Test
    @DisplayName("Chiffrement puis dechiffrement retrouve le plaintext")
    void aes_encryptThenDecryptReturnsOriginal() {
        byte[] plaintext = "Bonjour le monde !".getBytes();
        byte[] key       = new byte[32]; // cle nulle de 32 bytes
        byte[] iv        = new byte[16]; // IV nul de 16 bytes

        byte[] ciphertext = cryptoEngine.encryptAES(plaintext, key, iv);
        byte[] decrypted  = cryptoEngine.decryptAES(ciphertext, key, iv);

        assertArrayEquals(plaintext, decrypted, "Le dechiffrement doit retrouver le plaintext original");
    }

    @Test
    @DisplayName("Le ciphertext est different du plaintext")
    void aes_ciphertextDiffersFromPlaintext() {
        byte[] plaintext = "Signal Protocol TP".getBytes();
        byte[] key       = new byte[32];
        byte[] iv        = new byte[16];

        byte[] ciphertext = cryptoEngine.encryptAES(plaintext, key, iv);

        assertFalse(Arrays.equals(plaintext, Arrays.copyOf(ciphertext, plaintext.length)),
                "Le ciphertext ne doit pas contenir le plaintext en clair");
    }

    @Test
    @DisplayName("Des cles differentes produisent des ciphertexts differents")
    void aes_differentKeysDifferentCiphertext() {
        byte[] plaintext = "meme message".getBytes();
        byte[] iv        = new byte[16];
        byte[] key1      = new byte[32];
        byte[] key2      = new byte[32];
        key2[0] = 1; // cle legerement differente

        byte[] ct1 = cryptoEngine.encryptAES(plaintext, key1, iv);
        byte[] ct2 = cryptoEngine.encryptAES(plaintext, key2, iv);

        assertFalse(Arrays.equals(ct1, ct2), "Des cles differentes doivent produire des ciphertexts differents");
    }

    @Test
    @DisplayName("Dechiffrement avec mauvaise cle echoue ou produit un plaintext different")
    void aes_wrongKeyFailsDecryption() {
        byte[] plaintext = "message secret".getBytes();
        byte[] correctKey = new byte[32];
        byte[] wrongKey   = new byte[32];
        wrongKey[5] = 99;
        byte[] iv = new byte[16];

        byte[] ciphertext = cryptoEngine.encryptAES(plaintext, correctKey, iv);

        // Soit une exception est levee, soit le plaintext est different
        try {
            byte[] decrypted = cryptoEngine.decryptAES(ciphertext, wrongKey, iv);
            assertFalse(Arrays.equals(plaintext, decrypted),
                    "Une mauvaise cle ne doit pas produire le bon plaintext");
        } catch (Exception e) {
            // Un BadPaddingException est aussi acceptable
            assertTrue(true, "Exception attendue avec une mauvaise cle");
        }
    }

    // =========================================================================
    // Tests HMAC-SHA256
    // =========================================================================

    @Test
    @DisplayName("HMAC-SHA256 produit 32 bytes")
    void hmac_produces32Bytes() {
        byte[] key  = new byte[32];
        byte[] data = "donnees test".getBytes();

        byte[] mac = cryptoEngine.hmacSHA256(key, data);

        assertEquals(32, mac.length, "HMAC-SHA256 doit produire 32 bytes");
    }

    @Test
    @DisplayName("HMAC est deterministe")
    void hmac_isDeterministic() {
        byte[] key  = "ma_cle".getBytes();
        byte[] data = "mes_donnees".getBytes();

        byte[] mac1 = cryptoEngine.hmacSHA256(key, data);
        byte[] mac2 = cryptoEngine.hmacSHA256(key, data);

        assertArrayEquals(mac1, mac2, "HMAC doit etre deterministe");
    }

    @Test
    @DisplayName("verifyHMAC accepte un MAC valide")
    void hmac_verifyAcceptsValidMac() {
        byte[] key  = new byte[32];
        byte[] data = "donnees authentiques".getBytes();
        byte[] mac  = cryptoEngine.hmacSHA256(key, data);

        assertTrue(cryptoEngine.verifyHMAC(key, data, mac), "verifyHMAC doit accepter un MAC valide");
    }

    @Test
    @DisplayName("verifyHMAC rejette un MAC invalide")
    void hmac_verifyRejectsInvalidMac() {
        byte[] key     = new byte[32];
        byte[] data    = "donnees authentiques".getBytes();
        byte[] badMac  = new byte[32]; // MAC incorrect (tout zeros)

        assertFalse(cryptoEngine.verifyHMAC(key, data, badMac), "verifyHMAC doit rejeter un MAC invalide");
    }

    @Test
    @DisplayName("verifyHMAC rejette si les donnees sont modifiees")
    void hmac_verifyRejectsModifiedData() {
        byte[] key     = new byte[32];
        byte[] data    = "donnees originales".getBytes();
        byte[] mac     = cryptoEngine.hmacSHA256(key, data);
        byte[] modified = "donnees modifiees!".getBytes();

        assertFalse(cryptoEngine.verifyHMAC(key, modified, mac), "verifyHMAC doit rejeter des donnees modifiees");
    }

    // =========================================================================
    // Tests IV
    // =========================================================================

    @Test
    @DisplayName("generateIV produit 16 bytes aleatoires")
    void generateIV_produces16Bytes() {
        byte[] iv = cryptoEngine.generateIV();
        assertEquals(16, iv.length, "L'IV doit faire 16 bytes");
    }

    @Test
    @DisplayName("Deux IV generes sont differents (unicite statistique)")
    void generateIV_producesUniqueValues() {
        byte[] iv1 = cryptoEngine.generateIV();
        byte[] iv2 = cryptoEngine.generateIV();
        assertFalse(Arrays.equals(iv1, iv2), "Deux IV generes ne doivent pas etre identiques");
    }

    // Tests KDF_RK

    @Test
    @DisplayName("kdfRootKey retourne deux tableaux de 32 bytes")
    void kdfRootKey_returnsTwoArraysOf32Bytes() {
        byte[] rootKey  = new byte[32];
        byte[] dhOutput = new byte[32];

        byte[][] result = cryptoEngine.kdfRootKey(rootKey, dhOutput);

        assertEquals(2, result.length);
        assertEquals(32, result[0].length, "newRootKey doit faire 32 bytes");
        assertEquals(32, result[1].length, "newChainKey doit faire 32 bytes");
    }

    @Test
    @DisplayName("kdfRootKey est deterministe")
    void kdfRootKey_isDeterministic() {
        byte[] rk  = "rootKey_test".getBytes();
        byte[] dh  = "dhOutput_test".getBytes();

        byte[][] r1 = cryptoEngine.kdfRootKey(rk, dh);
        byte[][] r2 = cryptoEngine.kdfRootKey(rk, dh);

        assertArrayEquals(r1[0], r2[0], "newRootKey doit etre deterministe");
        assertArrayEquals(r1[1], r2[1], "newChainKey doit etre deterministe");
    }
}
