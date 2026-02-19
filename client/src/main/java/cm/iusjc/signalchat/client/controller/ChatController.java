package cm.iusjc.signalchat.client.controller;

import cm.iusjc.signalchat.client.model.ChatMessage;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import cm.iusjc.signalchat.client.service.MessageService;
import cm.iusjc.signalchat.client.service.ServerConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Controller principal du client SignalChat.
 *
 * Responsabilités :
 * - Servir la page Thymeleaf chat.html
 * - Exposer les endpoints REST pour le frontend (messages, users, status, crypto-events)
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final MessageService messageService;
    private final ServerConnectionService serverConnectionService;
    private final CryptoLogger cryptoLogger;
    private final RestTemplate restTemplate;

    @Value("${signalchat.username}")
    private String username;

    @Value("${signalchat.server-url}")
    private String serverUrl;

    // =========================================================================
    // Page Thymeleaf
    // =========================================================================

    /**
     * Sert la page de chat principale.
     */
    @GetMapping("/")
    public String chat(Model model) {
        model.addAttribute("username", username);
        return "chat";
    }

    // =========================================================================
    // REST — Utilisateurs
    // =========================================================================

    /**
     * Proxy vers GET {serverUrl}/api/keys/users.
     * Filtre l'utilisateur local pour ne pas s'afficher soi-même.
     */
    @GetMapping("/api/chat/users")
    @ResponseBody
    public ResponseEntity<List<String>> getUsers() {
        try {
            @SuppressWarnings("unchecked")
            List<String> allUsers = restTemplate.getForObject(
                    serverUrl + "/api/keys/users", List.class);
            if (allUsers == null) {
                return ResponseEntity.ok(List.of());
            }
            List<String> filtered = allUsers.stream()
                    .filter(u -> !u.equals(username))
                    .toList();
            return ResponseEntity.ok(filtered);
        } catch (Exception e) {
            log.warn("Impossible de récupérer la liste des utilisateurs : {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    // =========================================================================
    // REST — Messages
    // =========================================================================

    /**
     * Retourne l'historique des messages avec un destinataire spécifique.
     */
    @GetMapping("/api/chat/messages/{recipient}")
    @ResponseBody
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable String recipient) {
        List<ChatMessage> history = messageService.getMessagesByRecipient(recipient);
        return ResponseEntity.ok(history);
    }

    /**
     * Envoie un message chiffré à un destinataire.
     * Corps attendu : { "recipient": "Bob", "content": "Bonjour" }
     */
    @PostMapping("/api/chat/send")
    @ResponseBody
    public ResponseEntity<Map<String, String>> sendMessage(
            @RequestBody Map<String, String> body) {
        String recipient = body.get("recipient");
        String content   = body.get("content");

        if (recipient == null || recipient.isBlank() || content == null || content.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "recipient et content sont requis"));
        }

        try {
            messageService.sendMessage(recipient, content);
            return ResponseEntity.ok(Map.of("status", "sent"));
        } catch (IllegalStateException e) {
            log.error("Envoi impossible : {}", e.getMessage());
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Non connecté au serveur : " + e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de l'envoi vers {} : {}", recipient, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // REST — Statut de connexion
    // =========================================================================

    /**
     * Retourne le statut de connexion au serveur central.
     */
    @GetMapping("/api/chat/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "connected", serverConnectionService.isConnected(),
                "username", username
        ));
    }

    // =========================================================================
    // REST — Événements crypto
    // =========================================================================

    /**
     * Retourne tous les événements crypto enregistrés (chargement initial du panneau).
     */
    @GetMapping("/api/chat/crypto-events")
    @ResponseBody
    public ResponseEntity<?> getCryptoEvents() {
        return ResponseEntity.ok(cryptoLogger.getEvents());
    }

    /**
     * Efface les événements crypto.
     */
    @DeleteMapping("/api/chat/crypto-events")
    @ResponseBody
    public ResponseEntity<Void> clearCryptoEvents() {
        cryptoLogger.clearEvents();
        return ResponseEntity.noContent().build();
    }
}
