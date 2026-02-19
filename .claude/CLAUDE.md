# SignalChat — Contexte Projet

## Objectif

Application de messagerie chiffrée de bout en bout (E2EE) démontrant le protocole Signal,
pour un TP de sécurité informatique en 4e année à l'IUST, Yaoundé.

## Architecture

- **Serveur** (Spring Boot, port 9000) : stocke les clés publiques, relaie les messages chiffrés via WebSocket STOMP. Ne voit JAMAIS le contenu en clair.
- **Client** (Spring Boot, port configurable) : fait toute la crypto en local (X3DH + Double Ratchet), sert une UI web locale avec chat + panneau de monitoring crypto.

## Stack

- Java 21, Spring Boot 3.5.x, Maven multi-modules
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
