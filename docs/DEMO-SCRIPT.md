# Script de Démonstration — TP Sécurité SignalChat

## Durée totale : ~15 minutes

---

## Préparation (avant le TP)

Compiler le projet depuis la racine :

```bash
mvn clean package -DskipTests
```

Vérifier que les deux JARs sont présents :

```
server/target/server.jar
client/target/client.jar
```

Ouvrir **trois terminaux** côte à côte (ou dans tmux/iTerm split) :
- Terminal 1 → serveur
- Terminal 2 → Alice
- Terminal 3 → Bob

Ouvrir **deux onglets de navigateur** :
- Onglet 1 → http://localhost:8081 (Alice)
- Onglet 2 → http://localhost:8082 (Bob)

---

## Déroulé

---

### (0:00) Introduction — Le protocole Signal (2 minutes)

#### Pourquoi le chiffrement de bout en bout ?

Dans une messagerie classique (SMS, email non chiffré), le serveur voit tous les messages
en clair. Il peut les lire, les stocker, les transmettre à des tiers. Le chiffrement
de bout en bout (E2EE) garantit que **seuls les deux interlocuteurs** peuvent lire
le contenu d'un message — pas le serveur, pas l'opérateur réseau, personne d'autre.

#### Qu'est-ce que le protocole Signal ?

Le protocole Signal, développé par Open Whisper Systems, est utilisé dans Signal,
WhatsApp, et d'autres messageries. Il repose sur deux algorithmes complémentaires :

- **X3DH** (Extended Triple Diffie-Hellman) : permet à deux personnes de se mettre
  d'accord sur un secret partagé **sans jamais l'avoir communiqué**, en utilisant
  uniquement des clés publiques pre-publiées. C'est l'établissement de session.

- **Double Ratchet** : une fois la session établie, chaque message chiffre avec
  une clé **unique et éphémère**, immédiatement détruite après usage. C'est le
  chiffrement par message.

#### Trois propriétés de sécurité clés

| Propriété          | Définition                                                                 |
|--------------------|----------------------------------------------------------------------------|
| **Forward secrecy** | Compromettre les clés actuelles ne révèle pas les messages passés          |
| **Future secrecy** | Se remettre d'une compromission : les futures clés restent sûres           |
| **Authentification** | X3DH lie les clés d'identité → on sait avec qui on parle              |

#### Ce qu'on va démontrer

Dans les 13 minutes suivantes, on va voir en temps réel, événement par événement,
comment ces propriétés sont mises en œuvre dans le code Java.

---

### (2:00) Démarrage du serveur (1 minute)

**Terminal 1 :**

```bash
./scripts/start-server.sh
```

Ou directement :

```bash
java -jar server/target/server.jar
```

Observer les logs de démarrage Spring Boot. Le serveur écoute sur le **port 9000**.

**Montrer ce que fait le serveur :**

```
[KeyStoreService]    Démarrage — aucun bundle enregistré
[WebSocketConfig]    STOMP endpoint : /ws
[KeyBundleController] REST API : /api/keys/**
```

**Point clé à souligner :**

> "Le serveur expose deux choses : une API REST pour stocker et récupérer les clés
> publiques, et un endpoint WebSocket pour relayer les messages chiffrés. Il ne
> stocke jamais de clés privées. Il ne déchiffre jamais un message."

Appeler l'API directement pour montrer qu'elle est vide :

```bash
curl http://localhost:9000/api/keys/users
# Retourne : []
```

---

### (3:00) Démarrage d'Alice (1 minute)

**Terminal 2 :**

```bash
./scripts/start-client.sh Alice 8081
```

Ou directement :

```bash
CHAT_USERNAME=Alice SERVER_PORT=8081 java -jar client/target/client.jar
```

**Ouvrir http://localhost:8081 dans le navigateur.**

Dans le **panneau de monitoring crypto** (colonne droite), les premiers événements
apparaissent immédiatement au démarrage :

```
[KEY_GEN]  IK_A — Identity Key (X25519) : a3f8c2d1...9e7b4f02
[KEY_GEN]  IK_A signing — Identity Signing Key (Ed25519)
[KEY_GEN]  SPK_A — Signed Pre-Key (X25519) + signature XEdDSA
[KEY_GEN]  OPK_A #0 — One-Time Pre-Key
[KEY_GEN]  OPK_A #1 — One-Time Pre-Key
[KEY_GEN]  OPK_A #2 — One-Time Pre-Key
[KEY_GEN]  OPK_A #3 — One-Time Pre-Key
[KEY_GEN]  OPK_A #4 — One-Time Pre-Key
```

**Expliquer chaque type de clé :**

| Clé          | Type     | Durée de vie       | Rôle                                              |
|--------------|----------|--------------------|---------------------------------------------------|
| IK (Identity Key) | X25519 | Permanente    | Identité cryptographique de l'utilisateur         |
| SPK (Signed Pre-Key) | X25519 | ~semaines   | Pre-key signée, rotatée périodiquement            |
| OPK (One-Time Pre-Key) | X25519 | Usage unique | Utilisée une seule fois puis détruite             |

**Point clé :**

> "Le serveur ne voit que les clés publiques — jamais les clés privées. Les clés
> privées ne quittent jamais la mémoire du client."

Vérifier dans l'API serveur :

```bash
curl http://localhost:9000/api/keys/Alice
# Retourne le bundle de clés publiques d'Alice (JSON)
```

---

### (4:00) Démarrage de Bob (1 minute)

**Terminal 3 :**

```bash
./scripts/start-client.sh Bob 8082
```

**Ouvrir http://localhost:8082 dans le navigateur.**

Même séquence de génération de clés pour Bob. Observer dans le panneau de monitoring
de Bob les mêmes événements `KEY_GEN`.

Vérifier que les deux utilisateurs sont enregistrés :

```bash
curl http://localhost:9000/api/keys/users
# Retourne : ["Alice", "Bob"]
```

**Point :**

> "Alice et Bob ont chacun publié leur bundle de clés publiques. Ils ne se sont
> pas encore parlé. Aucun secret partagé n'existe encore entre eux."

---

### (5:00) Premier message Alice → Bob (3 minutes)

**Dans l'onglet d'Alice (http://localhost:8081) :**

1. Cliquer sur **Bob** dans la liste des utilisateurs
2. Taper : `Bonjour Bob !`
3. Appuyer sur Entrée ou cliquer Envoyer

Le panneau de monitoring d'Alice affiche la séquence complète en temps réel.
**Dérouler événement par événement :**

---

**Phase 1 — Récupération du bundle de clés de Bob**

Alice interroge le serveur pour obtenir les clés publiques de Bob :
`GET /api/keys/Bob`

---

**Phase 2 — X3DH : 4 échanges Diffie-Hellman**

```
[X3DH_DH1]  DH1 = ECDH(IK_A_priv, SPK_B_pub)
             input  : clé privée d'Alice (IK) + clé publique SPK de Bob
             output : 32 bytes de secret partagé intermédiaire
```

```
[X3DH_DH2]  DH2 = ECDH(EK_A_priv, IK_B_pub)
             input  : clé éphémère privée d'Alice + clé publique IK de Bob
             output : 32 bytes de secret partagé intermédiaire
```

```
[X3DH_DH3]  DH3 = ECDH(EK_A_priv, SPK_B_pub)
             input  : clé éphémère privée d'Alice + clé publique SPK de Bob
             output : 32 bytes de secret partagé intermédiaire
```

```
[X3DH_DH4]  DH4 = ECDH(EK_A_priv, OPK_B_pub)
             input  : clé éphémère privée d'Alice + One-Time Pre-Key de Bob
             output : 32 bytes de secret partagé intermédiaire
```

> "Chacun de ces 4 DH produit un secret différent. Seuls Alice (qui a les clés
> privées) et Bob (qui a les clés privées correspondantes) peuvent les calculer.
> La propriété de commutativité d'ECDH garantit :
> ECDH(A_priv, B_pub) = ECDH(B_priv, A_pub)"

---

**Phase 3 — Master Secret via HKDF**

```
[X3DH_MASTER_SECRET]
  SK = HKDF-SHA256(
    IKM  = 0xFF*32 || DH1 || DH2 || DH3 || DH4,
    salt = 0x00*32,
    info = "SignalX3DH"
  )
  → masterSecret = a3b7f2...c9d4e1  (32 bytes)
```

> "Le préfixe 0xFF*32 est la constante de domaine Signal. Elle empêche les
> attaques par confusion de protocole — ce secret ne peut pas être réutilisé
> dans un autre protocole."

---

**Phase 4 — Initialisation du Double Ratchet**

```
[RATCHET_STEP]       Double Ratchet initialisé
                     rootKey    = dérivé du masterSecret
                     sendChainKey = dérivé du masterSecret
```

---

**Phase 5 — Chiffrement du message**

```
[SYMMETRIC_RATCHET]  messageKey = KDF(chainKey)
                     chainKey   = KDF(chainKey)  ← avancement irréversible

[HMAC_COMPUTE]       HMAC-SHA256(messageKey, ciphertext || IV || metadata)
                     → tag d'authentification de 32 bytes

[ENCRYPT]            AES-256-CBC(messageKey, "Bonjour Bob !", IV aléatoire)
                     → ciphertext (blob chiffré)
```

> "La clé de message est immédiatement détruite après usage. Il est impossible
> de recalculer cette clé sans rejouer toute la séquence depuis le début."

---

### (6:00) Côté serveur — le serveur ne voit rien (1 minute)

**Revenir sur les logs du Terminal 1 (serveur) :**

```
MessageRelayHandler  Message reçu de Alice pour Bob — relais effectué
```

C'est tout ce qu'il y a. Le serveur reçoit un objet JSON structuré comme :

```json
{
  "from": "Alice",
  "to": "Bob",
  "ciphertext": "7a3f9c2b...",
  "iv": "d8e4f1a2...",
  "hmac": "bc72d9e1...",
  "ephemeralKey": "3c8a7f2e...",
  "usedOPKId": 2
}
```

Appeler l'API pour le confirmer :

```bash
# Le serveur ne stocke pas les messages — il ne fait que les relayer
# Il n'y a aucune table de messages côté serveur
```

**Point clé :**

> "E2EE signifie que le serveur ne peut structurellement pas lire les messages.
> Ce n'est pas une question de politique de confidentialité — c'est une garantie
> mathématique. Sans les clés privées des utilisateurs, les données sont
> inutilisables."

---

### (7:00) Bob reçoit le message (1 minute)

**Passer sur l'onglet de Bob (http://localhost:8082) :**

Le message est arrivé via WebSocket STOMP. Le panneau de monitoring de Bob affiche :

```
[X3DH_DH1]           DH1 = ECDH(SPK_B_priv, IK_A_pub)  [côté Bob]
[X3DH_DH2]           DH2 = ECDH(IK_B_priv, EK_A_pub)   [côté Bob]
[X3DH_DH3]           DH3 = ECDH(SPK_B_priv, EK_A_pub)  [côté Bob]
[X3DH_DH4]           DH4 = ECDH(OPK_B_priv, EK_A_pub)  [côté Bob]
[X3DH_MASTER_SECRET]  SK identique à celui calculé par Alice
[SYMMETRIC_RATCHET]   Dérivation de la messageKey de réception
[HMAC_VERIFY]         Vérification du tag d'authenticité → OK
[DECRYPT]             AES-256-CBC → "Bonjour Bob !"
```

**Point :**

> "Bob a recalculé exactement les mêmes 4 DH, en sens inverse, grâce à la
> commutativité d'ECDH. Il obtient le même masterSecret, dérive la même
> messageKey, et déchiffre le message. Zéro communication de secrets en dehors
> des clés publiques pré-publiées."

---

### (8:00) Bob répond (1 minute)

**Dans l'onglet de Bob :**

Taper `Salut Alice !` et envoyer.

Observer le panneau de monitoring de Bob :

```
[RATCHET_STEP]  Bob génère une nouvelle paire DH de ratchet
                Nouvel échange DH avec Alice → nouveau rootKey
                Nouveau sendingChainKey dérivé
[SYMMETRIC_RATCHET] messageKey pour ce message (différente de la précédente)
[HMAC_COMPUTE]  ...
[ENCRYPT]       AES-256-CBC → blob chiffré envoyé
```

**Point clé :**

> "À chaque changement de direction de la conversation, le ratchet DH tourne.
> Cela signifie que même si un attaquant compromet les clés d'une session à un
> instant T, il ne peut pas lire les messages échangés avant — c'est la
> forward secrecy — ni calculer les clés futures — c'est la future secrecy."

---

### (9:00) Forward Secrecy en action (2 minutes)

**Échanger 5 à 6 messages supplémentaires entre Alice et Bob.**

Observer le compteur de sécurité dans l'interface : il affiche le nombre de
ratchet steps effectués et le nombre total de clés uniques générées.

**Mettre en évidence :**

1. **Chaque message → une clé unique** : les `messageKey` affichées en hex
   dans le monitoring sont toutes différentes

2. **La chaîne est irréversible** : le `chainKey` est mis à jour via une
   fonction à sens unique (KDF). On ne peut pas revenir en arrière.

3. **Le ratchet DH tourne à chaque changement de direction** : quand Alice
   envoie après Bob, un nouveau ratchet DH s'effectue, renouvelant les clés
   de chaîne.

Scénario à dérouler pour illustrer :

```
Alice → Bob  : msg 1  [X3DH init, ratchet step 1, messageKey #1]
Bob → Alice  : msg 2  [ratchet step 2, messageKey #2]
Alice → Bob  : msg 3  [ratchet step 3, messageKey #3]
Alice → Bob  : msg 4  [ratchet step 3 (même direction), messageKey #4]
Bob → Alice  : msg 5  [ratchet step 4, messageKey #5]
```

**Poser la question :**

> "Si un attaquant capture les clés actuelles de Bob à l'instant du message 5,
> que peut-il faire ?"

Réponse : il peut déchiffrer les messages futurs jusqu'au prochain ratchet DH,
mais **pas les messages 1 à 4** (forward secrecy). Et dès le prochain message
d'Alice, les clés changent à nouveau (future secrecy via self-healing).

---

### (10:00) Démonstration réseau — optionnel (2 minutes)

Cette section est optionnelle selon le matériel disponible.

#### Option A — Wireshark

Lancer une capture sur l'interface loopback (ou la carte réseau si multi-machines),
filtrer sur le port 9000 :

```
tcp.port == 9000
```

Observer le trafic WebSocket :
- La connexion STOMP est visible
- Les payloads sont des JSON avec `ciphertext` : des chaînes Base64 opaques
- Aucun texte en clair ne transite sur le réseau

#### Option B — curl direct sur l'API serveur

```bash
# Montrer qu'on ne peut pas récupérer les messages depuis le serveur
# Le serveur ne les stocke pas — il relaie en temps réel

# On peut uniquement voir les clés publiques enregistrées
curl -s http://localhost:9000/api/keys/Alice | python3 -m json.tool
```

Le JSON retourné contient uniquement des clés publiques encodées en Base64.
Aucune clé privée, aucun message déchiffré.

#### Option C — Démo multi-machines

Si plusieurs machines sont sur le même réseau local :

```bash
# Démarrer le serveur sur la machine A (IP : 192.168.1.10)
./scripts/start-server.sh

# Sur la machine B (Alice)
./scripts/start-client.sh Alice 8081 192.168.1.10

# Sur la machine C (Bob)
./scripts/start-client.sh Bob 8082 192.168.1.10
```

---

### (12:00) Points clés à retenir (2 minutes)

#### Résumé des garanties de sécurité

**Confidentialité**
- Les messages ne sont jamais en clair côté serveur
- AES-256-CBC avec clé éphémère unique par message

**Authentification**
- X3DH lie les identity keys : on sait cryptographiquement avec qui on parle
- HMAC-SHA256 sur chaque message : détection de toute altération

**Forward Secrecy**
- Les clés de message sont détruites après usage
- Compromission des clés actuelles → messages passés toujours protégés

**Future Secrecy (Post-Compromise Security)**
- Le ratchet DH tourne régulièrement → auto-guérison après compromission
- Un attaquant ayant compromis les clés à T ne peut pas lire les messages après
  le prochain ratchet DH

**Déni plausible**
- X3DH ne génère pas de preuve cryptographique de la conversation
- Un tiers ne peut pas prouver qu'Alice a dit telle chose à Bob

#### Ce que démontre cette implémentation

1. Le protocole Signal n'est pas "magie noire" — c'est de la crypto standard
   bien assemblée (Curve25519, AES, HMAC, HKDF)
2. La sécurité vient de la **structure** du protocole, pas de la confiance
   accordée au serveur
3. Le serveur peut être totalement compromis → les messages restent illisibles

---

## Points de discussion possibles

1. **Rotation des Signed Pre-Keys** : La SPK doit être rotatée régulièrement.
   Si un attaquant la compromet, combien de temps est-il exposé ? Comment
   le protocole limite-t-il cette fenêtre ?

2. **Épuisement des One-Time Pre-Keys** : Que se passe-t-il si Bob a publié
   5 OPKs et que 5 personnes lui envoient un message en même temps ? Le protocole
   fonctionne-t-il sans OPK ? Quelles garanties perd-on ?

3. **Vérification d'identité hors-bande** : X3DH garantit l'authenticité des
   clés d'identité *telles que publiées sur le serveur*. Mais comment vérifier
   que ces clés appartiennent vraiment à la bonne personne ? (Lien avec le
   "safety number" de Signal)

4. **Messages hors-ligne** : Alice peut-elle envoyer un message à Bob s'il n'est
   pas connecté ? Que stocke le serveur pour permettre ça ? Quelle est la
   différence avec un email chiffré ?

5. **Forward secrecy vs sauvegarde** : Forward secrecy signifie que si on
   perd son téléphone, on perd ses messages. Comment Signal gère-t-il les
   sauvegardes tout en préservant cette propriété ?

6. **Attaque de type "man-in-the-middle" sur le serveur** : Si le serveur est
   compromis et substitue les clés publiques de Bob par les siennes, que se
   passe-t-il ? Pourquoi la vérification hors-bande (safety number) est-elle
   indispensable pour une vraie garantie d'authenticité ?
