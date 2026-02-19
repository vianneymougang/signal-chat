package cm.iusjc.signalchat.server.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bundle de cles publiques d'un utilisateur, stocke par le serveur.
 * Toutes les cles sont encodees en Base64.
 * Le serveur stocke uniquement les cles PUBLIQUES — jamais les cles privees.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyBundle {

    /** Nom d'utilisateur proprietaire de ce bundle */
    private String username;

    /** Cle d'identite publique X25519 (Base64, 32 bytes) — pour le DH X3DH */
    private String identityKey;

    /** Cle de signature publique Ed25519 (Base64, 32 bytes) — pour la verification XEdDSA */
    private String identitySigningKey;

    /** Cle pre-signee publique X25519 (Base64, 32 bytes) */
    private String signedPreKey;

    /** Identifiant numerique de la Signed Pre-Key */
    private int signedPreKeyId;

    /** Signature XEdDSA de la Signed Pre-Key (Base64, 64 bytes) */
    private String signedPreKeySignature;

    /** Liste des One-Time Pre-Keys publiques X25519 (Base64, 32 bytes chacune) */
    private List<String> oneTimePreKeys;
}
