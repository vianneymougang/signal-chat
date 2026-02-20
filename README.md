# SignalChat — Messagerie E2EE avec le protocole Signal

Application de messagerie chiffrée de bout en bout (E2EE) démontrant le protocole Signal
(X3DH + Double Ratchet), développée dans le cadre d'un TP de sécurité informatique
en 4e année à l'IUSJC, Yaoundé.

Le serveur ne voit jamais le contenu en clair : il stocke uniquement les clés publiques
et relaie des blobs chiffrés. Toute la cryptographie s'exécute localement sur chaque client.

---

## Prérequis

- **Java 17+** (Java 21 recommandé)
- **Maven 3.8+**
- Un terminal (bash/zsh sur Linux/macOS, PowerShell ou cmd sur Windows)

Vérifier les versions installées :

```bash
java -version
mvn -version
```

---

## Compilation

Depuis la racine du projet, compiler les deux modules en une seule commande :

```bash
mvn clean package -DskipTests
```

Les JARs sont produits dans :

```
server/target/server.jar
client/target/client.jar
```

---

## Lancement

### 1. Démarrer le serveur

```bash
# Linux / macOS
./scripts/start-server.sh

# Windows
scripts\start-server.bat
```

Le serveur démarre sur le **port 9000**. Il expose une API REST pour les bundles de clés
publiques et un endpoint WebSocket STOMP pour le relais des messages chiffrés.

### 2. Démarrer Alice (client 1)

```bash
./scripts/start-client.sh Alice 8081
```

### 3. Démarrer Bob (client 2)

```bash
./scripts/start-client.sh Bob 8082
```

### 4. Ouvrir les interfaces web

| Utilisateur | URL                        |
|-------------|----------------------------|
| Alice       | http://localhost:8081       |
| Bob         | http://localhost:8082       |

Chaque interface affiche :
- Le **panneau de chat** (colonne gauche) avec la liste des utilisateurs connectés
- Le **panneau de monitoring crypto** (colonne droite) avec chaque événement cryptographique en temps réel

---

## Scénario de démo rapide (5 minutes)

**Étape 1 — Serveur seul**

Lancer le serveur et consulter ses logs : la liste des utilisateurs est vide,
aucun bundle de clés n'est encore enregistré.

**Étape 2 — Alice se connecte**

```bash
./scripts/start-client.sh Alice 8081
```

Dans le panneau de monitoring d'Alice, les premiers événements apparaissent :

```
[KEY_GEN] IK_A — Identity Key générée (X25519)
[KEY_GEN] SPK_A — Signed Pre-Key générée + signature XEdDSA
[KEY_GEN] OPK_A #0..#4 — 5 One-Time Pre-Keys générées
```

Alice enregistre ses clés **publiques** auprès du serveur.

**Étape 3 — Bob se connecte**

```bash
./scripts/start-client.sh Bob 8082
```

Même séquence de génération de clés côté Bob.
Les deux utilisateurs sont maintenant enregistrés sur le serveur.

**Étape 4 — Alice envoie "Bonjour Bob !"**

Alice clique sur Bob dans la liste, tape `Bonjour Bob !` et envoie.
Le panneau de monitoring d'Alice affiche la séquence complète :

```
[X3DH_DH1]          DH1 = ECDH(IK_A, SPK_B)
[X3DH_DH2]          DH2 = ECDH(EK_A, IK_B)
[X3DH_DH3]          DH3 = ECDH(EK_A, SPK_B)
[X3DH_DH4]          DH4 = ECDH(EK_A, OPK_B)
[X3DH_MASTER_SECRET] SK = HKDF(F || DH1 || DH2 || DH3 || DH4) → 32 bytes
[RATCHET_STEP]       Double Ratchet initialisé
[SYMMETRIC_RATCHET]  Clé de message dérivée depuis chainKey
[HMAC_COMPUTE]       Authentification HMAC-SHA256
[ENCRYPT]            AES-256-CBC — message chiffré
```

**Étape 5 — Ce que voit le serveur**

Les logs du serveur affichent uniquement :

```
Message reçu de Alice pour Bob — relais effectué
```

Aucun contenu en clair. Le serveur ne voit qu'un blob chiffré.

**Étape 6 — Bob reçoit le message**

Dans l'onglet de Bob, le panneau de monitoring affiche l'opération inverse :

```
[X3DH_DH1..DH4]      Bob recalcule les 4 DH (propriété commutative d'ECDH)
[X3DH_MASTER_SECRET]  Même secret partagé → masterSecret identique
[SYMMETRIC_RATCHET]   Dérivation de la clé de message
[HMAC_VERIFY]         Vérification de l'authenticité
[DECRYPT]             AES-256-CBC — "Bonjour Bob !" en clair
```

**Étape 7 — Bob répond**

Bob tape `Salut Alice !`. Le panneau affiche un événement `RATCHET_STEP` :
le ratchet DH avance, une nouvelle paire de clés DH est générée pour cette direction.

**Étape 8 — Forward Secrecy en action**

Continuer à échanger plusieurs messages. Chaque message utilise une clé unique,
immédiatement détruite après usage. Le compteur de sécurité dans l'interface affiche
le nombre de ratchet steps et de clés uniques générées.

---

## Protocole implémenté

| Primitive            | Rôle                                                          |
|----------------------|---------------------------------------------------------------|
| **X3DH**             | Établissement de session asynchrone (4 échanges DH)           |
| **Double Ratchet**   | Chiffrement par message avec forward et future secrecy        |
| **Curve25519 (X25519)** | Échanges Diffie-Hellman sur courbe elliptique              |
| **AES-256-CBC**      | Chiffrement symétrique des messages                           |
| **HMAC-SHA256**      | Authentification des messages chiffrés                        |
| **HKDF**             | Dérivation de clés (rootKey, chainKey, messageKey)            |
| **XEdDSA**           | Signature de la Signed Pre-Key (vérifiée par l'autre partie)  |

### Formule X3DH

```
SK = HKDF-SHA256(
  IKM  = F || DH1 || DH2 || DH3 [|| DH4],
  salt = 0x00 * 32,
  info = "SignalX3DH"
)

où F = 0xFF * 32  (constante de domaine Signal)
   DH1 = ECDH(IK_A, SPK_B)
   DH2 = ECDH(EK_A, IK_B)
   DH3 = ECDH(EK_A, SPK_B)
   DH4 = ECDH(EK_A, OPK_B)  [si OPK disponible]
```

---

## Structure du projet

```
signal-chat/
├── pom.xml                          # POM parent (multi-modules Maven)
├── README.md
│
├── server/                          # Module serveur (port 9000)
│   └── src/main/java/cm/iusjc/signalchat/server/
│       ├── config/                  # WebSocket STOMP + CORS
│       ├── controller/              # KeyBundleController (REST)
│       ├── handler/                 # MessageRelayHandler (WebSocket)
│       ├── model/                   # KeyBundle, EncryptedMessage, UserRegistration
│       └── service/                 # KeyStoreService, UserRegistryService
│
├── client/                          # Module client (port configurable, défaut 8081)
│   └── src/main/java/cm/iusjc/signalchat/client/
│       ├── config/                  # WebSocket local + RestTemplate
│       ├── controller/              # ChatController (REST + WebSocket)
│       ├── crypto/                  # X3DHService, DoubleRatchetService,
│       │                            # CryptoEngine, KeyManager, SessionState
│       ├── model/                   # CryptoEventType, EncryptedMessage, KeyBundle…
│       └── monitoring/              # CryptoLogger (événements temps réel)
│
├── scripts/                         # Scripts de démarrage
│   ├── start-server.sh
│   ├── start-server.bat
│   ├── start-client.sh
│   └── start-client.bat
│
└── docs/                            # Documentation technique
    ├── PROTOCOL.md                  # Spécification du protocole
    ├── DEMO-SCRIPT.md               # Script de démonstration TP
    └── architecture.png
```

---

## Démonstration multi-machines (réseau)

Pour une démo sur plusieurs machines du même réseau local, passer l'adresse IP
du serveur en paramètre :

```bash
# Sur la machine qui héberge le serveur
./scripts/start-server.sh

# Sur les machines clientes (remplacer 192.168.1.10 par l'IP réelle du serveur)
./scripts/start-client.sh Alice 8081 192.168.1.10
./scripts/start-client.sh Bob   8082 192.168.1.10
```

Le paramètre `SERVER_IP` est transmis au client via la variable d'environnement
`SIGNAL_SERVER_HOST`. La variable `SIGNAL_SERVER_PORT` permet de surcharger le port
si nécessaire (défaut : 9000).

---

## Dépendances principales

| Bibliothèque                  | Version  | Rôle                              |
|-------------------------------|----------|-----------------------------------|
| Spring Boot                   | 3.5.x    | Framework applicatif              |
| Bouncy Castle (`bcprov-jdk18on`) | 1.80  | Primitives crypto (Curve25519, AES, HMAC, HKDF) |
| Spring WebSocket + STOMP      | (Boot)   | Communication temps réel          |
| Thymeleaf                     | (Boot)   | Templates HTML côté serveur       |
| Lombok                        | (Boot)   | Réduction du boilerplate Java     |
