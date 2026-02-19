package cm.iusjc.signalchat.client.monitoring;

import cm.iusjc.signalchat.client.model.CryptoEvent;
import cm.iusjc.signalchat.client.model.CryptoEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilitaire de journalisation des evenements cryptographiques.
 * Capture chaque operation crypto pour le monitoring en temps reel.
 * Les cles sont affichees en hex tronque : 8 premiers + 8 derniers.
 */
@Slf4j
@Component
public class CryptoLogger {

    private final List<CryptoEvent> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * Journalise un evenement cryptographique.
     * @param type        type de l operation
     * @param input       donnees d entree (bytes bruts)
     * @param output      donnees de sortie (bytes bruts)
     * @param description description lisible de l operation
     */
    public void logEvent(CryptoEventType type, byte[] input, byte[] output, String description) {
        String inputHex  = input  != null ? truncateHex(input)  : "—";
        String outputHex = output != null ? truncateHex(output) : "—";

        CryptoEvent event = CryptoEvent.builder()
                .timestamp(System.currentTimeMillis())
                .type(type)
                .inputHex(inputHex)
                .outputHex(outputHex)
                .description(description)
                .build();

        events.add(event);
        log.debug("[CRYPTO] {} | {} | in={} | out={}", type, description, inputHex, outputHex);
    }

    /** Version simplifiee sans input/output binaires. */
    public void logEvent(CryptoEventType type, String description) {
        logEvent(type, null, null, description);
    }

    /**
     * Tronque des bytes en hex lisible.
     * Format : "0x{8 premiers chars}...{8 derniers chars}"
     */
    public String truncateHex(byte[] data) {
        if (data == null || data.length == 0) return "[]";
        String fullHex = toHex(data);
        if (fullHex.length() <= 16) { return "0x" + fullHex; }
        return "0x" + fullHex.substring(0, 8) + "..." + fullHex.substring(fullHex.length() - 8);
    }

    /** Convertit des bytes en chaine hexadecimale complete. */
    public String toHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) { sb.append(String.format("%02x", b & 0xff)); }
        return sb.toString();
    }

    /** Retourne la liste des evenements (pour les tests). */
    public List<CryptoEvent> getEvents() { return Collections.unmodifiableList(events); }

    /** Efface la liste des evenements. */
    public void clearEvents() { events.clear(); }
}
