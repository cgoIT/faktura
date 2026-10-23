package de.cgoit.faktura.document;

import java.util.Optional;

import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.PlaceholderValues;
import de.cgoit.faktura.document.internal.RenderModelFactory;
import de.cgoit.faktura.document.internal.SnapshotCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives other modules the data of a finalized invoice that a follow-up letter needs, taken from the invoice's own
 * snapshots. Nothing is read from live master data.
 */
@Service
public class InvoiceLetterService {

    private final DocumentRepository documents;
    private final SnapshotCodec codec;
    private final PlaceholderValues placeholders;

    public InvoiceLetterService(DocumentRepository documents, SnapshotCodec codec, PlaceholderValues placeholders) {
        this.documents = documents;
        this.codec = codec;
        this.placeholders = placeholders;
    }

    /**
     * Returns the dunning basis of a finalized invoice, empty for a draft, an unknown id or a document that is no
     * invoice.
     */
    @Transactional(readOnly = true)
    public Optional<DunningBasis> dunningBasis(String invoiceId) {
        return documents.findById(invoiceId)
                .filter(header -> header.type() == DocumentType.INVOICE && header.status() == DocumentStatus.FINAL)
                .flatMap(header -> documents.findSnapshots(invoiceId)
                        .map(snapshots -> {
                            var sender = codec.sender(snapshots);
                            var recipient = codec.recipient(snapshots);

                            return new DunningBasis(header.id(), header.number(), header.issueDate(), header.dueDate(),
                                    header.grossTotalCents(), recipient.number(), RenderModelFactory.party(sender),
                                    RenderModelFactory.party(recipient), RenderModelFactory.account(sender),
                                    placeholders.build(header, sender, recipient, header.grossTotalCents(),
                                            header.number()));
                        }));
    }

    /**
     * Returns the mail basis of a finalized invoice, cancellation or offer, empty for a draft or an unknown id.
     */
    @Transactional(readOnly = true)
    public Optional<MailBasis> mailBasis(String documentId) {
        return documents.findById(documentId)
                .filter(header -> header.status() == DocumentStatus.FINAL)
                .flatMap(header -> documents.findSnapshots(documentId)
                        .map(snapshots -> {
                            var sender = codec.sender(snapshots);
                            var recipient = codec.recipient(snapshots);

                            return new MailBasis(header.number(), header.dueDate(),
                                    recipient.invoiceEmail(), recipient.contactEmail(),
                                    placeholders.build(header, sender, recipient, header.grossTotalCents(),
                                            header.number()));
                        }));
    }
}
