package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.EncryptedMessage;
import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.model.X3DHResult;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour DoubleRatchetService.
 *
 * Scenarios testes :
 * 1. Alice chiffre, Bob dechiffre (sens unique)
 * 2. Plusieurs messages consecutifs dans le meme sens
 * 3. Echange bidirectionnel avec DH ratchet step
 * 4. Verification HMAC rejette un message falsifie
 */
class DoubleRatchetServiceTest {

    private static KeyManager keyManager;
    private static CryptoEngine cryptoEngine;
    private static X3DHService x3dhService;
    private static DoubleRatchetService doubleRatchet;

    @BeforeAll
    static void setUpClass() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CryptoLogger logger = new CryptoLogger();
        keyManager     = new KeyManager(logger);
        cryptoEngine   = new CryptoEngine(logger);
        x3dhService    = new X3DHService(keyManager, cryptoEngine, logger);
        doubleRatchet  = new DoubleRatchetService(keyManager, cryptoEngine, logger);
    }

    // Sessions etablies avant chaque test
    private SessionState aliceSession;
    private SessionState bobSession;
    private IdentityKeyPair aliceIdentityKey;
    private IdentityKeyPair bobIdentityKey;
    private byte[][] bobSPK;

    @BeforeEach
    void setUpSessions() {
        aliceIdentityKey  = keyManager.generateIdentityKeyPair();
        bobIdentityKey    = keyManager.generateIdentityKeyPair();
        bobSPK            = keyManager.generateSignedPreKeyPair();
        byte[]   bobSPKSig = keyManager.signXEdDSA(bobIdentityKey.getSeed(), bobSPK[1]);

        byte[][][] bobOPKs = keyManager.generateOneTimePreKeys(5);
        List<String> opkPublics = new java.util.ArrayList<>();
        for (byte[][] opk : bobOPKs) {
            opkPublics.add(b64(opk[1]));
        }

        KeyBundle bobBundle = KeyBundle.builder()
                .username("Bob")
                .identityKey(b64(bobIdentityKey.getX25519PublicKey()))
                .identitySigningKey(b64(bobIdentityKey.getEd25519PublicKey()))
                .signedPreKey(b64(bobSPK[1]))
                .signedPreKeyId(1)
                .signedPreKeySignature(b64(bobSPKSig))
                .oneTimePreKeys(opkPublics)
                .build();

        X3DHResult aliceX3DH = x3dhService.initiateHandshake(aliceIdentityKey, bobBundle);

        byte[] bobOPKSeed = bobOPKs[aliceX3DH.getUsedOneTimePreKeyId()][0];
        byte[] bobMasterSecret = x3dhService.processHandshake(
                bobIdentityKey,
                bobSPK[0],
                bobOPKSeed,
                aliceIdentityKey.getX25519PublicKey(),
                aliceX3DH.getEphemeralPublicKey()
        );

        assertArrayEquals(aliceX3DH.getMasterSecret(), bobMasterSecret,
                "Prerequis : le master secret X3DH doit etre identique");

        aliceSession = doubleRatchet.initSessionAlice(aliceX3DH.getMasterSecret(), bobSPK[1]);
        bobSession   = doubleRatchet.initSessionBob(bobMasterSecret, bobSPK[0], bobSPK[1]);
    }

    // =========================================================================
    // Test 1 : message simple Alice Bob
    // =========================================================================

    @Test
    @DisplayName("Alice chiffre, Bob dechiffre : le plaintext est identique")
    void encrypt_aliceToBob_decryptReturnsOriginal() {
        String original = "Bonjour Bob !";
        byte[] plaintext = original.getBytes(StandardCharsets.UTF_8);

        EncryptedMessage encrypted = doubleRatchet.ratchetEncrypt(
                aliceSession, plaintext, "Alice", "Bob");

        byte[] decrypted = doubleRatchet.ratchetDecrypt(bobSession, encrypted);

        assertEquals(original, new String(decrypted, StandardCharsets.UTF_8),
                "Bob doit dechiffrer le meme message qu Alice a envoye");
    }

    // =========================================================================
    // Test 2 : plusieurs messages consecutifs (meme sens)
    // =========================================================================

    @Test
    @DisplayName("Plusieurs messages Alice Bob : tous dechiffres correctement")
    void encrypt_multipleMessagesAliceToBob_allDecryptedCorrectly() {
        String[] messages = { "Premier message", "Deuxieme message", "Troisieme message" };

        for (String msg : messages) {
            byte[] plaintext = msg.getBytes(StandardCharsets.UTF_8);
            EncryptedMessage encrypted = doubleRatchet.ratchetEncrypt(
                    aliceSession, plaintext, "Alice", "Bob");
            byte[] decrypted = doubleRatchet.ratchetDecrypt(bobSession, encrypted);

            assertEquals(msg, new String(decrypted, StandardCharsets.UTF_8),
                    "Message '" + msg + "' doit etre dechiffre correctement");
        }
    }

    @Test
    @DisplayName("Chaque message utilise une cle differente (forward secrecy)")
    void encrypt_eachMessageUsesUniqueKey_forwardSecrecy() {
        byte[] plaintext = "message test".getBytes(StandardCharsets.UTF_8);

        EncryptedMessage msg1 = doubleRatchet.ratchetEncrypt(aliceSession, plaintext, "Alice", "Bob");
        EncryptedMessage msg2 = doubleRatchet.ratchetEncrypt(aliceSession, plaintext, "Alice", "Bob");

        assertNotEquals(msg1.getCiphertext(), msg2.getCiphertext(),
                "Deux messages consecutifs doivent avoir des ciphertexts differents");

        assertEquals(0, msg1.getMessageNumber());
        assertEquals(1, msg2.getMessageNumber());

        byte[] dec1 = doubleRatchet.ratchetDecrypt(bobSession, msg1);
        byte[] dec2 = doubleRatchet.ratchetDecrypt(bobSession, msg2);
        assertArrayEquals(plaintext, dec1);
        assertArrayEquals(plaintext, dec2);
    }

    // Test 3 : echange bidirectionnel avec DH Ratchet Step

    @Test
    @DisplayName("Echange bidirectionnel : DH ratchet step fonctionne")
    void encrypt_bidirectionalExchange_dhRatchetStepWorks() {
        byte[] m1 = "Alice dit : Bonjour Bob !".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedMessage e1 = doubleRatchet.ratchetEncrypt(aliceSession, m1, "Alice", "Bob");
        assertArrayEquals(m1, doubleRatchet.ratchetDecrypt(bobSession, e1));

        byte[] m2 = "Bob repond : Salut Alice !".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedMessage e2 = doubleRatchet.ratchetEncrypt(bobSession, m2, "Bob", "Alice");
        assertArrayEquals(m2, doubleRatchet.ratchetDecrypt(aliceSession, e2));

        byte[] m3 = "Alice : Comment vas-tu ?".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedMessage e3 = doubleRatchet.ratchetEncrypt(aliceSession, m3, "Alice", "Bob");
        assertArrayEquals(m3, doubleRatchet.ratchetDecrypt(bobSession, e3));

        byte[] m4 = "Bob : Tres bien merci !".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedMessage e4 = doubleRatchet.ratchetEncrypt(bobSession, m4, "Bob", "Alice");
        assertArrayEquals(m4, doubleRatchet.ratchetDecrypt(aliceSession, e4));
    }

    @Test
    @DisplayName("Les ratchet keys changent a chaque direction de conversation")
    void encrypt_ratchetKeyChangesOnDirectionSwitch() {
        // Alice envoie a Bob
        byte[] msg1 = "Premier".getBytes(StandardCharsets.UTF_8);
        EncryptedMessage enc1 = doubleRatchet.ratchetEncrypt(aliceSession, msg1, "Alice", "Bob");

        // Bob doit d'abord dechiffrer le message d'Alice pour initialiser
        // son sendingChainKey via dhRatchetStep
        doubleRatchet.ratchetDecrypt(bobSession, enc1);

        // On capture la cle ratchet d'Alice avant le ratchet step
        byte[] aliceKeyBefore = aliceSession.getLocalRatchetPublicKey().clone();

        // Bob repond (il a maintenant une sendingChainKey apres avoir recu d'Alice)
        byte[] msg2 = "Reponse".getBytes(StandardCharsets.UTF_8);
        EncryptedMessage enc2 = doubleRatchet.ratchetEncrypt(bobSession, msg2, "Bob", "Alice");

        // Alice dechiffre → declenche son DH ratchet step (nouvelle localRatchetKey)
        doubleRatchet.ratchetDecrypt(aliceSession, enc2);

        byte[] aliceKeyAfter = aliceSession.getLocalRatchetPublicKey();

        assertFalse(Arrays.equals(aliceKeyBefore, aliceKeyAfter),
                "Alice doit generer une nouvelle cle de ratchet apres le DH ratchet step");
    }

    // =========================================================================
    // Test 4 : securite - rejet des messages falsifies
    // =========================================================================

    @Test
    @DisplayName("Un message avec HMAC falsifie est rejete")
    void decrypt_modifiedHMAC_throwsSecurityException() {
        byte[] plaintext = "message original".getBytes(StandardCharsets.UTF_8);
        EncryptedMessage encrypted = doubleRatchet.ratchetEncrypt(aliceSession, plaintext, "Alice", "Bob");

        EncryptedMessage tampered = EncryptedMessage.builder()
                .sender(encrypted.getSender())
                .recipient(encrypted.getRecipient())
                .ciphertext(encrypted.getCiphertext())
                .iv(encrypted.getIv())
                .hmac(Base64.getEncoder().encodeToString(new byte[32]))
                .senderRatchetKey(encrypted.getSenderRatchetKey())
                .messageNumber(encrypted.getMessageNumber())
                .previousChainLength(encrypted.getPreviousChainLength())
                .build();

        assertThrows(SecurityException.class,
                () -> doubleRatchet.ratchetDecrypt(bobSession, tampered),
                "Un message avec HMAC invalide doit lever une SecurityException");
    }

    @Test
    @DisplayName("Un message avec ciphertext falsifie est rejete")
    void decrypt_modifiedCiphertext_throwsExceptionOrWrongPlaintext() {
        byte[] plaintext = "message original".getBytes(StandardCharsets.UTF_8);
        EncryptedMessage encrypted = doubleRatchet.ratchetEncrypt(aliceSession, plaintext, "Alice", "Bob");

        byte[] badCiphertext = new byte[32];
        badCiphertext[0] = (byte) 0xFF;
        EncryptedMessage tampered = EncryptedMessage.builder()
                .sender(encrypted.getSender())
                .recipient(encrypted.getRecipient())
                .ciphertext(Base64.getEncoder().encodeToString(badCiphertext))
                .iv(encrypted.getIv())
                .hmac(encrypted.getHmac())
                .senderRatchetKey(encrypted.getSenderRatchetKey())
                .messageNumber(encrypted.getMessageNumber())
                .previousChainLength(encrypted.getPreviousChainLength())
                .build();

        assertThrows(Exception.class,
                () -> doubleRatchet.ratchetDecrypt(bobSession, tampered),
                "Un ciphertext falsifie doit etre detecte");
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
