package cm.iusjc.signalchat.server.service;

import cm.iusjc.signalchat.server.model.KeyBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de stockage des bundles de cles publiques en memoire.
 * Utilise un ConcurrentHashMap pour la thread-safety.
 * Le serveur ne stocke que les cles PUBLIQUES.
 */
@Slf4j
@Service
public class KeyStoreService {

    /** Stockage en memoire : username -> KeyBundle */
    private final ConcurrentHashMap<String, KeyBundle> keyBundles = new ConcurrentHashMap<>();

    /** Compteur d'OPK consommees par utilisateur (pour retrouver l'index original) */
    private final ConcurrentHashMap<String, Integer> opkConsumedCount = new ConcurrentHashMap<>();

    /**
     * Enregistre ou met a jour le bundle de cles d'un utilisateur.
     */
    public void registerKeyBundle(KeyBundle keyBundle) {
        keyBundles.put(keyBundle.getUsername(), keyBundle);
        opkConsumedCount.remove(keyBundle.getUsername());
        log.info("Bundle de cles enregistre pour l'utilisateur : {}", keyBundle.getUsername());
        log.debug("  -> identityKey      : {}...", truncate(keyBundle.getIdentityKey()));
        log.debug("  -> signedPreKey     : {}...", truncate(keyBundle.getSignedPreKey()));
        log.debug("  -> oneTimePreKeys   : {} cle(s)",
                  keyBundle.getOneTimePreKeys() != null ? keyBundle.getOneTimePreKeys().size() : 0);
    }

    /**
     * Retourne le bundle de cles d'un utilisateur.
     * Consomme et retire la premiere One-Time Pre-Key disponible.
     */
    public Optional<KeyBundle> getKeyBundle(String username) {
        KeyBundle bundle = keyBundles.get(username);
        if (bundle == null) {
            log.warn("Aucun bundle de cles trouve pour : {}", username);
            return Optional.empty();
        }
        // Consommer une One-Time Pre-Key si disponible
        // L'OPK consommee est retiree du stockage et renvoyee avec son index original
        KeyBundle response = KeyBundle.builder()
                .username(bundle.getUsername())
                .identityKey(bundle.getIdentityKey())
                .identitySigningKey(bundle.getIdentitySigningKey())
                .signedPreKey(bundle.getSignedPreKey())
                .signedPreKeyId(bundle.getSignedPreKeyId())
                .signedPreKeySignature(bundle.getSignedPreKeySignature())
                .build();

        if (bundle.getOneTimePreKeys() != null && !bundle.getOneTimePreKeys().isEmpty()) {
            String consumedOpk = bundle.getOneTimePreKeys().remove(0);
            int originalIndex = opkConsumedCount.getOrDefault(username, 0);
            opkConsumedCount.put(username, originalIndex + 1);

            response.setOneTimePreKeys(List.of(consumedOpk));
            response.setOneTimePreKeyId(originalIndex);
            log.debug("One-Time Pre-Key #{} consommee pour {} — {} restante(s)",
                      originalIndex, username, bundle.getOneTimePreKeys().size());
        } else {
            response.setOneTimePreKeys(List.of());
            log.warn("Aucune One-Time Pre-Key disponible pour {} — utilisation sans OPK", username);
        }
        return Optional.of(response);
    }

    /**
     * Retourne la liste des noms d'utilisateurs ayant un bundle enregistre.
     */
    public Set<String> getRegisteredUsers() {
        return keyBundles.keySet();
    }

    /** Tronque une chaine Base64 pour l'affichage dans les logs */
    private String truncate(String s) {
        if (s == null || s.length() <= 16) return s;
        return s.substring(0, 8) + "..." + s.substring(s.length() - 8);
    }
}
