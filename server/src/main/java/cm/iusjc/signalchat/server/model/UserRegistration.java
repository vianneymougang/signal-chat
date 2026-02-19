package cm.iusjc.signalchat.server.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enregistrement d'un utilisateur avec sa session WebSocket.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRegistration {
    private String username;
    private String sessionId;
}
