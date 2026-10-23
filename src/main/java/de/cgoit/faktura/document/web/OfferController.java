package de.cgoit.faktura.document.web;

import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.OfferEventType;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Actions on a finalized offer: reject it, or create an invoice draft from it. The other offer pages are served by
 * {@link InvoiceController}.
 */
@Controller
@RequestMapping("/offers")
public class OfferController {

    private static final Logger LOG = LoggerFactory.getLogger(OfferController.class);
    private static final String REDIRECT_OFFER = "redirect:/offers/";

    private final OfferService offers;
    private final InvoiceService invoices;

    public OfferController(OfferService offers, InvoiceService invoices) {
        this.offers = offers;
        this.invoices = invoices;
    }

    /**
     * Records that the customer rejected the offer.
     */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable String id, RedirectAttributes redirect) {
        requireOffer(id);

        try {
            offers.record(id, OfferEventType.REJECTED);
            redirect.addFlashAttribute("message", "offer.rejected");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return REDIRECT_OFFER + id;
    }

    /**
     * Creates an invoice draft from the offer and opens it in the invoice editor.
     */
    @PostMapping("/{id}/invoice")
    public String createInvoice(@PathVariable String id, RedirectAttributes redirect) {
        requireOffer(id);

        try {
            var draft = offers.createInvoiceDraft(id);

            redirect.addFlashAttribute("message", "offer.invoiceCreated");

            return "redirect:/invoices/" + draft.id();
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
            redirect.addFlashAttribute("errorDetails", e.details());
        } catch (RuntimeException e) {
            LOG.error("Creating an invoice from offer {} failed", id, e);
            redirect.addFlashAttribute("error", "offer.error.invoiceFailed");
        }

        return REDIRECT_OFFER + id;
    }

    private void requireOffer(String id) {
        invoices.find(id)
                .filter(found -> found.type() == DocumentType.OFFER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
