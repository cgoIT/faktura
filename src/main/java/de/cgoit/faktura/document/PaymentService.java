package de.cgoit.faktura.document;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.PaymentRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records payments on finalized invoices and derives their payment state. Payments are append-only (GoBD): a
 * mistake is corrected with a reversing entry, so the history stays complete. The invoice itself is never changed.
 */
@Service
public class PaymentService {


    private final PaymentRepository payments;
    private final DocumentRepository documents;
    private final AuditService audit;
    private final InputValidator validator;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, DocumentRepository documents, AuditService audit,
            InputValidator validator, Clock clock) {
        this.payments = payments;
        this.documents = documents;
        this.audit = audit;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Records a payment on a finalized, not cancelled invoice. An overpayment is allowed and shown, not blocked.
     *
     * @throws RuleViolationException if the document is no finalized invoice, is cancelled, or the input is invalid
     */
    @Transactional
    public PaymentInfo record(String invoiceId, PaymentInput input) {
        requirePayable(invoiceId);

        var normalized = new PaymentInput(input.date(), input.amountCents(), input.method(),
                blankToNull(input.reference()), blankToNull(input.note()));

        validator.check(normalized);

        if (normalized.date().isAfter(LocalDate.now(clock))) {
            throw new RuleViolationException("payment.error.future", "date");
        }

        var id = UUID.randomUUID().toString();

        payments.insert(id, invoiceId, normalized.date(), normalized.amountCents(), normalized.method(),
                normalized.reference(), normalized.note(), null,
                Instant.now(clock).toString());
        audit.record(AuditObjectType.DOCUMENT, invoiceId, AuditAction.PAYMENT_RECORD,
                Map.of("paymentId", id, "amountCents", String.valueOf(input.amountCents()), "method",
                        input.method().name()));

        return payments.findById(id)
                .orElseThrow();
    }

    /**
     * Corrects a payment with a reversing entry for the same amount with the opposite sign, dated today. Each
     * payment can be reversed once; a reversing entry cannot be reversed. Reversing is allowed on a cancelled invoice
     * as well, so that money that was booked by mistake or refunded can be corrected.
     *
     * @throws RuleViolationException if the payment does not belong to the invoice or was already corrected
     */
    @Transactional
    public PaymentInfo reverse(String invoiceId, String paymentId) {
        var header = documents.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document: " + invoiceId));
        var original = payments.findById(paymentId)
                .filter(payment -> payment.documentId().equals(header.id()))
                .orElseThrow(() -> new RuleViolationException("payment.error.unknown"));

        if (!original.canBeReversed()) {
            throw new RuleViolationException("payment.error.alreadyReversed");
        }

        var id = UUID.randomUUID().toString();

        payments.insert(id, invoiceId, LocalDate.now(clock), -original.amountCents(), original.method(), null, null,
                original.id(), Instant.now(clock).toString());
        audit.record(AuditObjectType.DOCUMENT, invoiceId, AuditAction.PAYMENT_REVERSE,
                Map.of("paymentId", id, "reverses", original.id(), "amountCents", String.valueOf(-original.amountCents())));

        return payments.findById(id)
                .orElseThrow();
    }

    /**
     * Lists the payments of an invoice in booking order, reversing entries included.
     */
    @Transactional(readOnly = true)
    public List<PaymentInfo> list(String invoiceId) {
        return payments.findByDocument(invoiceId);
    }

    /**
     * Returns the payment situation of a finalized invoice today, or {@code null} for other documents.
     */
    @Transactional(readOnly = true)
    public InvoiceBalance balance(String invoiceId) {
        return documents.findById(invoiceId)
                .filter(PaymentService::isFinalInvoice)
                .map(header -> PaymentCalculator.balance(header.grossTotalCents(), payments.sumOf(invoiceId),
                        header.dueDate(), documents.findCancellationOf(invoiceId).isPresent(), LocalDate.now(clock)))
                .orElse(null);
    }

    private void requirePayable(String invoiceId) {
        var header = documents.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document: " + invoiceId));

        if (!isFinalInvoice(header)) {
            throw new RuleViolationException("payment.error.notInvoice");
        }

        if (documents.findCancellationOf(invoiceId).isPresent()) {
            throw new RuleViolationException("payment.error.cancelled");
        }
    }

    private static boolean isFinalInvoice(DocumentRepository.Header header) {
        return header.type() == DocumentType.INVOICE && header.status() == DocumentStatus.FINAL;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

}
