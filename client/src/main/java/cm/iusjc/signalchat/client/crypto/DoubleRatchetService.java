package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.model.CryptoEventType;
import cm.iusjc.signalchat.client.model.EncryptedMessage;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Base64;

/**
 * Implementation du Double Ratchet Algorithm.
 *
 * Le Double Ratchet combine deux mecanismes de ratchet :
 * 1. Ratchet DH (Diffie-Hellman) : rotation des cles de chaine a chaque
 *    changement de direction de la conversation. Fournit la Forward Secrecy.
 * 2. Ratchet symetrique : derivation d'une nouvelle cle de message a partir
 *    de la cle de chaine courante. Chaque message utilise une cle unique.
 *
 * Initialisation (apres X3DH) :
 * - Alice : initSessionAlice(masterSecret, SPK_B_pub)
 * - Bob   : initSessionBob(masterSecret, SPK_B_seed, SPK_B_pub)
 *
 * References :
 * - https://signal.org/docs/specifications/doubleratchet/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoubleRatchetService {

    private final KeyManager keyManager;
    private final CryptoEngine cryptoEngine;
    private final CryptoLogger cryptoLogger;

    // =========================================================================
    // Initialisation des sessions
    // =========================================================================

    /**
     * Initialise la session Double Ratchet cote Alice (initiatrice).
     *
     * Conformement a la spec Signal (RatchetInitAlice) :
     * 1. Genere une nouvelle paire DH de ratchet (distincte de EK_A)
     * 2. Effectue un premier ratchet DH avec SPK_B
     * 3. Derive (rootKey, sendingChainKey) depuis masterSecret
     *
     * @param masterSecret       secret partage X3DH (32 bytes)
     * @param remoteSPKPublicKey cle publique SPK_B de Bob (32 bytes)
     * @return SessionState initialise pour Alice
     */
    public SessionState initSessionAlice(byte[] masterSecret, byte[] remoteSPKPublicKey) {
        log.info("=== Initialisation Double Ratchet (Alice) ===");

        // Genere une nouvelle paire DH de ratchet pour Alice
        byte[][] ratchetKeyPair = keyManager.generateRatchetKeyPair();
        byte[] ratchetSeed = ratchetKeyPair[0];
        byte[] ratchetPub  = ratchetKeyPair[1];

        // Premier ratchet DH : DH(DHs_A, SPK_B) → derive (rootKey, sendingChainKey)
        byte[] dhOutput = keyManager.performDH(
                ratchetSeed, remoteSPKPublicKey,
                CryptoEventType.RATCHET_STEP,
                "Init DH Ratchet Alice : DH(DHs_A, SPK_B)");

        byte[][] derived = cryptoEngine.kdfRootKey(masterSecret, dhOutput);
        byte[] rootKey         = derived[0];
        byte[] sendingChainKey = derived[1];

        cryptoLogger.logEvent(CryptoEventType.X3DH_SESSION_INIT, masterSecret, rootKey,
                "Session Alice initialisee"
                + " | rootKey=" + cryptoLogger.truncateHex(rootKey)
                + " | sendingChainKey=" + cryptoLogger.truncateHex(sendingChainKey));

        return SessionState.builder()
                .rootKey(rootKey)
                .sendingChainKey(sendingChainKey)
                .receivingChainKey(null)          // pas encore de reception
                .localRatchetPrivateKey(ratchetSeed)
                .localRatchetPublicKey(ratchetPub)
                .remoteRatchetPublicKey(remoteSPKPublicKey)
                .sendingMessageNumber(0)
                .receivingMessageNumber(0)
                .previousSendingChainLength(0)
                .build();
    }

    /**
     * Initialise la session Double Ratchet cote Bob (recepteur).
     *
     * Conformement a la spec Signal (RatchetInitBob) :
     * Bob n'effectue pas de ratchet DH initial. Il attend le premier
     * message d'Alice qui contiendra sa cle de ratchet initiale.
     *
     * @param masterSecret     secret partage X3DH (32 bytes)
     * @param spkSeed          cle privee SPK_B de Bob (seed 32 bytes)
     * @param spkPublicKey     cle publique SPK_B de Bob (32 bytes)
     * @return SessionState initialise pour Bob
     */
    public SessionState initSessionBob(byte[] masterSecret, byte[] spkSeed, byte[] spkPublicKey) {
        log.info("=== Initialisation Double Ratchet (Bob) ===");

        cryptoLogger.logEvent(CryptoEventType.X3DH_SESSION_INIT, masterSecret, masterSecret,
                "Session Bob initialisee | rootKey=masterSecret | en attente du premier message");

        return SessionState.builder()
                .rootKey(masterSecret)            // SK devient le rootKey initial
                .sendingChainKey(null)            // pas encore de chaine d'envoi
                .receivingChainKey(null)          // pas encore de chaine de reception
                .localRatchetPrivateKey(spkSeed)  // SPK_B est le premier ratchet key de Bob
                .localRatchetPublicKey(spkPublicKey)
                .remoteRatchetPublicKey(null)     // inconnu jusqu'au premier message d'Alice
                .sendingMessageNumber(0)
                .receivingMessageNumber(0)
                .previousSendingChainLength(0)
                .build();
    }

    // =========================================================================
    // Chiffrement
    // =========================================================================

    /**
     * Chiffre un message avec le Double Ratchet (ratchet symetrique).
     *
     * Etapes du ratchet symetrique (cote envoi) :
     * 1. messageKey = HMAC-SHA256(sendingChainKey, 0x01)
     * 2. sendingChainKey = HMAC-SHA256(sendingChainKey, 0x02)   [avancement de la chaine]
     * 3. (encryptionKey, hmacKey) = HKDF(messageKey, zeros, "SignalMsgKeys", 64)
     * 4. iv = random 16 bytes
     * 5. ciphertext = AES-256-CBC(plaintext, encryptionKey, iv)
     * 6. mac = HMAC-SHA256(hmacKey, ciphertext)
     *
     * @param state     etat de session courant (modifie en place)
     * @param plaintext message en clair a chiffrer
     * @param sender    nom de l'expediteur (pour remplir le champ sender)
     * @param recipient nom du destinataire
     * @return EncryptedMessage pret a etre envoye
     */
    public EncryptedMessage ratchetEncrypt(SessionState state, byte[] plaintext,
                                           String sender, String recipient) {
        byte[] chainKeyBefore = state.getSendingChainKey().clone();

        // --- Ratchet symetrique : derive la cle de message et avance la chaine ---
        byte[] messageKey        = advanceSymmetricRatchet(state);

        // --- Derive la cle de chiffrement AES et la cle HMAC depuis messageKey ---
        byte[] derived           = cryptoEngine.hkdf(messageKey, new byte[32],
                                                     CryptoEngine.INFO_MESSAGE_KEYS, 64);
        byte[] encryptionKey     = Arrays.copyOfRange(derived, 0, 32);  // AES-256
        byte[] hmacKey           = Arrays.copyOfRange(derived, 32, 64); // HMAC

        // --- Chiffrement AES-256-CBC ---
        byte[] iv                = cryptoEngine.generateIV();
        byte[] ciphertext        = cryptoEngine.encryptAES(plaintext, encryptionKey, iv);

        // --- Calcul du HMAC sur le ciphertext ---
        byte[] mac               = cryptoEngine.hmacSHA256(hmacKey, ciphertext);

        // --- Construction du message chiffre ---
        int msgNumber            = state.getSendingMessageNumber();
        state.setSendingMessageNumber(msgNumber + 1);

        cryptoLogger.logEvent(CryptoEventType.ENCRYPT, plaintext, ciphertext,
                "Double Ratchet ENCRYPT"
                + " | msgNum=" + msgNumber
                + " | chainKey[avant]=" + cryptoLogger.truncateHex(chainKeyBefore)
                + " | messageKey=" + cryptoLogger.truncateHex(messageKey));

        return EncryptedMessage.builder()
                .sender(sender)
                .recipient(recipient)
                .ciphertext(encodeBase64(ciphertext))
                .iv(encodeBase64(iv))
                .hmac(encodeBase64(mac))
                .senderRatchetKey(encodeBase64(state.getLocalRatchetPublicKey()))
                .messageNumber(msgNumber)
                .previousChainLength(state.getPreviousSendingChainLength())
                .build();
    }

    // =========================================================================
    // Dechiffrement
    // =========================================================================

    /**
     * Dechiffre un message recu avec le Double Ratchet.
     *
     * Etapes :
     * 1. Si la cle de ratchet DH de l'expediteur a change → dhRatchetStep()
     * 2. messageKey = HMAC-SHA256(receivingChainKey, 0x01)
     * 3. receivingChainKey = HMAC-SHA256(receivingChainKey, 0x02)
     * 4. (encryptionKey, hmacKey) = HKDF(messageKey, ...)
     * 5. Verification HMAC
     * 6. Dechiffrement AES-256-CBC
     *
     * @param state   etat de session courant (modifie en place)
     * @param message message chiffre recu
     * @return plaintext en bytes
     * @throws SecurityException si le HMAC est invalide
     */
    public byte[] ratchetDecrypt(SessionState state, EncryptedMessage message) {
        byte[] senderRatchetKey = decodeBase64(message.getSenderRatchetKey());

        // --- Verification si un nouveau ratchet DH est necessaire ---
        if (state.getRemoteRatchetPublicKey() == null
                || !Arrays.equals(senderRatchetKey, state.getRemoteRatchetPublicKey())) {
            log.debug("Nouveau ratchet DH detecte | ancienne_cle={} | nouvelle_cle={}",
                    state.getRemoteRatchetPublicKey() != null
                            ? cryptoLogger.truncateHex(state.getRemoteRatchetPublicKey())
                            : "null",
                    cryptoLogger.truncateHex(senderRatchetKey));
            dhRatchetStep(state, senderRatchetKey);
        }

        // --- Ratchet symetrique : derive la cle de message et avance la chaine ---
        byte[] messageKey    = advanceReceivingRatchet(state);

        // --- Derive les cles de chiffrement et HMAC ---
        byte[] derived       = cryptoEngine.hkdf(messageKey, new byte[32],
                                                 CryptoEngine.INFO_MESSAGE_KEYS, 64);
        byte[] encryptionKey = Arrays.copyOfRange(derived, 0, 32);
        byte[] hmacKey       = Arrays.copyOfRange(derived, 32, 64);

        // --- Decodage des champs du message ---
        byte[] ciphertext    = decodeBase64(message.getCiphertext());
        byte[] iv            = decodeBase64(message.getIv());
        byte[] receivedMac   = decodeBase64(message.getHmac());

        // --- Verification du HMAC (authentification avant dechiffrement) ---
        boolean macValid     = cryptoEngine.verifyHMAC(hmacKey, ciphertext, receivedMac);
        if (!macValid) {
            throw new SecurityException("HMAC invalide — message rejete (possible falsification)");
        }

        // --- Dechiffrement AES-256-CBC ---
        byte[] plaintext     = cryptoEngine.decryptAES(ciphertext, encryptionKey, iv);
        state.setReceivingMessageNumber(state.getReceivingMessageNumber() + 1);

        cryptoLogger.logEvent(CryptoEventType.DECRYPT, ciphertext, plaintext,
                "Double Ratchet DECRYPT"
                + " | msgNum=" + message.getMessageNumber()
                + " | messageKey=" + cryptoLogger.truncateHex(messageKey));

        return plaintext;
    }

    // =========================================================================
    // Ratchet DH (rotation des cles de chaine)
    // =========================================================================

    /**
     * Effectue une etape de ratchet DH (Diffie-Hellman Ratchet Step).
     *
     * Declenche quand une nouvelle cle de ratchet distante est detectee.
     * Deux demi-etapes :
     *
     * Demi-etape RECEPTION :
     *   dhOutput = DH(localRatchet_priv, newRemoteRatchet_pub)
     *   (rootKey, receivingChainKey) = KDF_RK(rootKey, dhOutput)
     *
     * Demi-etape ENVOI :
     *   [genere nouvelle paire DH locale]
     *   dhOutput2 = DH(newLocal_priv, newRemoteRatchet_pub)
     *   (rootKey, sendingChainKey) = KDF_RK(rootKey, dhOutput2)
     *
     * @param state          etat de session courant (modifie en place)
     * @param remoteRatchetPub nouvelle cle de ratchet publique distante (32 bytes)
     */
    public void dhRatchetStep(SessionState state, byte[] remoteRatchetPub) {
        log.info("=== DH Ratchet Step ===");
        byte[] oldRootKey = state.getRootKey().clone();

        // Sauvegarde du compteur d'envoi pour les messages hors-ordre
        state.setPreviousSendingChainLength(state.getSendingMessageNumber());
        state.setSendingMessageNumber(0);
        state.setReceivingMessageNumber(0);
        state.setRemoteRatchetPublicKey(remoteRatchetPub);

        // --- Demi-etape 1 : calcul de la receivingChainKey ---
        byte[] dhOutput1 = keyManager.performDH(
                state.getLocalRatchetPrivateKey(), remoteRatchetPub,
                CryptoEventType.RATCHET_STEP,
                "DH Ratchet Step — reception : DH(localRatchet, newRemote)");

        byte[][] derived1 = cryptoEngine.kdfRootKey(state.getRootKey(), dhOutput1);
        state.setRootKey(derived1[0]);
        state.setReceivingChainKey(derived1[1]);

        cryptoLogger.logEvent(CryptoEventType.RATCHET_STEP, oldRootKey, state.getRootKey(),
                "Ratchet DH demi-etape reception"
                + " | rootKey[avant]=" + cryptoLogger.truncateHex(oldRootKey)
                + " | rootKey[apres]=" + cryptoLogger.truncateHex(state.getRootKey())
                + " | receivingChainKey=" + cryptoLogger.truncateHex(state.getReceivingChainKey()));

        // --- Demi-etape 2 : genere une nouvelle paire DH locale et calcule sendingChainKey ---
        byte[][] newRatchetPair = keyManager.generateRatchetKeyPair();
        byte[] newRatchetSeed   = newRatchetPair[0];
        byte[] newRatchetPub    = newRatchetPair[1];

        byte[] dhOutput2 = keyManager.performDH(
                newRatchetSeed, remoteRatchetPub,
                CryptoEventType.RATCHET_STEP,
                "DH Ratchet Step — envoi : DH(newLocalRatchet, newRemote)");

        byte[] rootKeyBefore2 = state.getRootKey().clone();
        byte[][] derived2     = cryptoEngine.kdfRootKey(state.getRootKey(), dhOutput2);
        state.setRootKey(derived2[0]);
        state.setSendingChainKey(derived2[1]);
        state.setLocalRatchetPrivateKey(newRatchetSeed);
        state.setLocalRatchetPublicKey(newRatchetPub);

        cryptoLogger.logEvent(CryptoEventType.RATCHET_STEP, rootKeyBefore2, state.getRootKey(),
                "Ratchet DH demi-etape envoi"
                + " | rootKey[avant]=" + cryptoLogger.truncateHex(rootKeyBefore2)
                + " | rootKey[apres]=" + cryptoLogger.truncateHex(state.getRootKey())
                + " | sendingChainKey=" + cryptoLogger.truncateHex(state.getSendingChainKey())
                + " | newLocalPub=" + cryptoLogger.truncateHex(newRatchetPub));

        log.info("=== DH Ratchet Step termine ===");
    }

    // =========================================================================
    // Ratchets symetriques (prives)
    // =========================================================================

    /**
     * Avance le ratchet symetrique d'envoi.
     * Derive une messageKey depuis sendingChainKey et avance la chaine.
     *
     * @param state etat de session (modifie : sendingChainKey avance)
     * @return messageKey unique pour chiffrer ce message
     */
    private byte[] advanceSymmetricRatchet(SessionState state) {
        byte[] chainKey  = state.getSendingChainKey();

        // messageKey = HMAC-SHA256(chainKey, 0x01)
        byte[] messageKey = cryptoEngine.hmacSHA256(chainKey, new byte[]{0x01});

        // sendingChainKey = HMAC-SHA256(chainKey, 0x02)
        byte[] newChainKey = cryptoEngine.hmacSHA256(chainKey, new byte[]{0x02});
        state.setSendingChainKey(newChainKey);

        cryptoLogger.logEvent(CryptoEventType.SYMMETRIC_RATCHET, chainKey, newChainKey,
                "Ratchet symetrique ENVOI"
                + " | chainKey[avant]=" + cryptoLogger.truncateHex(chainKey)
                + " | chainKey[apres]=" + cryptoLogger.truncateHex(newChainKey)
                + " | messageKey=" + cryptoLogger.truncateHex(messageKey));
        return messageKey;
    }

    /**
     * Avance le ratchet symetrique de reception.
     * Derive une messageKey depuis receivingChainKey et avance la chaine.
     *
     * @param state etat de session (modifie : receivingChainKey avance)
     * @return messageKey unique pour dechiffrer ce message
     */
    private byte[] advanceReceivingRatchet(SessionState state) {
        byte[] chainKey   = state.getReceivingChainKey();

        byte[] messageKey  = cryptoEngine.hmacSHA256(chainKey, new byte[]{0x01});
        byte[] newChainKey = cryptoEngine.hmacSHA256(chainKey, new byte[]{0x02});
        state.setReceivingChainKey(newChainKey);

        cryptoLogger.logEvent(CryptoEventType.SYMMETRIC_RATCHET, chainKey, newChainKey,
                "Ratchet symetrique RECEPTION"
                + " | chainKey[avant]=" + cryptoLogger.truncateHex(chainKey)
                + " | chainKey[apres]=" + cryptoLogger.truncateHex(newChainKey)
                + " | messageKey=" + cryptoLogger.truncateHex(messageKey));
        return messageKey;
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    private byte[] decodeBase64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
