package de.cgoit.faktura.mail.web;

import de.cgoit.faktura.mail.MailDraft;
import de.cgoit.faktura.mail.MailOwnerType;
import de.cgoit.faktura.mail.MailService;
import de.cgoit.faktura.mail.MailTarget;
import de.cgoit.faktura.mail.MailTemplateInfo;
import de.cgoit.faktura.mail.MailTemplateService;
import de.cgoit.faktura.mail.SendMailCommand;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Fragments for sending a document by mail: the panel with the send button and the history, and the send dialog.
 * Documents pages load them with htmx. Nothing is sent without the confirmation in the dialog.
 */
@Controller
@RequestMapping("/mail")
public class MailController {

    private static final String DIALOG_VIEW = "mail/compose :: dialog";

    private final MailService mail;
    private final MailTemplateService templates;

    public MailController(MailService mail, MailTemplateService templates) {
        this.mail = mail;
        this.templates = templates;
    }

    /**
     * Returns the panel of a document: the send button (disabled with a reason when mail is not available), the
     * history and the empty dialog.
     */
    @GetMapping("/panel")
    public String panel(@RequestParam MailOwnerType type, @RequestParam String id, Model model) {
        model.addAttribute("type", type);
        model.addAttribute("id", id);
        model.addAttribute("status", mail.status());

        return "mail/panel :: panel";
    }

    /**
     * Returns the history of send attempts for a document.
     */
    @GetMapping("/history")
    public String history(@RequestParam MailOwnerType type, @RequestParam String id, Model model) {
        model.addAttribute("entries", mail.history(new MailTarget(type, id)));

        return "mail/panel :: history";
    }

    /**
     * Returns the content of the send dialog, prefilled from the chosen template; nothing is sent.
     */
    @GetMapping("/compose")
    public String compose(@RequestParam MailOwnerType type, @RequestParam String id,
            @RequestParam(required = false) String templateId, Model model) {
        var target = new MailTarget(type, id);

        try {
            var draft = mail.prepare(target, templateId);

            var chosenId = draft.template()
                    .map(MailTemplateInfo::id)
                    .orElse("");

            return dialog(model, target, draft, new MailForm(chosenId, draft.recipients(), draft.subject(),
                    draft.body()));
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
            model.addAttribute("target", target);

            return DIALOG_VIEW;
        }
    }

    /**
     * Renders subject and text as the recipient will see them.
     */
    @PostMapping("/preview")
    public String preview(@RequestParam(defaultValue = "") String subject, @RequestParam(defaultValue = "") String body,
            Model model) {
        model.addAttribute("preview", mail.preview(subject, body));

        return "mail/preview :: preview";
    }

    /**
     * Sends the mail after the user has confirmed it. The dialog shows the result; on success the response tells
     * the history to reload.
     */
    @PostMapping("/send")
    public String send(@RequestParam MailOwnerType type, @RequestParam String id, MailForm form,
            @RequestParam(defaultValue = "false") boolean confirmed, Model model, HttpServletResponse response) {
        var target = new MailTarget(type, id);

        try {
            var entry = mail.send(new SendMailCommand(target, form.recipients(), form.subject(), form.body(),
                    confirmed));

            model.addAttribute("sent", entry);
            model.addAttribute("target", target);
            response.setHeader("HX-Trigger", "mailSent");
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
            model.addAttribute("field", e.field());
            redisplay(model, target, form);
        }

        return DIALOG_VIEW;
    }

    private void redisplay(Model model, MailTarget target, MailForm form) {
        model.addAttribute("target", target);

        try {
            dialog(model, target, mail.prepare(target, form.templateId()), form);
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
        }
    }

    private String dialog(Model model, MailTarget target, MailDraft draft, MailForm form) {
        model.addAttribute("target", target);
        model.addAttribute("draft", draft);
        model.addAttribute("form", form);
        model.addAttribute("templates", templates.list(draft.scope()));

        return DIALOG_VIEW;
    }
}
