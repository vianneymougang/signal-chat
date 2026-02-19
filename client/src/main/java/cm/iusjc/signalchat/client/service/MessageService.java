package cm.iusjc.signalchat.client.service;

import cm.iusjc.signalchat.client.crypto.DoubleRatchetService;
import cm.iusjc.signalchat.client.crypto.SessionState;
import cm.iusjc.signalchat.client.crypto.X3DHService;
import cm.iusjc.signalchat.client.model.ChatMessage;
import cm.iusjc.signalchat.client.model.EncryptedMessage;
import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.model.X3DHResult;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chef d'orchestre du flux de messagerie chiffree bout-en-bout.
 *
 * Flux d'envoi (sendMessage) :
 * 1. Session existante ? Non → X3DH (init) → init Double Ratchet
 * 2. ratchetEncrypt(plaintext) → EncryptedMessage
 * 3. Premier message → injecter les headers X3DH dans l'enveloppe
 * 4. Envoyer via ServerConnectionService
 *
 * Flux de reception (onMessageReceived) :
 * 1. Session existante ? Non → X3DH (processHandshake) → init Double Ratchet
 * 2. ratchetDecrypt(message) → plaintext
 * 3. Stocker dans chatMessages (pour l'UI)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final KeyRegistrationService keyRegistrationService;
    private final ServerConnectionService serverConnectionService;
    private final X3DHService x3dhService;
    private final DoubleRatchetService doubleRatchetService;
    private final CryptoLogger cryptoLogger;
    private final RestTemplate restTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${signalchat.username}")
    private String username;

    @Value("${signalchat.server-url}")
    private String serverUrl;

    /** Sessions Double Ratchet actives : destinataire → SessionState */
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /** Historique des messages en clair (jamais envoyé sur le réseau) */
    private final List<ChatMessage> chatMessages = Collections.synchronizedList(new ArrayList<>());

    // =========================================================================
    // Initialisation
    // =========================================================================

    /**
     * Enregistre le callback de reception avant que les cles soient publiees.
     * S'execute apres la creation du bean, avant ApplicationReadyEvent.
     */
    @PostConstruct
    public void init() {
        serverConnectionService.setMessageHandler(this::onMessageReceived);
        log.debug("MessageService : callback de reception enregistre dans ServerConnectionService");
    }

    // =========================================================================
    // Envoi de message
    // =========================================================================

    /**
     * Chiffre et envoie un message en clair a un destinataire.
     *
     * Si aucune session n'existe avec ce destinataire, effectue d'abord
     * un echange X3DH pour etablir le secret partage.
     *
     * @param recipientUsername nom du destinataire (doit etre enregistre sur le serveur)
     * @param plaintext         message en clair a envoyer
     */
    public void sendMessage(String recipientUsername, String plaintext) {
        log.info("=== Envoi de message vers {} ===", recipientUsername);

        // Synchronise par destinataire pour eviter les races sur la creation de session
        synchronized (recipientUsername.intern()) {
            boolean isFirstMessage = !sessions.containsKey(recipientUsername);
            X3DHResult x3dhResult = null;

            // --- Etablissement de session si necessaire ---
            if (isFirstMessage) {
                x3dhResult = initiateX3DHSession(recipientUsername);
                if (x3dhResult == null) return; // echec X3DH — erreur deja loggee
            }

            SessionState session = sessions.get(recipientUsername);

            // --- Chiffrement Double Ratchet ---
            EncryptedMessage encrypted = doubleRatchetService.ratchetEncrypt(
                    session,
                    plaintext.getBytes(StandardCharsets.UTF_8),
                    username,
                    recipientUsername);

            // --- Headers X3DH : uniquement pour le premier message ---
            if (isFirstMessage && x3dhResult != null) {
                injectX3DHHeaders(encrypted, x3dhResult);
            }

            // --- Envoi via WebSocket ---
            serverConnectionService.send(encrypted);
            log.info("Message chiffre envoye | msgNum={} | ciphertext={}",
                    encrypted.getMessageNumber(),
                    cryptoLogger.truncateHex(Base64.getDecoder().decode(encrypted.getCiphertext())));

            // --- Historique local (UI) + broadcast WebSocket ---
            ChatMessage chatMsg = ChatMessage.builder()
                    .sender(username)
                    .recipient(recipientUsername)
                    .content(plaintext)
                    .timestamp(System.currentTimeMillis())
                    .build();
            chatMessages.add(chatMsg);
            messagingTemplate.convertAndSend("/topic/messages", chatMsg);
        }
    }

    /**
     * Effectue le X3DH cote Alice (initiatrice) pour etablir une nouvelle session.
     * Recupere le KeyBundle du destinataire depuis le serveur, effectue le handshake,
     * puis initialise la session Double Ratchet.
     *
     * @param recipientUsername nom du destinataire
     * @return X3DHResult contenant les donnees a injecter dans le premier message,
     *         ou null en cas d'echec
     */
    private X3DHResult initiateX3DHSession(String recipientUsername) {
        log.info("Aucune session avec {} — demarrage X3DH (cote Alice)", recipientUsername);

        // Recuperer le KeyBundle du destinataire
        KeyBundle recipientBundle;
        try {
            recipientBundle = restTemplate.getForObject(
                    serverUrl + "/api/keys/bundle/" + recipientUsername, KeyBundle.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.error("Destinataire {} non trouve sur le serveur", recipientUsername);
            return null;
        } catch (Exception e) {
            log.error("Erreur lors de la recuperation du KeyBundle de {} : {}", recipientUsername, e.getMessage());
            return null;
        }

        if (recipientBundle == null) {
            log.error("KeyBundle de {} est null", recipientUsername);
            return null;
        }

        // X3DH cote Alice : derive le masterSecret et les headers a envoyer
        X3DHResult result = x3dhService.initiateHandshake(
                keyRegistrationService.getIdentityKeyPair(), recipientBundle);
        log.info("X3DH termine | masterSecret={}", cryptoLogger.truncateHex(result.getMasterSecret()));

        // Initialiser la session Double Ratchet cote Alice
        byte[] remoteSPKPub = Base64.getDecoder().decode(recipientBundle.getSignedPreKey());
        SessionState session = doubleRatchetService.initSessionAlice(result.getMasterSecret(), remoteSPKPub);
        sessions.put(recipientUsername, session);
        log.info("Session Double Ratchet initialisee (cote Alice) avec {}", recipientUsername);

        return result;
    }

    /**
     * Injecte les metadonnees X3DH dans le premier message d'une session.
     * Ces champs permettent au destinataire de recalculer le masterSecret.
     */
    private void injectX3DHHeaders(EncryptedMessage msg, X3DHResult x3dhResult) {
        msg.setSenderIdentityKey(Base64.getEncoder().encodeToString(
                keyRegistrationService.getIdentityKeyPair().getX25519PublicKey()));
        msg.setSenderIdentitySigningKey(Base64.getEncoder().encodeToString(
                keyRegistrationService.getIdentityKeyPair().getEd25519PublicKey()));
        msg.setSenderEphemeralKey(Base64.getEncoder().encodeToString(
                x3dhResult.getEphemeralPublicKey()));
        msg.setUsedOneTimePreKeyId(x3dhResult.getUsedOneTimePreKeyId());
        log.debug("Headers X3DH injectes dans le premier message vers {}", msg.getRecipient());
    }

    // =========================================================================
    // Reception de message
    // =========================================================================

    /**
     * Recoit un message chiffre, le dechiffre et le publie dans l'historique.
     * Callback enregistre dans ServerConnectionService.
     *
     * Si c'est le premier message de cet expediteur, effectue d'abord le
     * handshake X3DH cote Bob pour etablir la session.
     *
     * @param message EncryptedMessage recu via WebSocket STOMP
     */
    public void onMessageReceived(EncryptedMessage message) {
        String senderUsername = message.getSender();
        log.info("=== Message chiffre recu de {} | msgNum={} ===",
                senderUsername, message.getMessageNumber());

        synchronized (senderUsername.intern()) {
            // --- Etablissement de session si necessaire (cote Bob) ---
            if (!sessions.containsKey(senderUsername)) {
                boolean sessionCreated = processX3DHHandshake(message);
                if (!sessionCreated) return; // echec X3DH — erreur deja loggee
            }

            SessionState session = sessions.get(senderUsername);

            // --- Dechiffrement Double Ratchet ---
            byte[] plaintext;
            try {
                plaintext = doubleRatchetService.ratchetDecrypt(session, message);
            } catch (SecurityException e) {
                log.error("SECURITE : HMAC invalide pour le message de {} — message rejete : {}",
                        senderUsername, e.getMessage());
                return;
            }

            String plaintextStr = new String(plaintext, StandardCharsets.UTF_8);
            log.info("Message dechiffre de {} : '{}'", senderUsername, plaintextStr);

            // --- Historique local (UI) + broadcast WebSocket ---
            ChatMessage chatMsg = ChatMessage.builder()
                    .sender(senderUsername)
                    .recipient(username)
                    .content(plaintextStr)
                    .timestamp(System.currentTimeMillis())
                    .build();
            chatMessages.add(chatMsg);
            messagingTemplate.convertAndSend("/topic/messages", chatMsg);
        }
    }

    /**
     * Effectue le X3DH cote Bob (recepteur) depuis les headers du premier message.
     * Initialise ensuite la session Double Ratchet cote Bob.
     *
     * @param message premier message de l'expediteur contenant les headers X3DH
     * @return true si la session a ete etablie avec succes, false sinon
     */
    private boolean processX3DHHandshake(EncryptedMessage message) {
        String senderUsername = message.getSender();
        log.info("Premier message de {} — traitement X3DH (cote Bob)", senderUsername);

        if (message.getSenderIdentityKey() == null || message.getSenderEphemeralKey() == null) {
            log.error("Headers X3DH absents dans le message de {} — session impossible", senderUsername);
            return false;
        }

        byte[] senderIdentityPub  = Base64.getDecoder().decode(message.getSenderIdentityKey());
        byte[] senderEphemeralPub = Base64.getDecoder().decode(message.getSenderEphemeralKey());

        // Recuperer la OPK utilisee (si presente)
        byte[] opkSeed = null;
        if (message.getUsedOneTimePreKeyId() != null) {
            opkSeed = keyRegistrationService.getOneTimePreKeySeed(message.getUsedOneTimePreKeyId());
            if (opkSeed == null) {
                log.warn("OPK id={} introuvable — poursuite X3DH sans OPK", message.getUsedOneTimePreKeyId());
            }
        }

        // X3DH cote Bob : recalcule le meme masterSecret qu'Alice
        byte[] masterSecret = x3dhService.processHandshake(
                keyRegistrationService.getIdentityKeyPair(),
                keyRegistrationService.getSignedPreKeySeed(),
                opkSeed,
                senderIdentityPub,
                senderEphemeralPub);

        log.info("X3DH Bob termine | masterSecret={}", cryptoLogger.truncateHex(masterSecret));

        // Initialiser la session Double Ratchet cote Bob
        SessionState session = doubleRatchetService.initSessionBob(
                masterSecret,
                keyRegistrationService.getSignedPreKeySeed(),
                keyRegistrationService.getSignedPreKeyPublic());
        sessions.put(senderUsername, session);
        log.info("Session Double Ratchet initialisee (cote Bob) avec {}", senderUsername);

        return true;
    }

    // =========================================================================
    // Accesseurs pour l'UI
    // =========================================================================

    /**
     * Retourne une copie de l'historique des messages en clair.
     */
    public List<ChatMessage> getChatMessages() {
        return Collections.unmodifiableList(new ArrayList<>(chatMessages));
    }

    /**
     * Retourne les messages échangés avec un correspondant spécifique.
     * Inclut les messages envoyés (sender = username) et reçus (sender = recipient).
     */
    public List<ChatMessage> getMessagesByRecipient(String recipient) {
        return chatMessages.stream()
                .filter(m -> recipient.equals(m.getSender()) || recipient.equals(m.getRecipient()))
                .toList();
    }

    /**
     * Retourne les noms des correspondants avec une session active.
     */
    public java.util.Set<String> getActiveSessions() {
        return sessions.keySet();
    }
}
