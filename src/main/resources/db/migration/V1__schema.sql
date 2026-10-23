-- faktura schema. Portable across SQLite and PostgreSQL:
--  * IDs: UUID as text (assigned by the application)
--  * Money: whole cents (BIGINT), percentages in basis points (INTEGER), no floating point
--  * Date/time: ISO-8601 as text, booleans: INTEGER 0/1, JSON snapshots: TEXT
--  * Append-only tables (file, audit_log, payment, dunning, mail_log, notification_log, offer_event) are only inserted into.

-- company and tax --------------------------------------------------------------------------------------------------

CREATE TABLE company (
    id               VARCHAR(36)  PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    owner            VARCHAR(200),
    street           VARCHAR(200),
    zip              VARCHAR(20),
    city             VARCHAR(100),
    country          VARCHAR(2)   NOT NULL DEFAULT 'DE',
    email            VARCHAR(200),
    phone            VARCHAR(50),
    tax_number       VARCHAR(50),
    vat_id           VARCHAR(30),
    iban             VARCHAR(34),
    bic              VARCHAR(11),
    bank_name        VARCHAR(100),
    payment_days     INTEGER      NOT NULL DEFAULT 14,
    offer_valid_days INTEGER      NOT NULL DEFAULT 30,
    created_at       VARCHAR(30)  NOT NULL
);

CREATE TABLE tax_profile (
    id                 VARCHAR(36)  PRIMARY KEY,
    company_id         VARCHAR(36)  NOT NULL REFERENCES company(id),
    valid_from         VARCHAR(10)  NOT NULL,
    valid_to           VARCHAR(10),
    regime             VARCHAR(20)  NOT NULL,         -- SMALL_BUSINESS | REGULAR
    default_rate_bp    INTEGER      NOT NULL DEFAULT 0, -- basis points, 1900 = 19 %
    default_category   VARCHAR(2)   NOT NULL DEFAULT 'E',
    exemption_text     VARCHAR(500),
    notice_text        VARCHAR(500),
    allowed_categories VARCHAR(100)                   -- comma separated; NULL = every category is allowed
);

-- Reason per tax category without tax; a row overrides the default from the configuration.
CREATE TABLE tax_category_reason (
    category  VARCHAR(2)   PRIMARY KEY,
    reason    VARCHAR(500) NOT NULL
);

-- numbering --------------------------------------------------------------------------------------------------------

CREATE TABLE number_pattern (
    scope    VARCHAR(20)  PRIMARY KEY,  -- INVOICE, OFFER, CANCELLATION, DUNNING, CUSTOMER, CATALOG
    pattern  VARCHAR(100) NOT NULL      -- e.g. RE{jahr}{#####}
);

-- One counter per scope and period (year, or '0' for patterns without {jahr}), created on first use.
CREATE TABLE number_sequence (
    id          VARCHAR(36) PRIMARY KEY,
    scope       VARCHAR(20) NOT NULL,
    period      VARCHAR(10) NOT NULL,
    last_value  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_number_sequence UNIQUE (scope, period)
);

-- catalog ----------------------------------------------------------------------------------------------------------

CREATE TABLE unit (
    id          VARCHAR(36)  PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,  -- short code, e.g. Std
    name        VARCHAR(100) NOT NULL,
    unece_code  VARCHAR(5)   NOT NULL,         -- HUR, H87, MON, LS, DAY
    name_plural VARCHAR(100)                   -- NULL falls back to the singular name
);

CREATE TABLE catalog_item (
    id               VARCHAR(36)  PRIMARY KEY,
    number           VARCHAR(50)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(2000),
    unit_id          VARCHAR(36)  NOT NULL REFERENCES unit(id),
    unit_price_cents BIGINT       NOT NULL,
    tax_category     VARCHAR(2),
    active           INTEGER      NOT NULL DEFAULT 1
);

-- Master data, never deleted, only switched off. The document copies the chosen option.
CREATE TABLE shipping_option (
    id            VARCHAR(36)   PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    amount_cents  BIGINT        NOT NULL DEFAULT 0,   -- net
    tax_category  VARCHAR(2),                         -- empty = per tax profile
    tax_rate_bp   INTEGER       NOT NULL DEFAULT 0,
    sort_order    INTEGER       NOT NULL DEFAULT 0,
    active        INTEGER       NOT NULL DEFAULT 1,
    created_at    VARCHAR(30)   NOT NULL,
    CONSTRAINT ck_shipping_option_amount CHECK (amount_cents >= 0),
    CONSTRAINT ck_shipping_option_rate CHECK (tax_rate_bp >= 0 AND tax_rate_bp <= 10000),
    CONSTRAINT ck_shipping_option_active CHECK (active IN (0, 1))
);

-- customer ---------------------------------------------------------------------------------------------------------

CREATE TABLE customer (
    id             VARCHAR(36)  PRIMARY KEY,
    number         VARCHAR(50)  NOT NULL UNIQUE,
    customer_type  VARCHAR(20)  NOT NULL DEFAULT 'B2B', -- B2B | B2C | PUBLIC_AUTHORITY
    name           VARCHAR(200) NOT NULL,
    street         VARCHAR(200),
    zip            VARCHAR(20),
    city           VARCHAR(100),
    country        VARCHAR(2)   NOT NULL DEFAULT 'DE',
    vat_id         VARCHAR(30),
    invoice_email  VARCHAR(200),
    payment_days   INTEGER,
    discount_bp    INTEGER,                 -- default discount in basis points
    einvoice_mode  VARCHAR(10)  NOT NULL DEFAULT 'NONE', -- NONE | ZUGFERD | XRECHNUNG
    note           VARCHAR(2000),
    archived       INTEGER      NOT NULL DEFAULT 0,
    created_at     VARCHAR(30)  NOT NULL
);

CREATE TABLE customer_contact (
    id          VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(36)  NOT NULL REFERENCES customer(id),
    salutation  VARCHAR(10),                -- MR | MS | NULL
    first_name  VARCHAR(100) NOT NULL DEFAULT '',
    last_name   VARCHAR(100),
    email       VARCHAR(200),
    phone       VARCHAR(50),
    mobile      VARCHAR(50),
    role        VARCHAR(100)
);

-- document: offers, invoices, cancellations, payments -----------------------------------------------------------------

-- Changeable only while status = 'DRAFT'. The snapshot columns stay empty until finalizing.
CREATE TABLE document (
    id                   VARCHAR(36)  PRIMARY KEY,
    type                 VARCHAR(20)  NOT NULL,  -- OFFER | INVOICE | CANCELLATION
    status               VARCHAR(20)  NOT NULL,  -- DRAFT | FINAL | CANCELLED
    number               VARCHAR(50)  UNIQUE,    -- assigned when finalizing only
    customer_id          VARCHAR(36)  NOT NULL REFERENCES customer(id),
    contact_id           VARCHAR(36),            -- draft only; copied into the recipient snapshot when finalizing
    issue_date           VARCHAR(10)  NOT NULL,
    service_from         VARCHAR(10),
    service_to           VARCHAR(10),
    due_date             VARCHAR(10),
    valid_until          VARCHAR(10),            -- offers only
    subject              VARCHAR(300),
    customer_reference   VARCHAR(200),
    header_text          VARCHAR(4000),
    footer_text          VARCHAR(4000),
    total_discount_type  VARCHAR(10),            -- NULL | PERCENT | AMOUNT
    total_discount_value BIGINT,                 -- basis points or cents, depending on the type
    shipping_name        VARCHAR(100),           -- copy of the chosen shipping option
    shipping_cents       BIGINT       NOT NULL DEFAULT 0,  -- net; negated in a cancellation
    shipping_tax_category VARCHAR(2),
    shipping_tax_rate_bp INTEGER      NOT NULL DEFAULT 0,
    net_total_cents      BIGINT       NOT NULL DEFAULT 0,  -- draft: cache of the last calculation
    tax_total_cents      BIGINT       NOT NULL DEFAULT 0,
    gross_total_cents    BIGINT       NOT NULL DEFAULT 0,
    sender_snapshot      TEXT,                   -- JSON, set when finalizing
    recipient_snapshot   TEXT,
    tax_snapshot         TEXT,
    finalized_at         VARCHAR(30),
    ref_document_id      VARCHAR(36)  REFERENCES document(id),
    created_by           VARCHAR(100),           -- user name of the creator of the draft
    version              INTEGER      NOT NULL DEFAULT 1,  -- optimistic locking of drafts
    created_at           VARCHAR(30)  NOT NULL,
    updated_at           VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_document_type CHECK (type IN ('OFFER', 'INVOICE', 'CANCELLATION')),
    CONSTRAINT ck_document_status CHECK (status IN ('DRAFT', 'FINAL', 'CANCELLED')),
    CONSTRAINT ck_document_discount CHECK (total_discount_type IS NULL OR total_discount_type IN ('PERCENT', 'AMOUNT'))
);

CREATE INDEX ix_document_status ON document(status);
CREATE INDEX ix_document_type_issue_date ON document(type, issue_date);
CREATE INDEX ix_document_customer ON document(customer_id);
-- An invoice can be cancelled only once.
CREATE UNIQUE INDEX uq_document_cancellation ON document(ref_document_id) WHERE type = 'CANCELLATION';

CREATE TABLE document_item (
    id                   VARCHAR(36)  PRIMARY KEY,
    document_id          VARCHAR(36)  NOT NULL REFERENCES document(id),
    position             INTEGER      NOT NULL,
    description          VARCHAR(2000) NOT NULL,
    quantity_thousandths BIGINT       NOT NULL,  -- 1,5 = 1500
    unit_code            VARCHAR(20),
    unit_name            VARCHAR(100),
    unit_name_plural     VARCHAR(100),
    unit_unece_code      VARCHAR(5),
    unit_price_cents     BIGINT       NOT NULL,
    discount_bp          INTEGER      NOT NULL DEFAULT 0,
    tax_category         VARCHAR(2)   NOT NULL,
    tax_rate_bp          INTEGER      NOT NULL DEFAULT 0,
    line_total_cents     BIGINT       NOT NULL,
    catalog_item_id      VARCHAR(36)             -- origin hint only, no foreign key: the item is a copy
);

CREATE INDEX ix_document_item_document ON document_item(document_id);

CREATE TABLE document_tax_total (
    id           VARCHAR(36) PRIMARY KEY,
    document_id  VARCHAR(36) NOT NULL REFERENCES document(id),
    category     VARCHAR(2)  NOT NULL,
    rate_bp      INTEGER     NOT NULL,
    basis_cents  BIGINT      NOT NULL,
    tax_cents    BIGINT      NOT NULL
);

CREATE INDEX ix_document_tax_total_document ON document_tax_total(document_id);

-- A wrong payment is corrected by a reversing entry (negative amount, reverses_payment_id), never by update or delete.
-- Paid, partly paid and overdue are derived from payments, totals and the due date.
CREATE TABLE payment (
    id                  VARCHAR(36)  PRIMARY KEY,
    document_id         VARCHAR(36)  NOT NULL REFERENCES document(id),
    payment_date        VARCHAR(10)  NOT NULL,
    amount_cents        BIGINT       NOT NULL,
    method              VARCHAR(20)  NOT NULL,  -- BANK_TRANSFER | CASH | OTHER
    reference           VARCHAR(200),
    note                VARCHAR(500),
    reverses_payment_id VARCHAR(36)  REFERENCES payment(id),
    created_at          VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_payment_method CHECK (method IN ('BANK_TRANSFER', 'CASH', 'OTHER')),
    CONSTRAINT ck_payment_amount CHECK (
        (reverses_payment_id IS NULL AND amount_cents > 0) OR (reverses_payment_id IS NOT NULL AND amount_cents < 0)),
    CONSTRAINT uq_payment_reversal UNIQUE (reverses_payment_id)
);

CREATE INDEX ix_payment_document ON payment(document_id);

-- Course of an offer; "expired" is derived from valid_until and needs no event.
CREATE TABLE offer_event (
    id           VARCHAR(36)  PRIMARY KEY,
    document_id  VARCHAR(36)  NOT NULL,   -- no foreign key, like file
    event        VARCHAR(10)  NOT NULL,   -- SENT | ACCEPTED | REJECTED
    event_date   VARCHAR(10)  NOT NULL,
    actor        VARCHAR(100),
    created_at   VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_offer_event_event CHECK (event IN ('SENT', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX ix_offer_event_document ON offer_event(document_id);

-- dunning ----------------------------------------------------------------------------------------------------------

-- fee_cents and interest_bp are optional: 0 switches them off. interest_bp is an annual rate in basis points.
-- days_after_due counts from the due date of the invoice.
CREATE TABLE dunning_level (
    id             VARCHAR(36)  PRIMARY KEY,
    level          INTEGER      NOT NULL UNIQUE,
    name           VARCHAR(100) NOT NULL,
    days_after_due INTEGER      NOT NULL,
    fee_cents      BIGINT       NOT NULL DEFAULT 0,
    interest_bp    INTEGER      NOT NULL DEFAULT 0,
    new_due_days   INTEGER      NOT NULL,
    header_text    VARCHAR(4000) NOT NULL,
    footer_text    VARCHAR(4000) NOT NULL,
    active         INTEGER      NOT NULL DEFAULT 1,
    CONSTRAINT ck_dunning_level_values CHECK (level > 0 AND days_after_due >= 0 AND fee_cents >= 0
        AND interest_bp >= 0 AND new_due_days >= 0),
    CONSTRAINT ck_dunning_level_active CHECK (active IN (0, 1))
);

-- A dunning letter. level and name are copied from the level so that later changes do not alter history.
CREATE TABLE dunning (
    id             VARCHAR(36)  PRIMARY KEY,
    document_id    VARCHAR(36)  NOT NULL REFERENCES document(id),
    level          INTEGER      NOT NULL,
    level_name     VARCHAR(100) NOT NULL,
    number         VARCHAR(50)  NOT NULL UNIQUE,
    dunning_date   VARCHAR(10)  NOT NULL,
    open_cents     BIGINT       NOT NULL,
    fee_cents      BIGINT       NOT NULL,
    interest_cents BIGINT       NOT NULL,
    new_due_date   VARCHAR(10)  NOT NULL,
    created_at     VARCHAR(30)  NOT NULL,
    CONSTRAINT uq_dunning_level UNIQUE (document_id, level)
);

CREATE INDEX ix_dunning_document ON dunning(document_id);

-- recurring invoices -----------------------------------------------------------------------------------------------

-- Plans and their items may change at any time; a change only affects future runs. There is deliberately no foreign key
-- from recurring_plan.customer_id to customer (checked by the application) nor from recurring_run.document_id to
-- document (a draft can be deleted, the run stays as a record).
CREATE TABLE recurring_plan (
    id                  VARCHAR(36)   PRIMARY KEY,
    customer_id         VARCHAR(36)   NOT NULL,
    name                VARCHAR(200)  NOT NULL,
    period_interval     VARCHAR(10)   NOT NULL,  -- MONTH | TWO_MONTHS | QUARTER | HALF_YEAR | YEAR
    anchor_date         VARCHAR(10)   NOT NULL,
    end_date            VARCHAR(10),
    lead_days           INTEGER       NOT NULL,
    status              VARCHAR(10)   NOT NULL,  -- ACTIVE | PAUSED | ENDED
    next_period_index   INTEGER       NOT NULL DEFAULT 0,
    payment_days        INTEGER,
    subject             VARCHAR(300),
    customer_reference  VARCHAR(200),
    header_text         VARCHAR(4000),
    footer_text         VARCHAR(4000),
    total_discount_type VARCHAR(10),             -- PERCENT | AMOUNT
    total_discount_value BIGINT       NOT NULL DEFAULT 0,
    contact_id          VARCHAR(36),
    last_error          VARCHAR(100),
    version             INTEGER       NOT NULL DEFAULT 1,
    created_at          VARCHAR(30)   NOT NULL,
    updated_at          VARCHAR(30)   NOT NULL,
    CONSTRAINT ck_recurring_plan_interval CHECK (period_interval IN ('MONTH', 'TWO_MONTHS', 'QUARTER', 'HALF_YEAR', 'YEAR')),
    CONSTRAINT ck_recurring_plan_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT ck_recurring_plan_discount CHECK (total_discount_type IS NULL OR total_discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT ck_recurring_plan_values CHECK (lead_days >= 0 AND next_period_index >= 0 AND total_discount_value >= 0)
);

CREATE INDEX ix_recurring_plan_status ON recurring_plan(status);

-- Item template: tax_category is empty to take the category of the tax profile.
CREATE TABLE recurring_plan_item (
    id                  VARCHAR(36)   PRIMARY KEY,
    plan_id             VARCHAR(36)   NOT NULL REFERENCES recurring_plan(id),
    position            INTEGER       NOT NULL,
    description         VARCHAR(2000) NOT NULL,
    quantity_thousandths BIGINT       NOT NULL,
    unit_code           VARCHAR(20),
    unit_price_cents    BIGINT        NOT NULL,
    discount_bp         INTEGER       NOT NULL DEFAULT 0,
    tax_category        VARCHAR(2),
    catalog_item_id     VARCHAR(36)
);

CREATE INDEX ix_recurring_plan_item_plan ON recurring_plan_item(plan_id);

-- One generated period of a plan. SKIPPED without a document is a period that was not billed (skipped by the user or
-- too old to catch up). The unique key makes the daily job idempotent.
CREATE TABLE recurring_run (
    id            VARCHAR(36)  PRIMARY KEY,
    plan_id       VARCHAR(36)  NOT NULL REFERENCES recurring_plan(id),
    service_from  VARCHAR(10)  NOT NULL,
    service_to    VARCHAR(10)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,  -- DRAFT | RELEASED | SKIPPED
    document_id   VARCHAR(36),
    created_at    VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_recurring_run_status CHECK (status IN ('DRAFT', 'RELEASED', 'SKIPPED')),
    CONSTRAINT uq_recurring_run_period UNIQUE (plan_id, service_from)
);

CREATE INDEX ix_recurring_run_status ON recurring_run(status);
CREATE INDEX ix_recurring_run_document ON recurring_run(document_id);

-- archive and audit ------------------------------------------------------------------------------------------------

-- A file row points to its owner by type and id without a foreign key, so the archive stays independent.
CREATE TABLE file (
    id          VARCHAR(36)  PRIMARY KEY,
    owner_type  VARCHAR(20)  NOT NULL,   -- DOCUMENT | DUNNING
    owner_id    VARCHAR(36)  NOT NULL,
    type        VARCHAR(20)  NOT NULL,   -- PDF | XML | ATTACHMENT
    path        VARCHAR(500) NOT NULL,   -- relative to the archive directory, always with '/'
    size_bytes  BIGINT       NOT NULL,
    sha256      VARCHAR(64)  NOT NULL,
    created_at  VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_file_type CHECK (type IN ('PDF', 'XML', 'ATTACHMENT')),
    CONSTRAINT uq_file_path UNIQUE (path)
);

CREATE INDEX ix_file_owner ON file(owner_type, owner_id);

-- Remembers which PDF was copied to the optional export folder (faktura.archive.export-path). Append-only. An external
-- program such as ecoDMS may remove the copy after importing it, so a missing copy alone does not mean "not exported".
CREATE TABLE file_export (
    file_id     VARCHAR(36)  PRIMARY KEY,   -- file.id, without a foreign key like the other archive references
    exported_at VARCHAR(30)  NOT NULL,
    sha256      VARCHAR(64)  NOT NULL
);

CREATE TABLE audit_log (
    id           VARCHAR(36)  PRIMARY KEY,
    logged_at    VARCHAR(30)  NOT NULL,
    object_type  VARCHAR(20)  NOT NULL,
    object_id    VARCHAR(36)  NOT NULL,
    action       VARCHAR(30)  NOT NULL,
    detail       TEXT,                   -- JSON, no personal data
    actor        VARCHAR(100)            -- user name, 'system' for jobs
);

CREATE INDEX ix_audit_log_object ON audit_log(object_type, object_id);

-- texts and mail ---------------------------------------------------------------------------------------------------

-- Reusable header and footer texts per document type; at most one default per scope and kind.
CREATE TABLE text_template (
    id          VARCHAR(36)  PRIMARY KEY,
    scope       VARCHAR(20)  NOT NULL,   -- INVOICE | OFFER | DUNNING
    kind        VARCHAR(10)  NOT NULL,   -- HEADER | FOOTER
    name        VARCHAR(100) NOT NULL,
    body        VARCHAR(4000) NOT NULL,
    is_default  INTEGER      NOT NULL DEFAULT 0,
    created_at  VARCHAR(30)  NOT NULL,
    CONSTRAINT ck_text_template_scope CHECK (scope IN ('INVOICE', 'OFFER', 'DUNNING')),
    CONSTRAINT ck_text_template_kind CHECK (kind IN ('HEADER', 'FOOTER'))
);

CREATE UNIQUE INDEX uq_text_template_default ON text_template(scope, kind) WHERE is_default = 1;

-- Mail texts per document type, any number of variants, at most one default per scope.
-- NOTIFICATION_DIGEST is a system template for the daily notice to the operator.
CREATE TABLE mail_template (
    id          VARCHAR(36)   PRIMARY KEY,
    scope       VARCHAR(30)   NOT NULL,   -- INVOICE | CANCELLATION | DUNNING | OFFER | NOTIFICATION_DIGEST
    name        VARCHAR(100)  NOT NULL,
    subject     VARCHAR(200)  NOT NULL,
    body        VARCHAR(4000) NOT NULL,
    is_default  INTEGER       NOT NULL DEFAULT 0,
    created_at  VARCHAR(30)   NOT NULL,
    CONSTRAINT ck_mail_template_scope CHECK (scope IN ('INVOICE', 'CANCELLATION', 'DUNNING', 'OFFER',
                                                         'NOTIFICATION_DIGEST'))
);

CREATE UNIQUE INDEX uq_mail_template_default ON mail_template(scope) WHERE is_default = 1;

-- Proof of every send attempt.
CREATE TABLE mail_log (
    id                  VARCHAR(36)   PRIMARY KEY,
    owner_type          VARCHAR(20)   NOT NULL,   -- DOCUMENT | DUNNING
    owner_id            VARCHAR(36)   NOT NULL,   -- no foreign key, like file
    recipients          VARCHAR(1000) NOT NULL,
    subject             VARCHAR(500)  NOT NULL,
    body                TEXT          NOT NULL,   -- the text as sent (markup, placeholders already filled in)
    attachment_file_id  VARCHAR(36),
    attachment_name     VARCHAR(255),
    status              VARCHAR(10)   NOT NULL,   -- SENT | FAILED
    error_key           VARCHAR(100),
    message_id          VARCHAR(255),
    sent_at             VARCHAR(30)   NOT NULL,
    CONSTRAINT ck_mail_log_owner CHECK (owner_type IN ('DOCUMENT', 'DUNNING')),
    CONSTRAINT ck_mail_log_status CHECK (status IN ('SENT', 'FAILED'))
);

CREATE INDEX ix_mail_log_owner ON mail_log(owner_type, owner_id);

-- notification (daily mail to the operator, never to customers) ----------------------------------------------------

CREATE TABLE notification_setting (
    id                  VARCHAR(36)  PRIMARY KEY,   -- exactly one row, id 'default'
    draft_enabled       INTEGER      NOT NULL DEFAULT 0,
    plan_error_enabled  INTEGER      NOT NULL DEFAULT 0,
    overdue_enabled     INTEGER      NOT NULL DEFAULT 0,
    dunning_enabled     INTEGER      NOT NULL DEFAULT 0,
    recipient           VARCHAR(254),               -- empty: the e-mail address of the company
    send_time           VARCHAR(5),                 -- HH:mm, empty: faktura.notification.default-send-time
    last_run_date       VARCHAR(10),                -- ISO date of the last run that finished without a mail error
    failure_count       INTEGER      NOT NULL DEFAULT 0,
    offer_enabled       INTEGER      NOT NULL DEFAULT 0
);

-- What was reported already; keeps the same item from being reported twice.
CREATE TABLE notification_log (
    id          VARCHAR(36)   PRIMARY KEY,
    category    VARCHAR(20)   NOT NULL,   -- DRAFT | PLAN_ERROR | OVERDUE | DUNNING_DUE | OFFER_EXPIRING
    object_id   VARCHAR(36)   NOT NULL,   -- document or plan id, no foreign key
    stage       VARCHAR(100)  NOT NULL,   -- dunning level, error key, or 0
    sent_at     VARCHAR(30)   NOT NULL,
    CONSTRAINT ck_notification_log_category CHECK (category IN ('DRAFT', 'PLAN_ERROR', 'OVERDUE', 'DUNNING_DUE',
                                                                'OFFER_EXPIRING')),
    CONSTRAINT uq_notification_log_item UNIQUE (category, object_id, stage)
);

-- security ---------------------------------------------------------------------------------------------------------

-- Never deleted, only deactivated: audit_log.actor and document.created_by name users by username (no foreign key).
CREATE TABLE app_user (
    id             VARCHAR(36)   PRIMARY KEY,
    username       VARCHAR(100)  NOT NULL,   -- lower case
    display_name   VARCHAR(200),
    password_hash  VARCHAR(100),             -- BCrypt, empty in mode PROXY_HEADER
    admin          INTEGER       NOT NULL DEFAULT 0,
    active         INTEGER       NOT NULL DEFAULT 1,
    created_at     VARCHAR(30)   NOT NULL,
    last_login_at  VARCHAR(30),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_admin CHECK (admin IN (0, 1)),
    CONSTRAINT ck_app_user_active CHECK (active IN (0, 1))
);

-- home -------------------------------------------------------------------------------------------------------------

-- Set-up steps the user ticks off by hand on the start page; the other steps are derived from the data.
CREATE TABLE setup_step (
    step     VARCHAR(30) PRIMARY KEY,
    done_at  VARCHAR(30) NOT NULL
);

-- seed data --------------------------------------------------------------------------------------------------------

INSERT INTO unit (id, code, name, unece_code, name_plural) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Std',   'Stunde',   'HUR', 'Stunden'),
    ('00000000-0000-0000-0000-000000000002', 'Stk',   'Stück',    'H87', 'Stück'),
    ('00000000-0000-0000-0000-000000000003', 'Mon',   'Monat',    'MON', 'Monate'),
    ('00000000-0000-0000-0000-000000000004', 'Psch',  'Pauschale','LS',  'Pauschalen'),
    ('00000000-0000-0000-0000-000000000005', 'Tag',   'Tag',      'DAY', 'Tage');

INSERT INTO number_pattern (scope, pattern) VALUES
    ('INVOICE',      'RE{jahr}{#####}'),
    ('OFFER',        'AN{jahr}{#####}'),
    ('CANCELLATION', 'ST{jahr}{#####}'),
    ('DUNNING',      'MA{jahr}{#####}'),
    ('CUSTOMER',     'KD{####}'),
    ('CATALOG',      'ART{####}');

INSERT INTO text_template (id, scope, kind, name, body, is_default, created_at) VALUES
    ('10000000-0000-0000-0000-000000000001', 'INVOICE', 'HEADER', 'Standard',
     '{anrede}

ich erlaube mir, für die erbrachten Leistungen folgendes in Rechnung zu stellen:',
     1, '2026-10-21T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000002', 'INVOICE', 'FOOTER', 'Standard',
     'Ich bedanke mich für die gute Zusammenarbeit und würde mich freuen, wenn Sie mich bei Ihren nächsten Aufträgen erneut ansprechen.

Bitte überweisen Sie den Betrag in Höhe von {betrag} unter Angabe der Rechnungsnummer {rechnungsnummer} auf das Konto

{firma_iban} bei der {firma_bank}, BIC {firma_bic}, Kontoinhaber {firma_kontoinhaber}.

Zahlbar innerhalb von {zahlungsziel} Tagen (spätestens bis zum {faelligkeit}) ohne Abzug.

Sofern nicht anders angegeben entspricht das Liefer-/Leistungsdatum dem Rechnungsdatum.',
     1, '2026-10-21T00:00:00Z');

INSERT INTO dunning_level (id, level, name, days_after_due, fee_cents, interest_bp, new_due_days, header_text, footer_text, active)
VALUES ('20000000-0000-0000-0000-000000000001', 1, 'Zahlungserinnerung', 7, 0, 0, 7,
'{anrede}

zu meiner Rechnung {rechnungsnummer} vom {rechnungsdatum} konnte ich bisher keinen Zahlungseingang feststellen. Vielleicht ist die Zahlung nur in der Bearbeitung untergegangen.',
'Ich bitte Sie, den offenen Betrag bis zum {neue_frist} auf das Konto {firma_iban} bei der {firma_bank}, BIC {firma_bic}, Kontoinhaber {firma_kontoinhaber}, zu überweisen. Sollten Sie bereits bezahlt haben, betrachten Sie dieses Schreiben bitte als gegenstandslos.',
1);

INSERT INTO dunning_level (id, level, name, days_after_due, fee_cents, interest_bp, new_due_days, header_text, footer_text, active)
VALUES ('20000000-0000-0000-0000-000000000002', 2, '1. Mahnung', 21, 0, 0, 7,
'{anrede}

trotz meiner Zahlungserinnerung ist die Rechnung {rechnungsnummer} vom {rechnungsdatum} noch nicht beglichen. Sie ist seit {tage_ueberfaellig} Tagen überfällig.',
'Ich bitte Sie, die Gesamtforderung von {gesamtforderung} bis zum {neue_frist} auf das Konto {firma_iban} bei der {firma_bank}, BIC {firma_bic}, Kontoinhaber {firma_kontoinhaber}, zu überweisen.',
1);

INSERT INTO dunning_level (id, level, name, days_after_due, fee_cents, interest_bp, new_due_days, header_text, footer_text, active)
VALUES ('20000000-0000-0000-0000-000000000003', 3, '2. Mahnung', 35, 0, 0, 7,
'{anrede}

die Rechnung {rechnungsnummer} vom {rechnungsdatum} ist trotz Erinnerung und Mahnung weiterhin offen. Dies ist meine letzte Aufforderung zur Zahlung.',
'Bitte überweisen Sie die Gesamtforderung von {gesamtforderung} bis spätestens {neue_frist} auf das Konto {firma_iban} bei der {firma_bank}, BIC {firma_bic}, Kontoinhaber {firma_kontoinhaber}. Danach behalte ich mir weitere Schritte vor.',
1);

INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at) VALUES
    ('30000000-0000-0000-0000-000000000001', 'INVOICE', 'Formell', 'Rechnung {rechnungsnummer} von {firma_name}',
     '{anrede}

anbei erhalten Sie die Rechnung {rechnungsnummer} über {betrag}. Bitte überweisen Sie den Betrag bis zum {faelligkeit}.

Bei Fragen melden Sie sich gerne.

Mit freundlichen Grüßen
{firma_inhaber}', 1, '2026-10-22T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000002', 'INVOICE', 'Informell', 'Deine Rechnung {rechnungsnummer}',
     'Hallo {ansprechpartner_vorname},

hier ist die Rechnung {rechnungsnummer} über {betrag}. Sie ist bis zum {faelligkeit} fällig.

Melde dich einfach, wenn etwas unklar ist.

Viele Grüße
{firma_inhaber}', 0, '2026-10-22T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000003', 'CANCELLATION', 'Formell', 'Stornorechnung {rechnungsnummer} von {firma_name}',
     '{anrede}

anbei erhalten Sie die Stornorechnung {rechnungsnummer} über {betrag}. Sie hebt die ursprüngliche Rechnung auf.

Bei Fragen melden Sie sich gerne.

Mit freundlichen Grüßen
{firma_inhaber}', 1, '2026-10-22T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000004', 'CANCELLATION', 'Informell', 'Storno {rechnungsnummer}',
     'Hallo {ansprechpartner_vorname},

im Anhang findest du die Stornorechnung {rechnungsnummer} über {betrag}. Sie hebt die ursprüngliche Rechnung auf.

Viele Grüße
{firma_inhaber}', 0, '2026-10-22T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000005', 'DUNNING', 'Formell', '{mahnstufe} zur Rechnung {rechnungsnummer}',
     '{anrede}

zur Rechnung {rechnungsnummer} vom {rechnungsdatum} konnten wir bislang keinen Zahlungseingang feststellen. Im Anhang finden Sie die {mahnstufe}.

Wir bitten Sie, den offenen Betrag von {gesamtforderung} bis zum {neue_frist} zu überweisen. Sollte sich Ihre Zahlung mit diesem Schreiben überschnitten haben, betrachten Sie es bitte als gegenstandslos.

Mit freundlichen Grüßen
{firma_inhaber}', 1, '2026-10-22T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000006', 'DUNNING', 'Informell', 'Erinnerung zur Rechnung {rechnungsnummer}',
     'Hallo {ansprechpartner_vorname},

zur Rechnung {rechnungsnummer} habe ich noch keinen Zahlungseingang gesehen. Im Anhang findest du die {mahnstufe}.

Könntest du die {gesamtforderung} bis zum {neue_frist} überweisen? Falls sich das mit deiner Zahlung überschnitten hat, ignoriere die Mail einfach.

Viele Grüße
{firma_inhaber}', 0, '2026-10-22T00:00:00Z');

INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at) VALUES
    ('30000000-0000-0000-0000-000000000008', 'OFFER', 'Formell', 'Angebot {angebotsnummer} von {firma_name}',
     '{anrede}

vielen Dank für Ihre Anfrage. Anbei erhalten Sie das Angebot {angebotsnummer} über {betrag}. Es ist gültig bis zum {gueltig_bis}.

Bei Fragen melden Sie sich gerne.

Mit freundlichen Grüßen
{firma_inhaber}', 1, '2026-10-23T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000009', 'OFFER', 'Informell', 'Dein Angebot {angebotsnummer}',
     'Hallo {ansprechpartner_vorname},

hier ist das Angebot {angebotsnummer} über {betrag}. Es gilt bis zum {gueltig_bis}.

Melde dich einfach, wenn etwas unklar ist.

Viele Grüße
{firma_inhaber}', 0, '2026-10-23T00:00:00Z');

INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at) VALUES
    ('30000000-0000-0000-0000-000000000007', 'NOTIFICATION_DIGEST', 'Hinweise', 'faktura: {anzahl} Hinweise',
     'Hallo {firma_inhaber},

in faktura gibt es etwas zu tun:

{entwuerfe}

{planfehler}

{ueberfaellig}

{mahnfaehig}

{angebote}

{link}', 1, '2026-10-23T00:00:00Z');

INSERT INTO notification_setting (id) VALUES ('default');
