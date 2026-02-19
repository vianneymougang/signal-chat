package cm.iusjc.signalchat.client;

import cm.iusjc.signalchat.client.crypto.DoubleRatchetService;
import cm.iusjc.signalchat.client.crypto.IdentityKeyPair;
import cm.iusjc.signalchat.client.crypto.KeyManager;
import cm.iusjc.signalchat.client.crypto.CryptoEngine;
import cm.iusjc.signalchat.client.crypto.SessionState;
import cm.iusjc.signalchat.client.crypto.X3DHService;
import cm.iusjc.signalchat.client.model.EncryptedMessage;
import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.model.X3DHResult;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'integration End-to-End du protocole Signal.
 *
 * Valide le flux complet :
 *   Generation des cles → X3DH → Double Ratchet → Chiffrement
 *   → Transit chiffre (simule) → Dechiffrement
 *
 * Ce test est entierement autonome : aucun serveur, aucun contexte Spring.
 * Il instancie directement les services crypto et simule le relai reseau.
 *
 * Scenario :
 *   1. Alice et Bob generent leurs cles
 *   2. Alice initie le X3DH avec le bundle de Bob
 *   3. Alice chiffre "Bonjour Bob"
 *   4. [RELAI SIMULE] Le message chiffre est transmis a Bob
 *   5. Bob recoit, effectue son X3DH et dechiffre → "Bonjour Bob"
 *   6. Bob repond "Salut Alice"
 *   7. [RELAI SIMULE] Le message de Bob est transmis a Alice
 *   8. Alice dechiffre → "Salut Alice"
 *   9. Verification de l'etat des sessions (ratchet steps)
 */
@DisplayName("Test End-to-End : protocole Signal complet (X3DH + Double Ratchet)")
class EndToEndEncryptionTest {

    // --- Services Alice ---
    private CryptoLogger aliceLogger;
    private KeyManager   aliceKeyManager;
    private X3DHService  aliceX3DH;
    private DoubleRatchetService aliceRatchet;

    // --- Services Bob ---
    private CryptoLogger bobLogger;
    private KeyManager   bobKeyManager;
    private X3DHService  bobX3DH;
    private DoubleRatchetService bobRatchet;

    @BeforeEach
    void setUp() {
        // Chaque participant a ses propres instances de services (isolation totale)
        aliceLogger     = new CryptoLogger();
        aliceKeyManager = new KeyManager(aliceLogger);
        CryptoEngine aliceCrypto = new CryptoEngine(aliceLogger);
        aliceX3DH       = new X3DHService(aliceKeyManager, aliceCrypto, aliceLogger);
        aliceRatchet    = new DoubleRatchetService(aliceKeyManager, aliceCrypto, aliceLogger);

        bobLogger       = new CryptoLogger();
        bobKeyManager   = new KeyManager(bobLogger);
        CryptoEngine bobCrypto = new CryptoEngine(bobLogger);
        bobX3DH         = new X3DHService(bobKeyManager, bobCrypto, bobLogger);
        bobRatchet      = new DoubleRatchetService(bobKeyManager, bobCrypto, bobLogger);
    }

    // =========================================================================
    // Test principal : echange bidirectionnel complet
    // =========================================================================

    @Test
    @DisplayName("Alice → Bob → Alice : chiffrement/dechiffrement bidirectionnel")
    void aliceEtBobPeuventEchangerDesMessagesChiffres() {

        // =====================================================================
        // PHASE 1 — GENERATION DES CLES
        // =====================================================================
        logSection("PHASE 1 : GENERATION DES CLES");

        // --- Alice genere ses cles ---
        IdentityKeyPair aliceIdentity = aliceKeyManager.generateIdentityKeyPair();
        byte[][] aliceSPK     = aliceKeyManager.generateSignedPreKeyPair();
        byte[]   aliceSPKSig  = aliceKeyManager.signXEdDSA(aliceIdentity.getSeed(), aliceSPK[1]);
        byte[][][] aliceOPKs  = aliceKeyManager.generateOneTimePreKeys(5);
        log("Alice IK(X25519) : " + aliceLogger.truncateHex(aliceIdentity.getX25519PublicKey()));

        // --- Bob genere ses cles ---
        IdentityKeyPair bobIdentity = bobKeyManager.generateIdentityKeyPair();
        byte[][] bobSPK     = bobKeyManager.generateSignedPreKeyPair();
        byte[]   bobSPKSig  = bobKeyManager.signXEdDSA(bobIdentity.getSeed(), bobSPK[1]);
        byte[][][] bobOPKs  = bobKeyManager.generateOneTimePreKeys(5);
        log("Bob   IK(X25519) : " + bobLogger.truncateHex(bobIdentity.getX25519PublicKey()));

        // =====================================================================
        // PHASE 2 — CONSTRUCTION DES KEY BUNDLES (simule l'enregistrement serveur)
        // =====================================================================
        logSection("PHASE 2 : KEY BUNDLES");

        KeyBundle bobBundle = buildBundle("Bob", bobIdentity, bobSPK, bobSPKSig, bobOPKs);
        log("Bundle de Bob cree | OPKs: " + bobBundle.getOneTimePreKeys().size());

        // =====================================================================
        // PHASE 3 — X3DH COTE ALICE (initiatrice)
        // =====================================================================
        logSection("PHASE 3 : X3DH (cote Alice)");

        X3DHResult aliceX3DHResult = aliceX3DH.initiateHandshake(aliceIdentity, bobBundle);
        byte[] aliceMasterSecret = aliceX3DHResult.getMasterSecret();
        log("Alice masterSecret : " + aliceLogger.truncateHex(aliceMasterSecret));

        // =====================================================================
        // PHASE 4 — INITIALISATION DU DOUBLE RATCHET (Alice)
        // =====================================================================
        logSection("PHASE 4 : INIT DOUBLE RATCHET (Alice)");

        byte[] bobSPKPub = Base64.getDecoder().decode(bobBundle.getSignedPreKey());
        SessionState aliceState = aliceRatchet.initSessionAlice(aliceMasterSecret, bobSPKPub);
        log("Alice rootKey initial    : " + aliceLogger.truncateHex(aliceState.getRootKey()));
        log("Alice sendingChainKey   : " + aliceLogger.truncateHex(aliceState.getSendingChainKey()));

        // =====================================================================
        // PHASE 5 — ALICE CHIFFRE "Bonjour Bob"
        // =====================================================================
        logSection("PHASE 5 : ALICE CHIFFRE");

        byte[] rootKeyAvantEnvoi = aliceState.getRootKey().clone();
        EncryptedMessage msg1 = aliceRatchet.ratchetEncrypt(
                aliceState,
                "Bonjour Bob".getBytes(StandardCharsets.UTF_8),
                "Alice", "Bob");

        // Injecter les headers X3DH (necessaires pour que Bob etablisse la session)
        msg1.setSenderIdentityKey(b64(aliceIdentity.getX25519PublicKey()));
        msg1.setSenderIdentitySigningKey(b64(aliceIdentity.getEd25519PublicKey()));
        msg1.setSenderEphemeralKey(b64(aliceX3DHResult.getEphemeralPublicKey()));
        msg1.setUsedOneTimePreKeyId(aliceX3DHResult.getUsedOneTimePreKeyId());

        assertNotNull(msg1.getCiphertext(), "Le ciphertext ne doit pas etre null");
        assertNotNull(msg1.getHmac(),       "Le HMAC ne doit pas etre null");
        assertEquals("Alice", msg1.getSender());
        assertEquals("Bob",   msg1.getRecipient());
        assertEquals(0, msg1.getMessageNumber());
        log("Alice ciphertext  : " + aliceLogger.truncateHex(b64Decode(msg1.getCiphertext())));
        log("Alice HMAC        : " + aliceLogger.truncateHex(b64Decode(msg1.getHmac())));

        // =====================================================================
        // PHASE 6 — RELAI SIMULE (serveur)
        // =====================================================================
        logSection("PHASE 6 : RELAI SIMULE (serveur)");
        log("RELAI [Alice] -> [Bob] | ciphertext: " + msg1.getCiphertext().length() + " bytes (Base64)");
        log("  ⚠ Le serveur NE PEUT PAS lire ce message — chiffrement E2EE actif");
        // Le serveur passe msg1 tel quel a Bob — aucun dechiffrement ici

        // =====================================================================
        // PHASE 7 — BOB RECOIT ET DECHIFFRE
        // =====================================================================
        logSection("PHASE 7 : X3DH + DECHIFFREMENT (cote Bob)");

        // Bob effectue son X3DH depuis les headers du message
        byte[] aliceIdentityPub  = b64Decode(msg1.getSenderIdentityKey());
        byte[] aliceEphemeralPub = b64Decode(msg1.getSenderEphemeralKey());
        byte[] opkSeed = msg1.getUsedOneTimePreKeyId() != null
                ? bobOPKs[msg1.getUsedOneTimePreKeyId()][0] : null;

        byte[] bobMasterSecret = bobX3DH.processHandshake(
                bobIdentity, bobSPK[0], opkSeed, aliceIdentityPub, aliceEphemeralPub);
        log("Bob masterSecret  : " + bobLogger.truncateHex(bobMasterSecret));

        // Les deux masterSecret doivent etre identiques (propriete fondamentale du X3DH)
        assertArrayEquals(aliceMasterSecret, bobMasterSecret,
                "Alice et Bob doivent calculer le meme masterSecret");
        log("✓ masterSecrets identiques des deux cotes");

        // Bob initialise sa session Double Ratchet
        SessionState bobState = bobRatchet.initSessionBob(bobMasterSecret, bobSPK[0], bobSPK[1]);

        // Bob dechiffre le message d'Alice
        byte[] plaintext1 = bobRatchet.ratchetDecrypt(bobState, msg1);
        String decrypted1 = new String(plaintext1, StandardCharsets.UTF_8);
        log("Bob a dechiffre   : '" + decrypted1 + "'");

        assertEquals("Bonjour Bob", decrypted1,
                "Bob doit dechiffrer exactement 'Bonjour Bob'");

        // =====================================================================
        // PHASE 8 — BOB REPOND "Salut Alice"
        // =====================================================================
        logSection("PHASE 8 : BOB CHIFFRE SA REPONSE");

        EncryptedMessage msg2 = bobRatchet.ratchetEncrypt(
                bobState,
                "Salut Alice".getBytes(StandardCharsets.UTF_8),
                "Bob", "Alice");

        assertNotNull(msg2.getCiphertext());
        assertEquals("Bob",   msg2.getSender());
        assertEquals("Alice", msg2.getRecipient());
        log("Bob ciphertext    : " + bobLogger.truncateHex(b64Decode(msg2.getCiphertext())));

        // =====================================================================
        // PHASE 9 — RELAI SIMULE (serveur)
        // =====================================================================
        logSection("PHASE 9 : RELAI SIMULE (serveur)");
        log("RELAI [Bob] -> [Alice] | ciphertext: " + msg2.getCiphertext().length() + " bytes (Base64)");
        log("  ⚠ Le serveur NE PEUT PAS lire ce message — chiffrement E2EE actif");

        // =====================================================================
        // PHASE 10 — ALICE DECHIFFRE LA REPONSE DE BOB
        // =====================================================================
        logSection("PHASE 10 : ALICE DECHIFFRE");

        byte[] plaintext2 = aliceRatchet.ratchetDecrypt(aliceState, msg2);
        String decrypted2 = new String(plaintext2, StandardCharsets.UTF_8);
        log("Alice a dechiffre : '" + decrypted2 + "'");

        assertEquals("Salut Alice", decrypted2,
                "Alice doit dechiffrer exactement 'Salut Alice'");

        // =====================================================================
        // PHASE 11 — VERIFICATION DE L'ETAT DES SESSIONS
        // =====================================================================
        logSection("PHASE 11 : ETAT DES SESSIONS (DH Ratchet steps)");

        // Alice a envoye 1 message dans sa premiere chaine, puis a fait un DH ratchet
        // step en recevant la reponse de Bob (qui remet sendingMessageNumber a 0)
        assertEquals(1, aliceState.getPreviousSendingChainLength(),
                "Alice a envoye 1 message avant le DH ratchet step");
        assertEquals(1, aliceState.getReceivingMessageNumber(),
                "Alice a recu 1 message de Bob");
        log("Alice.previousSendingChainLength : " + aliceState.getPreviousSendingChainLength());
        log("Alice.receivingMessageNumber     : " + aliceState.getReceivingMessageNumber());

        // Bob a recu 1 message d'Alice et envoye 1 message en retour
        assertEquals(1, bobState.getReceivingMessageNumber(),
                "Bob a recu 1 message d'Alice");
        assertEquals(1, bobState.getSendingMessageNumber(),
                "Bob a envoye 1 message en retour");
        log("Bob.receivingMessageNumber : " + bobState.getReceivingMessageNumber());
        log("Bob.sendingMessageNumber   : " + bobState.getSendingMessageNumber());

        // Le rootKey a evolue depuis le masterSecret initial (forward secrecy)
        assertFalse(Arrays.equals(aliceMasterSecret, aliceState.getRootKey()),
                "Le rootKey d'Alice doit avoir evolue depuis le masterSecret initial");
        assertFalse(Arrays.equals(bobMasterSecret, bobState.getRootKey()),
                "Le rootKey de Bob doit avoir evolue depuis le masterSecret initial");
        log("✓ rootKey evolue (forward secrecy confirmee)");

        logSection("TOUS LES TESTS PASSES ✓");
    }

    // =========================================================================
    // Test supplementaire : plusieurs messages d'affile (ratchet symetrique)
    // =========================================================================

    @Test
    @DisplayName("Alice envoie 3 messages : chaque message a une cle unique")
    void troisMessagesDAffile_chaqueMessageACleUnique() {
        // Setup : Alice et Bob etablissent une session
        IdentityKeyPair aliceId = aliceKeyManager.generateIdentityKeyPair();
        IdentityKeyPair bobId   = bobKeyManager.generateIdentityKeyPair();
        byte[][] bobSPK         = bobKeyManager.generateSignedPreKeyPair();
        byte[] bobSPKSig        = bobKeyManager.signXEdDSA(bobId.getSeed(), bobSPK[1]);
        byte[][][] bobOPKs      = bobKeyManager.generateOneTimePreKeys(3);

        KeyBundle bobBundle = buildBundle("Bob", bobId, bobSPK, bobSPKSig, bobOPKs);

        X3DHResult x3dhResult = aliceX3DH.initiateHandshake(aliceId, bobBundle);
        byte[] masterSecret   = x3dhResult.getMasterSecret();
        SessionState aliceState = aliceRatchet.initSessionAlice(
                masterSecret, Base64.getDecoder().decode(bobBundle.getSignedPreKey()));

        byte[] opkSeed = x3dhResult.getUsedOneTimePreKeyId() != null
                ? bobOPKs[x3dhResult.getUsedOneTimePreKeyId()][0] : null;
        byte[] bobMasterSecret = bobX3DH.processHandshake(
                bobId, bobSPK[0], opkSeed,
                aliceId.getX25519PublicKey(), x3dhResult.getEphemeralPublicKey());
        SessionState bobState = bobRatchet.initSessionBob(bobMasterSecret, bobSPK[0], bobSPK[1]);

        // Alice envoie 3 messages
        String[] plaintexts = {"Message 1", "Message 2", "Message 3"};
        List<EncryptedMessage> messages = new ArrayList<>();
        byte[][] ciphertexts = new byte[3][];

        for (int i = 0; i < 3; i++) {
            EncryptedMessage msg = aliceRatchet.ratchetEncrypt(
                    aliceState, plaintexts[i].getBytes(StandardCharsets.UTF_8), "Alice", "Bob");
            if (i == 0) {
                msg.setSenderIdentityKey(b64(aliceId.getX25519PublicKey()));
                msg.setSenderIdentitySigningKey(b64(aliceId.getEd25519PublicKey()));
                msg.setSenderEphemeralKey(b64(x3dhResult.getEphemeralPublicKey()));
                msg.setUsedOneTimePreKeyId(x3dhResult.getUsedOneTimePreKeyId());
            }
            messages.add(msg);
            ciphertexts[i] = b64Decode(msg.getCiphertext());
        }

        // Chaque ciphertext doit etre unique (ratchet symetrique → cle unique par message)
        assertFalse(Arrays.equals(ciphertexts[0], ciphertexts[1]),
                "Les ciphertexts 0 et 1 doivent etre differents");
        assertFalse(Arrays.equals(ciphertexts[1], ciphertexts[2]),
                "Les ciphertexts 1 et 2 doivent etre differents");

        // Bob dechiffre les 3 messages dans l'ordre
        for (int i = 0; i < 3; i++) {
            byte[] plaintext = bobRatchet.ratchetDecrypt(bobState, messages.get(i));
            assertEquals(plaintexts[i], new String(plaintext, StandardCharsets.UTF_8),
                    "Bob doit dechiffrer correctement le message " + i);
        }

        assertEquals(3, bobState.getReceivingMessageNumber());
    }

    // =========================================================================
    // Test : rejet d'un message falsifie (HMAC invalide)
    // =========================================================================

    @Test
    @DisplayName("Message falsifie : HMAC invalide -> SecurityException")
    void messageFalsifie_hmacInvalide_doitEtreRejete() {
        // Setup minimal
        IdentityKeyPair aliceId = aliceKeyManager.generateIdentityKeyPair();
        IdentityKeyPair bobId   = bobKeyManager.generateIdentityKeyPair();
        byte[][] bobSPK         = bobKeyManager.generateSignedPreKeyPair();
        byte[] bobSPKSig        = bobKeyManager.signXEdDSA(bobId.getSeed(), bobSPK[1]);
        byte[][][] bobOPKs      = bobKeyManager.generateOneTimePreKeys(1);

        KeyBundle bobBundle = buildBundle("Bob", bobId, bobSPK, bobSPKSig, bobOPKs);

        X3DHResult x3dhResult = aliceX3DH.initiateHandshake(aliceId, bobBundle);
        SessionState aliceState = aliceRatchet.initSessionAlice(
                x3dhResult.getMasterSecret(), Base64.getDecoder().decode(bobBundle.getSignedPreKey()));

        byte[] opkSeed = x3dhResult.getUsedOneTimePreKeyId() != null
                ? bobOPKs[x3dhResult.getUsedOneTimePreKeyId()][0] : null;
        byte[] bobMasterSecret = bobX3DH.processHandshake(
                bobId, bobSPK[0], opkSeed,
                aliceId.getX25519PublicKey(), x3dhResult.getEphemeralPublicKey());
        SessionState bobState = bobRatchet.initSessionBob(bobMasterSecret, bobSPK[0], bobSPK[1]);

        // Alice chiffre un message
        EncryptedMessage msg = aliceRatchet.ratchetEncrypt(
                aliceState, "Message secret".getBytes(StandardCharsets.UTF_8), "Alice", "Bob");
        msg.setSenderIdentityKey(b64(aliceId.getX25519PublicKey()));
        msg.setSenderIdentitySigningKey(b64(aliceId.getEd25519PublicKey()));
        msg.setSenderEphemeralKey(b64(x3dhResult.getEphemeralPublicKey()));
        msg.setUsedOneTimePreKeyId(x3dhResult.getUsedOneTimePreKeyId());

        // Falsifier le HMAC (attaque de type man-in-the-middle)
        msg.setHmac(Base64.getEncoder().encodeToString(new byte[32])); // HMAC nul = invalide

        // Bob doit rejeter le message falsifie
        assertThrows(SecurityException.class,
                () -> bobRatchet.ratchetDecrypt(bobState, msg),
                "Un message avec HMAC invalide doit lever SecurityException");
        log("✓ Message falsifie correctement rejete (HMAC invalide)");
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /**
     * Construit un KeyBundle depuis les cles generees d'un participant.
     */
    private KeyBundle buildBundle(String username, IdentityKeyPair identity,
            byte[][] spk, byte[] spkSig, byte[][][] opks) {
        List<String> opkPublics = new ArrayList<>();
        for (byte[][] opk : opks) {
            opkPublics.add(b64(opk[1]));
        }
        return KeyBundle.builder()
                .username(username)
                .identityKey(b64(identity.getX25519PublicKey()))
                .identitySigningKey(b64(identity.getEd25519PublicKey()))
                .signedPreKey(b64(spk[1]))
                .signedPreKeyId(0)
                .signedPreKeySignature(b64(spkSig))
                .oneTimePreKeys(opkPublics)
                .build();
    }

    private String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] b64Decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    private void logSection(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    private void log(String msg) {
        System.out.println("  " + msg);
    }
}
