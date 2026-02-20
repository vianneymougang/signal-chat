package cm.iusjc.signalchat.client.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Configuration du RestTemplate pour les appels REST vers le serveur.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        // Bypass l'interstitiel ngrok free tier sur toutes les requetes HTTP sortantes
        restTemplate.setInterceptors(List.of((request, body, execution) -> {
            request.getHeaders().set("ngrok-skip-browser-warning", "true");
            return execution.execute(request, body);
        }));
        return restTemplate;
    }
}
