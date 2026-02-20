# SignalChat — Phase 5 : Connexion Crypto ↔ Réseau

## Vue d'ensemble

Le squelette crypto (X3DH + Double Ratchet) et le serveur de relai sont en place.
Cette phase connecte les deux : enregistrement des clés, communication WebSocket STOMP,
orchestration des messages chiffrés de bout en bout, et test d'intégration complet.

**Objectif** : Alice envoie "Bonjour Bob" → le serveur relaie du ciphertext illisible →
Bob reçoit "Bonjour Bob" en clair. Les logs montrent chaque étape crypto.

---

## Inventaire de l'existant

### Côté Client (déjà implémenté)

| Classe | Package | Rôle |
|--------|---------|------|
| `KeyManager` | `client.crypto` | Génération de clés (identité XEdDSA, SPK, OPK, ratchet), DH X25519, signatures Ed25519 |
| `X3DHService` | `client.crypto` | `initiateHandshake(IdentityKeyPair, KeyBundle) → X3DHResult`, `processHandshake(...)  → byte[] masterSecret` |
| `DoubleRatchetService` | `client.crypto` | `initSessionAlice/Bob(...)`, `ratchetEncrypt(state, plaintext, sender, recipient) → EncryptedMessage`, `ratchetDecrypt(state, message) → byte[]` |
| `CryptoEngine` | `client.crypto` | HKDF, AES-256-CBC, HMAC-SHA256 |
| `SessionState` | `client.crypto` | État complet d'une session Double Ratchet |
| `IdentityKeyPair` | `client.crypto` | Seed + X25519 pub + Ed25519 pub |
| `KeyBundle` | `client.model` | DTO bundle de clés publiques (Base64) |
| `EncryptedMessage` | `client.model` | DTO message chiffré avec headers X3DH + Double Ratchet |
| `X3DHResult` | `client.model` | Résultat X3DH : ephemeralPublicKey, usedOneTimePreKeyId, masterSecret |
| `CryptoLogger` | `client.monitoring` | Journalisation des événements crypto |

### Côté Serveur (déjà implémenté)

| Classe | Package | Rôle |
|--------|---------|------|
| `KeyBundleController` | `server.controller` | `POST /api/keys/register`, `GET /api/keys/bundle/{username}`, `GET /api/keys/users` |
| `KeyStoreService` | `server.service` | Stockage ConcurrentHashMap des KeyBundles, consommation des OPK |
| `UserRegistryService` | `server.service` | Registre des utilisateurs connectés (username → sessionId) |
| `MessageRelayHandler` | `server.handler` | `@MessageMapping("/send")` → relaie vers `/topic/messages/{recipient}` |
| `WebSocketConfig` | `server.config` | STOMP sur `/ws` avec SockJS, broker `/topic`, prefix `/app` |

### Ce qui manque

| # | Classe à créer | Module | Rôle |
|---|----------------|--------|------|
| 1 | `KeyRegistrationService` | client | Génère les clés au démarrage, les enregistre auprès du serveur |
| 2 | `ServerConnectionService` | client | Connexion WebSocket STOMP, abonnement aux messages, envoi |
| 3 | `MessageService` | client | Chef d'orchestre : envoi/réception de messages, gestion des sessions |
| 4 | `MessageRelayHandler` (amélioration) | server | File d'attente pour les destinataires hors-ligne |
| 5 | `EndToEndEncryptionTest` | client (test) | Test d'intégration Alice ↔ Serveur ↔ Bob |

---

## Étape 1 — KeyRegistrationService.java

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/service/KeyRegistrationService.java`

**Dépendances** : `KeyManager`, `RestTemplate` (Spring), config `signalchat.*`

**Responsabilité** : Au démarrage du client, générer toutes les clés et les publier sur le serveur.

### Données à conserver en mémoire

Le service doit stocker les clés privées localement (elles ne quittent jamais le client) :

```java
@Service
public class KeyRegistrationService {

    private final KeyManager keyManager;
    private final CryptoLogger cryptoLogger;

    @Value("${signalchat.username}")
    private String username;

    @Value("${signalchat.server-url}")
    private String serverUrl;

    @Value("${signalchat.one-time-prekeys-count:20}")
    private int oneTimePreKeysCount;

    // --- Clés privées (ne quittent JAMAIS le client) ---
    private IdentityKeyPair identityKeyPair;
    private byte[] signedPreKeySeed;        // seed privé de la SPK
    private byte[] signedPreKeyPublic;      // clé publique SPK
    private byte[][][] oneTimePreKeys;      // [i][0]=seed, [i][1]=pub

    // Accesseurs nécessaires pour MessageService
    // getIdentityKeyPair(), getSignedPreKeySeed(), getSignedPreKeyPublic()
    // getOneTimePreKeySeed(int id) → retourne oneTimePreKeys[id][0]
}
```

### Méthode `registerKeys()` — déclenchée au démarrage via `@EventListener(ApplicationReadyEvent.class)`

```
1. identityKeyPair = keyManager.generateIdentityKeyPair()
   → log: "Clé d'identité générée | X25519={trunc} | Ed25519={trunc}"

2. byte[][] spk = keyManager.generateSignedPreKeyPair()
   signedPreKeySeed = spk[0]
   signedPreKeyPublic = spk[1]

3. byte[] spkSignature = keyManager.signXEdDSA(identityKeyPair.getSeed(), signedPreKeyPublic)
   → log: "SPK signée via XEdDSA"

4. oneTimePreKeys = keyManager.generateOneTimePreKeys(oneTimePreKeysCount)
   → log: "{count} One-Time Pre-Keys générées"

5. Construire le KeyBundle (tout en Base64) :
   KeyBundle bundle = KeyBundle.builder()
       .username(username)
       .identityKey(base64(identityKeyPair.getX25519PublicKey()))
       .identitySigningKey(base64(identityKeyPair.getEd25519PublicKey()))
       .signedPreKey(base64(signedPreKeyPublic))
       .signedPreKeyId(0)
       .signedPreKeySignature(base64(spkSignature))
       .oneTimePreKeys(oneTimePreKeys → stream → base64(pub) → list)
       .build()

6. POST vers {serverUrl}/api/keys/register avec le bundle
   → RestTemplate.postForEntity(serverUrl + "/api/keys/register", bundle, String.class)

7. log.info("Clés enregistrées auprès du serveur pour l'utilisateur : {}", username)
```

### Points d'attention

- Utiliser `RestTemplate` (pas WebClient) pour rester simple.
- Si le serveur n'est pas joignable, logger une erreur et réessayer (ou échouer au démarrage).
- Le `@EventListener(ApplicationReadyEvent.class)` garantit que Spring est prêt avant l'appel réseau.

---

## Étape 2 — ServerConnectionService.java

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/service/ServerConnectionService.java`

**Dépendances** : `SockJS + STOMP client`, config `signalchat.*`

**Responsabilité** : Maintenir la connexion WebSocket STOMP avec le serveur, recevoir les messages
et les transmettre au `MessageService`.

### Structure

```java
@Service
public class ServerConnectionService {

    @Value("${signalchat.server-url}")
    private String serverUrl;

    @Value("${signalchat.username}")
    private String username;

    private StompSession stompSession;
    private Consumer<EncryptedMessage> messageHandler;  // callback vers MessageService

    // Appelé par MessageService pour s'enregistrer comme handler de messages
    public void setMessageHandler(Consumer<EncryptedMessage> handler);

    // Connexion + abonnement
    public void connect();

    // Envoi d'un message chiffré
    public void send(EncryptedMessage message);
}
```

### Méthode `connect()` — déclenchée après l'enregistrement des clés

```
1. Construire le transport SockJS :
   List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()))
   SockJsClient sockJsClient = new SockJsClient(transports)
   WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient)
   stompClient.setMessageConverter(new MappingJackson2MessageConverter())

2. Se connecter au serveur :
   String wsUrl = serverUrl.replace("http", "ws") + "/ws"
   // Ou garder http:// et laisser SockJS gérer le fallback :
   stompSession = stompClient.connectAsync(serverUrl + "/ws", new StompSessionHandlerAdapter() {
       @Override
       public void afterConnected(StompSession session, StompHeaders headers) {
           log.info("Connecté au serveur WebSocket STOMP : {}", serverUrl);
           // Abonnement au topic personnel
           session.subscribe("/topic/messages/" + username, new StompFrameHandler() {
               @Override
               public Type getPayloadType(StompHeaders headers) {
                   return EncryptedMessage.class;
               }
               @Override
               public void handleFrame(StompHeaders headers, Object payload) {
                   EncryptedMessage msg = (EncryptedMessage) payload;
                   log.info("Message chiffré reçu de {} | ciphertext: {} bytes",
                       msg.getSender(), msg.getCiphertext().length());
                   if (messageHandler != null) {
                       messageHandler.accept(msg);
                   }
               }
           });
       }
       @Override
       public void handleTransportError(StompSession session, Throwable exception) {
           log.error("Erreur de transport WebSocket : {}", exception.getMessage());
           // Reconnexion automatique après délai
           scheduleReconnect();
       }
   }).get(5, TimeUnit.SECONDS);

3. log.info("Abonné à /topic/messages/{}", username);
```

### Méthode `send(EncryptedMessage message)`

```
1. Vérifier que stompSession != null && stompSession.isConnected()
   → sinon : throw IllegalStateException ou tenter reconnexion
2. stompSession.send("/app/send", message)
3. log.info("Message chiffré envoyé vers {} | ciphertext: {} bytes",
       message.getRecipient(), message.getCiphertext().length());
```

### Reconnexion automatique

```java
private void scheduleReconnect() {
    // ScheduledExecutorService avec délai de 3 secondes
    // Réappeler connect() dans un try-catch
    // Maximum 5 tentatives avec backoff exponentiel (3s, 6s, 12s, 24s, 48s)
}
```

### Dépendances Maven supplémentaires (déjà dans le POM client)

Les dépendances `spring-boot-starter-websocket`, `sockjs-client`, et `stomp-websocket` sont
déjà déclarées dans le POM client. Le `WebSocketStompClient` de Spring est disponible.

---

## Étape 3 — MessageService.java — Le chef d'orchestre

**Fichier** : `client/src/main/java/cm/iusjc/signalchat/client/service/MessageService.java`

**Dépendances** : `KeyRegistrationService`, `ServerConnectionService`, `X3DHService`,
`DoubleRatchetService`, `CryptoLogger`, `RestTemplate`

**Responsabilité** : Orchestrer tout le flux de messagerie chiffrée.

### Structure

```java
@Service
public class MessageService {

    private final KeyRegistrationService keyRegistrationService;
    private final ServerConnectionService serverConnectionService;
    private final X3DHService x3dhService;
    private final DoubleRatchetService doubleRatchetService;
    private final CryptoLogger cryptoLogger;

    @Value("${signalchat.username}")
    private String username;

    @Value("${signalchat.server-url}")
    private String serverUrl;

    // Sessions actives : recipientUsername → SessionState
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    // Messages déchiffrés pour l'UI : liste thread-safe
    private final List<ChatMessage> chatMessages = Collections.synchronizedList(new ArrayList<>());

    // RestTemplate pour récupérer les KeyBundles
    private final RestTemplate restTemplate;
}
```

### Initialisation — `@PostConstruct` ou `@EventListener`

```
1. serverConnectionService.setMessageHandler(this::onMessageReceived)
   → Enregistre le callback de réception
```

### `sendMessage(String recipientUsername, String plaintext)`

```
1. log.info("Envoi de message vers {} | taille plaintext: {} bytes", recipient, plaintext.length())

2. SessionState session = sessions.get(recipientUsername)

3. SI session == null (pas de session existante) :
   a. log.info("Aucune session avec {} — démarrage X3DH", recipientUsername)

   b. Récupérer le KeyBundle du destinataire :
      ResponseEntity<KeyBundle> response = restTemplate.getForEntity(
          serverUrl + "/api/keys/bundle/" + recipientUsername, KeyBundle.class)
      Si 404 → throw "Utilisateur non trouvé"
      KeyBundle recipientBundle = response.getBody()

   c. X3DH côté Alice :
      X3DHResult x3dhResult = x3dhService.initiateHandshake(
          keyRegistrationService.getIdentityKeyPair(), recipientBundle)
      → log: "X3DH terminé | masterSecret={trunc}"

   d. Initialiser la session Double Ratchet :
      byte[] remoteSPKPub = Base64.getDecoder().decode(recipientBundle.getSignedPreKey())
      session = doubleRatchetService.initSessionAlice(x3dhResult.getMasterSecret(), remoteSPKPub)
      sessions.put(recipientUsername, session)
      → log: "Session Double Ratchet initialisée (côté Alice)"

   e. IMPORTANT — Stocker les infos X3DH pour le premier message :
      Sauvegarder ephemeralPublicKey et usedOneTimePreKeyId pour les inclure
      dans le premier EncryptedMessage (header X3DH).

4. Chiffrement Double Ratchet :
   EncryptedMessage encrypted = doubleRatchetService.ratchetEncrypt(
       session, plaintext.getBytes(StandardCharsets.UTF_8), username, recipientUsername)

5. SI c'est le premier message (session nouvellement créée) :
   Ajouter les headers X3DH au message :
   encrypted.setSenderIdentityKey(base64(identityKeyPair.getX25519PublicKey()))
   encrypted.setSenderIdentitySigningKey(base64(identityKeyPair.getEd25519PublicKey()))
   encrypted.setSenderEphemeralKey(base64(x3dhResult.getEphemeralPublicKey()))
   encrypted.setUsedOneTimePreKeyId(x3dhResult.getUsedOneTimePreKeyId())

6. Envoi via WebSocket :
   serverConnectionService.send(encrypted)
   → log: "Message chiffré envoyé | ciphertext={trunc} | msgNum={n}"

7. Ajouter le message en clair à la liste locale (pour l'UI) :
   chatMessages.add(ChatMessage(username, plaintext, System.currentTimeMillis()))
```

### `onMessageReceived(EncryptedMessage message)` — callback depuis ServerConnectionService

```
1. String senderUsername = message.getSender()
   log.info("Message chiffré reçu de {} | ciphertext: {} bytes",
       senderUsername, message.getCiphertext().length())

2. SessionState session = sessions.get(senderUsername)

3. SI session == null (premier message de cet expéditeur → X3DH côté Bob) :
   a. log.info("Premier message de {} — traitement X3DH côté Bob", senderUsername)

   b. Extraire les headers X3DH du message :
      byte[] senderIdentityPub = base64Decode(message.getSenderIdentityKey())
      byte[] senderEphemeralPub = base64Decode(message.getSenderEphemeralKey())

   c. Déterminer la clé OPK utilisée :
      byte[] opkSeed = null
      if (message.getUsedOneTimePreKeyId() != null) {
          opkSeed = keyRegistrationService.getOneTimePreKeySeed(message.getUsedOneTimePreKeyId())
      }

   d. X3DH côté Bob :
      byte[] masterSecret = x3dhService.processHandshake(
          keyRegistrationService.getIdentityKeyPair(),
          keyRegistrationService.getSignedPreKeySeed(),  // SPK_B seed
          opkSeed,                                        // OPK_B seed (nullable)
          senderIdentityPub,                              // IK_A pub
          senderEphemeralPub)                             // EK_A pub
      → log: "X3DH Bob terminé | masterSecret={trunc}"

   e. Initialiser la session Double Ratchet côté Bob :
      session = doubleRatchetService.initSessionBob(
          masterSecret,
          keyRegistrationService.getSignedPreKeySeed(),
          keyRegistrationService.getSignedPreKeyPublic())
      sessions.put(senderUsername, session)
      → log: "Session Double Ratchet initialisée (côté Bob)"

4. Déchiffrement Double Ratchet :
   byte[] plaintext = doubleRatchetService.ratchetDecrypt(session, message)
   String plaintextStr = new String(plaintext, StandardCharsets.UTF_8)
   → log: "Message déchiffré de {} : '{}'", senderUsername, plaintextStr

5. Ajouter le message déchiffré à la liste locale (pour l'UI) :
   chatMessages.add(ChatMessage(senderUsername, plaintextStr, System.currentTimeMillis()))
```

### Point critique : Gestion du premier message (headers X3DH)

Le `EncryptedMessage` contient des champs X3DH (`senderIdentityKey`, `senderEphemeralKey`, etc.)
qui ne sont remplis que pour le **premier message** d'une session. Pour les messages suivants,
ces champs restent `null`. Le `MessageService` doit :
- Les remplir côté envoi uniquement au premier message
- Les lire côté réception uniquement quand `session == null`

### Point critique : Stockage temporaire du X3DHResult

Quand `sendMessage()` crée une nouvelle session, il faut stocker le `X3DHResult` temporairement
pour injecter les headers X3DH dans le message. Deux options :

**Option A** : Variable locale dans sendMessage() — simple, recommandé.

**Option B** : Map `pendingX3DH` — plus complexe, nécessaire si on sépare la création
de session de l'envoi du premier message.

→ **Retenu : Option A** (le X3DHResult est utilisé immédiatement dans la même méthode).

---

## Étape 4 — Amélioration de MessageRelayHandler (serveur)

**Fichier** : `server/src/main/java/cm/iusjc/signalchat/server/handler/MessageRelayHandler.java`

### Modifications

```java
@Slf4j
@Controller
@RequiredArgsConstructor
public class MessageRelayHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRegistryService userRegistryService;

    // File d'attente des messages pour les destinataires hors-ligne
    private final ConcurrentHashMap<String, Queue<EncryptedMessage>> pendingMessages
        = new ConcurrentHashMap<>();

    @MessageMapping("/send")
    public void relayMessage(EncryptedMessage message) {
        int ciphertextSize = message.getCiphertext() != null
            ? message.getCiphertext().length() : 0;

        log.info("RELAI [{}] → [{}] | ciphertext: {} bytes (Base64) | messageNumber: {}",
            message.getSender(), message.getRecipient(),
            ciphertextSize, message.getMessageNumber());
        log.info("  ⚠ Le serveur NE PEUT PAS lire ce message — chiffrement E2EE actif");

        // Relai vers le topic du destinataire
        messagingTemplate.convertAndSend(
            "/topic/messages/" + message.getRecipient(), message);

        // Si le destinataire n'est pas connecté, stocker en file d'attente
        if (!userRegistryService.isUserOnline(message.getRecipient())) {
            pendingMessages
                .computeIfAbsent(message.getRecipient(), k -> new ConcurrentLinkedQueue<>())
                .add(message);
            log.info("  Destinataire {} hors-ligne — message mis en file d'attente ({} en attente)",
                message.getRecipient(),
                pendingMessages.get(message.getRecipient()).size());
        }
    }

    /**
     * Délivre les messages en attente quand un utilisateur se connecte.
     * Appelé depuis un listener de connexion WebSocket.
     */
    public void deliverPendingMessages(String username) {
        Queue<EncryptedMessage> pending = pendingMessages.remove(username);
        if (pending != null && !pending.isEmpty()) {
            log.info("Délivrance de {} message(s) en attente pour {}", pending.size(), username);
            for (EncryptedMessage msg : pending) {
                messagingTemplate.convertAndSend("/topic/messages/" + username, msg);
            }
        }
    }
}
```

### Listener de connexion WebSocket (nouveau ou à ajouter dans WebSocketConfig)

Pour détecter la connexion d'un utilisateur et délivrer les messages en attente,
ajouter un `WebSocketEventListener` :

```java
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {
    private final MessageRelayHandler messageRelayHandler;
    private final UserRegistryService userRegistryService;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        // Extraire le username depuis les headers STOMP ou la session
        // et appeler messageRelayHandler.deliverPendingMessages(username)
    }
}
```

**Note** : La détection du username à la connexion STOMP nécessite que le client
envoie son username dans les headers STOMP lors du CONNECT. Alternative plus simple :
le client envoie un message de type "presence" après connexion, et le serveur délivre
les messages en attente à ce moment-là.

---

## Étape 5 — Séquence de démarrage du client

L'ordre de démarrage est critique. Voici la séquence complète :

```
1. Spring Boot démarre
2. Tous les beans sont initialisés
3. @EventListener(ApplicationReadyEvent.class) sur KeyRegistrationService.registerKeys()
   → Génère toutes les clés
   → POST /api/keys/register vers le serveur
   → log: "Clés enregistrées auprès du serveur"
4. Après succès de registerKeys() → déclencher la connexion WebSocket
   KeyRegistrationService appelle serverConnectionService.connect()
   → Connexion STOMP
   → Abonnement à /topic/messages/{username}
   → log: "Connecté et abonné aux messages"
5. MessageService est prêt à envoyer/recevoir
```

---

## Étape 6 — Test d'intégration End-to-End

**Fichier** : `client/src/test/java/cm/iusjc/signalchat/client/EndToEndEncryptionTest.java`

### Configuration du test

```java
@SpringBootTest(
    classes = ServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class EndToEndEncryptionTest {

    @LocalServerPort
    private int serverPort;

    // On ne peut PAS démarrer deux contextes Spring client dans le même test.
    // À la place, on instancie les services manuellement.
}
```

### Approche recommandée

Le test ne démarre pas deux applications Spring complètes. Il :
1. Démarre le serveur Spring Boot sur un port aléatoire
2. Instancie manuellement les services crypto (pas de contexte Spring pour les clients)
3. Utilise `RestTemplate` et `WebSocketStompClient` directement

### Scénario détaillé

```java
@Test
void aliceAndBobCanExchangeEncryptedMessages() throws Exception {
    String serverUrl = "http://localhost:" + serverPort;

    // --- Setup : instancier les services crypto pour Alice et Bob ---
    CryptoLogger aliceLogger = new CryptoLogger();
    CryptoLogger bobLogger = new CryptoLogger();

    KeyManager aliceKeyManager = new KeyManager(aliceLogger);
    KeyManager bobKeyManager = new KeyManager(bobLogger);

    CryptoEngine aliceCrypto = new CryptoEngine(aliceLogger);
    CryptoEngine bobCrypto = new CryptoEngine(bobLogger);

    X3DHService aliceX3DH = new X3DHService(aliceKeyManager, aliceCrypto, aliceLogger);
    X3DHService bobX3DH = new X3DHService(bobKeyManager, bobCrypto, bobLogger);

    DoubleRatchetService aliceRatchet = new DoubleRatchetService(aliceKeyManager, aliceCrypto, aliceLogger);
    DoubleRatchetService bobRatchet = new DoubleRatchetService(bobKeyManager, bobCrypto, bobLogger);

    // === PHASE 1 : Génération et enregistrement des clés ===

    // Alice génère ses clés
    IdentityKeyPair aliceIdentity = aliceKeyManager.generateIdentityKeyPair();
    byte[][] aliceSPK = aliceKeyManager.generateSignedPreKeyPair();
    byte[] aliceSPKSignature = aliceKeyManager.signXEdDSA(aliceIdentity.getSeed(), aliceSPK[1]);
    byte[][][] aliceOPKs = aliceKeyManager.generateOneTimePreKeys(5);

    // Bob génère ses clés
    IdentityKeyPair bobIdentity = bobKeyManager.generateIdentityKeyPair();
    byte[][] bobSPK = bobKeyManager.generateSignedPreKeyPair();
    byte[] bobSPKSignature = bobKeyManager.signXEdDSA(bobIdentity.getSeed(), bobSPK[1]);
    byte[][][] bobOPKs = bobKeyManager.generateOneTimePreKeys(5);

    // Enregistrer les clés sur le serveur via REST
    RestTemplate rest = new RestTemplate();
    rest.postForEntity(serverUrl + "/api/keys/register", buildKeyBundle("Alice", aliceIdentity, aliceSPK, aliceSPKSignature, aliceOPKs), String.class);
    rest.postForEntity(serverUrl + "/api/keys/register", buildKeyBundle("Bob", bobIdentity, bobSPK, bobSPKSignature, bobOPKs), String.class);

    // === PHASE 2 : Connexion WebSocket STOMP ===

    CountDownLatch bobReceivedLatch = new CountDownLatch(1);
    CountDownLatch aliceReceivedLatch = new CountDownLatch(1);
    AtomicReference<EncryptedMessage> bobReceivedMsg = new AtomicReference<>();
    AtomicReference<EncryptedMessage> aliceReceivedMsg = new AtomicReference<>();

    // Connecter Bob au WebSocket
    StompSession bobSession = connectStomp(serverUrl);
    bobSession.subscribe("/topic/messages/Bob", new StompFrameHandler() {
        // handleFrame → bobReceivedMsg.set(msg); bobReceivedLatch.countDown();
    });

    // Connecter Alice au WebSocket
    StompSession aliceSession = connectStomp(serverUrl);
    aliceSession.subscribe("/topic/messages/Alice", new StompFrameHandler() {
        // handleFrame → aliceReceivedMsg.set(msg); aliceReceivedLatch.countDown();
    });

    // === PHASE 3 : Alice envoie "Bonjour Bob" ===

    // Alice récupère le KeyBundle de Bob
    KeyBundle bobBundle = rest.getForObject(serverUrl + "/api/keys/bundle/Bob", KeyBundle.class);

    // Alice effectue le X3DH
    X3DHResult aliceX3DHResult = aliceX3DH.initiateHandshake(aliceIdentity, bobBundle);

    // Alice initialise sa session Double Ratchet
    byte[] bobSPKPub = Base64.getDecoder().decode(bobBundle.getSignedPreKey());
    SessionState aliceState = aliceRatchet.initSessionAlice(aliceX3DHResult.getMasterSecret(), bobSPKPub);

    // Alice chiffre le message
    EncryptedMessage msg1 = aliceRatchet.ratchetEncrypt(
        aliceState, "Bonjour Bob".getBytes(StandardCharsets.UTF_8), "Alice", "Bob");

    // Ajouter les headers X3DH (premier message)
    msg1.setSenderIdentityKey(Base64.getEncoder().encodeToString(aliceIdentity.getX25519PublicKey()));
    msg1.setSenderIdentitySigningKey(Base64.getEncoder().encodeToString(aliceIdentity.getEd25519PublicKey()));
    msg1.setSenderEphemeralKey(Base64.getEncoder().encodeToString(aliceX3DHResult.getEphemeralPublicKey()));
    msg1.setUsedOneTimePreKeyId(aliceX3DHResult.getUsedOneTimePreKeyId());

    // Alice envoie via STOMP
    aliceSession.send("/app/send", msg1);

    // === PHASE 4 : Bob reçoit et déchiffre ===

    assertTrue(bobReceivedLatch.await(5, TimeUnit.SECONDS), "Bob n'a pas reçu le message");
    EncryptedMessage bobReceived = bobReceivedMsg.get();

    // Bob effectue le X3DH côté récepteur
    byte[] aliceIdentityPub = Base64.getDecoder().decode(bobReceived.getSenderIdentityKey());
    byte[] aliceEphemeralPub = Base64.getDecoder().decode(bobReceived.getSenderEphemeralKey());
    byte[] opkSeed = bobReceived.getUsedOneTimePreKeyId() != null
        ? bobOPKs[bobReceived.getUsedOneTimePreKeyId()][0] : null;

    byte[] bobMasterSecret = bobX3DH.processHandshake(
        bobIdentity, bobSPK[0], opkSeed, aliceIdentityPub, aliceEphemeralPub);

    // Bob initialise sa session Double Ratchet
    SessionState bobState = bobRatchet.initSessionBob(bobMasterSecret, bobSPK[0], bobSPK[1]);

    // Bob déchiffre
    byte[] plaintext1 = bobRatchet.ratchetDecrypt(bobState, bobReceived);
    assertEquals("Bonjour Bob", new String(plaintext1, StandardCharsets.UTF_8));

    // === PHASE 5 : Bob répond "Salut Alice" ===

    EncryptedMessage msg2 = bobRatchet.ratchetEncrypt(
        bobState, "Salut Alice".getBytes(StandardCharsets.UTF_8), "Bob", "Alice");
    bobSession.send("/app/send", msg2);

    assertTrue(aliceReceivedLatch.await(5, TimeUnit.SECONDS), "Alice n'a pas reçu le message");
    EncryptedMessage aliceReceived = aliceReceivedMsg.get();

    byte[] plaintext2 = aliceRatchet.ratchetDecrypt(aliceState, aliceReceived);
    assertEquals("Salut Alice", new String(plaintext2, StandardCharsets.UTF_8));

    // === PHASE 6 : Vérifications de l'état des sessions ===

    // Alice a envoyé 1 message dans la première chaîne
    assertEquals(1, aliceState.getSendingMessageNumber());

    // Bob a envoyé 1 message (après DH ratchet step, donc sendingMessageNumber = 1)
    assertEquals(1, bobState.getSendingMessageNumber());

    // Le rootKey a évolué (n'est plus le masterSecret original)
    assertFalse(Arrays.equals(aliceX3DHResult.getMasterSecret(), aliceState.getRootKey()));
    assertFalse(Arrays.equals(bobMasterSecret, bobState.getRootKey()));
}
```

### Méthodes utilitaires du test

```java
private KeyBundle buildKeyBundle(String username, IdentityKeyPair identity,
        byte[][] spk, byte[] spkSig, byte[][][] opks) {
    return KeyBundle.builder()
        .username(username)
        .identityKey(Base64.getEncoder().encodeToString(identity.getX25519PublicKey()))
        .identitySigningKey(Base64.getEncoder().encodeToString(identity.getEd25519PublicKey()))
        .signedPreKey(Base64.getEncoder().encodeToString(spk[1]))
        .signedPreKeyId(0)
        .signedPreKeySignature(Base64.getEncoder().encodeToString(spkSig))
        .oneTimePreKeys(Arrays.stream(opks)
            .map(kp -> Base64.getEncoder().encodeToString(kp[1]))
            .collect(Collectors.toList()))
        .build();
}

private StompSession connectStomp(String serverUrl) throws Exception {
    WebSocketStompClient client = new WebSocketStompClient(
        new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
    client.setMessageConverter(new MappingJackson2MessageConverter());
    return client.connectAsync(serverUrl + "/ws", new StompSessionHandlerAdapter() {})
        .get(5, TimeUnit.SECONDS);
}
```

### Ce que les logs doivent montrer

```
=== Alice: Génération des clés ===
  Clé d'identité XEdDSA | X25519=0x7a3f89c1...d2e4f6a8
  Signed Pre-Key X25519  | pub=0xa1b2c3d4...e5f6a7b8
  20 One-Time Pre-Keys générées

=== Bob: Génération des clés ===
  (idem)

=== Alice: X3DH vers Bob ===
  DH1 = ECDH(IK_A, SPK_B) | secret=0x...
  DH2 = ECDH(EK_A, IK_B) | secret=0x...
  DH3 = ECDH(EK_A, SPK_B) | secret=0x...
  DH4 = ECDH(EK_A, OPK_B) | secret=0x...
  Master Secret X3DH | SK=0x...

=== Alice: Init Double Ratchet ===
  Session Alice initialisée | rootKey=0x... | sendingChainKey=0x...

=== Alice: Chiffrement "Bonjour Bob" ===
  Ratchet symétrique ENVOI | chainKey[avant]=0x... | messageKey=0x...
  Double Ratchet ENCRYPT | msgNum=0

=== SERVEUR: Relai ===
  RELAI [Alice] → [Bob] | ciphertext: 44 bytes | messageNumber: 0
  Le serveur NE PEUT PAS lire ce message

=== Bob: X3DH (côté récepteur) ===
  DH1 = ECDH(SPK_B, IK_A) [côté Bob] | secret=0x...
  (même masterSecret qu'Alice)

=== Bob: Init Double Ratchet ===
  Session Bob initialisée | rootKey=masterSecret

=== Bob: Déchiffrement ===
  DH Ratchet Step (nouveau ratchet key détecté)
  Ratchet symétrique RECEPTION
  Double Ratchet DECRYPT | msgNum=0 → "Bonjour Bob" ✓

=== Bob: Chiffrement "Salut Alice" ===
  (ratchet symétrique + encrypt)

=== SERVEUR: Relai ===
  RELAI [Bob] → [Alice] | ciphertext: 48 bytes

=== Alice: Déchiffrement ===
  DH Ratchet Step (nouveau ratchet key de Bob)
  Double Ratchet DECRYPT → "Salut Alice" ✓
```

---

## Ordre d'implémentation

| # | Tâche | Fichiers | Dépend de |
|---|-------|----------|-----------|
| 1 | `KeyRegistrationService` | `client/service/KeyRegistrationService.java` | KeyManager (existant) |
| 2 | `ServerConnectionService` | `client/service/ServerConnectionService.java` | — |
| 3 | `MessageService` | `client/service/MessageService.java` | 1, 2, X3DHService, DoubleRatchetService |
| 4 | Améliorer `MessageRelayHandler` | `server/handler/MessageRelayHandler.java` | UserRegistryService (existant) |
| 5 | `RestTemplate` bean config | `client/config/RestTemplateConfig.java` | — |
| 6 | **Validation** : `mvn compile` | — | 1-5 |
| 7 | `EndToEndEncryptionTest` | `client/test/.../EndToEndEncryptionTest.java` | 1-5 |
| 8 | **Validation** : test passe | — | 7 |

---

## Points d'attention techniques

### Import du modèle KeyBundle (client vs serveur)

Le client et le serveur ont chacun leur propre `KeyBundle`. Ils sont identiques mais dans
des packages différents. Pour le test d'intégration qui utilise le serveur ET les services
crypto du client, on utilise le modèle du client (`cm.iusjc.signalchat.client.model.KeyBundle`).
Le serveur désérialise le JSON correctement grâce à Jackson (même structure).

### Thread-safety

- `sessions` (ConcurrentHashMap) : thread-safe pour les accès concurrents
- `chatMessages` (Collections.synchronizedList) : thread-safe
- Les `SessionState` sont modifiés en place par le Double Ratchet.
  Si deux messages arrivent simultanément du même expéditeur, il y aura un problème.
  → Solution : synchroniser sur la SessionState par utilisateur.

### Gestion d'erreur du RestTemplate

Si `GET /api/keys/bundle/{username}` retourne 404, le `RestTemplate` lance une
`HttpClientErrorException`. Le `MessageService` doit catcher cette exception et
informer l'utilisateur que le destinataire n'est pas enregistré.

### Ordre d'enregistrement des clés

Les deux clients doivent avoir enregistré leurs clés AVANT d'envoyer un message.
Dans le test, c'est garanti par l'ordre séquentiel. En production (deux JVM séparées),
le `KeyRegistrationService` s'exécute au démarrage, donc les clés sont disponibles
dès que le client est prêt.

### Format du EncryptedMessage serveur vs client

Le serveur a son propre `EncryptedMessage` dans `server.model`. Les deux classes
doivent avoir exactement les mêmes champs pour que la sérialisation Jackson fonctionne
correctement. Vérifier que le serveur a bien les champs `senderIdentitySigningKey`
(ajouté dans la Phase 3 crypto).

---

## Risques et mitigations

| Risque | Impact | Mitigation |
|--------|--------|------------|
| Timeout connexion WebSocket dans le test | Test flaky | `CountDownLatch` avec timeout de 5s + message d'erreur explicite |
| Désérialisation JSON échoue (champs manquants) | Message perdu | Vérifier que les modèles client et serveur sont identiques |
| OPK déjà consommée par le serveur | X3DH échoue | Le serveur consomme une OPK à chaque `getKeyBundle()` — le test ne doit appeler `getKeyBundle` qu'une fois par session |
| Race condition sur SessionState | Corruption crypto | Synchroniser les accès par utilisateur |
| Port déjà occupé dans le test | Test échoue | `RANDOM_PORT` dans `@SpringBootTest` |
