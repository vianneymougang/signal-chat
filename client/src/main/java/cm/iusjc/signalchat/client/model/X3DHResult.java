package cm.iusjc.signalchat.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultat du protocole X3DH cote initiateur (Alice).
 * Contient l'etat de session initial et les informations
 * a transmettre a Bob pour qu'il reconstitue le meme secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class X3DHResult {

    /** Cle ephemere publique generee par Alice (Base64, 32 bytes) */
    private byte[] ephemeralPublicKey;

    /** Identifiant de la One-Time Pre-Key de Bob utilisee (null si aucune) */
    private Integer usedOneTimePreKeyId;

    /** Master secret derive de X3DH (32 bytes) - servira a initialiser le Double Ratchet */
    private byte[] masterSecret;
}
