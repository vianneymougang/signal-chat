# 🔐 SignalChat — Guide complet de setup & prompts Claude Code

## Table des matières

1. [Configuration Spring Initializr](#1-configuration-spring-initializr)
2. [Arborescence du projet](#2-arborescence-du-projet)
3. [Dépendances manuelles à ajouter](#3-dépendances-manuelles-à-ajouter)
4. [Add-ons & outils de développement](#4-add-ons--outils-de-développement)
5. [Conseils stratégiques](#5-conseils-stratégiques)
6. [Prompts Claude Code](#6-prompts-claude-code)

---

## 1. Configuration Spring Initializr

Rends-toi sur [start.spring.io](https://start.spring.io) et configure :

| Paramètre        | Valeur                  |
| ---------------- | ----------------------- |
| **Project**      | Maven                   |
| **Language**     | Java                    |
| **Spring Boot**  | 3.5.x (dernière stable) |
| **Group**        | cm.iusjc.signalchat     |
| **Artifact**     | signal-chat             |
| **Name**         | SignalChat              |
| **Package name** | cm.iusjc.signalchat     |
| **Packaging**    | Jar                     |
| **Java**         | 21                      |

### Dépendances à cocher dans Initializr :

- **Spring Web** — pour le REST controller (KeyBundleController)
- **Spring WebSocket** — pour la communication temps réel (STOMP)
- **Thymeleaf** — pour servir l'interface web locale
- **Spring Boot DevTools** — rechargement à chaud pendant le dev
- **Lombok** — réduction du boilerplate (getters, setters, builders, loggers)
- **Spring Configuration Processor** — auto-complétion des propriétés dans `application.yml`

> ⚠️ **Ne PAS cocher** Spring Security pour l'instant — ça complexifierait le setup. On gère l'authentification au niveau crypto.

---

## 2. Arborescence du projet

Voici la structure **complète** à viser. Tu vas générer le squelette Spring Initializr, puis Claude Code organisera les fichiers dedans.

```
signal-chat/
├── pom.xml
├── README.md
├── docs/
│   ├── PROTOCOL.md              # Documentation du protocole implémenté
│   ├── DEMO-SCRIPT.md           # Script de démo pour le TP
│   └── architecture.png         # Diagramme d'architecture
│
├── server/                      # Module SERVEUR (relai + key store)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/cm/iusjc/signalchat/server/
│       │   │   ├── ServerApplication.java
│       │   │   │
│       │   │   ├── config/
│       │   │   │   ├── WebSocketConfig.java          # Configuration STOMP
│       │   │   │   └── CorsConfig.java
│       │   │   │
│       │   │   ├── controller/
│       │   │   │   └── KeyBundleController.java       # REST: register/fetch key bundles
│       │   │   │
│       │   │   ├── handler/
│       │   │   │   └── MessageRelayHandler.java       # WebSocket: relayer les messages chiffrés
│       │   │   │
│       │   │   ├── model/
│       │   │   │   ├── KeyBundle.java                 # Pre-Key Bundle (clés publiques)
│       │   │   │   ├── EncryptedMessage.java          # Message chiffré en transit
│       │   │   │   └── UserRegistration.java
│       │   │   │
│       │   │   └── service/
│       │   │       ├── KeyStoreService.java           # Stockage en mémoire des clés publiques
│       │   │       └── UserRegistryService.java       # Mapping username → session
│       │   │
│       │   └── resources/
│       │       └── application.yml                    # port: 9000
│       │
│       └── test/java/cm/iusjc/signalchat/server/
│           └── KeyBundleControllerTest.java
│
├── client/                      # Module CLIENT (crypto + UI)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/cm/iusjc/signalchat/client/
│       │   │   ├── ClientApplication.java
│       │   │   │
│       │   │   ├── config/
│       │   │   │   └── WebSocketClientConfig.java
│       │   │   │
│       │   │   ├── controller/
│       │   │   │   ├── ChatController.java            # Sert la page web locale
│       │   │   │   └── ChatWebSocketController.java   # WebSocket local (UI ↔ backend client)
│       │   │   │
│       │   │   ├── crypto/
│       │   │   │   ├── KeyManager.java                # Génération Curve25519 (Identity, Signed Pre, OTP)
│       │   │   │   ├── X3DHService.java               # Extended Triple Diffie-Hellman handshake
│       │   │   │   ├── DoubleRatchetService.java      # Double Ratchet Algorithm
│       │   │   │   ├── CryptoEngine.java              # AES-256-CBC + HMAC-SHA256 + HKDF
│       │   │   │   ├── SessionState.java              # État d'une session (Root/Chain/Message keys)
│       │   │   │   ├── SessionStore.java              # Map des sessions par utilisateur
│       │   │   │   ├── RatchetKeyPair.java            # Wrapper pour paire de clés DH ratchet
│       │   │   │   └── SenderKeyManager.java          # Gestion Sender Keys (groupes) [NICE TO HAVE]
│       │   │   │
│       │   │   ├── model/
│       │   │   │   ├── KeyBundle.java                 # Miroir du modèle serveur
│       │   │   │   ├── EncryptedMessage.java
│       │   │   │   ├── CryptoEvent.java               # Événement crypto pour le monitoring
│       │   │   │   ├── MessageEnvelope.java            # Enveloppe : header + ciphertext + HMAC
│       │   │   │   └── ChatMessage.java               # Message en clair (côté UI)
│       │   │   │
│       │   │   ├── monitoring/
│       │   │   │   ├── CryptoLogger.java              # Capture chaque opération crypto
│       │   │   │   └── CryptoEventPublisher.java      # Push les événements vers l'UI via WebSocket
│       │   │   │
│       │   │   └── service/
│       │   │       ├── ServerConnectionService.java   # Connexion WebSocket vers le serveur central
│       │   │       ├── MessageService.java            # Orchestration envoi/réception
│       │   │       └── KeyRegistrationService.java    # Enregistrement des clés auprès du serveur
│       │   │
│       │   └── resources/
│       │       ├── application.yml                    # port dynamique, server.url configurable
│       │       ├── static/
│       │       │   ├── css/
│       │       │   │   └── styles.css                 # Chat + panneau monitoring
│       │       │   └── js/
│       │       │       ├── chat.js                    # Logique chat (envoi, réception, affichage)
│       │       │       ├── monitoring.js              # Affichage temps réel des événements crypto
│       │       │       └── websocket.js               # Connexion SockJS/STOMP vers le backend client
│       │       └── templates/
│       │           └── chat.html                      # Page Thymeleaf : chat + panneau latéral
│       │
│       └── test/java/cm/iusjc/signalchat/client/
│           ├── crypto/
│           │   ├── KeyManagerTest.java
│           │   ├── X3DHServiceTest.java
│           │   ├── DoubleRatchetServiceTest.java
│           │   └── CryptoEngineTest.java
│           └── integration/
│               └── EndToEndEncryptionTest.java        # Test Alice ↔ Bob complet
│
└── scripts/
    ├── start-server.sh          # java -jar server.jar
    ├── start-client.sh          # java -jar client.jar --username=Alice --server=192.168.x.x:9000
    └── demo-scenario.sh         # Lance serveur + 2 clients pour la démo
```

### Pourquoi un projet multi-modules Maven ?

Le serveur et le client sont deux applications Spring Boot **distinctes** mais partagent des modèles (`KeyBundle`, `EncryptedMessage`). Le POM parent permet de les builder ensemble et de partager les dépendances.

**POM parent** (`signal-chat/pom.xml`) :

```xml
<modules>
    <module>server</module>
    <module>client</module>
</modules>
```

---

## 3. Dépendances manuelles à ajouter

Après le download Spring Initializr, ajoute ces dépendances dans les `pom.xml` :

### POM Parent — dépendances communes

```xml
<!-- Bouncy Castle — la pierre angulaire crypto -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.80</version>
</dependency>

<!-- Jackson — sérialisation JSON (normalement déjà inclus via Spring Web) -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### POM Client — dépendances spécifiques

```xml
<!-- SockJS client pour la connexion WebSocket vers le serveur -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-websocket</artifactId>
</dependency>

<!-- WebSocket Messaging (STOMP) -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-messaging</artifactId>
</dependency>

<!-- Webjars pour le frontend (SockJS + STOMP côté navigateur) -->
<dependency>
    <groupId>org.webjars</groupId>
    <artifactId>sockjs-client</artifactId>
    <version>1.5.1</version>
</dependency>
<dependency>
    <groupId>org.webjars</groupId>
    <artifactId>stomp-websocket</artifactId>
    <version>2.3.4</version>
</dependency>
```

### Pourquoi Bouncy Castle et PAS libsignal-java ?

|                | Bouncy Castle                                   | libsignal-java                            |
| -------------- | ----------------------------------------------- | ----------------------------------------- |
| **Contrôle**   | On code chaque étape (X3DH, Ratchet) nous-mêmes | Boîte noire                               |
| **Monitoring** | On peut logger chaque opération intermédiaire   | API haut niveau, pas d'accès aux internes |
| **Pédagogie**  | Le code EST la documentation du protocole       | Difficile d'expliquer ce qui se passe     |
| **Complexité** | Plus de code à écrire                           | Prêt à l'emploi                           |

Pour une démo pédagogique, Bouncy Castle est le bon choix.

---

## 4. Add-ons & outils de développement

### IDE et extensions

Si tu utilises **VS Code** (logique avec Claude Code) :

- **Extension Pack for Java** (Microsoft) — indispensable
- **Spring Boot Extension Pack** — dashboard, live reload
- **Lombok Annotations Support** — pour que l'IDE reconnaisse @Slf4j, @Builder, etc.
- **Thunder Client** ou **REST Client** — tester les endpoints REST du serveur

Si tu utilises **IntelliJ** :

- Lombok plugin (activé par défaut dans les versions récentes)
- Enable annotation processing dans Settings → Build → Compiler

### Outils réseau pour le TP

- **Wireshark** (optionnel mais impressionnant) — capturer les paquets réseau pendant la démo pour montrer que les messages sont bien chiffrés en transit
- `ipconfig` / `ifconfig` — pour connaître les IPs locales des machines

### Configuration application.yml

**Serveur** (`server/src/main/resources/application.yml`) :

```yaml
server:
  port: 9000

logging:
  level:
    cm.iusjc.signalchat: DEBUG
```

**Client** (`client/src/main/resources/application.yml`) :

```yaml
server:
  port: ${CLIENT_PORT:8081}

signalchat:
  username: ${USERNAME:Alice}
  server-url: ${SERVER_URL:http://localhost:9000}
  one-time-prekeys-count: 20

logging:
  level:
    cm.iusjc.signalchat: DEBUG
```

Lancement : `java -jar client.jar --signalchat.username=Bob --server.port=8082 --signalchat.server-url=http://192.168.1.10:9000`

---

## 5. Conseils stratégiques

### Gestion du temps (< 1 semaine)

1. **Ne PAS coder les groupes (Sender Keys) en priorité.** Le 1-to-1 avec X3DH + Double Ratchet + monitoring est déjà une démo killer. Tu pourras expliquer les groupes à l'oral.

2. **Commence par les tests crypto, pas par l'UI.** Un `X3DHServiceTest` qui vérifie qu'Alice et Bob arrivent au même Master Secret est plus critique qu'un joli CSS.

3. **Utilise des clés hex tronquées dans le monitoring.** Affiche `0x7a3f...c2b1` (les 4 premiers + 4 derniers bytes) au lieu de la clé complète — c'est plus lisible et ça évite le scroll horizontal.

### Pièges techniques à anticiper

4. **Curve25519 avec Bouncy Castle** — utilise `X25519KeyPairGenerator` et `X25519Agreement`. Ne PAS utiliser les anciennes API `djb` deprecated. Voici l'import correct :

```java
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.agreement.X25519Agreement;
```

5. **Sérialisation des clés publiques** — les clés Curve25519 font exactement 32 bytes. Encode-les en **Base64** pour le transport JSON, pas en Hex (plus compact).

6. **Gestion des messages hors-ordre** — le Double Ratchet supporte les messages qui arrivent dans le désordre grâce aux compteurs de messages (`message_number`) et au stockage des Message Keys sautées. Pour la démo, implémente le cas simple (messages dans l'ordre) d'abord, puis ajoute la gestion du désordre si tu as le temps.

7. **Le premier message est spécial** — quand Alice envoie son premier message à Bob, elle inclut dans l'enveloppe sa clé d'identité publique, sa clé éphémère publique, et l'ID de la One-Time Pre-Key de Bob qu'elle a utilisée. Bob a besoin de ces infos pour recalculer le même X3DH secret de son côté.

8. **Un JAR par rôle** — même si le code est dans un monorepo Maven, build deux JARs séparés (`server.jar` et `client.jar`). Sur chaque machine du TP, tu copieras uniquement le JAR nécessaire.

9. **Prépare un script de démo** — écris à l'avance le scénario exact que tu suivras pendant le TP : "D'abord je lance le serveur, puis Alice, puis Bob, puis j'envoie tel message, et on regarde le panneau crypto..." Ça évitera le stress et les trous de mémoire le jour J.

10. **Teste sur 2 machines avant le TP.** Le LAN peut avoir des surprises (pare-feu, ports bloqués). Utilise le hotspot de ton téléphone si nécessaire.

---

## 6. Prompts Claude Code

### Configuration préalable de Claude Code

Avant de lancer les prompts, crée un fichier **CLAUDE.md** à la racine du projet pour donner du contexte persistant à Claude Code :

```markdown
# SignalChat — Contexte Projet

## Objectif

Application de messagerie chiffrée de bout en bout (E2EE) démontrant le protocole Signal,
pour un TP de sécurité informatique en 4e année à l'iusjc, Yaoundé.

## Architecture

- **Serveur** (Spring Boot, port 9000) : stocke les clés publiques, relaie les messages chiffrés via WebSocket STOMP. Ne voit JAMAIS le contenu en clair.
- **Client** (Spring Boot, port configurable) : fait toute la crypto en local (X3DH + Double Ratchet), sert une UI web locale avec chat + panneau de monitoring crypto.

## Stack

- Java 17, Spring Boot 3.4.x, Maven multi-modules
- Bouncy Castle (bcprov-jdk18on) pour les primitives crypto (Curve25519, AES-256-CBC, HMAC-SHA256, HKDF)
- WebSocket STOMP + SockJS pour le temps réel
- Thymeleaf + vanilla JS pour le frontend

## Protocole implémenté

1. **X3DH** (Extended Triple Diffie-Hellman) pour l'établissement de session
2. **Double Ratchet** (DH Ratchet + Symmetric Ratchet) pour le chiffrement par message
3. **AES-256-CBC** pour le chiffrement symétrique des messages
4. **HMAC-SHA256** pour l'authentification des messages
5. **HKDF** pour la dérivation des clés

## Conventions

- Noms de variables crypto en anglais (rootKey, chainKey, messageKey, etc.)
- Commentaires en français
- Logging détaillé de chaque opération crypto (pour le monitoring)
- Clés affichées en hex tronqué : premiers 8 + derniers 8 caractères

## Priorité

1. Crypto fonctionnelle (X3DH + Double Ratchet) — CRITIQUE
2. Communication réseau (WebSocket) — CRITIQUE
3. Interface web + monitoring — IMPORTANT
4. Groupes (Sender Keys) — NICE TO HAVE
```

---

### PROMPT 1 — Squelette du projet et couche crypto fondamentale

> **Modèle recommandé : Opus pour la réflexion, Sonnet pour l'exécution**

```
Je construis "SignalChat", une appli de messagerie E2EE qui implémente le protocole Signal
(X3DH + Double Ratchet) pour un TP de sécurité. L'architecture est Client-Serveur en Java
Spring Boot 3.5.x avec Maven multi-modules.

## Ce que je veux maintenant

Crée le squelette complet du projet Maven multi-modules avec :

### 1. POM Parent (signal-chat/pom.xml)
- groupId: cm.iusjc.signalchat
- modules: server, client
- Java 21, Spring Boot 3.5.x
- Dépendances communes : Bouncy Castle (bcprov-jdk18on 1.80), Lombok, Jackson

### 2. Module Server
- Spring Boot app sur le port 9000
- WebSocketConfig avec STOMP (endpoint /ws, topic /topic/messages, app prefix /app)
- KeyBundleController (REST) :
  - POST /api/keys/register — enregistre un KeyBundle
  - GET /api/keys/bundle/{username} — retourne le Pre-Key Bundle d'un user
  - GET /api/keys/users — liste les utilisateurs connectés
- MessageRelayHandler (WebSocket) : reçoit un EncryptedMessage sur /app/send, le relaie
  au bon destinataire sur /topic/messages/{username}
- KeyStoreService : stockage en mémoire (ConcurrentHashMap)
- UserRegistryService : mapping username → session WebSocket active
- Modèles : KeyBundle (identityKey, signedPreKey, signedPreKeySignature,
  oneTimePreKeys[], username), EncryptedMessage (sender, recipient, ciphertext, iv,
  hmac, senderIdentityKey, senderEphemeralKey, usedOneTimePreKeyId, messageNumber,
  previousChainLength, senderRatchetKey), UserRegistration

### 3. Module Client — Couche Crypto (LE PLUS IMPORTANT)
Implémente FIDÈLEMENT le protocole Signal avec Bouncy Castle :

#### KeyManager.java
- generateIdentityKeyPair() → Curve25519 via X25519KeyPairGenerator
- generateSignedPreKey(identityKeyPair) → Curve25519 + signature Ed25519
- generateOneTimePreKeys(count) → lot de paires Curve25519
- Chaque méthode doit logger (via CryptoLogger) les clés générées en hex tronqué

#### CryptoEngine.java
- hkdf(inputKeyMaterial, salt, info, outputLength) → HKDF-SHA256
- encryptAES(plaintext, key, iv) → AES-256-CBC
- decryptAES(ciphertext, key, iv) → AES-256-CBC
- hmacSHA256(key, data) → byte[32]
- verifyHMAC(key, data, expectedMac) → boolean
- generateIV() → 16 bytes aléatoires
- Chaque opération doit être loggée avec entrées et sorties en hex tronqué

#### X3DHService.java
Implémente EXACTEMENT le protocole X3DH :
- initiateHandshake(localIdentityKey, remoteKeyBundle) :
  1. Génère une clé éphémère (EK_A)
  2. Calcule DH1 = ECDH(IK_A_private, SPK_B_public)
  3. Calcule DH2 = ECDH(EK_A_private, IK_B_public)
  4. Calcule DH3 = ECDH(EK_A_private, SPK_B_public)
  5. Calcule DH4 = ECDH(EK_A_private, OPK_B_public) si disponible
  6. masterSecret = HKDF(DH1 || DH2 || DH3 || DH4)
  7. Dérive (rootKey, chainKey) à partir du masterSecret
  8. Retourne SessionState initial + les infos à envoyer à Bob
  → Logger CHAQUE DH intermédiaire et le master secret final

- processHandshake(localIdentityKey, localSignedPreKey, localOneTimePreKey,
    senderIdentityKey, senderEphemeralKey) :
  Même calcul côté Bob (recalcule les 4 DH dans le bon ordre)

#### DoubleRatchetService.java
Implémente EXACTEMENT le Double Ratchet Algorithm :
- ratchetEncrypt(sessionState, plaintext) :
  1. Dérive messageKey = HMAC-SHA256(chainKey, 0x01)
  2. Avance chainKey = HMAC-SHA256(chainKey, 0x02)
  3. Chiffre avec AES-256-CBC(plaintext, messageKey)
  4. Calcule HMAC du ciphertext
  5. Incrémente le compteur de messages
  → Logger : chainKey avant/après, messageKey, ciphertext

- ratchetDecrypt(sessionState, encryptedMessage) :
  1. Si nouveau ratchet public key reçu → dhRatchetStep()
  2. Dérive la messageKey correspondante
  3. Vérifie le HMAC
  4. Déchiffre le message

- dhRatchetStep(sessionState, remoteRatchetKey) :
  1. Calcule nouveau DH secret avec la nouvelle clé distante
  2. Dérive nouvelle (rootKey, receivingChainKey) via HKDF
  3. Génère nouvelle paire DH locale
  4. Calcule second DH secret
  5. Dérive nouvelle (rootKey, sendingChainKey) via HKDF
  → Logger CHAQUE transition de rootKey et chainKey

#### SessionState.java
- rootKey, sendingChainKey, receivingChainKey
- localRatchetKeyPair (paire DH courante)
- remoteRatchetPublicKey
- sendingMessageNumber, receivingMessageNumber
- previousSendingChainLength

#### CryptoLogger.java
- Classe utilitaire qui capture chaque événement crypto
- Méthode logEvent(CryptoEvent) qui publie vers l'UI via WebSocket
- CryptoEvent contient : timestamp, type (KEY_GEN, X3DH_DH1, X3DH_DH2, ...,
  RATCHET_STEP, ENCRYPT, DECRYPT, HMAC_VERIFY), inputHex, outputHex, description

### 4. Tests unitaires essentiels
- KeyManagerTest : vérifier que les clés générées sont de la bonne taille (32 bytes)
- CryptoEngineTest : chiffrer puis déchiffrer et vérifier qu'on retrouve le plaintext
- X3DHServiceTest : Alice et Bob calculent indépendamment le même masterSecret
- DoubleRatchetServiceTest : Alice chiffre, Bob déchiffre → plaintext identique ;
  puis Bob chiffre, Alice déchiffre (vérifier le DH ratchet step)

## Contraintes
- Utiliser EXCLUSIVEMENT Bouncy Castle pour la crypto (pas de libsignal-java)
- Logger CHAQUE opération crypto intermédiaire via CryptoLogger
- Tous les commentaires de code en français
- Noms de variables crypto en anglais
- Clés affichées en hex tronqué (8 premiers + 8 derniers caractères hex)
- Les tests doivent PASSER — c'est la validation que le protocole fonctionne

Commence par le POM parent et le module server, puis enchaîne avec toute la couche
crypto du client et les tests. Ne fais PAS l'UI pour l'instant.
```

---

### PROMPT 2 — Communication réseau (WebSocket)

```
Le squelette crypto est en place. Maintenant, connectons les machines.

## Ce que je veux

### Côté Client — Communication avec le serveur

#### KeyRegistrationService.java
- Au démarrage du client :
  1. Génère toutes les clés via KeyManager
  2. Envoie un POST /api/keys/register au serveur avec le KeyBundle complet
  3. Logge "Clés enregistrées auprès du serveur"

#### ServerConnectionService.java
- Connexion WebSocket STOMP vers le serveur central (signalchat.server-url)
- S'abonne à /topic/messages/{username} pour recevoir les messages
- Méthode send(EncryptedMessage) qui publie sur /app/send
- Gestion de la reconnexion automatique

#### MessageService.java — le chef d'orchestre
- sendMessage(recipientUsername, plaintext) :
  1. Vérifie s'il existe une session avec ce destinataire
  2. Si NON → récupère le KeyBundle via GET /api/keys/bundle/{recipient},
     puis X3DHService.initiateHandshake() pour créer la session
  3. DoubleRatchetService.ratchetEncrypt(session, plaintext)
  4. Construit le MessageEnvelope avec toutes les métadonnées
  5. Envoie via ServerConnectionService.send()

- onMessageReceived(EncryptedMessage) :
  1. Vérifie s'il existe une session avec cet expéditeur
  2. Si NON (premier message) → X3DHService.processHandshake() avec les infos
     de l'enveloppe pour créer la session
  3. DoubleRatchetService.ratchetDecrypt(session, message)
  4. Publie le message déchiffré vers l'UI locale

### Côté Serveur — Relai pur

#### Améliore MessageRelayHandler
- Reçoit un EncryptedMessage sur /app/send
- Logge (côté serveur) : "Relai message chiffré de {sender} vers {recipient}
  — taille ciphertext: {n} bytes — le serveur NE PEUT PAS lire ce message"
- Redirige vers /topic/messages/{recipient}
- Si le destinataire n'est pas connecté, stocke en mémoire (file d'attente)

### Test d'intégration
- EndToEndEncryptionTest.java :
  1. Démarre le serveur (port aléatoire)
  2. Crée deux instances MessageService (Alice et Bob) connectées au serveur
  3. Alice enregistre ses clés, Bob enregistre ses clés
  4. Alice envoie "Bonjour Bob" → vérifie que Bob reçoit "Bonjour Bob"
  5. Bob répond "Salut Alice" → vérifie qu'Alice reçoit "Salut Alice"
  6. Vérifie que les sessionStates ont bien avancé (ratchet step count)

Les logs doivent montrer clairement le flux : génération de clés → X3DH → ratchet →
encrypt → transit chiffré sur le serveur → decrypt.
```

---

### PROMPT 3 — Interface web (Chat + Monitoring)

```
La crypto et le réseau fonctionnent. Maintenant, l'interface.

## Ce que je veux

### chat.html (Thymeleaf)

Une page responsive avec deux colonnes :

#### Colonne gauche — Chat (60% largeur)

- En-tête : "🔒 SignalChat — {username}" + indicateur de connexion (●/○)
- Liste des utilisateurs en ligne (récupérée via GET /api/keys/users proxied par le client)
- Zone de messages avec bulles style WhatsApp :
  - Messages envoyés → bulle verte à droite
  - Messages reçus → bulle blanche à gauche
  - Chaque bulle affiche : texte, heure, icône cadenas (🔒)
- Input de message + bouton Envoyer

#### Colonne droite — Monitoring Crypto (40% largeur)

- Panneau scrollable avec fond sombre (style terminal)
- Affichage en temps réel des CryptoEvents reçus via WebSocket local
- Chaque événement est stylé selon son type :
  - 🔑 KEY_GEN → bleu
  - 🤝 X3DH\_\* → violet
  - 🔄 RATCHET\_\* → orange
  - 🔐 ENCRYPT → vert
  - 🔓 DECRYPT → cyan
  - ✅ HMAC_VERIFY → gris
- Format d'un événement :

[14:32:05.123] 🔐 ENCRYPT
Message Key: 0x7a3f...c2b1
Plaintext: "Bonjour" → 0x426f6e...
IV: 0x3c4d...8e9f
Ciphertext: 0x9f8e...7d6c
HMAC: 0x5a6b...3e2d ✅

- Bouton "Effacer les logs"
- Bouton "Exporter les logs" (télécharge un fichier .txt)

### WebSocket local (Client backend → UI)

- ChatWebSocketController qui publie sur /topic/crypto-events les CryptoEvents
- Le JS se connecte via SockJS/STOMP au backend local et affiche les événements

### chat.js + monitoring.js + websocket.js

- chat.js : gestion des messages (envoi via STOMP, affichage des bulles)
- monitoring.js : réception et formatage des CryptoEvents dans le panneau
- websocket.js : connexion SockJS/STOMP unique avec routing vers chat et monitoring

### styles.css

- Design moderne et professionnel, inspiré WhatsApp (fond gris clair, bulles vertes)
- Panneau monitoring avec fond #1e1e1e, police monospace, texte vert/bleu/orange
- Responsive : sur petit écran, le monitoring passe en dessous
- Animations subtiles : nouveau message (slide-in), nouvel événement crypto (fade-in)

## Contraintes

- PAS de framework JS (React, Vue...) — vanilla JS + STOMP uniquement
- L'UI doit fonctionner sur Chrome, Firefox, Edge
- Le panneau monitoring doit auto-scroller vers le bas à chaque nouvel événement
- Les clés sont affichées en hex tronqué (8+8 chars) avec un tooltip pour voir la clé complète
```

---

### PROMPT 4 — Polish, scripts de démo, et documentation

````
L'application fonctionne. Finalisons pour le TP.

## Ce que je veux

### 1. Scripts de lancement

Crée dans le dossier scripts/ :

- start-server.sh :

```bash
  #!/bin/bash
  echo "🚀 Démarrage du serveur SignalChat sur le port 9000..."
  java -jar server/target/server-*.jar
```

- start-client.sh :

```
  #!/bin/bash
  # Usage: ./start-client.sh Alice 8081 192.168.1.10
  USERNAME=${1:-Alice}
  PORT=${2:-8081}
  SERVER=${3:-localhost}
  echo "🚀 Démarrage du client $USERNAME sur le port $PORT, serveur: $SERVER:9000"
  java -jar client/target/client-*.jar \
    --signalchat.username=$USERNAME \
    --server.port=$PORT \
    --signalchat.server-url=http://$SERVER:9000
```

- Équivalents .bat pour Windows

### 2. Build en un clic

Ajoute au POM parent un profil Maven qui package les deux JARs en fat JARs exécutables.
Commande : `mvn clean package -DskipTests`

### 3. Documentation

#### README.md

- Prérequis : Java 17+, Maven 3.8+
- Comment builder : `mvn clean package`
- Comment lancer : serveur puis clients
- Scénario de démo rapide (5 minutes)
- Screenshots de l'interface

#### docs/DEMO-SCRIPT.md

Script détaillé minute par minute pour le TP :

1. (0:00) Explication théorique rapide du protocole Signal
2. (2:00) Lancement du serveur — montrer qu'il est vide
3. (3:00) Lancement d'Alice — montrer la génération de clés dans le monitoring
4. (4:00) Lancement de Bob — idem
5. (5:00) Alice envoie "Bonjour Bob" — montrer dans le panneau d'Alice :
   X3DH complet, premier ratchet, chiffrement
6. (6:00) Côté serveur : montrer le log "message relayé, serveur ne peut pas lire"
7. (7:00) Côté Bob : montrer le X3DH inverse, déchiffrement, message en clair
8. (8:00) Bob répond — montrer le DH Ratchet qui avance
9. (9:00) Échange de plusieurs messages — montrer que chaque message a une clé
   différente (forward secrecy)
10. (10:00) Optionnel : ouvrir Wireshark, montrer le trafic chiffré sur le réseau
11. (12:00) Questions / discussion

### 4. Gestion d'erreurs robuste

- Si le serveur n'est pas joignable → message clair dans l'UI "Serveur non disponible"
- Si un utilisateur se déconnecte → notifier l'autre dans le chat
- Si la crypto échoue → afficher l'erreur dans le panneau monitoring en rouge

### 5. Petit bonus : compteur de sécurité

Dans l'UI, affiche en permanence :

- Nombre de messages échangés dans cette session
- Nombre de ratchet steps effectués
- Nombre de clés uniques générées
- "Chaque message utilise une clé unique qui est immédiatement détruite"

````

---

## Remarques sur l'utilisation de Claude Code

### Workflow recommandé

1. **Lance chaque prompt séparément**, dans l'ordre (1 → 2 → 3 → 4)
2. **Vérifie que les tests passent** après le prompt 1 avant de passer au prompt 2
3. Si Claude Code fait une erreur crypto, corrige-le avec un message ciblé :

```

Le X3DH ne produit pas le même secret des deux côtés.
Vérifie l'ordre des paramètres dans les 4 ECDH :

- DH1 doit être ECDH(IK_A_private, SPK_B_public) côté Alice
  et ECDH(SPK_B_private, IK_A_public) côté Bob
  Les deux doivent donner le même résultat (propriété commutative de ECDH).
  Lance X3DHServiceTest pour vérifier.

```

### Commandes utiles Claude Code

```bash
# Lancer les tests crypto uniquement
claude "Lance les tests du package crypto : mvn test -pl client -Dtest='cm.iusjc.signalchat.client.crypto.*Test'"

# Debug un test spécifique
claude "Le X3DHServiceTest échoue. Analyse le stacktrace et corrige."

# Vérifier le build complet
claude "Build le projet complet : mvn clean package. Corrige les erreurs de compilation."
```

### Fichier .claude/settings.json recommandé

```json
{
  "permissions": {
    "allow": ["Bash(mvn *)", "Bash(java *)", "Bash(cat *)", "Bash(ls *)"]
  }
}
```
