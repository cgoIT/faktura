package de.cgoit.faktura.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.cgoit.faktura.audit.internal.AuditLogRepository;
import de.cgoit.faktura.shared.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit log of legally relevant events (finalizing, cancelling). An entry is written in the transaction
 * of the event it describes, so it exists exactly when the event happened. The log is never changed or deleted.
 */
@Service
public class AuditService {

    private final AuditLogRepository entries;
    private final Clock clock;
    private final CurrentUser currentUser;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AuditService(AuditLogRepository entries, Clock clock, CurrentUser currentUser) {
        this.entries = entries;
        this.clock = clock;
        this.currentUser = currentUser;
    }

    /**
     * Records an event together with the name of the current user.
     *
     * @param detail technical details such as the document number; must not contain personal data
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditObjectType objectType, String objectId, AuditAction action, Map<String, String> detail) {
        entries.insert(UUID.randomUUID().toString(), Instant.now(clock).toString(), objectType, objectId, action,
                mapper.writeValueAsString(detail), currentUser.username());
    }

    /**
     * Lists the actions logged for an object, oldest first.
     */
    @Transactional(readOnly = true)
    public List<AuditAction> actionsOf(AuditObjectType objectType, String objectId) {
        return entries.findActions(objectType, objectId);
    }
}
