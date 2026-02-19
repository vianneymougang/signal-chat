package cm.iusjc.signalchat.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree du serveur SignalChat.
 * Le serveur est un relai pur : il stocke les cles publiques
 * et relaie les messages chiffres SANS jamais voir le contenu en clair.
 */
@SpringBootApplication
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
