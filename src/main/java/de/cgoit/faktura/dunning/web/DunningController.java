package de.cgoit.faktura.dunning.web;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.cgoit.faktura.archive.ArchiveIntegrityException;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The page "Mahnwesen": suggestions for overdue invoices, preview and creation of dunning letters, and the list of
 * created letters. Nothing is sent from here; the user downloads the PDF and sends it.
 */
@Controller
@RequestMapping("/dunning")
public class DunningController {

    private static final Logger LOG = LoggerFactory.getLogger(DunningController.class);

    private final DunningService dunnings;
    private final PaymentService payments;
    private final MessageSource messages;

    public DunningController(DunningService dunnings, PaymentService payments, MessageSource messages) {
        this.dunnings = dunnings;
        this.payments = payments;
        this.messages = messages;
    }

    @GetMapping
    public String overview(@RequestParam(defaultValue = "all") String show, Model model) {
        var onlyOpen = "open".equals(show);

        model.addAttribute("suggestions", dunnings.suggestions());
        model.addAttribute("letters", dunnings.list()
                .stream()
                .filter(row -> !onlyOpen || !row.settled())
                .toList());
        model.addAttribute("onlyOpen", onlyOpen);

        return "dunning/overview";
    }

    /**
     * Returns the dunning panel of an invoice (history and creation) for the invoice page, which loads it with htmx.
     */
    @GetMapping("/by-invoice/{invoiceId}")
    public String forInvoice(@PathVariable String invoiceId, Model model) {
        model.addAttribute("invoiceId", invoiceId);
        model.addAttribute("balance", payments.balance(invoiceId));
        model.addAttribute("letters", dunnings.listFor(invoiceId));
        model.addAttribute("available", dunnings.availableLevels(invoiceId));

        return "dunning/invoice :: panel";
    }

    /**
     * Returns the content of the confirmation dialog for creating a letter; nothing is stored.
     */
    @GetMapping("/confirm")
    public String confirm(@RequestParam String invoiceId, @RequestParam String levelId,
            @RequestParam(defaultValue = "overview") String from, Model model) {
        model.addAttribute("from", from);

        try {
            model.addAttribute("review", dunnings.review(invoiceId, levelId));
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
        }

        return "dunning/confirm :: dialog";
    }

    /**
     * Shows the letter as it would be created today as PDF; nothing is stored and it has no number.
     */
    @GetMapping("/preview.pdf")
    public ResponseEntity<byte[]> preview(@RequestParam String invoiceId, @RequestParam String levelId) {
        try {
            return pdfResponse("preview.pdf", dunnings.preview(invoiceId, levelId));
        } catch (RuleViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(messages.getMessage(e.messageKey(), null, e.messageKey(), Locale.GERMAN)
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Creates the letter. The {@code confirmed} flag guards against creating by accident; any client can send it.
     */
    @PostMapping
    public String create(@RequestParam String invoiceId, @RequestParam String levelId,
            @RequestParam(defaultValue = "overview") String from, @RequestParam(required = false) String confirmed,
            RedirectAttributes redirect) {
        var target = "invoice".equals(from) ? "redirect:/invoices/" + invoiceId : "redirect:/dunning";

        if (confirmed == null) {
            redirect.addFlashAttribute("error", "dunning.error.notConfirmed");

            return target;
        }

        try {
            dunnings.create(invoiceId, levelId);
            redirect.addFlashAttribute("message", "dunning.created.ok");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        } catch (RuntimeException e) {
            LOG.error("Creating a dunning letter for invoice {} failed", invoiceId, e);
            redirect.addFlashAttribute("error", "dunning.error.failed");
        }

        return target;
    }

    /**
     * Shows one created letter: data, state of the invoice, PDF and sending by mail.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        var row = dunnings.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("row", row);

        return "dunning/detail";
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        try {
            var letter = dunnings.findPdf(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            return pdfResponse(letter.filename(), letter.content());
        } catch (ArchiveIntegrityException e) {
            LOG.error("Archived PDF of dunning {} failed verification", id, e);

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static ResponseEntity<byte[]> pdfResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename)
                        .build()
                        .toString())
                .body(content);
    }
}
