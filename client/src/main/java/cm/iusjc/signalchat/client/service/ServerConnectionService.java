package cm.iusjc.signalchat.client.service;

import cm.iusjc.signalchat.client.model.EncryptedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service de connexion WebSocket STOMP vers le serveur central.
 *
 * Responsabilites :
 * - Etablir et maintenir la connexion WebSocket STOMP
 * - S'abonner au topic personnel /topic/messages/{username}
 * - Transmettre les messages recus au MessageService via callback
 * - Gerer la reconnexion automatique en cas de perte de connexion
 */
@Slf4j
@Service
public class ServerConnectionService {

    @Value("${signalchat.server-url}")
    private String serverUrl;

    @Value("${signalchat.username}")
    private String username;

    /** Session STOMP active */
    private StompSession stompSession;

    /** Callback vers MessageService pour les messages recus */
    private volatile Consumer<EncryptedMessage> messageHandler;

    /** Push des changements de statut vers le frontend (optionnel au démarrage) */
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /** Planificateur pour la reconnexion automatique */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    /**
     * Enregistre le handler de messages entrants (appele par MessageService).
     */
    public void setMessageHandler(Consumer<EncryptedMessage> handler) {
        this.messageHandler = handler;
    }

    /**
     * Etablit la connexion WebSocket STOMP vers le serveur.
     * Utilise SockJS avec WebSocket natif + XHR comme transport de repli.
     * Appele par KeyRegistrationService apres enregistrement des cles.
     */
    public void connect() {
        // RestTemplate avec header ngrok pour le transport XHR (bypass interstitiel ngrok)
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("ngrok-skip-browser-warning", "true");
            return execution.execute(request, body);
        });

        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient()),
                new RestTemplateXhrTransport(restTemplate)
        );
        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        String wsUrl = serverUrl + "/ws";
        log.info("Connexion WebSocket STOMP vers {} ...", wsUrl);

        // Header pour le handshake WebSocket natif (bypass interstitiel ngrok free tier)
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.set("ngrok-skip-browser-warning", "true");

        try {
            stompSession = stompClient.connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {

                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    reconnectAttempts = 0;
                    log.info("Connecte au serveur WebSocket : {}", wsUrl);
                    subscribeToMessages(session);
                    broadcastStatus(true);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    log.error("Erreur transport WebSocket : {}", exception.getMessage());
                    broadcastStatus(false);
                    scheduleReconnect();
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                        StompHeaders headers, byte[] payload, Throwable exception) {
                    log.error("STOMP EXCEPTION | command={} | destination={} | erreur={}",
                            command, headers.getDestination(), exception.getMessage(), exception);
                }

            }).get(10, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Echec de connexion WebSocket : {}", e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * S'abonne au topic personnel du client pour recevoir les messages chiffres.
     */
    private void subscribeToMessages(StompSession session) {
        String destination = "/topic/messages/" + username;
        log.info("Abonnement STOMP en cours : destination={} | sessionId={}", destination, session.getSessionId());

        session.subscribe(destination, new StompFrameHandler() {

            @Override
            public Type getPayloadType(StompHeaders headers) {
                log.debug("Frame entrante sur {} | content-type={}", destination, headers.getContentType());
                return EncryptedMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                log.info(">>> FRAME REÇUE sur {} | payload null ? {} | payload class : {}",
                        destination, payload == null, payload != null ? payload.getClass().getName() : "N/A");
                try {
                    EncryptedMessage msg = (EncryptedMessage) payload;
                    int ciphertextSize = msg.getCiphertext() != null ? msg.getCiphertext().length() : 0;
                    log.info("Message chiffre recu de {} | ciphertext: {} bytes (Base64) | msgNum: {}",
                            msg.getSender(), ciphertextSize, msg.getMessageNumber());
                    if (messageHandler != null) {
                        messageHandler.accept(msg);
                    } else {
                        log.error("messageHandler est NULL — le message de {} est perdu !", msg.getSender());
                    }
                } catch (Exception e) {
                    log.error("ERREUR dans handleFrame : {}", e.getMessage(), e);
                }
            }
        });
        log.info("Abonne a {}", destination);
    }

    /**
     * Envoie un message chiffre vers le serveur via STOMP.
     *
     * @param message EncryptedMessage pret a etre envoye
     * @throws IllegalStateException si la connexion STOMP nest pas etablie
     */
    public void send(EncryptedMessage message) {
        if (stompSession == null || !stompSession.isConnected()) {
            throw new IllegalStateException("Non connecte au serveur WebSocket");
        }
        stompSession.send("/app/send", message);
        log.debug("Message chiffre envoye vers {} | msgNum={}",message.getRecipient(), message.getMessageNumber());
    }

    /**
     * Indique si la connexion STOMP est active.
     */
    public boolean isConnected() {
        return stompSession != null && stompSession.isConnected();
    }

    /**
     * Publie le statut de connexion vers le frontend via STOMP local.
     */
    private void broadcastStatus(boolean connected) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(
                        "/topic/connection-status",
                        java.util.Map.of("connected", connected));
            } catch (Exception e) {
                log.trace("Push STOMP connection-status ignoré : {}", e.getMessage());
            }
        }
    }

    /**
     * Planifie une tentative de reconnexion avec backoff exponentiel.
     * Delais : 3s, 6s, 12s, 24s, 48s (max 5 tentatives).
     */
    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("Nombre maximum de tentatives de reconnexion atteint ({})", MAX_RECONNECT_ATTEMPTS);
            return;
        }
        long delaySeconds = (long) Math.pow(2, reconnectAttempts) * 3L;
        reconnectAttempts++;
        log.info("Reconnexion dans {} secondes (tentative {}/{})", delaySeconds, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }
}
