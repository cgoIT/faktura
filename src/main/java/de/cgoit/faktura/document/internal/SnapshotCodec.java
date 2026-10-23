package de.cgoit.faktura.document.internal;

import de.cgoit.faktura.document.internal.DocumentRepository.Snapshots;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts the snapshots of a document to and from the JSON text stored in the document table.
 */
@Component
public class SnapshotCodec {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public Snapshots write(SenderSnapshot sender, RecipientSnapshot recipient, TaxSnapshot tax) {
        return new Snapshots(mapper.writeValueAsString(sender), mapper.writeValueAsString(recipient),
                mapper.writeValueAsString(tax));
    }

    public SenderSnapshot sender(Snapshots snapshots) {
        return mapper.readValue(snapshots.sender(), SenderSnapshot.class);
    }

    public RecipientSnapshot recipient(String json) {
        return mapper.readValue(json, RecipientSnapshot.class);
    }

    public RecipientSnapshot recipient(Snapshots snapshots) {
        return recipient(snapshots.recipient());
    }

    public TaxSnapshot tax(Snapshots snapshots) {
        return mapper.readValue(snapshots.tax(), TaxSnapshot.class);
    }
}
