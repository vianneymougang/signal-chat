package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.CryptoEventType;
import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.model.X3DHResult;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * Implementation du protocole X3DH (Extended Triple Diffie-Hellman).
 *
 * X3DH permet a deux parties (Alice et Bob) de calculer independamment
 * le meme secret partage, sans avoir communique au prealable, en utilisant
 * uniquement les cles publiques pre-publiees par Bob.
 *
 * Notation du protocole Signal :
 * - IK_A : Identity Key d'Alice (X25519)
 * - EK_A : Ephemeral Key d'Alice (X25519, generee pour cet echange)
 * - IK_B : Identity Key de Bob (X25519)
 * - SPK_B : Signed Pre-Key de Bob (X25519, signee par IK_B via XEdDSA)
 * - OPK_B : One-Time Pre-Key de Bob (X25519, optionnelle)
 *
 * Secret partage :
 * SK = HKDF(F || DH1 || DH2 || DH3 [|| DH4])
 * ou F = 0xFF * 32 bytes (constante de domaine Signal)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class X3DHService {

    private final KeyManager keyManager;
    private final CryptoEngine cryptoEngine;
    private final CryptoLogger cryptoLogger;

    /**
     * Constante de domaine Signal : 32 bytes a 0xFF.
     * Utilisee comme prefixe de l'IKM de l'HKDF pour separer les domaines
     * et prevenir les attaques par confusion de protocole.
     */
    private static final byte[] SIGNAL_DOMAIN_SEPARATOR = new byte[32];
    static {
        java.util.Arrays.fill(SIGNAL_DOMAIN_SEPARATOR, (byte) 0xFF);
    }

    // =========================================================================
    // COTE ALICE (initiatrice)
    // =========================================================================

    /**
     * Initie un echange X3DH cote Alice.
     *
     * Etapes :
     * 1. Verifie la signature XEdDSA de la Signed Pre-Key de Bob
     * 2. Genere une cle ephemere EK_A
     * 3. Calcule DH1 = X25519(IK_A, SPK_B)
     * 4. Calcule DH2 = X25519(EK_A, IK_B)
     * 5. Calcule DH3 = X25519(EK_A, SPK_B)
     * 6. Calcule DH4 = X25519(EK_A, OPK_B) [si OPK disponible]
     * 7. masterSecret = HKDF(F || DH1 || DH2 || DH3 [|| DH4])
     *
     * @param aliceIdentityKey  paire de cles d'identite d'Alice
     * @param bobKeyBundle      bundle de cles publiques de Bob (recu du serveur)
     * @return X3DHResult contenant masterSecret, EK_A public, OPK_B id utilise
     */
    public X3DHResult initiateHandshake(IdentityKeyPair aliceIdentityKey, KeyBundle bobKeyBundle) {
        log.info("=== Demarrage X3DH (cote Alice) → destinataire : {} ===", bobKeyBundle.getUsername());

        // --- Decodage des cles publiques de Bob depuis Base64 ---
        byte[] ikBPub     = decodeBase64(bobKeyBundle.getIdentityKey());
        byte[] ikBSigPub  = decodeBase64(bobKeyBundle.getIdentitySigningKey());
        byte[] spkBPub    = decodeBase64(bobKeyBundle.getSignedPreKey());
        byte[] spkBSig    = decodeBase64(bobKeyBundle.getSignedPreKeySignature());

        // --- Etape 0 : Verification de la signature XEdDSA de SPK_B ---
        log.debug("Verification signature XEdDSA de SPK_B...");
        boolean sigValid = keyManager.verifyXEdDSA(ikBSigPub, spkBPub, spkBSig);
        if (!sigValid) {
            throw new SecurityException("Signature XEdDSA de la Signed Pre-Key de "
                    + bobKeyBundle.getUsername() + " INVALIDE — echange X3DH abandonne");
        }
        log.debug("Signature SPK_B : ✓ valide");

        // --- Etape 1 : Generation de la cle ephemere EK_A ---
        byte[][] ekAKeyPair = keyManager.generateSignedPreKeyPair(); // X25519 simple
        byte[] ekASeed = ekAKeyPair[0];
        byte[] ekAPub  = ekAKeyPair[1];
        cryptoLogger.logEvent(CryptoEventType.KEY_GEN, null, ekAPub,
                "Generation EK_A (ephemeral key d'Alice) | pub=" + cryptoLogger.truncateHex(ekAPub));

        // --- Etape 2 : Quatre echanges DH ---

        // DH1 = ECDH(IK_A_priv, SPK_B_pub)
        byte[] dh1 = keyManager.performDH(
                aliceIdentityKey.getSeed(), spkBPub,
                CryptoEventType.X3DH_DH1,
                "DH1 = ECDH(IK_A, SPK_B)");

        // DH2 = ECDH(EK_A_priv, IK_B_pub)
        byte[] dh2 = keyManager.performDH(
                ekASeed, ikBPub,
                CryptoEventType.X3DH_DH2,
                "DH2 = ECDH(EK_A, IK_B)");

        // DH3 = ECDH(EK_A_priv, SPK_B_pub)
        byte[] dh3 = keyManager.performDH(
                ekASeed, spkBPub,
                CryptoEventType.X3DH_DH3,
                "DH3 = ECDH(EK_A, SPK_B)");

        // DH4 = ECDH(EK_A_priv, OPK_B_pub) — si une OPK est disponible
        byte[] dh4 = null;
        Integer usedOPKId = null;
        List<String> opks = bobKeyBundle.getOneTimePreKeys();
        if (opks != null && !opks.isEmpty()) {
            byte[] opkBPub = decodeBase64(opks.get(0));
            dh4 = keyManager.performDH(
                    ekASeed, opkBPub,
                    CryptoEventType.X3DH_DH4,
                    "DH4 = ECDH(EK_A, OPK_B)");
            // Utiliser l'index original fourni par le serveur (et non l'index dans la liste)
            usedOPKId = bobKeyBundle.getOneTimePreKeyId() != null
                    ? bobKeyBundle.getOneTimePreKeyId() : 0;
            log.debug("OPK_B utilisee (id={})", usedOPKId);
        } else {
            log.warn("Aucune OPK disponible pour {} — X3DH avec 3 DH seulement", bobKeyBundle.getUsername());
        }

        // --- Etape 3 : Calcul du master secret ---
        byte[] masterSecret = computeMasterSecret(dh1, dh2, dh3, dh4);

        log.info("=== X3DH termine | masterSecret={} ===",
                cryptoLogger.truncateHex(masterSecret));

        return X3DHResult.builder()
                .ephemeralPublicKey(ekAPub)
                .usedOneTimePreKeyId(usedOPKId)
                .masterSecret(masterSecret)
                .build();
    }

    // =========================================================================
    // COTE BOB (recepteur)
    // =========================================================================

    /**
     * Traite un echange X3DH cote Bob.
     *
     * Bob recalcule les memes 4 echanges DH qu'Alice, dans le meme ordre,
     * grace a la propriete de commutativite de ECDH :
     * ECDH(A_priv, B_pub) == ECDH(B_priv, A_pub)
     *
     * @param bobIdentityKey    paire de cles d'identite de Bob
     * @param bobSPKSeed        cle privee de la Signed Pre-Key de Bob (seed 32 bytes)
     * @param bobOPKSeed        cle privee de la One-Time Pre-Key utilisee (null si aucune)
     * @param aliceIdentityPub  cle publique X25519 d'Alice (32 bytes)
     * @param aliceEphemeralPub cle ephemere publique d'Alice EK_A (32 bytes)
     * @return masterSecret de 32 bytes (identique a celui calcule par Alice)
     */
    public byte[] processHandshake(
            IdentityKeyPair bobIdentityKey,
            byte[] bobSPKSeed,
            byte[] bobOPKSeed,
            byte[] aliceIdentityPub,
            byte[] aliceEphemeralPub) {

        log.info("=== Traitement X3DH (cote Bob) ===");

        // DH1 = ECDH(SPK_B_priv, IK_A_pub)   [Bob calcule l'equivalent de DH1 d'Alice]
        byte[] dh1 = keyManager.performDH(
                bobSPKSeed, aliceIdentityPub,
                CryptoEventType.X3DH_DH1,
                "DH1 = ECDH(SPK_B, IK_A) [cote Bob]");

        // DH2 = ECDH(IK_B_priv, EK_A_pub)    [equivalent du DH2 d'Alice]
        byte[] dh2 = keyManager.performDH(
                bobIdentityKey.getSeed(), aliceEphemeralPub,
                CryptoEventType.X3DH_DH2,
                "DH2 = ECDH(IK_B, EK_A) [cote Bob]");

        // DH3 = ECDH(SPK_B_priv, EK_A_pub)   [equivalent du DH3 d'Alice]
        byte[] dh3 = keyManager.performDH(
                bobSPKSeed, aliceEphemeralPub,
                CryptoEventType.X3DH_DH3,
                "DH3 = ECDH(SPK_B, EK_A) [cote Bob]");

        // DH4 = ECDH(OPK_B_priv, EK_A_pub)   [equivalent du DH4 d'Alice, si OPK utilisee]
        byte[] dh4 = null;
        if (bobOPKSeed != null) {
            dh4 = keyManager.performDH(
                    bobOPKSeed, aliceEphemeralPub,
                    CryptoEventType.X3DH_DH4,
                    "DH4 = ECDH(OPK_B, EK_A) [cote Bob]");
        }

        byte[] masterSecret = computeMasterSecret(dh1, dh2, dh3, dh4);

        log.info("=== X3DH Bob termine | masterSecret={} ===",
                cryptoLogger.truncateHex(masterSecret));

        return masterSecret;
    }

    // =========================================================================
    // Utilitaires prives
    // =========================================================================

    /**
     * Calcule le master secret X3DH via HKDF.
     *
     * Formule du protocole Signal :
     * SK = HKDF-SHA256(IKM = F || DH1 || DH2 || DH3 [|| DH4], salt=zeros(32), info="SignalX3DH")
     *
     * F = 32 bytes a 0xFF (constante de domaine pour la separation des protocoles)
     */
    private byte[] computeMasterSecret(byte[] dh1, byte[] dh2, byte[] dh3, byte[] dh4) {
        // Construction de l'IKM : F || DH1 || DH2 || DH3 [|| DH4]
        byte[] ikm;
        if (dh4 != null) {
            ikm = cryptoEngine.concat(SIGNAL_DOMAIN_SEPARATOR, dh1, dh2, dh3, dh4);
        } else {
            ikm = cryptoEngine.concat(SIGNAL_DOMAIN_SEPARATOR, dh1, dh2, dh3);
        }

        byte[] salt = new byte[32]; // sel nul de 32 bytes
        byte[] masterSecret = cryptoEngine.hkdf(ikm, salt, CryptoEngine.INFO_X3DH, 32);

        cryptoLogger.logEvent(CryptoEventType.X3DH_MASTER_SECRET, ikm, masterSecret,
                "Master Secret X3DH | avec" + (dh4 != null ? " OPK (4 DH)" : " sans OPK (3 DH)")
                + " | SK=" + cryptoLogger.truncateHex(masterSecret));

        return masterSecret;
    }

    /** Decode une chaine Base64 en tableau de bytes */
    private byte[] decodeBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
