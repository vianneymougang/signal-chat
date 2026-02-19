package cm.iusjc.signalchat.client.model;

/**
 * Types d'evenements cryptographiques captures par le CryptoLogger.
 * Utilises pour le monitoring en temps reel dans l'interface.
 */
public enum CryptoEventType {
    /** Generation d'une paire de cles (identite, pre-signee, ephemere, one-time) */
    KEY_GEN,

    /** Premier echange DH du protocole X3DH : ECDH(IK_A, SPK_B) */
    X3DH_DH1,

    /** Deuxieme echange DH du protocole X3DH : ECDH(EK_A, IK_B) */
    X3DH_DH2,

    /** Troisieme echange DH du protocole X3DH : ECDH(EK_A, SPK_B) */
    X3DH_DH3,

    /** Quatrieme echange DH du protocole X3DH : ECDH(EK_A, OPK_B) */
    X3DH_DH4,

    /** Calcul du master secret X3DH (concatenation des DH + HKDF) */
    X3DH_MASTER_SECRET,

    /** Initialisation de la session apres X3DH */
    X3DH_SESSION_INIT,

    /** Etape de ratchet DH (rotation des cles de chaine) */
    RATCHET_STEP,

    /** Avancement du ratchet symetrique (derivation messageKey + avancement chainKey) */
    SYMMETRIC_RATCHET,

    /** Derivation de cle via HKDF */
    HKDF_DERIVE,

    /** Chiffrement AES-256-CBC */
    ENCRYPT,

    /** Dechiffrement AES-256-CBC */
    DECRYPT,

    /** Calcul d'un HMAC-SHA256 */
    HMAC_COMPUTE,

    /** Verification d'un HMAC-SHA256 */
    HMAC_VERIFY,

    /** Verification d'une signature XEdDSA */
    SIGNATURE_VERIFY
}
