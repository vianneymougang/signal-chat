package cm.iusjc.signalchat.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message en clair cote interface utilisateur.
 * N'est jamais transmis sur le reseau dans cet etat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String sender;
    private String content;
    private long timestamp;
}
