# SignalChat — Phase 6 : Interface Web (Chat + Monitoring Crypto)

## Vue d'ensemble

La crypto (X3DH + Double Ratchet) et le réseau (WebSocket STOMP) fonctionnent.
Cette phase construit l'interface utilisateur : une page de chat style WhatsApp avec
un panneau de monitoring crypto en temps réel.

**Objectif** : Alice ouvre `http://localhost:8081`, voit les utilisateurs en ligne,
envoie "Bonjour Bob", la bulle apparaît côté Alice et Bob, et le panneau de droite
affiche chaque opération crypto (X3DH, ratchet, encrypt, decrypt) en temps réel.

---

## Inventaire de l'existant

### Ce qui est déjà implémenté

| Classe | Module | Rôle |
|--------|--------|------|
| `MessageService` | client | Orchestre envoi/réception E2EE, stocke l'historique (`chatHistory`) |
| `ServerConnectionService` | client | Client WebSocket STOMP vers le serveur, `send()`, `isConnected()` |
| `KeyRegistrationService` | client | Génère les clés au démarrage, publie le bundle |
| `CryptoLogger` | client | Stocke les `CryptoEvent` en mémoire synchronisée |
| `CryptoEvent` | client | Modèle : timestamp, type, inputHex, outputHex, description |
| `CryptoEventType` | client | Enum : KEY_GEN, X3DH_*, RATCHET_*, ENCRYPT, DECRYPT, HMAC_* |
| `ChatMessage` | client | Modèle : sender, content, timestamp |
| `KeyBundleController` | server | `GET /api/keys/users` — liste des utilisateurs enregistrés |

### Ce qui manque

| # | Fichier à créer | Module | Rôle |
|---|----------------|--------|------|
| 1 | `LocalWebSocketConfig` | client (config) | Config STOMP locale pour le frontend |
| 2 | `ChatController` | client (controller) | Sert la page Thymeleaf + endpoints REST |
| 3 | `ChatWebSocketController` | client (controller) | Publie messages + crypto events via STOMP local |
| 4 | `chat.html` | client (templates) | Page Thymeleaf principale |
| 5 | `styles.css` | client (static/css) | Design WhatsApp + terminal monitoring |
| 6 | `websocket.js` | client (static/js) | Connexion SockJS/STOMP unique |
| 7 | `chat.js` | client (static/js) | Envoi/affichage des messages |
| 8 | `monitoring.js` | client (static/js) | Affichage des événements crypto |

---

## Étape 1 — LocalWebSocketConfig.java

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/config/LocalWebSocketConfig.java`

**Rôle** : Configurer un broker STOMP **local** (client backend → navigateur).
C'est distinct de la connexion WebSocket vers le serveur (`ServerConnectionService`).

```java
@Configuration
@EnableWebSocketMessageBroker
public class LocalWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Topics pour le push vers le frontend
        registry.enableSimpleBroker("/topic");
        // Préfixe pour les messages du frontend vers le backend
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint local avec SockJS fallback
        registry.addEndpoint("/local-ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
```

**Topics exposés** :
- `/topic/messages` — Nouveaux messages chat (envoyés et reçus)
- `/topic/crypto-events` — Événements crypto en temps réel
- `/topic/connection-status` — Changements de statut de connexion

---

## Étape 2 — ChatController.java

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/controller/ChatController.java`

**Rôle** : Sert la page Thymeleaf, expose les endpoints REST pour le frontend.

### Endpoint Thymeleaf

```java
@Controller
public class ChatController {

    @Value("${signalchat.username}")
    private String username;

    @GetMapping("/")
    public String chat(Model model) {
        model.addAttribute("username", username);
        return "chat"; // → templates/chat.html
    }
}
```

### Endpoints REST

Tous préfixés par `/api/chat` :

| Méthode | Path | Rôle |
|---------|------|------|
| `GET` | `/api/chat/users` | Proxy vers `GET {serverUrl}/api/keys/users`, retourne la liste filtrée (exclut le username local) |
| `GET` | `/api/chat/messages/{recipient}` | Retourne l'historique des messages avec un destinataire (depuis `MessageService.chatHistory`) |
| `POST` | `/api/chat/send` | Corps : `{ "recipient": "Bob", "content": "Bonjour" }`. Appelle `MessageService.sendMessage()` |
| `GET` | `/api/chat/status` | Retourne `{ "connected": true/false, "username": "Alice" }` |
| `GET` | `/api/chat/crypto-events` | Retourne tous les `CryptoEvent` stockés (chargement initial du panneau monitoring) |
| `DELETE` | `/api/chat/crypto-events` | Appelle `CryptoLogger.clearEvents()` |

**Notes** :
- Le polling des users se fait côté JS (`setInterval` toutes les 5 secondes sur `GET /api/chat/users`)
- Les messages et crypto events arrivent en push via STOMP, mais les endpoints REST servent au chargement initial

---

## Étape 3 — ChatWebSocketController.java

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/controller/ChatWebSocketController.java`

**Rôle** : Pont entre les services backend et le frontend via STOMP local.

### Publication des messages chat

Le `MessageService` doit être modifié pour notifier le controller quand un message est envoyé ou reçu.

**Approche** : Injecter `SimpMessagingTemplate` dans `MessageService` (ou utiliser un Spring `ApplicationEvent`).

```java
@Service
public class ChatWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    // Appelé par MessageService après envoi/réception
    public void broadcastMessage(ChatMessage message) {
        messagingTemplate.convertAndSend("/topic/messages", message);
    }

    // Appelé par CryptoLogger après chaque événement
    public void broadcastCryptoEvent(CryptoEvent event) {
        messagingTemplate.convertAndSend("/topic/crypto-events", event);
    }

    public void broadcastConnectionStatus(boolean connected) {
        messagingTemplate.convertAndSend("/topic/connection-status",
            Map.of("connected", connected));
    }
}
```

### Modifications requises sur l'existant

1. **`CryptoLogger`** : Ajouter injection de `SimpMessagingTemplate` (ou du `ChatWebSocketController`).
   Dans `logEvent()`, après l'ajout à la liste, publier sur `/topic/crypto-events`.

2. **`MessageService`** : Après `sendMessage()` et `onMessageReceived()`, publier le `ChatMessage`
   sur `/topic/messages` via le controller.

3. **`ServerConnectionService`** : Publier les changements de statut de connexion
   sur `/topic/connection-status`.

---

## Étape 4 — chat.html (Thymeleaf)

**Fichier** : `client/src/main/resources/templates/chat.html`

### Structure HTML

```
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SignalChat</title>
    <link rel="stylesheet" href="/css/styles.css">
</head>
<body>
    <div class="app-container">

        <!-- COLONNE GAUCHE — CHAT (60%) -->
        <div class="chat-panel">

            <!-- En-tête -->
            <div class="chat-header">
                <h1>🔒 SignalChat — <span th:text="${username}">Alice</span></h1>
                <span id="connection-indicator" class="indicator offline">○ Déconnecté</span>
            </div>

            <!-- Liste des utilisateurs -->
            <div class="users-bar">
                <span class="users-label">En ligne :</span>
                <div id="users-list" class="users-list">
                    <!-- Rempli dynamiquement par chat.js -->
                </div>
            </div>

            <!-- Zone de messages -->
            <div id="messages-container" class="messages-container">
                <!-- Bulles de messages injectées par chat.js -->
            </div>

            <!-- Input de message -->
            <div class="message-input-bar">
                <input type="text" id="message-input" placeholder="Tapez un message..."
                       autocomplete="off" disabled>
                <button id="send-button" disabled>Envoyer</button>
            </div>
        </div>

        <!-- COLONNE DROITE — MONITORING CRYPTO (40%) -->
        <div class="monitoring-panel">

            <div class="monitoring-header">
                <h2>📊 Monitoring Crypto</h2>
                <div class="monitoring-actions">
                    <button id="clear-logs-btn">Effacer</button>
                    <button id="export-logs-btn">Exporter</button>
                </div>
            </div>

            <div id="crypto-log" class="crypto-log">
                <!-- Événements crypto injectés par monitoring.js -->
            </div>
        </div>
    </div>

    <!-- Username injecté par Thymeleaf pour le JS -->
    <script th:inline="javascript">
        const CURRENT_USER = [[${username}]];
    </script>

    <!-- Webjars SockJS + STOMP -->
    <script src="/webjars/sockjs-client/1.5.1/sockjs.min.js"></script>
    <script src="/webjars/stomp-websocket/2.3.4/stomp.min.js"></script>

    <!-- Application JS -->
    <script src="/js/websocket.js"></script>
    <script src="/js/chat.js"></script>
    <script src="/js/monitoring.js"></script>
</body>
</html>
```

### Éléments Thymeleaf

- `th:text="${username}"` — Injecté par `ChatController`
- `th:inline="javascript"` — Passe le username au JS via `CURRENT_USER`
- Les webjars sont déjà dans le `pom.xml` du client

---

## Étape 5 — styles.css

**Fichier** : `client/src/main/resources/static/css/styles.css`

### Design System

| Élément | Style |
|---------|-------|
| **Layout** | Flexbox, 60/40 split, `min-height: 100vh` |
| **Chat header** | Fond `#075e54` (vert WhatsApp), texte blanc |
| **Users bar** | Fond `#f0f0f0`, badges cliquables par user |
| **Messages container** | Fond `#e5ddd5` (beige WhatsApp), overflow-y scroll |
| **Bulle envoyée** | Fond `#dcf8c6` (vert clair), `align-self: flex-end`, `max-width: 70%` |
| **Bulle reçue** | Fond `#ffffff`, `align-self: flex-start`, `max-width: 70%` |
| **Bulle** | `border-radius: 8px`, ombre légère, padding 8px 12px |
| **Metadata bulle** | Heure en gris petit + 🔒, `font-size: 0.75rem` |
| **Input bar** | Fond blanc, input flexible + bouton vert |
| **Monitoring panel** | Fond `#1e1e1e`, texte `#d4d4d4` |
| **Monitoring header** | Fond `#252526`, boutons discrets |
| **Crypto log** | `font-family: 'Consolas', 'Courier New', monospace`, `font-size: 0.8rem` |

### Couleurs des événements crypto

| Type | Couleur | Emoji |
|------|---------|-------|
| `KEY_GEN` | `#569cd6` (bleu) | 🔑 |
| `X3DH_*` | `#c586c0` (violet) | 🤝 |
| `RATCHET_*`, `SYMMETRIC_RATCHET`, `HKDF_DERIVE` | `#ce9178` (orange) | 🔄 |
| `ENCRYPT` | `#6a9955` (vert) | 🔐 |
| `DECRYPT` | `#4ec9b0` (cyan) | 🔓 |
| `HMAC_*` | `#808080` (gris) | ✅ |
| `SIGNATURE_VERIFY` | `#dcdcaa` (jaune) | 🔏 |

### Responsive

```css
@media (max-width: 768px) {
    .app-container {
        flex-direction: column;
    }
    .chat-panel, .monitoring-panel {
        width: 100%;
    }
    .monitoring-panel {
        max-height: 40vh;
    }
}
```

### Animations

```css
/* Slide-in pour les nouveaux messages */
@keyframes slideIn {
    from { opacity: 0; transform: translateY(10px); }
    to { opacity: 1; transform: translateY(0); }
}
.message-bubble { animation: slideIn 0.3s ease-out; }

/* Fade-in pour les événements crypto */
@keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
}
.crypto-event { animation: fadeIn 0.2s ease-in; }
```

---

## Étape 6 — websocket.js

**Fichier** : `client/src/main/resources/static/js/websocket.js`

**Rôle** : Connexion SockJS/STOMP unique au backend local, avec routing vers chat et monitoring.

### API exposée

```javascript
const SignalWebSocket = {
    stompClient: null,
    connected: false,
    callbacks: {
        onMessage: null,        // (chatMessage) => void
        onCryptoEvent: null,    // (cryptoEvent) => void
        onConnectionStatus: null // (status) => void
    },

    connect() {
        const socket = new SockJS('/local-ws');
        this.stompClient = Stomp.over(socket);
        // Désactiver les logs STOMP verbeux en production
        this.stompClient.debug = null;

        this.stompClient.connect({}, (frame) => {
            this.connected = true;
            this._subscribe();
            if (this.callbacks.onConnectionStatus) {
                this.callbacks.onConnectionStatus({ connected: true });
            }
        }, (error) => {
            this.connected = false;
            if (this.callbacks.onConnectionStatus) {
                this.callbacks.onConnectionStatus({ connected: false });
            }
            // Reconnexion après 3 secondes
            setTimeout(() => this.connect(), 3000);
        });
    },

    _subscribe() {
        // Messages chat
        this.stompClient.subscribe('/topic/messages', (msg) => {
            const chatMessage = JSON.parse(msg.body);
            if (this.callbacks.onMessage) {
                this.callbacks.onMessage(chatMessage);
            }
        });

        // Événements crypto
        this.stompClient.subscribe('/topic/crypto-events', (msg) => {
            const event = JSON.parse(msg.body);
            if (this.callbacks.onCryptoEvent) {
                this.callbacks.onCryptoEvent(event);
            }
        });

        // Statut de connexion (backend → serveur)
        this.stompClient.subscribe('/topic/connection-status', (msg) => {
            const status = JSON.parse(msg.body);
            if (this.callbacks.onConnectionStatus) {
                this.callbacks.onConnectionStatus(status);
            }
        });
    },

    sendMessage(recipient, content) {
        // Envoi via REST (pas STOMP) car le traitement crypto est côté backend
        return fetch('/api/chat/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ recipient, content })
        });
    }
};
```

**Notes** :
- L'envoi de message passe par REST (`POST /api/chat/send`) et non par STOMP,
  car c'est le backend Java qui fait la crypto (X3DH + Double Ratchet) avant d'envoyer au serveur.
- La réception de messages passe par STOMP (push temps réel du backend vers le navigateur).

---

## Étape 7 — chat.js

**Fichier** : `client/src/main/resources/static/js/chat.js`

**Rôle** : Gestion des conversations, affichage des bulles, sélection du destinataire.

### État local

```javascript
const Chat = {
    currentRecipient: null,  // Utilisateur sélectionné
    messageHistory: {},      // { "Bob": [msg1, msg2, ...] }

    init() { ... },
    loadUsers() { ... },
    selectRecipient(username) { ... },
    loadMessageHistory(recipient) { ... },
    sendMessage() { ... },
    displayMessage(message) { ... },
    createBubble(message) { ... },
    scrollToBottom() { ... }
};
```

### Fonctionnalités

1. **`loadUsers()`** — `fetch('/api/chat/users')` toutes les 5 secondes.
   Affiche les users comme badges cliquables dans `.users-bar`.
   L'utilisateur sélectionné a la classe `.active`.

2. **`selectRecipient(username)`** — Met à jour `currentRecipient`,
   charge l'historique via `fetch('/api/chat/messages/{recipient}')`,
   vide et re-remplit `.messages-container`.

3. **`sendMessage()`** — Lit l'input, appelle `SignalWebSocket.sendMessage(recipient, content)`.
   Le message sera pushé par le backend via `/topic/messages` après chiffrement+envoi.

4. **`displayMessage(message)`** — Crée une bulle et l'ajoute au container.
   Ne l'affiche que si `message.sender === currentRecipient` ou `message.sender === CURRENT_USER`.

5. **`createBubble(message)`** — Retourne un `<div>` avec les classes appropriées :
   ```html
   <div class="message-bubble sent">
       <div class="bubble-content">Bonjour Bob</div>
       <div class="bubble-meta">
           <span class="bubble-time">14:32</span>
           <span class="bubble-lock" title="Message chiffré E2EE">🔒</span>
       </div>
   </div>
   ```

6. **Indicateur de notification** — Quand un message arrive d'un user non sélectionné,
   ajouter un badge de notification sur son bouton dans la barre des users.

### Événements

- `Enter` dans l'input → `sendMessage()`
- Clic sur un user → `selectRecipient(username)`
- `SignalWebSocket.callbacks.onMessage = Chat.displayMessage`

---

## Étape 8 — monitoring.js

**Fichier** : `client/src/main/resources/static/js/monitoring.js`

**Rôle** : Affichage et formatage des événements crypto dans le panneau de monitoring.

### Mapping des types

```javascript
const CryptoMonitor = {
    EVENT_STYLES: {
        'KEY_GEN':            { emoji: '🔑', color: '#569cd6', label: 'KEY_GEN' },
        'X3DH_DH1':          { emoji: '🤝', color: '#c586c0', label: 'X3DH_DH1' },
        'X3DH_DH2':          { emoji: '🤝', color: '#c586c0', label: 'X3DH_DH2' },
        'X3DH_DH3':          { emoji: '🤝', color: '#c586c0', label: 'X3DH_DH3' },
        'X3DH_DH4':          { emoji: '🤝', color: '#c586c0', label: 'X3DH_DH4' },
        'X3DH_MASTER_SECRET': { emoji: '🤝', color: '#c586c0', label: 'MASTER_SECRET' },
        'X3DH_SESSION_INIT':  { emoji: '🤝', color: '#c586c0', label: 'SESSION_INIT' },
        'RATCHET_STEP':       { emoji: '🔄', color: '#ce9178', label: 'DH_RATCHET' },
        'SYMMETRIC_RATCHET':  { emoji: '🔄', color: '#ce9178', label: 'SYM_RATCHET' },
        'HKDF_DERIVE':        { emoji: '🔄', color: '#ce9178', label: 'HKDF' },
        'ENCRYPT':            { emoji: '🔐', color: '#6a9955', label: 'ENCRYPT' },
        'DECRYPT':            { emoji: '🔓', color: '#4ec9b0', label: 'DECRYPT' },
        'HMAC_COMPUTE':       { emoji: '✅', color: '#808080', label: 'HMAC' },
        'HMAC_VERIFY':        { emoji: '✅', color: '#808080', label: 'HMAC_VERIFY' },
        'SIGNATURE_VERIFY':   { emoji: '🔏', color: '#dcdcaa', label: 'SIG_VERIFY' }
    },

    init() { ... },
    loadExistingEvents() { ... },
    addEvent(event) { ... },
    formatEvent(event) { ... },
    clearLogs() { ... },
    exportLogs() { ... },
    autoScroll() { ... }
};
```

### Format d'affichage

Chaque événement génère un bloc HTML :

```html
<div class="crypto-event" style="border-left: 3px solid #6a9955;">
    <div class="event-header">
        <span class="event-time">[14:32:05.123]</span>
        <span class="event-type" style="color: #6a9955;">🔐 ENCRYPT</span>
    </div>
    <div class="event-details">
        <div class="event-line">
            <span class="event-label">Input:</span>
            <span class="event-value hex-value"
                  title="0x7a3f8b2c...complet...c2b1e4d5">0x7a3f8b2c...c2b1e4d5</span>
        </div>
        <div class="event-line">
            <span class="event-label">Output:</span>
            <span class="event-value hex-value"
                  title="0x9f8e7d6c...complet...5a4b3c2d">0x9f8e7d6c...5a4b3c2d</span>
        </div>
        <div class="event-description">AES-256-CBC encryption du message</div>
    </div>
</div>
```

### Fonctionnalités

1. **`loadExistingEvents()`** — Au chargement, `fetch('/api/chat/crypto-events')` pour récupérer
   les événements qui se sont produits avant l'ouverture de la page (génération de clés, etc.).

2. **`addEvent(event)`** — Ajoute un événement au DOM, applique le style selon le type.

3. **`autoScroll()`** — Après chaque ajout, scroll vers le bas du `.crypto-log`.
   Utilise `scrollTop = scrollHeight`. Ne scroll que si l'utilisateur est déjà en bas
   (tolérance de 50px) pour ne pas interrompre une lecture.

4. **`clearLogs()`** — Vide le DOM + `DELETE /api/chat/crypto-events`.

5. **`exportLogs()`** — Collecte le texte de tous les `.crypto-event`, génère un Blob
   et déclenche le téléchargement d'un fichier `crypto-logs-{timestamp}.txt`.

6. **Tooltip hex** — Les valeurs hex tronquées ont un `title` avec la valeur complète.
   Le CryptoEvent doit inclure les valeurs complètes (inputHex, outputHex) pour le tooltip.

### Événements

- `SignalWebSocket.callbacks.onCryptoEvent = CryptoMonitor.addEvent`
- Clic "Effacer" → `CryptoMonitor.clearLogs()`
- Clic "Exporter" → `CryptoMonitor.exportLogs()`

---

## Étape 9 — Modifications de l'existant

### 9.1 — CryptoLogger : push temps réel

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/monitoring/CryptoLogger.java`

Ajouter l'injection de `SimpMessagingTemplate` et publier chaque événement :

```java
@Component
public class CryptoLogger {

    private final SimpMessagingTemplate messagingTemplate; // NOUVEAU

    public void logEvent(CryptoEventType type, String input, String output, String description) {
        CryptoEvent event = CryptoEvent.builder()
            .timestamp(LocalDateTime.now())
            .type(type)
            .inputHex(input)
            .outputHex(output)
            .description(description)
            .build();
        events.add(event);
        log.info("[CRYPTO] {} | {} → {}", type, truncateHex(input), truncateHex(output));

        // NOUVEAU : push vers le frontend
        messagingTemplate.convertAndSend("/topic/crypto-events", event);
    }
}
```

**Attention** : `SimpMessagingTemplate` peut être null au démarrage (avant l'init du WebSocket).
Entourer le `convertAndSend` d'un try-catch ou vérifier `messagingTemplate != null`.

### 9.2 — MessageService : broadcast des messages chat

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/service/MessageService.java`

Après chaque message envoyé/reçu, publier le `ChatMessage` :

```java
// Dans sendMessage(), après l'envoi réussi :
ChatMessage chatMsg = new ChatMessage(username, content, LocalDateTime.now());
chatHistory.computeIfAbsent(recipient, k -> new ArrayList<>()).add(chatMsg);
messagingTemplate.convertAndSend("/topic/messages", chatMsg);

// Dans onMessageReceived(), après le déchiffrement :
ChatMessage chatMsg = new ChatMessage(sender, plaintext, LocalDateTime.now());
chatHistory.computeIfAbsent(sender, k -> new ArrayList<>()).add(chatMsg);
messagingTemplate.convertAndSend("/topic/messages", chatMsg);
```

### 9.3 — ServerConnectionService : broadcast du statut

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/service/ServerConnectionService.java`

Publier les changements de statut de connexion au serveur :

```java
// Dans le callback de connexion réussie :
messagingTemplate.convertAndSend("/topic/connection-status", Map.of("connected", true));

// Dans le callback d'erreur/déconnexion :
messagingTemplate.convertAndSend("/topic/connection-status", Map.of("connected", false));
```

### 9.4 — ChatMessage : ajout du champ recipient

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/model/ChatMessage.java`

Le modèle actuel a `sender, content, timestamp`. Pour le frontend, il faut aussi le `recipient`
afin de savoir dans quelle conversation afficher le message.

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatMessage {
    private String sender;
    private String recipient;  // NOUVEAU
    private String content;
    private LocalDateTime timestamp;
}
```

---

## Ordre d'implémentation

```
Étape 1  LocalWebSocketConfig      [pas de dépendance]
Étape 9.4 ChatMessage (ajout recipient) [pas de dépendance]
    │
    ├── Étape 9.1  CryptoLogger modifié      [dépend de 1]
    ├── Étape 9.2  MessageService modifié     [dépend de 1, 9.4]
    └── Étape 9.3  ServerConnectionService    [dépend de 1]
    │
    ├── Étape 2   ChatController             [dépend de 9.2]
    └── Étape 3   ChatWebSocketController    [dépend de 1]
    │
    ├── Étape 4   chat.html                  [dépend de 2]
    └── Étape 5   styles.css                 [pas de dépendance]
    │
    ├── Étape 6   websocket.js               [dépend de 1, 4]
    ├── Étape 7   chat.js                    [dépend de 6]
    └── Étape 8   monitoring.js              [dépend de 6]
```

**Résumé séquentiel** :
1. `LocalWebSocketConfig` + `ChatMessage` modifié
2. Modifications backend (`CryptoLogger`, `MessageService`, `ServerConnectionService`)
3. `ChatController` + `ChatWebSocketController`
4. `chat.html` + `styles.css`
5. `websocket.js` → `chat.js` → `monitoring.js`

---

## Tests manuels

### Scénario de validation

1. Lancer le serveur : `mvn spring-boot:run -pl server`
2. Lancer Alice : `USERNAME=Alice CLIENT_PORT=8081 mvn spring-boot:run -pl client`
3. Lancer Bob : `USERNAME=Bob CLIENT_PORT=8082 mvn spring-boot:run -pl client`
4. Ouvrir `http://localhost:8081` (Alice) et `http://localhost:8082` (Bob)
5. Vérifier : les deux voient l'indicateur vert "● Connecté"
6. Vérifier : Alice voit "Bob" dans la liste des utilisateurs (et inversement)
7. Alice clique sur "Bob", tape "Bonjour Bob", envoie
8. Vérifier : bulle verte côté Alice, bulle blanche côté Bob
9. Vérifier : le panneau monitoring d'Alice affiche X3DH + ENCRYPT
10. Vérifier : le panneau monitoring de Bob affiche X3DH + DECRYPT
11. Bob répond "Salut Alice"
12. Vérifier : pas de nouveau X3DH (session déjà établie), seulement RATCHET + ENCRYPT/DECRYPT
13. Tester "Effacer les logs" et "Exporter les logs"
14. Tester le responsive : réduire la fenêtre < 768px, vérifier que le monitoring passe en dessous

### Points d'attention

- Le premier message déclenche X3DH (visible dans le monitoring) — c'est normal et attendu
- Les clés tronquées doivent afficher le tooltip au survol avec la clé complète
- L'auto-scroll du monitoring ne doit pas empêcher de remonter lire les anciens événements
- L'input est désactivé tant qu'aucun destinataire n'est sélectionné
