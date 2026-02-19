package cm.iusjc.signalchat.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuration du broker WebSocket STOMP.
 * - Endpoint de connexion : /ws (avec fallback SockJS)
 * - Topics de reception : /topic/...
 * - Prefix applicatif : /app (pour les MessageMapping)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Active le broker simple pour les topics de diffusion
        registry.enableSimpleBroker("/topic");
        // Prefixe pour les methodes @MessageMapping du serveur
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint principal avec fallback SockJS pour la compatibilite navigateur
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
