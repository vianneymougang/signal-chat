package cm.iusjc.signalchat.client.crypto;

import lombok.Getter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/**
 * Paire de cles d'identite XEdDSA.
 *
 * Un seul seed de 32 bytes derive deux paires de cles :
 * - X25519 : pour les echanges Diffie-Hellman (X3DH)
 * - Ed25519 : pour les signatures (XEdDSA)
 *
 * C'est l'essence du schema XEdDSA : les deux courbes partagent
 * le meme espace de scalaires, ce qui permet d'utiliser une seule
 * cle pour les deux usages.
 */
@Getter
public class IdentityKeyPair {

    /** Seed maitre de 32 bytes — ne doit JAMAIS quitter l'appareil */
    private final byte[] seed;

    /** Cle publique X25519 (32 bytes) — publiee dans le KeyBundle pour le DH */
    private final byte[] x25519PublicKey;

    /** Cle publique Ed25519 (32 bytes) — publiee dans le KeyBundle pour la verification */
    private final byte[] ed25519PublicKey;

    public IdentityKeyPair(byte[] seed, byte[] x25519PublicKey, byte[] ed25519PublicKey) {
        this.seed = seed.clone();
        this.x25519PublicKey = x25519PublicKey.clone();
        this.ed25519PublicKey = ed25519PublicKey.clone();
    }

    /**
     * Retourne la cle privee X25519 construite depuis le seed.
     * Utilisee pour les calculs DH dans X3DH et Double Ratchet.
     */
    public X25519PrivateKeyParameters getX25519PrivateKey() {
        return new X25519PrivateKeyParameters(seed, 0);
    }

    /**
     * Retourne la cle publique X25519 encapsulee.
     */
    public X25519PublicKeyParameters getX25519PublicKeyParams() {
        return new X25519PublicKeyParameters(x25519PublicKey, 0);
    }

    /**
     * Retourne la cle privee Ed25519 construite depuis le seed.
     * Utilisee pour signer la Signed Pre-Key (XEdDSA).
     */
    public Ed25519PrivateKeyParameters getEd25519PrivateKey() {
        return new Ed25519PrivateKeyParameters(seed, 0);
    }

    /**
     * Retourne la cle publique Ed25519 encapsulee.
     */
    public Ed25519PublicKeyParameters getEd25519PublicKeyParams() {
        return new Ed25519PublicKeyParameters(ed25519PublicKey, 0);
    }
}
