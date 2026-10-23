package de.cgoit.faktura.document;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.PaymentRepository;
import de.cgoit.faktura.document.internal.SnapshotCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of finalized invoices and payments for the accounting export. It returns the stored totals and
 * payments and never recalculates them.
 */
@Service
public class AccountingExportService {

    private final DocumentRepository documents;
    private final PaymentRepository payments;
    private final SnapshotCodec codec;
    private final Clock clock;

    public AccountingExportService(DocumentRepository documents, PaymentRepository payments, SnapshotCodec codec,
            Clock clock) {
        this.documents = documents;
        this.payments = payments;
        this.codec = codec;
        this.clock = clock;
    }

    /**
     * Lists the finalized invoices and cancellations with an invoice date in the year, oldest first. Drafts are
     * excluded. Customer data comes from the snapshot taken when the document was finalized.
     */
    @Transactional(readOnly = true)
    public List<InvoiceExportRow> invoices(int year) {
        var from = LocalDate.of(year, 1, 1);
        var to = from.plusYears(1);
        var cancelled = documents.findCancelledInvoiceIds();
        var paid = payments.sumsByDocument();
        var lastPayments = payments.lastPaymentDates();
        var taxTotals = documents.findTaxTotalsIssuedBetween(from, to);
        var today = LocalDate.now(clock);

        return documents.findFinalIssuedBetween(from, to).stream()
                .map(header -> {
                    var recipient = codec.recipient(header.recipientSnapshot());
                    var invoice = header.type() == DocumentType.INVOICE;
                    var state = invoice
                            ? PaymentCalculator.balance(header.grossTotalCents(), paid.getOrDefault(header.id(), 0L),
                                    header.dueDate(), cancelled.contains(header.id()), today).state()
                            : null;
                    var paidOn = state == PaymentState.PAID ? lastPayments.get(header.id()) : null;

                    return new InvoiceExportRow(header.number(), header.type(), header.issueDate(),
                            header.serviceFrom(), header.serviceTo(), recipient.number(), recipient.name(),
                            header.customerReference(), header.netTotalCents(), header.shipping().cents(),
                            header.grossTotalCents(), taxTotals.getOrDefault(header.id(), List.of()), state, paidOn);
                })
                .toList();
    }

    /**
     * Lists the payments booked in the year (inflow by payment date), including reversing entries, oldest first.
     */
    @Transactional(readOnly = true)
    public List<PaymentExportRow> payments(int year) {
        var from = LocalDate.of(year, 1, 1);

        return payments.findForExport(from, from.plusYears(1));
    }

    /**
     * Lists the years with invoices or payments, newest first. Without data it holds the current year.
     */
    @Transactional(readOnly = true)
    public List<Integer> years() {
        var years = new TreeSet<>(documents.findIssueYears());

        years.addAll(payments.findPaymentYears());
        years.add(LocalDate.now(clock).getYear());

        return years.descendingSet().stream()
                .toList();
    }
}
