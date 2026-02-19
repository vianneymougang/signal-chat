package cm.iusjc.signalchat.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message chiffre echange entre clients via le serveur de relai.
 * Toutes les donnees binaires sont encodees en Base64.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedMessage {

    /** Expediteur */
    private String sender;

    /** Destinataire */
    private String recipient;

    /** Ciphertext AES-256-CBC (Base64) */
    private String ciphertext;

    /** Vecteur d'initialisation AES (Base64, 16 bytes) */
    private String iv;

    /** HMAC-SHA256 du ciphertext (Base64, 32 bytes) */
    private String hmac;

    /** Cle d'identite publique X25519 de l'expediteur (Base64) - header X3DH */
    private String senderIdentityKey;

    /** Cle de signature publique Ed25519 de l'expediteur (Base64) - header X3DH */
    private String senderIdentitySigningKey;

    /** Cle ephemere publique X25519 de l'expediteur (Base64) - header X3DH */
    private String senderEphemeralKey;

    /** ID de la One-Time Pre-Key utilisee (null si absent) */
    private Integer usedOneTimePreKeyId;

    /** Numero de sequence dans la chaine courante */
    private int messageNumber;

    /** Longueur de la chaine d'envoi precedente */
    private int previousChainLength;

    /** Cle publique de ratchet DH courante de l'expediteur (Base64) */
    private String senderRatchetKey;
}
