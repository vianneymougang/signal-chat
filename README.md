# SignalChat

SignalChat is an end-to-end encrypted chat app that follows the Signal protocol: X3DH to start a session and the Double Ratchet to encrypt each message. The server stores public keys and relays encrypted messages. It never sees plain text.

Solo project.

Built for a security course at Saint Jean Ingénieur, 2026.

## Main features

- Each client generates an identity key (X25519 for key exchange, Ed25519 for signatures), a signed pre-key and a set of one-time pre-keys, then registers the public keys on the server.
- X3DH session setup with three or four Diffie-Hellman exchanges, depending on whether a one-time pre-key is available. The shared secret comes from HKDF-SHA256.
- Double Ratchet: a DH ratchet step when the sender changes, and a new message key for every message.
- Messages are encrypted with AES-256-CBC and authenticated with HMAC-SHA256.
- The server keeps messages for offline users and delivers them when they connect.
- A web chat UI per client, with a panel that shows each crypto step live (key generation, each DH, ratchet steps, encrypt, decrypt).
- Unit tests for the crypto engine, key manager, X3DH and Double Ratchet, plus an end-to-end Alice and Bob test.

## Architecture and stack

Java 21, Spring Boot 3.5.11, Bouncy Castle 1.80, Maven multi-module build.

- `server` (port 9000): REST API for key bundles (`/api/keys/register`, `/api/keys/bundle/{username}`, `/api/keys/users`) and a STOMP WebSocket endpoint at `/ws` (SockJS fallback) that relays encrypted messages. All data is kept in memory.
- `client` (port 8081 by default): runs all cryptography locally. It serves a Thymeleaf web page and pushes messages and crypto events to the browser over STOMP.

The protocol details are in `docs/PROTOCOL.md`.

## How to run it locally

Build both modules:

```bash
./mvnw clean package -DskipTests
```

Start the server:

```bash
./scripts/start-server.sh
```

Start two clients in two other terminals. The arguments are the username and the port:

```bash
./scripts/start-client.sh Alice 8081
./scripts/start-client.sh Bob 8082
```

Open http://localhost:8081 for Alice and http://localhost:8082 for Bob, then send a message from one to the other.

To use a server on another machine, pass its URL as the third argument:

```bash
./scripts/start-client.sh Alice 8081 http://192.168.1.10:9000
```

Windows versions of the scripts (`.bat`) are in `scripts/`.

Run the tests:

```bash
./mvnw test
```
