package de.cgoit.faktura.notification.web;

import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailService;
import de.cgoit.faktura.mail.MailTemplateInfo;
import de.cgoit.faktura.mail.MailTemplateService;
import de.cgoit.faktura.notification.DigestResult.Outcome;
import de.cgoit.faktura.notification.NotificationService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Settings page for the daily notification mail to the operator: which categories are reported, to whom and when,
 * plus a button to check right now and one to send a test mail. Nothing here ever sends to a customer.
 */
@Controller
@RequestMapping("/settings/notifications")
public class NotificationController {

    private static final String VIEW = "notification/settings";
    private static final String REDIRECT = "redirect:/settings/notifications";

    private final NotificationService notifications;
    private final MailService mail;
    private final MailTemplateService mailTemplates;
    private final boolean baseUrlSet;

    public NotificationController(NotificationService notifications, MailService mail,
            MailTemplateService mailTemplates, @Value("${faktura.base-url:}") String baseUrl) {
        this.notifications = notifications;
        this.mail = mail;
        this.mailTemplates = mailTemplates;
        this.baseUrlSet = baseUrl != null && !baseUrl.isBlank();
    }

    @GetMapping
    public String show(Model model) {
        return view(model, NotificationForm.from(notifications.settings()));
    }

    @PostMapping
    public String save(@ModelAttribute("notificationForm") NotificationForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        try {
            notifications.update(form.toInput());
            redirect.addFlashAttribute("message", "notification.saved");

            return REDIRECT;
        } catch (RuleViolationException e) {
            for (var violation : e.violations()) {
                if (violation.field() != null && ("recipient".equals(violation.field())
                        || "sendTime".equals(violation.field()))) {
                    result.rejectValue(violation.field(), violation.key());
                } else {
                    result.reject(violation.key());
                }
            }

            return view(model, form);
        }
    }

    /**
     * Runs the check right now, whatever the time of day.
     */
    @PostMapping("/check")
    public String check(RedirectAttributes redirect) {
        var result = notifications.runNow();

        switch (result.outcome()) {
            case SENT -> redirect.addFlashAttribute("message", "notification.check.sent");
            case NOTHING_TO_REPORT -> redirect.addFlashAttribute("message", "notification.check.nothing");
            case DISABLED -> redirect.addFlashAttribute("error", "notification.check.disabled");
            case PAUSED -> redirect.addFlashAttribute("error", "notification.check.paused");
            case BLOCKED, FAILED -> redirect.addFlashAttribute("error", result.errorKey());
            default -> throw new IllegalStateException("Unexpected outcome " + result.outcome());
        }

        return REDIRECT;
    }

    @PostMapping("/test")
    public String test(RedirectAttributes redirect) {
        try {
            notifications.sendTest();
            redirect.addFlashAttribute("message", "notification.test.sent");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return REDIRECT;
    }

    private String view(Model model, NotificationForm form) {
        if (!model.containsAttribute("notificationForm")) {
            model.addAttribute("notificationForm", form);
        }

        var settings = notifications.settings();

        model.addAttribute("mailStatus", mail.status());
        model.addAttribute("fallbackRecipient", notifications.recipient()
                .orElse(""));
        model.addAttribute("defaultSendTime", notifications.sendTime()
                .toString());
        model.addAttribute("paused", notifications.paused());
        model.addAttribute("lastRunDate", settings.lastRunDate());
        model.addAttribute("baseUrlSet", baseUrlSet);
        model.addAttribute("templateId", mailTemplates.defaultFor(MailScope.NOTIFICATION_DIGEST)
                .map(MailTemplateInfo::id)
                .orElse(null));

        return VIEW;
    }
}
