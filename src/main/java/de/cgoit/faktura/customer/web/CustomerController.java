package de.cgoit.faktura.customer.web;

import de.cgoit.faktura.customer.ContactInfo;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.shared.FieldViolation;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages of the customer master data: searchable list, form with contact persons, archive and restore.
 */
@Controller
@RequestMapping("/customers")
public class CustomerController {

    private static final String FORM_VIEW = "customer/form";
    private static final String CONTACT_VIEW = "customer/contact-form";

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /**
     * Shows the list. A htmx request (search while typing) gets only the table rows.
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "active") String status,
            @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        var filter = switch (status) {
            case "archived" -> CustomerStatus.ARCHIVED;
            case "all" -> CustomerStatus.ALL;
            default -> CustomerStatus.ACTIVE;
        };
        model.addAttribute("customers", customers.search(q, filter));
        model.addAttribute("q", q);
        model.addAttribute("status", status(filter));

        return htmx != null ? "customer/list :: rows" : "customer/list";
    }

    private static String status(CustomerStatus filter) {
        return switch (filter) {
            case ACTIVE -> "active";
            case ARCHIVED -> "archived";
            case ALL -> "all";
        };
    }

    @GetMapping("/new")
    public String newCustomer(Model model) {
        return form(model, CustomerForm.empty(), null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var customer = find(id);

        return form(model, CustomerForm.from(customer), customer);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("customerForm") CustomerForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        return save(null, form, result, model, redirect);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @Valid @ModelAttribute("customerForm") CustomerForm form,
            BindingResult result, Model model, RedirectAttributes redirect) {
        return save(find(id), form, result, model, redirect);
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable String id, RedirectAttributes redirect) {
        find(id);
        customers.archive(id);
        redirect.addFlashAttribute("message", "customer.archived");

        return "redirect:/customers";
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable String id, RedirectAttributes redirect) {
        find(id);
        customers.restore(id);
        redirect.addFlashAttribute("message", "customer.restored");

        return "redirect:/customers/" + id;
    }

    @GetMapping("/{id}/contacts/new")
    public String newContact(@PathVariable String id, Model model) {
        return contactForm(model, find(id), null, new ContactForm(null, null, null, null, null, null, null));
    }

    @GetMapping("/{id}/contacts/{contactId}")
    public String editContact(@PathVariable String id, @PathVariable String contactId, Model model) {
        var customer = find(id);
        var contact = customers.findContact(contactId)
                .filter(found -> found.customerId().equals(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return contactForm(model, customer, contact, ContactForm.of(contact));
    }

    @PostMapping("/{id}/contacts")
    public String addContact(@PathVariable String id, @ModelAttribute ContactForm contact, Model model,
            RedirectAttributes redirect) {
        var customer = find(id);

        try {
            customers.addContact(id, contact.toInput());
            redirect.addFlashAttribute("message", "contact.created");

            return "redirect:/customers/" + id;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return contactForm(model, customer, null, contact);
        }
    }

    @PostMapping("/{id}/contacts/{contactId}")
    public String updateContact(@PathVariable String id, @PathVariable String contactId,
            @ModelAttribute ContactForm contact, Model model, RedirectAttributes redirect) {
        var customer = find(id);

        try {
            customers.updateContact(id, contactId, contact.toInput());
            redirect.addFlashAttribute("message", "contact.saved");

            return "redirect:/customers/" + id;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            var existing = customers.findContact(contactId)
                    .filter(found -> found.customerId().equals(id))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            return contactForm(model, customer, existing, contact);
        }
    }

    @PostMapping("/{id}/contacts/{contactId}/delete")
    public String removeContact(@PathVariable String id, @PathVariable String contactId,
            RedirectAttributes redirect) {
        find(id);

        try {
            customers.removeContact(id, contactId);
            redirect.addFlashAttribute("message", "contact.deleted");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/customers/" + id;
    }

    private String contactForm(Model model, CustomerInfo customer, ContactInfo contact, ContactForm form) {
        model.addAttribute("customer", customer);
        model.addAttribute("contact", contact);
        model.addAttribute("contactForm", form);

        return CONTACT_VIEW;
    }

    private String save(CustomerInfo existing, CustomerForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return form(model, form, existing);
        }

        try {
            var input = form.toInput();
            var saved = existing == null ? customers.create(input) : customers.update(existing.id(), input);
            redirect.addFlashAttribute("message", existing == null ? "customer.created" : "customer.saved");

            return "redirect:/customers/" + saved.id();
        } catch (RuleViolationException e) {
            e.violations().forEach(violation -> result.rejectValue(formField(violation), violation.key()));

            return form(model, form, existing);
        }
    }

    /**
     * Maps a property of the input to the field of the form: the same name, except the discount, which the form shows
     * as a percentage.
     */
    private static String formField(String property) {
        return "discountBp".equals(property) ? "discount" : property;
    }

    private static String formField(FieldViolation violation) {
        if (violation.field() != null) {
            return formField(violation.field());
        }

        return "validation.percent".equals(violation.key()) ? "discount" : "name";
    }

    private String form(Model model, CustomerForm form, CustomerInfo customer) {
        if (!model.containsAttribute("customerForm")) {
            model.addAttribute("customerForm", form);
        }

        model.addAttribute("customer", customer);

        if (customer != null) {
            model.addAttribute("contacts", customers.listContacts(customer.id()));
        }

        return FORM_VIEW;
    }

    private CustomerInfo find(String id) {
        return customers.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
