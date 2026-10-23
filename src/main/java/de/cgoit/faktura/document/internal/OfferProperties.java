package de.cgoit.faktura.document.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of offers ({@code faktura.offer.*}).
 *
 * @param expiringDays an offer without a decision counts as expiring soon when it ends within this many days
 */
@Validated
@ConfigurationProperties("faktura.offer")
public record OfferProperties(@DefaultValue("7") @Min(0) int expiringDays) {
}
