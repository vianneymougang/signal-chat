package cm.iusjc.signalchat.client.service;

import cm.iusjc.signalchat.client.crypto.IdentityKeyPair;
import cm.iusjc.signalchat.client.crypto.KeyManager;
import cm.iusjc.signalchat.client.model.KeyBundle;
import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Service d'enregistrement des cles cryptographiques aupres du serveur.
 *
 * Responsabilites :
 * - Generer toutes les cles au demarrage (identite XEdDSA, SPK, OPK, ratchet)
 * - Publier le KeyBundle (cles PUBLIQUES uniquement) sur le serveur via REST
 * - Conserver les cles PRIVEES en memoire pour les operations crypto
 *
 * SECURITE : Les cles privees (seeds) ne quittent JAMAIS ce service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyRegistrationService {

    private final KeyManager keyManager;
    private final CryptoLogger cryptoLogger;
    private final RestTemplate restTemplate;
    private final ServerConnectionService serverConnectionService;

    @Value("${signalchat.username}")
    private String username;

    @Value("${signalchat.server-url}")
    private String serverUrl;

    @Value("${signalchat.one-time-prekeys-count:20}")
    private int oneTimePreKeysCount;

    /** Paire de cles identite XEdDSA (seed, X25519 pub, Ed25519 pub) */
    @Getter
    private IdentityKeyPair identityKeyPair;

    /** Seed (cle privee) de la Signed Pre-Key */
    @Getter
    private byte[] signedPreKeySeed;

    /** Cle publique de la Signed Pre-Key */
    @Getter
    private byte[] signedPreKeyPublic;

    /** One-Time Pre-Keys : [i][0]=seed prive, [i][1]=cle publique */
    private byte[][][] oneTimePreKeys;

    /**
     * Genere toutes les cles et les enregistre aupres du serveur.
     * Declenche apres le demarrage complet de Spring Boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerKeys() {
        log.info("=== Initialisation des cles cryptographiques pour {} ===", username);

        // --- 1. Paire de cles identite XEdDSA ---
        identityKeyPair = keyManager.generateIdentityKeyPair();
        log.debug("  Cle identite X25519  : {}", cryptoLogger.truncateHex(identityKeyPair.getX25519PublicKey()));
        log.debug("  Cle identite Ed25519 : {}", cryptoLogger.truncateHex(identityKeyPair.getEd25519PublicKey()));

        // --- 2. Signed Pre-Key + signature XEdDSA ---
        byte[][] spk = keyManager.generateSignedPreKeyPair();
        signedPreKeySeed   = spk[0];
        signedPreKeyPublic = spk[1];
        byte[] spkSignature = keyManager.signXEdDSA(identityKeyPair.getSeed(), signedPreKeyPublic);
        log.debug("  SPK signee via XEdDSA | sig={}", cryptoLogger.truncateHex(spkSignature));

        // --- 3. One-Time Pre-Keys ---
        oneTimePreKeys = keyManager.generateOneTimePreKeys(oneTimePreKeysCount);
        log.debug("  {} One-Time Pre-Keys generees", oneTimePreKeysCount);

        // --- 4. Construction du KeyBundle (cles PUBLIQUES uniquement) ---
        List<String> opkPublics = new ArrayList<>();
        for (byte[][] opk : oneTimePreKeys) {
            opkPublics.add(Base64.getEncoder().encodeToString(opk[1]));
        }

        KeyBundle bundle = KeyBundle.builder()
                .username(username)
                .identityKey(Base64.getEncoder().encodeToString(identityKeyPair.getX25519PublicKey()))
                .identitySigningKey(Base64.getEncoder().encodeToString(identityKeyPair.getEd25519PublicKey()))
                .signedPreKey(Base64.getEncoder().encodeToString(signedPreKeyPublic))
                .signedPreKeyId(0)
                .signedPreKeySignature(Base64.getEncoder().encodeToString(spkSignature))
                .oneTimePreKeys(opkPublics)
                .build();

        // --- 5. Enregistrement sur le serveur ---
        try {
            restTemplate.postForEntity(serverUrl + "/api/keys/register", bundle, String.class);
            log.info("Cles enregistrees aupres du serveur pour l'utilisateur : {}", username);
        } catch (Exception e) {
            log.error("Echec de l'enregistrement des cles aupres du serveur : {}", e.getMessage());
            log.warn("Le client demarre sans enregistrement — les messages ne peuvent pas etre envoyes");
            return;
        }

        // --- 6. Connexion WebSocket apres enregistrement des cles ---
        serverConnectionService.connect();
    }

    /**
     * Retourne le seed prive de la One-Time Pre-Key d'index donne.
     *
     * @param id index de la OPK (tel que recu dans le message X3DH)
     * @return seed prive de 32 bytes, ou null si l'index est invalide
     */
    public byte[] getOneTimePreKeySeed(int id) {
        if (oneTimePreKeys == null || id < 0 || id >= oneTimePreKeys.length) {
            log.warn("OPK index {} invalide ou OPKs non initialisees", id);
            return null;
        }
        return oneTimePreKeys[id][0];
    }
}
