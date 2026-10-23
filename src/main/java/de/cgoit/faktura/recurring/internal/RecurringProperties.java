package de.cgoit.faktura.recurring.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the recurring module ({@code faktura.recurring.*}).
 *
 * @param enabled         whether the daily job runs
 * @param cron            schedule of the daily job (Spring cron syntax)
 * @param catchUpDays     how many days after its start a missed period is still billed; older ones are marked skipped
 * @param defaultLeadDays lead time proposed for a new plan
 */
@ConfigurationProperties("faktura.recurring")
public record RecurringProperties(boolean enabled, String cron, int catchUpDays, int defaultLeadDays) {
}
