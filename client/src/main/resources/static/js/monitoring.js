/**
 * monitoring.js — Panneau de monitoring cryptographique
 *
 * Affiche les CryptoEvents reçus via STOMP en temps réel.
 * Dépend de : websocket.js (SignalWebSocket)
 */
const CryptoMonitor = (() => {

    // Mapping type → style visuel
    const EVENT_STYLES = {
        'KEY_GEN':            { emoji: '🔑', color: '#569cd6', css: 'KEY_GEN' },
        'X3DH_DH1':          { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'X3DH_DH2':          { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'X3DH_DH3':          { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'X3DH_DH4':          { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'X3DH_MASTER_SECRET': { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'X3DH_SESSION_INIT':  { emoji: '🤝', color: '#c586c0', css: 'X3DH' },
        'RATCHET_STEP':       { emoji: '🔄', color: '#ce9178', css: 'RATCHET' },
        'SYMMETRIC_RATCHET':  { emoji: '🔄', color: '#ce9178', css: 'RATCHET' },
        'HKDF_DERIVE':        { emoji: '🔄', color: '#ce9178', css: 'HKDF' },
        'ENCRYPT':            { emoji: '🔐', color: '#6a9955', css: 'ENCRYPT' },
        'DECRYPT':            { emoji: '🔓', color: '#4ec9b0', css: 'DECRYPT' },
        'HMAC_COMPUTE':       { emoji: '✅', color: '#808080', css: 'HMAC' },
        'HMAC_VERIFY':        { emoji: '✅', color: '#808080', css: 'HMAC' },
        'SIGNATURE_VERIFY':   { emoji: '🔏', color: '#dcdcaa', css: 'SIGNATURE' }
    };

    const DEFAULT_STYLE = { emoji: '⚙️', color: '#808080', css: 'HMAC' };

    // Buffer des événements bruts pour l'export
    const rawEvents = [];

    // =========================================================================
    // Initialisation
    // =========================================================================

    function init() {
        SignalWebSocket.callbacks.onCryptoEvent = addEvent;

        // Charger les événements existants (générés avant l'ouverture de la page)
        loadExistingEvents();

        // Boutons d'action
        document.getElementById('clear-logs-btn')
            .addEventListener('click', clearLogs);
        document.getElementById('export-logs-btn')
            .addEventListener('click', exportLogs);
    }

    // =========================================================================
    // Chargement initial
    // =========================================================================

    function loadExistingEvents() {
        fetch('/api/chat/crypto-events')
            .then(r => r.json())
            .then(events => {
                if (Array.isArray(events) && events.length > 0) {
                    events.forEach(e => addEvent(e, false));
                    scrollToBottom(true);
                }
            })
            .catch(() => {});
    }

    // =========================================================================
    // Ajout d'un événement
    // =========================================================================

    /**
     * Ajoute un événement crypto dans le panneau.
     * @param {Object}  event   - CryptoEvent { timestamp, type, inputHex, outputHex, description }
     * @param {boolean} animate - Activer l'animation fade-in (défaut: true)
     */
    function addEvent(event, animate = true) {
        rawEvents.push(event);

        const container = document.getElementById('crypto-log');
        const style = EVENT_STYLES[event.type] || DEFAULT_STYLE;

        const el = document.createElement('div');
        el.className = `crypto-event event-${style.css}`;
        if (!animate) el.style.animation = 'none';

        // En-tête : heure + type
        const header = document.createElement('div');
        header.className = 'event-header';

        const timeEl = document.createElement('span');
        timeEl.className = 'event-time';
        timeEl.textContent = `[${formatTimestamp(event.timestamp)}]`;

        const typeEl = document.createElement('span');
        typeEl.className = 'event-type';
        typeEl.style.color = style.color;
        typeEl.textContent = `${style.emoji} ${event.type}`;

        header.appendChild(timeEl);
        header.appendChild(typeEl);
        el.appendChild(header);

        const details = document.createElement('div');
        details.className = 'event-details';

        if (event.inputHex && event.inputHex !== '—') {
            details.appendChild(makeDetailLine('In:', event.inputHex));
        }
        if (event.outputHex && event.outputHex !== '—') {
            details.appendChild(makeDetailLine('Out:', event.outputHex));
        }
        if (event.description) {
            const desc = document.createElement('div');
            desc.className = 'event-description';
            desc.textContent = event.description;
            details.appendChild(desc);
        }

        el.appendChild(details);
        container.appendChild(el);

        // Auto-scroll si déjà en bas
        scrollToBottom(false);
    }

    /**
     * Crée une ligne de détail (label + valeur hex avec tooltip).
     */
    function makeDetailLine(label, hexValue) {
        const line = document.createElement('div');
        line.className = 'event-line';

        const labelEl = document.createElement('span');
        labelEl.className = 'event-label';
        labelEl.textContent = label;

        const valueEl = document.createElement('span');
        valueEl.className = 'event-value hex-value';
        // La valeur affichée est déjà tronquée par le backend (8+8 chars)
        valueEl.textContent = hexValue;
        // Le tooltip contiendrait la valeur complète si disponible
        // (le backend stocke uniquement la version tronquée, donc le title = la même valeur)
        valueEl.title = hexValue;

        line.appendChild(labelEl);
        line.appendChild(valueEl);
        return line;
    }

    // =========================================================================
    // Auto-scroll
    // =========================================================================

    /**
     * Scroll vers le bas sauf si l'utilisateur est en train de lire (tolérance 80px).
     * @param {boolean} force - Forcer le scroll même si l'utilisateur a remonté
     */
    function scrollToBottom(force = false) {
        const container = document.getElementById('crypto-log');
        const tolerance = 80;
        const isNearBottom =
            container.scrollHeight - container.scrollTop - container.clientHeight < tolerance;

        if (force || isNearBottom) {
            container.scrollTop = container.scrollHeight;
        }
    }

    // =========================================================================
    // Effacer
    // =========================================================================

    function clearLogs() {
        document.getElementById('crypto-log').innerHTML = '';
        rawEvents.length = 0;

        // Notifier le backend pour vider sa liste
        fetch('/api/chat/crypto-events', { method: 'DELETE' })
            .catch(() => {});
    }

    // =========================================================================
    // Exporter
    // =========================================================================

    function exportLogs() {
        if (rawEvents.length === 0) {
            alert('Aucun événement à exporter.');
            return;
        }

        const lines = rawEvents.map(e => {
            const style = EVENT_STYLES[e.type] || DEFAULT_STYLE;
            const parts = [
                `[${formatTimestamp(e.timestamp)}] ${style.emoji} ${e.type}`
            ];
            if (e.inputHex && e.inputHex !== '—')   parts.push(`  In:  ${e.inputHex}`);
            if (e.outputHex && e.outputHex !== '—') parts.push(`  Out: ${e.outputHex}`);
            if (e.description)                       parts.push(`  ${e.description}`);
            return parts.join('\n');
        });

        const content = [
            '=== SignalChat — Logs Cryptographiques ===',
            `Exporté le : ${new Date().toLocaleString('fr-FR')}`,
            `Utilisateur : ${CURRENT_USER}`,
            `Nombre d'événements : ${rawEvents.length}`,
            '==========================================',
            '',
            ...lines
        ].join('\n');

        const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
        const url = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = `crypto-logs-${Date.now()}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /**
     * Formate un timestamp Unix (ms) en HH:MM:SS.mmm
     */
    function formatTimestamp(ts) {
        const d = new Date(ts);
        const h  = String(d.getHours()).padStart(2, '0');
        const m  = String(d.getMinutes()).padStart(2, '0');
        const s  = String(d.getSeconds()).padStart(2, '0');
        const ms = String(d.getMilliseconds()).padStart(3, '0');
        return `${h}:${m}:${s}.${ms}`;
    }

    return { init };
})();

// Initialiser après le DOM (chat.js a déjà démarré SignalWebSocket.connect())
document.addEventListener('DOMContentLoaded', () => {
    CryptoMonitor.init();
});
