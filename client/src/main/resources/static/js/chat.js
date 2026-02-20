/**
 * chat.js — Gestion des conversations et affichage des bulles
 *
 * Dépend de : websocket.js (SignalWebSocket), CURRENT_USER (injecté par Thymeleaf)
 */
const Chat = (() => {
    let currentRecipient = null;
    const pendingNotifications = {}; // { username: count }
    let usersPollingInterval = null;
    let previousUsers = []; // pour détecter les déconnexions

    // =========================================================================
    // Initialisation
    // =========================================================================

    function init() {
        // Enregistrer le callback de réception de message
        SignalWebSocket.callbacks.onMessage = handleIncomingMessage;
        SignalWebSocket.callbacks.onConnectionStatus = updateConnectionIndicator;

        // Initialiser le statut depuis le backend
        fetch('/api/chat/status')
            .then(r => r.json())
            .then(s => updateConnectionIndicator(s))
            .catch(() => {});

        // Bouton Envoyer + touche Enter
        document.getElementById('send-button')
            .addEventListener('click', sendMessage);
        document.getElementById('message-input')
            .addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    sendMessage();
                }
            });

        // Démarrer le polling des utilisateurs
        loadUsers();
        usersPollingInterval = setInterval(loadUsers, 5000);
    }

    // =========================================================================
    // Utilisateurs en ligne
    // =========================================================================

    function loadUsers() {
        fetch('/api/chat/users')
            .then(r => r.json())
            .then(users => {
                // Détecter les utilisateurs qui se sont déconnectés
                previousUsers.forEach(prev => {
                    if (!users.includes(prev)) {
                        showSystemMessage(`${prev} s'est déconnecté`);
                        // Si on était en train de lui parler, désactiver l'input
                        if (prev === currentRecipient) {
                            const input = document.getElementById('message-input');
                            input.placeholder = `${prev} est déconnecté...`;
                        }
                    }
                });
                previousUsers = [...users];
                renderUserList(users);
            })
            .catch(() => {});
    }

    function renderUserList(users) {
        const container = document.getElementById('users-list');
        container.innerHTML = '';

        if (!users || users.length === 0) {
            container.innerHTML = '<span class="no-users">Aucun utilisateur en ligne</span>';
            return;
        }

        users.forEach(username => {
            const badge = document.createElement('button');
            badge.className = 'user-badge' + (username === currentRecipient ? ' active' : '');
            badge.dataset.username = username;
            badge.addEventListener('click', () => selectRecipient(username));

            // Texte du badge
            const label = document.createTextNode(username);
            badge.appendChild(label);

            // Notification de messages en attente
            const notifCount = pendingNotifications[username] || 0;
            if (notifCount > 0) {
                const dot = document.createElement('span');
                dot.className = 'notif-dot';
                dot.textContent = notifCount > 9 ? '9+' : notifCount;
                badge.appendChild(dot);
            }

            container.appendChild(badge);
        });
    }

    // =========================================================================
    // Sélection du destinataire
    // =========================================================================

    function selectRecipient(username) {
        currentRecipient = username;

        // Effacer les notifications pour cet utilisateur
        delete pendingNotifications[username];

        // Mettre à jour l'UI de la liste (refresh badges)
        loadUsers();

        // Activer l'input
        const input = document.getElementById('message-input');
        const button = document.getElementById('send-button');
        input.placeholder = `Message à ${username}...`;
        input.disabled = false;
        button.disabled = false;
        input.focus();

        // Vider et recharger les messages
        const container = document.getElementById('messages-container');
        container.innerHTML = '';
        document.getElementById('conversation-placeholder')?.remove();

        // Charger l'historique depuis le backend
        fetch(`/api/chat/messages/${encodeURIComponent(username)}`)
            .then(r => r.json())
            .then(messages => {
                messages.forEach(msg => displayMessage(msg, false));
                scrollToBottom();
            })
            .catch(() => {});
    }

    // =========================================================================
    // Envoi de message
    // =========================================================================

    function sendMessage() {
        const input = document.getElementById('message-input');
        const content = input.value.trim();

        if (!content || !currentRecipient) return;

        input.value = '';
        input.disabled = true;
        document.getElementById('send-button').disabled = true;

        SignalWebSocket.sendMessage(currentRecipient, content)
            .then(() => {
                // Le message sera affiché via le push STOMP /topic/messages
            })
            .catch(err => {
                console.error('[Chat] Erreur envoi :', err);
                // Réafficher le contenu en cas d'erreur
                input.value = content;
                showError(`Erreur d'envoi : ${err.message}`);
            })
            .finally(() => {
                input.disabled = false;
                document.getElementById('send-button').disabled = false;
                input.focus();
            });
    }

    // =========================================================================
    // Réception et affichage
    // =========================================================================

    function handleIncomingMessage(msg) {
        const otherUser = msg.sender === CURRENT_USER ? msg.recipient : msg.sender;

        // Notification si la conversation n'est pas active
        if (otherUser !== currentRecipient) {
            pendingNotifications[otherUser] = (pendingNotifications[otherUser] || 0) + 1;
            loadUsers(); // Rafraîchir les badges
        }

        // Afficher seulement si la conversation est active
        if (otherUser === currentRecipient || msg.sender === CURRENT_USER) {
            if (otherUser === currentRecipient || msg.recipient === currentRecipient) {
                displayMessage(msg, true);
            }
        }
    }

    /**
     * Affiche une bulle de message dans le container.
     * @param {Object} msg       - ChatMessage { sender, recipient, content, timestamp }
     * @param {boolean} animate  - Activer l'animation slide-in
     */
    function displayMessage(msg, animate) {
        const container = document.getElementById('messages-container');
        const isSent = msg.sender === CURRENT_USER;

        const bubble = document.createElement('div');
        bubble.className = `message-bubble ${isSent ? 'sent' : 'received'}`;
        if (!animate) bubble.style.animation = 'none';

        const contentEl = document.createElement('div');
        contentEl.className = 'bubble-content';
        contentEl.textContent = msg.content;

        const meta = document.createElement('div');
        meta.className = 'bubble-meta';

        const time = document.createElement('span');
        time.className = 'bubble-time';
        time.textContent = formatTime(msg.timestamp);

        const lock = document.createElement('span');
        lock.className = 'bubble-lock';
        lock.textContent = '🔒';
        lock.title = 'Message chiffré E2EE (AES-256 + Double Ratchet)';

        meta.appendChild(time);
        meta.appendChild(lock);
        bubble.appendChild(contentEl);
        bubble.appendChild(meta);
        container.appendChild(bubble);

        if (animate) scrollToBottom();
    }

    // =========================================================================
    // Statut de connexion
    // =========================================================================

    function updateConnectionIndicator(status) {
        const indicator = document.getElementById('connection-indicator');
        const banner = document.getElementById('server-unavailable-banner');

        if (indicator) {
            if (status.connected) {
                indicator.className = 'indicator online';
                indicator.textContent = '● Connecté';
            } else {
                indicator.className = 'indicator offline';
                indicator.textContent = '○ Déconnecté';
            }
        }

        if (banner) {
            if (status.connected) {
                banner.classList.add('hidden');
            } else {
                banner.classList.remove('hidden');
            }
        }
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    function scrollToBottom() {
        const container = document.getElementById('messages-container');
        container.scrollTop = container.scrollHeight;
    }

    function formatTime(timestamp) {
        const date = new Date(timestamp);
        return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    }

    function showError(message) {
        const container = document.getElementById('messages-container');
        const error = document.createElement('div');
        error.style.cssText = 'text-align:center;color:#ef4444;font-size:0.8rem;padding:8px;';
        error.textContent = message;
        container.appendChild(error);
        setTimeout(() => error.remove(), 5000);
    }

    function showSystemMessage(message) {
        const container = document.getElementById('messages-container');
        const el = document.createElement('div');
        el.style.cssText = 'text-align:center;color:#f59e0b;font-size:0.75rem;padding:6px 16px;' +
            'background:rgba(245,158,11,0.08);border-radius:12px;margin:4px auto;max-width:80%;';
        el.textContent = message;
        container.appendChild(el);
        scrollToBottom();
    }

    return { init, showSystemMessage };
})();

// Démarrer quand le DOM est prêt
document.addEventListener('DOMContentLoaded', () => {
    SignalWebSocket.connect();
    Chat.init();
});