package cm.iusjc.signalchat.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree du client SignalChat.
 * Le client effectue toute la cryptographie en local :
 * - Generation des cles (X25519, Ed25519)
 * - Protocole X3DH pour l'etablissement de session
 * - Double Ratchet pour le chiffrement de chaque message
 * - AES-256-CBC + HMAC-SHA256 pour la protection des donnees
 */
@SpringBootApplication
public class ClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }
}
