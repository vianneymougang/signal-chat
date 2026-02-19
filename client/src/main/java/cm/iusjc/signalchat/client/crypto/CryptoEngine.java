package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.CryptoEventType;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Primitives cryptographiques bas niveau.
 * Toutes les operations utilisent EXCLUSIVEMENT Bouncy Castle.
 *
 * Operations disponibles :
 * - HKDF-SHA256 (derivation de cles)
 * - AES-256-CBC (chiffrement/dechiffrement)
 * - HMAC-SHA256 (authentification)
 *
 * Chaque operation est journalisee via CryptoLogger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoEngine {

    private final CryptoLogger cryptoLogger;

    /** Info HKDF pour la derivation du ratchet DH */
    public static final byte[] INFO_RATCHET = "SignalRatchet".getBytes();

    /** Info HKDF pour la derivation des cles de message */
    public static final byte[] INFO_MESSAGE_KEYS = "SignalMsgKeys".getBytes();

    /** Info HKDF pour la derivation des cles de session X3DH */
    public static final byte[] INFO_X3DH = "SignalX3DH".getBytes();

    static {
        // Enregistrement du provider Bouncy Castle
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Derivation de cle via HKDF-SHA256.
     *
     * @param inputKeyMaterial materiau d'entree (IKM)
     * @param salt             sel (utiliser new byte[32] pour un sel nul)
     * @param info             contexte de derivation (label)
     * @param outputLength     longueur de la sortie en bytes
     * @return cle derivee de outputLength bytes
     */
    public byte[] hkdf(byte[] inputKeyMaterial, byte[] salt, byte[] info, int outputLength) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(inputKeyMaterial, salt, info));
        byte[] output = new byte[outputLength];
        hkdf.generateBytes(output, 0, outputLength);

        cryptoLogger.logEvent(CryptoEventType.HKDF_DERIVE, inputKeyMaterial, output,
                "HKDF-SHA256 → " + outputLength + " bytes | info=\"" + new String(info) + "\"");
        return output;
    }

    /**
     * Derivation KDF_RK telle que specifiee par le Double Ratchet.
     * Utilise le rootKey courant comme SEL et le secret DH comme IKM.
     *
     * @param currentRootKey cle racine courante (32 bytes) — utilisee comme sel
     * @param dhOutput       sortie du DH (32 bytes) — utilisee comme IKM
     * @return tableau [nouveauRootKey(32), nouvelleChainKey(32)]
     */
    public byte[][] kdfRootKey(byte[] currentRootKey, byte[] dhOutput) {
        byte[] derived = hkdf(dhOutput, currentRootKey, INFO_RATCHET, 64);
        byte[] newRootKey   = new byte[32];
        byte[] newChainKey  = new byte[32];
        System.arraycopy(derived, 0, newRootKey, 0, 32);
        System.arraycopy(derived, 32, newChainKey, 0, 32);
        return new byte[][] { newRootKey, newChainKey };
    }

    /**
     * Chiffrement AES-256-CBC avec PKCS5Padding.
     * La cle doit faire exactement 32 bytes.
     *
     * @param plaintext  texte en clair
     * @param key        cle AES de 32 bytes
     * @param iv         vecteur d'initialisation de 16 bytes
     * @return ciphertext
     */
    public byte[] encryptAES(byte[] plaintext, byte[] key, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "BC");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] ciphertext = cipher.doFinal(plaintext);

            cryptoLogger.logEvent(CryptoEventType.ENCRYPT, plaintext, ciphertext,
                    "AES-256-CBC chiffrement | IV=" + cryptoLogger.truncateHex(iv)
                    + " | key=" + cryptoLogger.truncateHex(key));
            return ciphertext;
        } catch (Exception e) {
            throw new CryptoException("Echec du chiffrement AES-256-CBC", e);
        }
    }

    /**
     * Dechiffrement AES-256-CBC avec PKCS5Padding.
     *
     * @param ciphertext ciphertext a dechiffrer
     * @param key        cle AES de 32 bytes
     * @param iv         vecteur d'initialisation de 16 bytes
     * @return plaintext
     */
    public byte[] decryptAES(byte[] ciphertext, byte[] key, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "BC");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plaintext = cipher.doFinal(ciphertext);

            cryptoLogger.logEvent(CryptoEventType.DECRYPT, ciphertext, plaintext,
                    "AES-256-CBC dechiffrement | IV=" + cryptoLogger.truncateHex(iv));
            return plaintext;
        } catch (Exception e) {
            throw new CryptoException("Echec du dechiffrement AES-256-CBC", e);
        }
    }

    /**
     * Calcul HMAC-SHA256 via Bouncy Castle.
     *
     * @param key  cle HMAC
     * @param data donnees a authentifier
     * @return MAC de 32 bytes
     */
    public byte[] hmacSHA256(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] result = new byte[hmac.getMacSize()]; // 32 bytes
        hmac.doFinal(result, 0);

        cryptoLogger.logEvent(CryptoEventType.HMAC_COMPUTE, data, result,
                "HMAC-SHA256 | key=" + cryptoLogger.truncateHex(key));
        return result;
    }

    /**
     * Verification HMAC-SHA256 en comparaison a temps constant.
     * Protege contre les attaques de timing.
     *
     * @param key         cle HMAC
     * @param data        donnees authentifiees
     * @param expectedMac MAC attendu (32 bytes)
     * @return true si le MAC est valide
     */
    public boolean verifyHMAC(byte[] key, byte[] data, byte[] expectedMac) {
        byte[] computed = hmacSHA256(key, data);
        // Comparaison a temps constant (protection contre les attaques timing)
        boolean valid = MessageDigest.isEqual(computed, expectedMac);

        cryptoLogger.logEvent(CryptoEventType.HMAC_VERIFY, data, computed,
                "HMAC-SHA256 verification : " + (valid ? "✓ VALIDE" : "✗ INVALIDE"));
        return valid;
    }

    /**
     * Generation d'un vecteur d'initialisation AES aleatoire (16 bytes).
     */
    public byte[] generateIV() {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    /**
     * Concatene plusieurs tableaux de bytes.
     * Utilise pour construire l'IKM de l'HKDF X3DH : F || DH1 || DH2 || DH3 [|| DH4]
     */
    public byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] arr : arrays) {
            totalLength += arr.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    /** Exception non-checked pour les erreurs cryptographiques */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
