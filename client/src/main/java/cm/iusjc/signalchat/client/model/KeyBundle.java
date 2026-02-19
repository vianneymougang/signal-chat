package cm.iusjc.signalchat.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bundle de cles publiques d'un utilisateur (miroir du modele serveur).
 * Recu du serveur lors de la resolution du Pre-Key Bundle.
 * Toutes les cles sont encodees en Base64.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyBundle {

    /** Nom d'utilisateur */
    private String username;

    /** Cle d'identite publique X25519 (Base64, 32 bytes) - pour DH */
    private String identityKey;

    /** Cle de signature publique Ed25519 (Base64, 32 bytes) - pour verification XEdDSA */
    private String identitySigningKey;

    /** Cle pre-signee publique X25519 (Base64, 32 bytes) */
    private String signedPreKey;

    /** Identifiant de la Signed Pre-Key */
    private int signedPreKeyId;

    /** Signature XEdDSA de la Signed Pre-Key (Base64, 64 bytes) */
    private String signedPreKeySignature;

    /** One-Time Pre-Keys publiques X25519 disponibles (Base64, 32 bytes chacune) */
    private List<String> oneTimePreKeys;
}
