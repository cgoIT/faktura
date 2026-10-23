package de.cgoit.faktura.shared;

import java.util.List;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Makes the country list available to every page as {@code countries}.
 */
@ControllerAdvice
public class CountryAdvice {

    @ModelAttribute("countries")
    public List<Countries.Country> countries() {
        return Countries.all();
    }
}
