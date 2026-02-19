package cm.iusjc.signalchat.client.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuration du broker WebSocket STOMP local (client backend → navigateur).
 *
 * Distinct de la connexion vers le serveur central (ServerConnectionService).
 * Expose trois topics vers le frontend :
 *   /topic/messages         — nouveaux messages chat (envoyés ou reçus)
 *   /topic/crypto-events    — événements cryptographiques en temps réel
 *   /topic/connection-status — statut de la connexion vers le serveur
 */
@Configuration
@EnableWebSocketMessageBroker
public class LocalWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker in-memory pour les topics locaux
        registry.enableSimpleBroker("/topic");
        // Préfixe pour les messages frontend → backend (non utilisé actuellement)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint WebSocket local avec SockJS pour compatibilité multi-navigateur
        registry.addEndpoint("/local-ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
