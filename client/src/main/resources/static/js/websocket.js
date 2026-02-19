/**
 * websocket.js — Connexion SockJS/STOMP locale unique
 *
 * Gère la connexion au broker STOMP du backend client.
 * Route les messages entrants vers chat.js et monitoring.js via callbacks.
 *
 * Usage :
 *   SignalWebSocket.callbacks.onMessage = fn;
 *   SignalWebSocket.callbacks.onCryptoEvent = fn;
 *   SignalWebSocket.callbacks.onConnectionStatus = fn;
 *   SignalWebSocket.connect();
 */
const SignalWebSocket = (() => {
    let stompClient = null;
    let connected = false;
    let reconnectTimer = null;

    const callbacks = {
        onMessage: null,
        onCryptoEvent: null,
        onConnectionStatus: null
    };

    /**
     * Établit la connexion SockJS/STOMP vers le backend local.
     */
    function connect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }

        const socket = new SockJS('/local-ws');
        stompClient = Stomp.over(socket);

        // Désactiver les logs STOMP verbeux
        stompClient.debug = null;

        stompClient.connect(
            {},
            (frame) => {
                connected = true;
                console.log('[WS] Connecté au backend local');
                _subscribe();
                if (callbacks.onConnectionStatus) {
                    callbacks.onConnectionStatus({ connected: true });
                }
            },
            (error) => {
                connected = false;
                console.warn('[WS] Erreur connexion locale :', error);
                if (callbacks.onConnectionStatus) {
                    callbacks.onConnectionStatus({ connected: false, source: 'local' });
                }
                // Reconnexion après 3 secondes
                reconnectTimer = setTimeout(() => connect(), 3000);
            }
        );
    }

    /**
     * Abonne aux trois topics locaux.
     */
    function _subscribe() {
        // Messages chat (envoyés + reçus après chiffrement/déchiffrement)
        stompClient.subscribe('/topic/messages', (frame) => {
            try {
                const msg = JSON.parse(frame.body);
                if (callbacks.onMessage) callbacks.onMessage(msg);
            } catch (e) {
                console.error('[WS] Erreur parsing message :', e);
            }
        });

        // Événements cryptographiques
        stompClient.subscribe('/topic/crypto-events', (frame) => {
            try {
                const event = JSON.parse(frame.body);
                if (callbacks.onCryptoEvent) callbacks.onCryptoEvent(event);
            } catch (e) {
                console.error('[WS] Erreur parsing crypto-event :', e);
            }
        });

        // Statut de connexion au serveur central
        stompClient.subscribe('/topic/connection-status', (frame) => {
            try {
                const status = JSON.parse(frame.body);
                if (callbacks.onConnectionStatus) callbacks.onConnectionStatus(status);
            } catch (e) {
                console.error('[WS] Erreur parsing connection-status :', e);
            }
        });
    }

    /**
     * Envoie un message via REST (le backend fait la crypto).
     * @param {string} recipient - Nom du destinataire
     * @param {string} content   - Contenu en clair
     * @returns {Promise<Response>}
     */
    async function sendMessage(recipient, content) {
        const response = await fetch('/api/chat/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ recipient, content })
        });
        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            throw new Error(err.error || `HTTP ${response.status}`);
        }
        return response;
    }

    /**
     * Vérifie si la connexion locale est établie.
     */
    function isConnected() {
        return connected;
    }

    return { connect, sendMessage, isConnected, callbacks };
})();
