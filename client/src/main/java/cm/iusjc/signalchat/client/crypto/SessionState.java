package cm.iusjc.signalchat.client.crypto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Etat complet d'une session de chiffrement Double Ratchet.
 *
 * Contient toutes les cles de chaine et compteurs necessaires
 * au chiffrement/dechiffrement des messages.
 *
 * Tous les champs sont des tableaux de bytes bruts pour eviter
 * les problemes de serialisation avec les types Bouncy Castle.
 *
 * Cycle de vie :
 * 1. Initialisation via DoubleRatchetService.initSessionAlice/Bob() apres X3DH
 * 2. Mise a jour a chaque chiffrement/dechiffrement (avancement des chainKeys)
 * 3. Rotation des cles DH a chaque reception d'un nouveau ratchet public key
 */
@Getter
@Setter
@Builder
public class SessionState {

    /** Cle racine courante (32 bytes) — evolue a chaque etape DH ratchet */
    private byte[] rootKey;

    /**
     * Cle de chaine d'envoi courante (32 bytes).
     * Null si pas encore initialisee (cas Bob avant son premier envoi).
     */
    private byte[] sendingChainKey;

    /**
     * Cle de chaine de reception courante (32 bytes).
     * Null si pas encore de message recu.
     */
    private byte[] receivingChainKey;

    /** Cle privee X25519 de ratchet locale (seed de 32 bytes) */
    private byte[] localRatchetPrivateKey;

    /** Cle publique X25519 de ratchet locale (32 bytes) — incluse dans les headers de message */
    private byte[] localRatchetPublicKey;

    /**
     * Derniere cle publique X25519 de ratchet distante connue (32 bytes).
     * Null si aucun message recu depuis le dernier ratchet step.
     */
    private byte[] remoteRatchetPublicKey;

    /** Nombre de messages envoyes dans la chaine courante */
    @Builder.Default
    private int sendingMessageNumber = 0;

    /** Nombre de messages recus dans la chaine courante */
    @Builder.Default
    private int receivingMessageNumber = 0;

    /** Longueur de la chaine d'envoi precedente (pour la gestion des messages hors-ordre) */
    @Builder.Default
    private int previousSendingChainLength = 0;
}
