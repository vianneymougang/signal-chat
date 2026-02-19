package cm.iusjc.signalchat.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de registre des utilisateurs actifs.
 * Maintient un mapping username -> sessionId WebSocket.
 */
@Slf4j
@Service
public class UserRegistryService {

    /** Mapping username -> identifiant de session WebSocket */
    private final ConcurrentHashMap<String, String> activeUsers = new ConcurrentHashMap<>();

    public void registerUser(String username, String sessionId) {
        activeUsers.put(username, sessionId);
        log.info("Utilisateur connecte : {} (session: {})", username, sessionId);
    }

    public void unregisterUser(String sessionId) {
        activeUsers.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(sessionId)) {
                log.info("Utilisateur deconnecte : {} (session: {})", entry.getKey(), sessionId);
                return true;
            }
            return false;
        });
    }

    public boolean isUserOnline(String username) {
        return activeUsers.containsKey(username);
    }

    public Set<String> getOnlineUsers() {
        return activeUsers.keySet();
    }

    public String getSessionId(String username) {
        return activeUsers.get(username);
    }
}
