package cm.iusjc.signalchat.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Evenement cryptographique capture pour le monitoring.
 * Publie via WebSocket vers l'interface de monitoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoEvent {

    /** Timestamp de l'evenement en millisecondes */
    private long timestamp;

    /** Type de l'evenement */
    private CryptoEventType type;

    /** Entree en hex tronque (ex: "7a3f89c1...d2e4f6a8") */
    private String inputHex;

    /** Sortie en hex tronque */
    private String outputHex;

    /** Description lisible de l'operation */
    private String description;
}
