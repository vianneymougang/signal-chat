package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.CryptoEventType;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Gestionnaire de cles cryptographiques.
 *
 * Responsabilites :
 * - Generation des paires de cles (identite XEdDSA, pre-signee, one-time)
 * - Echanges Diffie-Hellman X25519
 * - Signatures et verifications XEdDSA (Ed25519)
 *
 * Implementation XEdDSA : le meme seed de 32 bytes est utilise pour
 * construire a la fois la cle X25519 (DH) et la cle Ed25519 (signature),
 * conformement au schema XEdDSA du protocole Signal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyManager {

    private final CryptoLogger cryptoLogger;
    private final SecureRandom secureRandom = new SecureRandom();

    // =========================================================================
    // Generation des paires de cles
    // =========================================================================

    /**
     * Genere une paire de cles d'identite XEdDSA.
     *
     * Un seed aleatoire de 32 bytes est utilise pour construire :
     * - une cle X25519 (pour les echanges DH dans X3DH)
     * - une cle Ed25519 (pour la signature XEdDSA de la Signed Pre-Key)
     *
     * @return IdentityKeyPair contenant seed, x25519Public, ed25519Public
     */
    public IdentityKeyPair generateIdentityKeyPair() {
        byte[] seed = generateRandomBytes(32);

        // Derivation X25519 depuis le seed
        X25519PrivateKeyParameters x25519Priv = new X25519PrivateKeyParameters(seed, 0);
        byte[] x25519Pub = x25519Priv.generatePublicKey().getEncoded();

        // Derivation Ed25519 depuis le meme seed (XEdDSA)
        Ed25519PrivateKeyParameters ed25519Priv = new Ed25519PrivateKeyParameters(seed, 0);
        byte[] ed25519Pub = ed25519Priv.generatePublicKey().getEncoded();

        cryptoLogger.logEvent(CryptoEventType.KEY_GEN, null, x25519Pub,
                "Paire cles identite XEdDSA"
                + " | X25519=" + cryptoLogger.truncateHex(x25519Pub)
                + " | Ed25519=" + cryptoLogger.truncateHex(ed25519Pub));

        return new IdentityKeyPair(seed, x25519Pub, ed25519Pub);
    }

    /**
     * Genere une paire de cles pre-signee (Signed Pre-Key) X25519.
     * La cle privee est le seed brut de 32 bytes.
     *
     * @return tableau [seed/privateKey(32), publicKey(32)]
     */
    public byte[][] generateSignedPreKeyPair() {
        byte[] seed = generateRandomBytes(32);
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(seed, 0);
        byte[] pub = priv.generatePublicKey().getEncoded();

        cryptoLogger.logEvent(CryptoEventType.KEY_GEN, null, pub,
                "Signed Pre-Key X25519 | pub=" + cryptoLogger.truncateHex(pub));

        return new byte[][] { seed, pub };
    }

    /**
     * Genere un lot de One-Time Pre-Keys X25519.
     *
     * @param count nombre de paires a generer
     * @return tableau de [seed(32), publicKey(32)] pour chaque paire
     */
    public byte[][][] generateOneTimePreKeys(int count) {
        byte[][][] keyPairs = new byte[count][2][];
        for (int i = 0; i < count; i++) {
            byte[] seed = generateRandomBytes(32);
            X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(seed, 0);
            byte[] pub = priv.generatePublicKey().getEncoded();
            keyPairs[i][0] = seed;
            keyPairs[i][1] = pub;

            cryptoLogger.logEvent(CryptoEventType.KEY_GEN, null, pub,
                    "One-Time Pre-Key #" + i + " | pub=" + cryptoLogger.truncateHex(pub));
        }
        return keyPairs;
    }

    /**
     * Genere une paire de cles DH pour le ratchet Double Ratchet.
     *
     * @return tableau [seed/privateKey(32), publicKey(32)]
     */
    public byte[][] generateRatchetKeyPair() {
        byte[] seed = generateRandomBytes(32);
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(seed, 0);
        byte[] pub = priv.generatePublicKey().getEncoded();

        cryptoLogger.logEvent(CryptoEventType.KEY_GEN, null, pub,
                "Ratchet DH key pair | pub=" + cryptoLogger.truncateHex(pub));

        return new byte[][] { seed, pub };
    }

    // =========================================================================
    // Signature et verification XEdDSA
    // =========================================================================

    /**
     * Signe des donnees avec la cle Ed25519 derivee du seed d'identite (XEdDSA).
     *
     * @param identitySeed seed de 32 bytes de la cle d'identite
     * @param dataToSign   donnees a signer (typiquement : publicKey d'une SPK)
     * @return signature Ed25519 de 64 bytes
     */
    public byte[] signXEdDSA(byte[] identitySeed, byte[] dataToSign) {
        Ed25519PrivateKeyParameters signingKey = new Ed25519PrivateKeyParameters(identitySeed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, signingKey);
        signer.update(dataToSign, 0, dataToSign.length);
        byte[] signature = signer.generateSignature();

        cryptoLogger.logEvent(CryptoEventType.KEY_GEN, dataToSign, signature,
                "XEdDSA signature Ed25519 | sig=" + cryptoLogger.truncateHex(signature));
        return signature;
    }

    /**
     * Verifie une signature XEdDSA avec la cle publique Ed25519.
     *
     * @param ed25519PublicKey cle publique Ed25519 du signataire (32 bytes)
     * @param data             donnees originales signees
     * @param signature        signature a verifier (64 bytes)
     * @return true si la signature est valide
     */
    public boolean verifyXEdDSA(byte[] ed25519PublicKey, byte[] data, byte[] signature) {
        Ed25519PublicKeyParameters verifyKey = new Ed25519PublicKeyParameters(ed25519PublicKey, 0);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, verifyKey);
        verifier.update(data, 0, data.length);
        boolean valid = verifier.verifySignature(signature);

        cryptoLogger.logEvent(CryptoEventType.SIGNATURE_VERIFY, data, signature,
                "XEdDSA verification Ed25519 : " + (valid ? "✓ VALIDE" : "✗ INVALIDE"));
        return valid;
    }

    // =========================================================================
    // Echange Diffie-Hellman X25519
    // =========================================================================

    /**
     * Effectue un echange Diffie-Hellman X25519 et journalise le resultat.
     *
     * Propriete fondamentale : DH(A_priv, B_pub) == DH(B_priv, A_pub)
     * C'est cette commutativite qui permet a Alice et Bob de calculer
     * le meme secret partage independamment.
     *
     * @param privateKeySeed  cle privee X25519 (seed de 32 bytes)
     * @param publicKeyBytes  cle publique X25519 (32 bytes)
     * @param eventType       type d'evenement pour le log (X3DH_DH1, DH2, etc.)
     * @param description     description de l'etape
     * @return shared secret de 32 bytes
     */
    public byte[] performDH(byte[] privateKeySeed, byte[] publicKeyBytes,
                            CryptoEventType eventType, String description) {
        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(privateKeySeed, 0);
        X25519PublicKeyParameters publicKey = new X25519PublicKeyParameters(publicKeyBytes, 0);

        X25519Agreement agreement = new X25519Agreement();
        agreement.init(privateKey);
        byte[] secret = new byte[agreement.getAgreementSize()]; // 32 bytes
        agreement.calculateAgreement(publicKey, secret, 0);

        cryptoLogger.logEvent(eventType, publicKeyBytes, secret,
                description
                + " | pub=" + cryptoLogger.truncateHex(publicKeyBytes)
                + " → secret=" + cryptoLogger.truncateHex(secret));
        return secret;
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /**
     * Extrait la cle publique X25519 depuis un seed de 32 bytes.
     */
    public byte[] deriveX25519Public(byte[] seed) {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(seed, 0);
        return priv.generatePublicKey().getEncoded();
    }

    /** Genere n bytes aleatoires cryptographiquement surs. */
    private byte[] generateRandomBytes(int n) {
        byte[] bytes = new byte[n];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
