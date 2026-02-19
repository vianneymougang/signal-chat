package cm.iusjc.signalchat.server.controller;

import cm.iusjc.signalchat.server.model.KeyBundle;
import cm.iusjc.signalchat.server.service.KeyStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Controller REST pour la gestion des bundles de cles publiques.
 * Expose trois endpoints :
 * - POST /api/keys/register — enregistre un KeyBundle
 * - GET  /api/keys/bundle/{username} — retourne le Pre-Key Bundle
 * - GET  /api/keys/users — liste les utilisateurs enregistres
 */
@Slf4j
@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class KeyBundleController {

    private final KeyStoreService keyStoreService;

    /**
     * Enregistre le bundle de cles publiques d'un utilisateur.
     * Appele au demarrage du client apres la generation des cles.
     */
    @PostMapping("/register")
    public ResponseEntity<String> registerKeyBundle(@RequestBody KeyBundle keyBundle) {
        log.info("Requete d'enregistrement de cles pour : {}", keyBundle.getUsername());
        keyStoreService.registerKeyBundle(keyBundle);
        return ResponseEntity.ok("Bundle de cles enregistre pour " + keyBundle.getUsername());
    }

    /**
     * Retourne le Pre-Key Bundle d'un utilisateur pour initier un X3DH.
     * Consomme une One-Time Pre-Key au passage.
     */
    @GetMapping("/bundle/{username}")
    public ResponseEntity<KeyBundle> getKeyBundle(@PathVariable String username) {
        log.info("Requete de Pre-Key Bundle pour : {}", username);
        return keyStoreService.getKeyBundle(username)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Liste tous les utilisateurs ayant enregistre un bundle de cles.
     */
    @GetMapping("/users")
    public ResponseEntity<Set<String>> getRegisteredUsers() {
        return ResponseEntity.ok(keyStoreService.getRegisteredUsers());
    }
}
