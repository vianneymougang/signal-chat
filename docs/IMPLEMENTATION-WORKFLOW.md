# SignalChat — Plan d'implementation

## Vue d'ensemble

Ce document decrit le workflow d'implementation complet pour construire le squelette
du projet SignalChat : structure Maven multi-modules, serveur de relai, couche crypto
client (X3DH + Double Ratchet), et tests unitaires.

**Pas d'UI dans cette phase.** On se concentre sur la fondation : crypto + serveur + tests.

---

## Phase 1 — Structure Maven multi-modules

### Etape 1.1 : POM Parent (`signal-chat/pom.xml`)

**Fichier** : `pom.xml` (racine)

- `groupId` : `cm.iusjc.signalchat`
- `artifactId` : `signal-chat`
- `packaging` : `pom`
- `modules` : `server`, `client`
- Spring Boot 3.5.0 comme parent BOM (`spring-boot-starter-parent`)
- Java 21 (`maven.compiler.source/target`)
- `dependencyManagement` avec :
  - Bouncy Castle `bcprov-jdk18on:1.80`
  - Jackson `jackson-databind` (herite de Spring Boot)
- Plugins communs :
  - `maven-compiler-plugin` (Java 21)
  - `spring-boot-maven-plugin`

### Etape 1.2 : POM Server (`server/pom.xml`)

- Parent : `signal-chat`
- Dependencies :
  - `spring-boot-starter-web`
  - `spring-boot-starter-websocket`
  - `spring-boot-starter-test` (scope test)
  - Bouncy Castle (herite de parent)
  - Lombok (provided)

### Etape 1.3 : POM Client (`client/pom.xml`)

- Parent : `signal-chat`
- Dependencies :
  - `spring-boot-starter-web`
  - `spring-boot-starter-websocket`
  - `spring-boot-starter-thymeleaf` (pour plus tard, mais on le declare deja)
  - `spring-boot-starter-test` (scope test)
  - Bouncy Castle (herite de parent)
  - Lombok (provided)
  - Webjars : `sockjs-client:1.5.1`, `stomp-websocket:2.3.4`

### Etape 1.4 : Arborescence des dossiers

Creer toutes les arborescences `src/main/java`, `src/main/resources`, `src/test/java`
pour les deux modules, avec les packages :

```
server/src/main/java/cm/iusjc/signalchat/server/
  ├── config/
  ├── controller/
  ├── handler/
  ├── model/
  └── service/

client/src/main/java/cm/iusjc/signalchat/client/
  ├── config/
  ├── controller/
  ├── crypto/
  ├── model/
  ├── monitoring/
  └── service/
```

**Validation** : `mvn validate` passe sans erreur.

---

## Phase 2 — Module Server

### Etape 2.1 : ServerApplication.java

Point d'entree Spring Boot basique avec `@SpringBootApplication`.

### Etape 2.2 : application.yml (server)

```yaml
server:
  port: 9000
logging:
  level:
    cm.iusjc.signalchat: DEBUG
```

### Etape 2.3 : Modeles

**KeyBundle.java** :
- `String username`
- `String identityKey` (Base64)
- `String signedPreKey` (Base64)
- `String signedPreKeySignature` (Base64)
- `List<String> oneTimePreKeys` (Base64)
- Annotations Lombok : `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`

**EncryptedMessage.java** :
- `String sender`, `String recipient`
- `String ciphertext` (Base64)
- `String iv` (Base64)
- `String hmac` (Base64)
- `String senderIdentityKey` (Base64)
- `String senderEphemeralKey` (Base64)
- `Integer usedOneTimePreKeyId`
- `int messageNumber`, `int previousChainLength`
- `String senderRatchetKey` (Base64)

**UserRegistration.java** :
- `String username`
- `String sessionId`

### Etape 2.4 : Services

**KeyStoreService.java** :
- `ConcurrentHashMap<String, KeyBundle> keyBundles`
- `registerKeyBundle(KeyBundle)` — stocke le bundle
- `getKeyBundle(String username)` — retourne le bundle
- `getRegisteredUsers()` — retourne `Set<String>`
- `consumeOneTimePreKey(String username)` — retire et retourne une OPK de la liste

**UserRegistryService.java** :
- `ConcurrentHashMap<String, String> activeUsers` (username → sessionId)
- `registerUser(String username, String sessionId)`
- `unregisterUser(String sessionId)`
- `isUserOnline(String username)`
- `getOnlineUsers()` — retourne `Set<String>`

### Etape 2.5 : Configuration WebSocket

**WebSocketConfig.java** :
- `@EnableWebSocketMessageBroker`
- `configureMessageBroker` : enable simple broker `/topic`, set app destination prefix `/app`
- `registerStompEndpoints` : endpoint `/ws` avec SockJS fallback, allowed origins `*`

**CorsConfig.java** :
- Autoriser toutes les origines (pour le TP en LAN)

### Etape 2.6 : Controller REST

**KeyBundleController.java** :
- `POST /api/keys/register` → `@RequestBody KeyBundle` → `keyStoreService.registerKeyBundle()`
  - Log : "KeyBundle enregistre pour {username}"
  - Retourne 200 OK
- `GET /api/keys/bundle/{username}` → retourne le KeyBundle (404 si absent)
  - Consomme une OPK au passage via `consumeOneTimePreKey()`
- `GET /api/keys/users` → retourne la liste des usernames enregistres

### Etape 2.7 : Handler WebSocket

**MessageRelayHandler.java** :
- `@MessageMapping("/send")` reçoit un `EncryptedMessage`
- Log : "Relai message chiffre de {sender} vers {recipient} — taille: {n} bytes"
- Envoie vers `/topic/messages/{recipient}` via `SimpMessagingTemplate`

### Validation Phase 2

- Le serveur compile : `mvn compile -pl server`
- Le serveur demarre : `mvn spring-boot:run -pl server`
- Les endpoints REST repondent (test manuel ou test unitaire basique)

---

## Phase 3 — Module Client : Couche Crypto

> C'est la phase la plus critique. Chaque classe doit etre implementee avec soin
> et testee individuellement.

### Etape 3.1 : CryptoEvent et CryptoLogger (monitoring)

**Prerequis** : ces classes sont utilisees par toute la couche crypto, on les cree en premier.

**CryptoEvent.java** (`client/model/`) :
- `long timestamp`
- `CryptoEventType type` (enum : `KEY_GEN`, `X3DH_DH1`, `X3DH_DH2`, `X3DH_DH3`, `X3DH_DH4`,
  `X3DH_MASTER_SECRET`, `X3DH_SESSION_INIT`, `RATCHET_STEP`, `SYMMETRIC_RATCHET`,
  `ENCRYPT`, `DECRYPT`, `HMAC_COMPUTE`, `HMAC_VERIFY`, `HKDF_DERIVE`)
- `String inputHex`
- `String outputHex`
- `String description`

**CryptoLogger.java** (`client/monitoring/`) :
- Liste en memoire de `CryptoEvent` (pour l'instant, pas de WebSocket push)
- `logEvent(CryptoEventType type, String inputHex, String outputHex, String description)`
- `truncateHex(byte[] data)` — retourne les 8 premiers + 8 derniers caracteres hex
  (format `0x7a3f89c1...d2e4f6a8`)
- `toHex(byte[] data)` — conversion complete
- `getEvents()` — retourne la liste des evenements (pour les tests)
- `clearEvents()`

### Etape 3.2 : CryptoEngine.java

**Dependances** : CryptoLogger

Toutes les primitives crypto bas niveau avec Bouncy Castle :

```java
// Enregistrement du provider Bouncy Castle
Security.addProvider(new BouncyCastleProvider());
```

**Methodes** :

1. `byte[] hkdf(byte[] inputKeyMaterial, byte[] salt, byte[] info, int outputLength)`
   - Utilise `HKDFBytesGenerator` de Bouncy Castle
   - `HKDFParameters(ikm, salt, info)`
   - Log via CryptoLogger : type=HKDF_DERIVE

2. `byte[] encryptAES(byte[] plaintext, byte[] key, byte[] iv)`
   - `Cipher.getInstance("AES/CBC/PKCS5Padding", "BC")`
   - `SecretKeySpec(key, "AES")`, `IvParameterSpec(iv)`
   - Log : type=ENCRYPT

3. `byte[] decryptAES(byte[] ciphertext, byte[] key, byte[] iv)`
   - Inverse de encryptAES
   - Log : type=DECRYPT

4. `byte[] hmacSHA256(byte[] key, byte[] data)`
   - `Mac.getInstance("HmacSHA256", "BC")`
   - Log : type=HMAC_COMPUTE

5. `boolean verifyHMAC(byte[] key, byte[] data, byte[] expectedMac)`
   - Calcule le HMAC et compare avec `MessageDigest.isEqual()`
   - Log : type=HMAC_VERIFY

6. `byte[] generateIV()`
   - `SecureRandom.getInstanceStrong()` → 16 bytes
   - Pas de log (trop frequent)

**Point d'attention** : Pour AES-256-CBC, la cle doit faire exactement 32 bytes.
On prendra les 32 premiers bytes de la messageKey derivee par HKDF si necessaire.

### Etape 3.3 : KeyManager.java

**Dependances** : CryptoEngine, CryptoLogger

**Methodes** :

1. `AsymmetricCipherKeyPair generateIdentityKeyPair()`
   - `X25519KeyPairGenerator` avec `SecureRandom`
   - Log : type=KEY_GEN, description="Generation paire de cles d'identite"
   - Log la cle publique en hex tronque

2. `AsymmetricCipherKeyPair generateSignedPreKey(AsymmetricCipherKeyPair identityKeyPair)`
   - Genere une nouvelle paire X25519
   - Signe la cle publique avec la cle privee d'identite
   - **Note importante** : X25519 ne supporte pas la signature directement.
     Approche : utiliser Ed25519 pour la signature, ou bien faire un HMAC de la
     cle publique avec la cle privee d'identite comme cle HMAC.
   - Approche retenue : **HMAC-SHA256(identityPrivateKey, signedPrePublicKey)** comme
     "signature" (suffisant pour le TP, simplifie l'implementation)
   - Log : type=KEY_GEN, description="Generation Signed Pre-Key"

3. `List<AsymmetricCipherKeyPair> generateOneTimePreKeys(int count)`
   - Genere `count` paires X25519
   - Log chaque cle generee

4. `byte[] extractPublicKey(AsymmetricCipherKeyPair keyPair)`
   - Extrait les 32 bytes de la cle publique X25519
   - `((X25519PublicKeyParameters) keyPair.getPublic()).getEncoded()`

5. `byte[] extractPrivateKey(AsymmetricCipherKeyPair keyPair)`
   - Extrait les 32 bytes de la cle privee X25519
   - `((X25519PrivateKeyParameters) keyPair.getPrivate()).getEncoded()`

6. `byte[] performDH(byte[] privateKey, byte[] publicKey)`
   - Calcule ECDH X25519
   - `X25519Agreement` avec les parametres
   - Retourne le shared secret (32 bytes)
   - Log : description avec les cles tronquees

### Etape 3.4 : SessionState.java

**Pas de dependance crypto** — c'est un POJO.

```java
@Data
@Builder
public class SessionState {
    private byte[] rootKey;              // 32 bytes
    private byte[] sendingChainKey;      // 32 bytes
    private byte[] receivingChainKey;    // 32 bytes

    private AsymmetricCipherKeyPair localRatchetKeyPair;
    private byte[] remoteRatchetPublicKey;   // 32 bytes

    private int sendingMessageNumber;
    private int receivingMessageNumber;
    private int previousSendingChainLength;
}
```

### Etape 3.5 : X3DHService.java

**Dependances** : KeyManager, CryptoEngine, CryptoLogger

C'est l'implementation du protocole X3DH. Deux methodes principales :

#### `X3DHResult initiateHandshake(AsymmetricCipherKeyPair localIdentityKey, KeyBundle remoteKeyBundle)`

Cote Alice (initiatrice) :

```
1. ephemeralKeyPair = keyManager.generateIdentityKeyPair()  // genere EK_A
2. DH1 = keyManager.performDH(IK_A_private, SPK_B_public)
   → Log X3DH_DH1 : "ECDH(IK_A, SPK_B)"
3. DH2 = keyManager.performDH(EK_A_private, IK_B_public)
   → Log X3DH_DH2 : "ECDH(EK_A, IK_B)"
4. DH3 = keyManager.performDH(EK_A_private, SPK_B_public)
   → Log X3DH_DH3 : "ECDH(EK_A, SPK_B)"
5. Si OPK_B disponible :
   DH4 = keyManager.performDH(EK_A_private, OPK_B_public)
   → Log X3DH_DH4 : "ECDH(EK_A, OPK_B)"
6. masterSecret = DH1 || DH2 || DH3 [|| DH4]
   → Log X3DH_MASTER_SECRET
7. derivedKeys = cryptoEngine.hkdf(masterSecret, salt=zeros(32), info="SignalChat_X3DH", 64)
8. rootKey = derivedKeys[0..31]
9. chainKey = derivedKeys[32..63]
10. sessionState = SessionState.builder()
      .rootKey(rootKey)
      .sendingChainKey(chainKey)
      .localRatchetKeyPair(ephemeralKeyPair)  // EK_A sert de premier ratchet key
      .remoteRatchetPublicKey(SPK_B_public)
      .build()
```

Retourne un `X3DHResult` contenant :
- `SessionState sessionState`
- `byte[] ephemeralPublicKey` (EK_A public — a envoyer a Bob)
- `Integer usedOneTimePreKeyId` (l'index de l'OPK utilisee, ou null)

#### `SessionState processHandshake(AsymmetricCipherKeyPair localIdentityKey, AsymmetricCipherKeyPair localSignedPreKey, AsymmetricCipherKeyPair localOneTimePreKey, byte[] senderIdentityKey, byte[] senderEphemeralKey)`

Cote Bob (recepteur) :

```
1. DH1 = keyManager.performDH(SPK_B_private, IK_A_public)
   → Meme resultat que DH1 d'Alice (propriete commutative ECDH)
2. DH2 = keyManager.performDH(IK_B_private, EK_A_public)
3. DH3 = keyManager.performDH(SPK_B_private, EK_A_public)
4. Si OPK_B utilisee :
   DH4 = keyManager.performDH(OPK_B_private, EK_A_public)
5. masterSecret, derivedKeys, rootKey, chainKey : meme calcul qu'Alice
6. sessionState = SessionState.builder()
      .rootKey(rootKey)
      .receivingChainKey(chainKey)  // ATTENTION : c'est receivingChain cote Bob
      .localRatchetKeyPair(localSignedPreKey)  // SPK_B sert de premier ratchet cote Bob
      .remoteRatchetPublicKey(senderEphemeralKey)  // EK_A
      .build()
```

**Point critique** : Cote Alice, `chainKey` est la `sendingChainKey`.
Cote Bob, le meme `chainKey` est la `receivingChainKey`.
C'est ce qui permet a Bob de dechiffrer les messages d'Alice.

### Etape 3.6 : DoubleRatchetService.java

**Dependances** : KeyManager, CryptoEngine, CryptoLogger

#### `EncryptedMessage ratchetEncrypt(SessionState state, byte[] plaintext)`

```
1. messageKey = cryptoEngine.hmacSHA256(state.sendingChainKey, new byte[]{0x01})
   → Log SYMMETRIC_RATCHET : "Derivation messageKey depuis sendingChainKey"
2. state.sendingChainKey = cryptoEngine.hmacSHA256(state.sendingChainKey, new byte[]{0x02})
   → Log SYMMETRIC_RATCHET : "Avancement sendingChainKey"
3. // Extraire cle AES (32 bytes) et cle HMAC (32 bytes) depuis messageKey
   derivedKeys = cryptoEngine.hkdf(messageKey, zeros(32), "SignalChat_MessageKeys", 64)
   encryptionKey = derivedKeys[0..31]
   hmacKey = derivedKeys[32..63]
4. iv = cryptoEngine.generateIV()
5. ciphertext = cryptoEngine.encryptAES(plaintext, encryptionKey, iv)
6. hmac = cryptoEngine.hmacSHA256(hmacKey, ciphertext)
7. msgNumber = state.sendingMessageNumber++
8. Retourne EncryptedMessage avec :
   - ciphertext, iv, hmac (tout en Base64)
   - senderRatchetKey = publicKey du localRatchetKeyPair courant
   - messageNumber = msgNumber
   - previousChainLength = state.previousSendingChainLength
```

#### `byte[] ratchetDecrypt(SessionState state, EncryptedMessage message)`

```
1. Si message.senderRatchetKey != state.remoteRatchetPublicKey :
   → dhRatchetStep(state, message.senderRatchetKey)
   → Log RATCHET_STEP : "Nouveau DH ratchet detecte"
2. messageKey = cryptoEngine.hmacSHA256(state.receivingChainKey, new byte[]{0x01})
3. state.receivingChainKey = cryptoEngine.hmacSHA256(state.receivingChainKey, new byte[]{0x02})
4. derivedKeys = cryptoEngine.hkdf(messageKey, zeros(32), "SignalChat_MessageKeys", 64)
   encryptionKey = derivedKeys[0..31]
   hmacKey = derivedKeys[32..63]
5. isValid = cryptoEngine.verifyHMAC(hmacKey, ciphertext, message.hmac)
   → Si invalide : throw SecurityException
6. plaintext = cryptoEngine.decryptAES(ciphertext, encryptionKey, iv)
7. state.receivingMessageNumber++
8. Retourne plaintext
```

#### `void dhRatchetStep(SessionState state, byte[] remoteRatchetKey)`

```
1. state.previousSendingChainLength = state.sendingMessageNumber
2. state.sendingMessageNumber = 0
3. state.receivingMessageNumber = 0
4. state.remoteRatchetPublicKey = remoteRatchetKey
5. // Demi-step reception
   dhSecret = keyManager.performDH(state.localRatchetKeyPair.private, remoteRatchetKey)
   derivedKeys = cryptoEngine.hkdf(dhSecret, state.rootKey, "SignalChat_Ratchet", 64)
   state.rootKey = derivedKeys[0..31]
   state.receivingChainKey = derivedKeys[32..63]
   → Log RATCHET_STEP : "Derivation receivingChainKey depuis nouveau DH"
6. // Demi-step envoi
   state.localRatchetKeyPair = keyManager.generateIdentityKeyPair()  // nouvelle paire DH
   dhSecret2 = keyManager.performDH(state.localRatchetKeyPair.private, remoteRatchetKey)
   derivedKeys2 = cryptoEngine.hkdf(dhSecret2, state.rootKey, "SignalChat_Ratchet", 64)
   state.rootKey = derivedKeys2[0..31]
   state.sendingChainKey = derivedKeys2[32..63]
   → Log RATCHET_STEP : "Derivation sendingChainKey depuis nouveau DH local"
```

### Etape 3.7 : Modeles Client

**KeyBundle.java** (miroir du modele serveur) — meme structure.

**EncryptedMessage.java** (miroir du modele serveur) — meme structure.

**ChatMessage.java** :
- `String sender`
- `String content`
- `long timestamp`

---

## Phase 4 — Tests unitaires

### Etape 4.1 : CryptoEngineTest.java

```java
@Test void hkdf_producesDeterministicOutput()
  // Meme entree → meme sortie

@Test void encryptThenDecrypt_returnsOriginalPlaintext()
  // plaintext → encrypt → decrypt → verifier egalite

@Test void hmac_producesConsistentResult()
  // Meme cle + data → meme HMAC

@Test void verifyHMAC_acceptsValidMac()
@Test void verifyHMAC_rejectsInvalidMac()

@Test void aesKey_mustBe32Bytes()
  // Verifier que les cles derivees font bien 32 bytes
```

### Etape 4.2 : KeyManagerTest.java

```java
@Test void generateIdentityKeyPair_producesValidKeys()
  // Cle publique = 32 bytes, cle privee = 32 bytes

@Test void generateSignedPreKey_producesValidKeys()
@Test void generateOneTimePreKeys_producesRequestedCount()

@Test void performDH_isCommutative()
  // DH(A_private, B_public) == DH(B_private, A_public)

@Test void performDH_producesDifferentSecretWithDifferentKeys()
```

### Etape 4.3 : X3DHServiceTest.java

```java
@Test void x3dh_aliceAndBobDeriveSameMasterSecret()
  // 1. Alice genere ses cles (IK_A)
  // 2. Bob genere ses cles (IK_B, SPK_B, OPK_B)
  // 3. Alice fait initiateHandshake() avec le KeyBundle de Bob
  // 4. Bob fait processHandshake() avec les infos d'Alice
  // 5. Verifier : alice.rootKey == bob.rootKey
  // 6. Verifier : alice.sendingChainKey == bob.receivingChainKey

@Test void x3dh_withoutOneTimePreKey_stillWorks()
  // Meme test mais sans OPK (DH4 absent)
  // Le protocole doit fonctionner avec seulement DH1+DH2+DH3

@Test void x3dh_differentKeyPairsProduceDifferentSecrets()
```

### Etape 4.4 : DoubleRatchetServiceTest.java

```java
@Test void singleMessage_aliceEncryptsBobDecrypts()
  // 1. Setup X3DH entre Alice et Bob
  // 2. Alice chiffre "Bonjour Bob"
  // 3. Bob dechiffre → verifier "Bonjour Bob"

@Test void multipleMessages_sameSender()
  // Alice envoie 3 messages, Bob dechiffre les 3
  // Verifier que chaque messageKey est differente (forward secrecy)

@Test void bidirectionalMessages_withDHRatchet()
  // 1. Alice → Bob : "Hello"
  // 2. Bob → Alice : "Hi"  ← declenche un DH ratchet step
  // 3. Alice → Bob : "How are you?"  ← encore un DH ratchet
  // Verifier que tous les messages sont correctement dechiffres

@Test void hmacVerification_rejectsModifiedCiphertext()
  // Modifier le ciphertext apres chiffrement → le dechiffrement doit echouer
```

---

## Phase 5 — Configuration serveur (application.yml)

### Etape 5.1 : server/src/main/resources/application.yml

```yaml
server:
  port: 9000
logging:
  level:
    cm.iusjc.signalchat: DEBUG
```

### Etape 5.2 : client/src/main/resources/application.yml

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

---

## Ordre d'execution

| # | Tache | Fichiers | Depend de |
|---|-------|----------|-----------|
| 1 | POM parent | `pom.xml` | — |
| 2 | POM server | `server/pom.xml` | 1 |
| 3 | POM client | `client/pom.xml` | 1 |
| 4 | Arborescences de dossiers | `server/src/...`, `client/src/...` | 2, 3 |
| 5 | ServerApplication + application.yml | `server/` | 4 |
| 6 | Modeles server (KeyBundle, EncryptedMessage, UserRegistration) | `server/model/` | 5 |
| 7 | KeyStoreService + UserRegistryService | `server/service/` | 6 |
| 8 | WebSocketConfig + CorsConfig | `server/config/` | 5 |
| 9 | KeyBundleController | `server/controller/` | 7 |
| 10 | MessageRelayHandler | `server/handler/` | 8 |
| 11 | **Validation** : `mvn compile -pl server` | — | 10 |
| 12 | CryptoEvent + CryptoLogger | `client/model/`, `client/monitoring/` | 4 |
| 13 | CryptoEngine | `client/crypto/` | 12 |
| 14 | CryptoEngineTest | `client/test/` | 13 |
| 15 | **Validation** : tests CryptoEngine passent | — | 14 |
| 16 | KeyManager | `client/crypto/` | 13 |
| 17 | KeyManagerTest | `client/test/` | 16 |
| 18 | **Validation** : tests KeyManager passent | — | 17 |
| 19 | SessionState | `client/crypto/` | 4 |
| 20 | X3DHService | `client/crypto/` | 16, 19 |
| 21 | X3DHServiceTest | `client/test/` | 20 |
| 22 | **Validation** : tests X3DH passent (meme masterSecret des deux cotes) | — | 21 |
| 23 | Modeles client (KeyBundle, EncryptedMessage, ChatMessage) | `client/model/` | 4 |
| 24 | DoubleRatchetService | `client/crypto/` | 20, 23 |
| 25 | DoubleRatchetServiceTest | `client/test/` | 24 |
| 26 | **Validation** : tests Double Ratchet passent | — | 25 |
| 27 | `mvn clean compile` sur tout le projet | — | 26 |
| 28 | `mvn test` — tous les tests passent | — | 27 |

---

## Points d'attention techniques

### Bouncy Castle X25519

```java
// Bonne maniere de generer une paire de cles X25519
X25519KeyPairGenerator generator = new X25519KeyPairGenerator();
generator.init(new X25519KeyGenerationParameters(new SecureRandom()));
AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();

// Bonne maniere de faire un ECDH
X25519Agreement agreement = new X25519Agreement();
agreement.init(privateKeyParams);
byte[] secret = new byte[agreement.getAgreementSize()];
agreement.calculateAgreement(publicKeyParams, secret, 0);
```

### Signed Pre-Key : strategie de signature

Le protocole Signal utilise XEdDSA pour signer les Signed Pre-Keys (convertit X25519 en
Ed25519 pour la signature). C'est complexe a implementer manuellement.

**Approche simplifiee pour le TP** : utiliser HMAC-SHA256(identityPrivateKey, signedPreKeyPublic)
comme "signature". Bob peut verifier en recalculant le HMAC avec la cle publique d'identite
d'Alice. Ce n'est pas une vraie signature asymetrique mais c'est suffisant pour la demo.

**Alternative** : generer une paire Ed25519 separee pour la signature, mais cela complexifie
le KeyBundle. A discuter avec l'utilisateur.

### Serialisation Base64

Toutes les cles et donnees binaires transitent en **Base64** dans le JSON.
Utiliser `java.util.Base64.getEncoder()/getDecoder()`.

### Forward Secrecy — ce qu'il faut montrer

Chaque message utilise une `messageKey` derivee differente. Apres utilisation,
la messageKey est jetee. Meme si un attaquant capture une messageKey, il ne peut
pas dechiffrer les messages precedents ni suivants. Le panneau de monitoring doit
rendre cela visible.

---

## Risques et mitigations

| Risque | Impact | Mitigation |
|--------|--------|------------|
| DH commutativite echoue (ordre des params) | X3DH casse | Test `performDH_isCommutative` des l'etape 17 |
| Encoding incoherent (Base64 vs Hex vs raw) | Dechiffrement echoue | Standardiser sur Base64 pour le transport, raw bytes en interne |
| ChainKey Alice ≠ ChainKey Bob | Double Ratchet casse | Test X3DH verifie `alice.sendingChainKey == bob.receivingChainKey` |
| AES padding error | Decrypt echoue | Utiliser PKCS5Padding systematiquement |
| IV reutilise | Faille de securite | Generer un IV aleatoire pour CHAQUE message |

---

## Estimation des taches

- **Phase 1** (Maven structure) : rapide, configuration uniquement
- **Phase 2** (Server) : module simple, pas de logique complexe
- **Phase 3** (Crypto) : le gros du travail, necessite de la rigueur
- **Phase 4** (Tests) : critique pour valider que le protocole fonctionne
- **Phase 5** (Config) : trivial

**Chemin critique** : Phase 3 (crypto) → Phase 4 (tests). Si les tests X3DH ne passent pas,
tout le reste est bloque.
