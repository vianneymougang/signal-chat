package cm.iusjc.signalchat.server.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message chiffre en transit sur le reseau.
 * Le serveur ne peut pas lire le contenu — il relaie uniquement.
 * Toutes les donnees binaires sont encodees en Base64.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedMessage {

    /** Nom de l'expediteur */
    private String sender;

    /** Nom du destinataire */
    private String recipient;

    /** Ciphertext AES-256-CBC (Base64) */
    private String ciphertext;

    /** Vecteur d'initialisation AES (Base64, 16 bytes) */
    private String iv;

    /** HMAC-SHA256 du ciphertext (Base64, 32 bytes) */
    private String hmac;

    /** Cle d'identite publique X25519 de l'expediteur (Base64, 32 bytes) — pour X3DH */
    private String senderIdentityKey;

    /** Cle de signature publique Ed25519 de l'expediteur (Base64, 32 bytes) — pour XEdDSA */
    private String senderIdentitySigningKey;

    /** Cle ephemere publique X25519 de l'expediteur (Base64, 32 bytes) — pour X3DH */
    private String senderEphemeralKey;

    /** Identifiant de la One-Time Pre-Key utilisee (null si pas de OPK) */
    private Integer usedOneTimePreKeyId;

    /** Numero de sequence du message dans la chaine courante */
    private int messageNumber;

    /** Longueur de la chaine d'envoi precedente (pour le DH ratchet) */
    private int previousChainLength;

    /** Cle publique de ratchet DH courante de l'expediteur (Base64, 32 bytes) */
    private String senderRatchetKey;
}
