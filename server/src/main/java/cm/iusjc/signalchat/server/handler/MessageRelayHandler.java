package cm.iusjc.signalchat.server.handler;

import cm.iusjc.signalchat.server.model.EncryptedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Handler WebSocket STOMP pour le relai des messages chiffres.
 * Ecoute sur /app/send et redirige vers /topic/messages/{recipient}.
 *
 * IMPORTANT : Le serveur ne peut PAS lire le contenu des messages.
 * Il voit uniquement : expediteur, destinataire, taille du ciphertext.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MessageRelayHandler {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Recoit un message chiffre et le relaie au destinataire.
     * Aucun dechiffrement n'est effectue ici.
     */
    @MessageMapping("/send")
    public void relayMessage(EncryptedMessage message) {
        int ciphertextSize = message.getCiphertext() != null ? message.getCiphertext().length() : 0;
        log.info("RELAI [{}] -> [{}] | ciphertext: {} bytes (Base64) | messageNumber: {}",
                message.getSender(), message.getRecipient(),
                ciphertextSize, message.getMessageNumber());
        log.info("  Le serveur NE PEUT PAS lire ce message — chiffrement E2EE actif");
        // Relai du message chiffre vers le topic du destinataire
        messagingTemplate.convertAndSend(
                "/topic/messages/" + message.getRecipient(),
                message
        );
        log.debug("  Message relaie vers /topic/messages/{}", message.getRecipient());
    }
}
