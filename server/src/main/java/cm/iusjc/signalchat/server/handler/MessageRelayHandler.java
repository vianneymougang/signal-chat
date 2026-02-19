package cm.iusjc.signalchat.server.handler;

import cm.iusjc.signalchat.server.model.EncryptedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handler WebSocket STOMP pour le relai des messages chiffres.
 *
 * PRINCIPE : Le serveur est un relai PUR. Il ne voit que les metadonnees
 * (expediteur, destinataire, taille) — JAMAIS le contenu en clair.
 *
 * Flux :
 * 1. Client A envoie sur /app/send
 * 2. Le handler logue les metadonnees (sans dechiffrer)
 * 3. Redirection vers /topic/messages/{recipient}
 * 4. Si le destinataire est hors-ligne : stockage en file d'attente
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MessageRelayHandler {

    private final SimpMessagingTemplate messagingTemplate;

    /** File d'attente des messages pour les destinataires hors-ligne : username → Queue<EncryptedMessage> */
    private final ConcurrentHashMap<String, Queue<EncryptedMessage>> pendingMessages
            = new ConcurrentHashMap<>();

    /**
     * Recoit un message chiffre sur /app/send et le relaie au destinataire.
     *
     * IMPORTANT : Aucun dechiffrement n'est effectue ici.
     * Le serveur est cryptographiquement aveugle au contenu des messages.
     */
    @MessageMapping("/send")
    public void relayMessage(EncryptedMessage message) {
        int ciphertextSize = message.getCiphertext() != null
                ? message.getCiphertext().length() : 0;

        log.info("RELAI [{}] -> [{}] | ciphertext: {} bytes (Base64) | messageNumber: {}",
                message.getSender(), message.getRecipient(),
                ciphertextSize, message.getMessageNumber());
        log.info("  Le serveur NE PEUT PAS lire ce message — chiffrement E2EE actif");

        // Relai vers le topic du destinataire (livre immediatement si connecte)
        messagingTemplate.convertAndSend(
                "/topic/messages/" + message.getRecipient(),
                message
        );
        log.debug("  Message relaie vers /topic/messages/{}", message.getRecipient());

        // Mise en file d'attente pour livraison differee si le destinataire se connecte plus tard
        pendingMessages
                .computeIfAbsent(message.getRecipient(), k -> new ConcurrentLinkedQueue<>())
                .add(message);
        log.debug("  Message mis en file d'attente pour {} ({} message(s) en attente)",
                message.getRecipient(),
                pendingMessages.get(message.getRecipient()).size());
    }

    /**
     * Livre les messages en attente a un utilisateur qui vient de se connecter.
     * A appeler depuis un listener de connexion WebSocket.
     *
     * @param username nom de l'utilisateur qui vient de se connecter
     */
    public void deliverPendingMessages(String username) {
        Queue<EncryptedMessage> pending = pendingMessages.remove(username);
        if (pending != null && !pending.isEmpty()) {
            log.info("Livraison de {} message(s) en attente pour {}", pending.size(), username);
            for (EncryptedMessage msg : pending) {
                messagingTemplate.convertAndSend("/topic/messages/" + username, msg);
                log.debug("  Message en attente livre : de {} | msgNum={}",
                        msg.getSender(), msg.getMessageNumber());
            }
        }
    }
}
