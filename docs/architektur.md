# Architektur

faktura ist ein kleiner modularer Monolith mit Spring Boot 4.1 auf Java 25, gebaut mit Maven und betrieben als ein
Docker-Container hinter dem eigenen Pangolin-Stack (Zugriffsschutz über Authentik).

```
Browser ──> Pangolin (TLS, Authentik) ──> faktura (ein Container) ──┬─> SQLite-Datei (./data/faktura.db)
                                                                    └─> Archiv-Verzeichnis (PDF, XML)
```

## Technologie-Stack

| Bereich | Wahl | Anmerkung |
| --- | --- | --- |
| Sprache | Java 25 (LTS) | `.tool-versions` |
| Framework | Spring Boot 4.1.x | Modularisierte Starter (`spring-boot-starter-webmvc`, `-flyway` …) |
| Build | Maven mit Wrapper | Maven Central ist in manchen Umgebungen gesperrt, der Build läuft lokal und in der CI |
| Web | Spring MVC, Thymeleaf, htmx | htmx und Schriften liegen lokal unter `static/`; Texte in `messages_de.properties` |
| Persistenz | Spring JDBC (`JdbcClient`), Flyway | kein ORM; portables SQL für SQLite und PostgreSQL. **Bewertet nach Schritt 4** (siehe „Persistenzbewertung“): `JdbcClient` bleibt, Entscheidung beim Anwender |
| Datenbank | SQLite (Standard), PostgreSQL | Treiber `sqlite-jdbc`, `postgresql`; dasselbe Flyway-Schema |
| Modulgrenzen | Spring Modulith 2.1 | `ModularityTests` |
| PDF | OpenHTMLtoPDF (Fork `io.github.openhtmltopdf`) | PDF/A-1b; PDFBox 3, passend zu Mustang; Schriften als Datei einbetten |
| E-Rechnung | Mustang 2.25.x | PDF/A-3, ZUGFeRD-XML (EN 16931), Validator; bei jeder Version den Validierungstest prüfen |
| Girocode | ZXing | EPC-QR (Version 002, UTF-8) |
| Tests | JUnit, AssertJ, Testcontainers (PostgreSQL), Spring Modulith Test | |
| Betrieb | Docker, Actuator | `/actuator/health` für Healthcheck |

## Modulschnitt

Jedes direkte Unterpackage von `de.cgoit.faktura` ist ein Modul. Öffentlich ist nur, was direkt im Modul-Package liegt
(Services, Events, DTOs); `internal` und `web` sind privat. Module rufen sich über öffentliche Services oder Events auf,
nie über fremde Repositories oder Tabellen.

| Modul | Verantwortung |
| --- | --- |
| `company` | Eigene Stammdaten, Einstellungen |
| `tax` | Steuerprofile mit Gültigkeit, Auflösen des gültigen Profils, Steuerberechnung |
| `customer` | Kundenstamm, Ansprechpartner |
| `catalog` | Leistungskatalog, Einheiten, Versandarten (`ShippingOptionService`) |
| `numbering` | Nummernmuster (`NumberPattern`), Nummernkreise, atomares Vergeben |
| `document` | Angebot, Rechnung, Storno: Entwurf, Berechnung, Finalisieren, Angebotszustand (`OfferService`), Zahlungen (`PaymentService`, berechneter Zahlungszustand), Daten für Folgebriefe (`InvoiceLetterService`) |
| `render` | Vorlagen, HTML zu PDF/A, Girocode |
| `einvoice` | Mapping Beleg zu ZUGFeRD (später XRechnung), Validierung, Einbetten |
| `recurring` | Wiedervorlagen: Pläne (`RecurringPlanService`), Zeitraumberechnung (`PeriodCalculator`), täglicher Job (`RecurringJobService`, `@Scheduled` in `internal`), Seite „Zur Freigabe“; hört auf die Ereignisse `DraftDeleted` und `InvoiceFinalized` aus `document`, damit der Lauf zum Beleg passt |
| `dunning` | Mahnstufen (`DunningLevelService`), Vorschläge und Mahnungen (`DunningService`, reine Regeln in `DunningPlanner`); hängt von `document` ab und meldet Vorschläge über die Schnittstelle `document.DunningHints` zurück, damit `document` nicht von `dunning` abhängt |
| `einvoice` | Mapping der berechneten Beleg-Daten (`EInvoiceDocument`) zu ZUGFeRD (später XRechnung), Summenprüfung, Einbetten, Validierung |
| `archive` | Dateien ablegen (Staging, nach Commit verschieben), Hash prüfen, Backup, optionaler Export-Ordner für ecoDMS (`ArchiveExportService`) |
| `textblock` | Textvorlagen für Kopf- und Fußtexte je Dokumenttyp, Platzhalter (`{anrede}`, `{leistungszeitraum}` …), Anredezeilen |
| `mail` | Versand finalisierter Belege per E-Mail: Mailvorlagen (`MailTemplateService`), Vorschau, Versand (`MailService`: Anhang aus dem Archiv, multipart, Versandprotokoll `mail_log`, Audit `MAIL_SEND`). Hängt von `document`, `dunning`, `archive`, `audit`, `company`, `textblock`, `render` ab; Controller in `mail.web` (`/mail/...`, `/mail-templates`), die Rechnungs- und Mahnungsseiten laden Panel und Dialog per htmx, ohne `mail` zu importieren, daher kein Zyklus |
| `notification` | Tägliche Hinweis-Mail an den Betreiber (`NotificationService`, `NotificationScheduler`, `DigestBuilder` in `internal`): berechnet aus Wiedervorlagen, Zahlungsstand und Mahnvorschlägen, was neu zu melden ist, filtert mit `notification_log`, sendet über `MailService.sendNotice`. Hängt von `mail`, `company`, `document`, `dunning`, `recurring`, `textblock` ab, niemand hängt von ihm ab. Seite `/settings/notifications` in `notification.web`. Keine Ereignisse: der Fachjob `recurring` bleibt unberührt, die Prüfung liest nur |
| `audit` | Append-only-Protokoll (`audit_log`) für Finalisieren, Storno, Zahlungen, Mahnungen und Mailversand |
| `home` | Startseite: Kennzahlen in zwei Gruppen (Rechnungen: Entwürfe, Rechnungen des Jahres, Überfällig, Stornos; Aufgaben: Mahnungen, Zur Freigabe, Angebote mit der Zahl der in `faktura.offer.expiring-days` ablaufenden), letzte Belege, abhakbare Einrichtung (`setup_step`: nur die von Hand abgehakten Schritte Nummernkreise und Einheiten; der Rest ergibt sich aus den Daten), Navigation |
| `dev` | Nur Profil `dev`: Zurücksetzen der Entwicklungsdatenbank und Testdaten; nie von anderen Modulen verwendet, `SPRING_PROFILES_ACTIVE=dev` ist in Produktion verboten |
| `shared` (offen) | Geld- und Datumshilfen, gemeinsame Typen, Eingabeprüfung (`InputValidator`, `FieldViolation`), Länderliste (`Countries`) |
| `security` | Zuschaltbare Anmeldung (`NONE`, `FORM`, `PROXY_HEADER`, `PROXY_HEADER_ADMIN_FORM`), Benutzerverwaltung (`app_user`, nur für Administratoren), CSRF-Schutz, Sicherheits-Header und CSP; liefert den Akteur über die Schnittstelle `shared.CurrentUser`, damit Fachmodule nicht von `security` abhängen |
| `export` | CSV-Export der Rechnungen und Zahlungen eines Jahres (`CsvWriter`, `AccountingCsvExporter` in `internal`, Seite in `export.web`); liest nur über `document.AccountingExportService`, schreibt nichts |
| `mcp` | MCP-Server (JSON-RPC über `POST /mcp`, zustandslos, ohne zusätzliche Bibliothek): `McpController` in `mcp.web`, die Werkzeuge `McpTools` und eine eigene Filterkette mit Bearer-Token (`McpSecurityConfiguration`, `BearerTokenFilter`) in `mcp.internal`. Ruft nur öffentliche Dienste von `customer`, `catalog`, `document`, `dunning`, `textblock` auf; niemand hängt von ihm ab. Schreibt nur Entwürfe (Akteur `mcp`). Aus, solange `faktura.mcp.enabled` fehlt. Tests: `McpServerTest`, `McpSourceTest` (kein Werkzeug finalisiert, storniert, bucht, sendet oder löscht) |

## Querschnittsthemen

### Datenbank und Portabilität
- Flyway legt das Schema an. Es steht komplett in `V1__schema.sql` (vor der Veröffentlichung aus den Einzelmigrationen zusammengefasst, nach Modulen gruppiert, mit den Startdaten: Einheiten, Nummernmuster, Textvorlagen, Mahnstufen, Mailvorlagen). `SchemaTest` vergleicht das Ergebnis auf SQLite und PostgreSQL mit der eingecheckten Beschreibung unter `src/test/resources/schema`; eine bewusste Schemaänderung ergänzt eine Migration und aktualisiert die Beschreibung (`-Dfaktura.schema.write=true`).
- Geld als ganze Cent, Prozent als Basispunkte, IDs als UUID-Text, Datum als ISO-Text, Wahrheitswerte 0/1, JSON als Text.
- SQLite: ein Schreiber, Verbindungspool mit einer Verbindung (`maximum-pool-size: 1`); bei PostgreSQL erhöhen.
- Nummern atomar vergeben: ein einziges `UPDATE … SET last_value = last_value + 1 … RETURNING last_value` in der Transaktion
  des Aufrufers (`Propagation.MANDATORY`); die Zählerzeile entsteht vorher per `INSERT … ON CONFLICT DO NOTHING`. Das Muster
  steht je Bereich in `number_pattern`. Kein Lesen-dann-Schreiben ohne Sperre.
- Tests laufen standardmäßig gegen SQLite, ein Teil zusätzlich gegen PostgreSQL (Testcontainers).

### Finalisieren (GoBD)
Technisch: eine Transaktion in `document`; Rendern und Validieren
passieren vor dem endgültigen Statuswechsel, Dateien werden erst nach dem Commit endgültig abgelegt (zuerst in ein
temporäres Verzeichnis schreiben, nach dem Commit verschieben, verwaiste Dateien aufräumen). Nach `FINAL` gibt es keinen
Schreibzugriff auf den Beleg; nur Zahlungen, Mahnungen und Storno entstehen als neue Datensätze.

### PDF und E-Rechnung (Ergebnisse des Spikes vom 20.10.2026)
- HTML (XHTML) wird mit OpenHTMLtoPDF zu PDF/A-1b gerendert, Mustang macht daraus PDF/A-3 mit eingebettetem XML.
- OpenHTMLtoPDF nur als Fork `io.github.openhtmltopdf` (PDFBox 3). Schriften als Datei einbinden, Familienname nicht
  `sans-serif` (im PDF/A-Modus bleibt sonst die Schriftliste leer und der Text fehlt).
- Mustang 2.25.0 erzeugt das XML selbst; mit 2.16.2 gab es Fehler im Kleinunternehmer-Fall. `ZUGFeRDExporterFromA1`,
  `ZUGFeRD2PullProvider`, `ZUGFeRDValidator` (Modul `validator`).
- Kleinunternehmer: Kategorie `E` mit Befreiungsgrund validiert sauber; Kategorie `O` erzeugt korrektes XML, aber der Validator
  stürzt ab (Mustang-Ticket #1219). Seller-Kennung (BT-29) und Steuernummer sind Pflicht.
- Mustang erzeugt das XML auch für Kategorie `E`, `S`, `Z` und `AE` fehlerfrei (Test); die Summen rechnet Mustang selbst nach, deshalb gleicht `TotalsCheck` sie mit den gespeicherten Beträgen ab. Der Validator loggt Regeltexte über Logback (statisch, ohne Werte).
- Fehlermeldungen: Schlägt die Prüfung fehl, zeigt die Oberfläche die Regel-IDs in verständlichem Deutsch (`einvoice.rule.<ID>` in `messages_de.properties`, unbekannte Regeln nur mit ID; Texte ohne Nutzdaten). Rückfälle (Land, Einheitencode) und der Rabatt-Text im XML stehen unter `faktura.einvoice.*` (ENV: `EINVOICE_DEFAULT_COUNTRY`, `EINVOICE_DEFAULT_UNIT_CODE`, `EINVOICE_DISCOUNT_REASON`).
- XRechnung-Hinweise des Validators (Leitweg-ID, Käuferreferenz, elektronische Adressen) betreffen nur den Prio-2-Zweig.
- Das Referenzprojekt liegt außerhalb dieses Repositories (`~/tmp/faktura-spike`) und ist nur Vorlage.

### Fußzeile und Seitenzähler im PDF (Spike vom 20.10.2026, Schritt 5)
- Gewählt: `@page`-Margin-Box `@bottom-left { content: element(footer) }` mit `position: running(footer)`; der Platz kommt aus
  dem unteren Seitenrand (40 mm). `counter(page)`/`counter(pages)` laufen in `@top-right` (ab Seite 2) und im Titel (Seite 1).
  `position: fixed` am Seitenende trägt ebenfalls, wurde aber nicht gewählt. Rückfall wäre das Stempeln mit PDFBox nach dem Rendern.
- Stolpersteine: Margin-Boxen brauchen `font-family` ausdrücklich (sonst `FontNotFoundException`). `position: fixed` wird auf den
  Inhaltsbereich der Seite beschnitten; der senkrechte Randtext liegt deshalb im 10-mm-Streifen rechts, der zum Inhaltsbereich
  gehört (Seitenrand rechts 10 mm, `body` mit 10 mm Rand). Tabellen brauchen `-fs-table-paginate: paginate`, damit der Kopf auf
  Folgeseiten wiederkehrt. Bilder müssen deckend sein (PDF/A-1): Logo und Girocode werden als opakes PNG eingebettet.
- Test: `InvoiceRendererTest` (1 und 40 Positionen: Seitenzahl, Fußzeile und „Seite x von y“ je Seite, kein Überlagern, Schriften eingebettet).
- Archiv: `faktura.archive.path` (`ARCHIVE_PATH`), Dateien als `<Jahr>/<Nummer>.pdf` (Mahnungen `dunning/<Jahr>/<Nummer>.pdf`; Zeichen außer `A-Z a-z 0-9 . _ -` werden zu `_`, dann hängt ein Teil der ID an, `ArchiveService.fileName`); eine Datei entsteht zuerst in `.staging`, wird nach
  dem Commit verschoben, bei Rollback gelöscht; beim Start räumt `ArchiveService.recoverStaged` auf. Logo: `faktura.render.logo-path`
  (`LOGO_PATH`), sonst Platzhalter. Texte der Vorlage unter `faktura.render.*`.

### Vorlagen
HTML mit Thymeleaf-Platzhaltern plus CSS, zunächst als Dateien mit Live-Vorschau in der App. Ein Vorlagentyp je Belegart
(Angebot, Rechnung, Storno, Mahnung). Briefkopf, Schrift und Logo kommen aus gemeinsamen Teilvorlagen. Alle Schriften als
Dateien im Projekt.

### Wiedervorlagen
Täglicher Job (`@Scheduled`) erzeugt nur Entwürfe, idempotent über (Plan, Zeitraum). Zeitraumberechnung als reine
Funktion mit Tabellentests (Monatsende, Schaltjahr, Quartal/Jahr mit beliebigem Anker).

### Mailversand
- `spring-boot-starter-mail`; der SMTP-Zugang steht nur in `spring.mail.*` aus Umgebungsvariablen (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_STARTTLS`, `MAIL_SSL`), die Funktion in `faktura.mail.*` (`enabled`, `from`, `reply-to`, `bcc`, `max-attachment-bytes`, `max-recipients`). Nichts davon in der Datenbank oder im Repository. Ohne Host/Absender/`enabled` ist die Funktion sichtbar gesperrt (`MailStatus`), der Gesundheitscheck prüft den Mailserver nicht.
- **SQLite:** `MailService.send` läuft nie in einer Transaktion und hält keine Verbindung während des SMTP-Dialogs (Test `MailServiceTest`: Pool-Zähler und Transaktionsstatus im Sender). Das Protokoll wird danach kurz in eigener Transaktion geschrieben.
- Entwicklung: `compose.dev.yml` startet Mailpit (SMTP 10025, Oberfläche 10081); das Profil `dev` startet es beim Anwendungsstart selbst (`spring-boot-docker-compose`, nur dort aktiv, nicht im Image, nicht in Tests) und zeigt darauf. Tests nutzen einen Fake-Sender (`CapturingMailSender`) und GreenMail für einen echten SMTP-Durchlauf, kein Docker nötig.
- STARTTLS ist Pflicht (`starttls.required`) und das Serverzertifikat wird geprüft (`ssl.checkserveridentity`); nur im Profil `dev` (Mailpit) aus. Scheitert das Protokollieren nach dem Senden, wird es einmal wiederholt und sonst mit Beleg- und Message-ID im Fehlerlog festgehalten. `POST /mail/send` ist wie alle `POST` durch den CSRF-Schutz des Moduls `security` geschützt (Doppelklick sperrt `app.js`).
- Datenschutz: Adressen, Betreff und Text stehen nicht in Logs und Ausnahmetexten, `toString()` der Mail-Typen verbirgt sie; Fehler werden als Schlüssel gespeichert.

### Hinweise an den Betreiber
- Modul `notification` (siehe docs/handbuch.md, „Hinweis-Mail“). Der Scheduler tickt in festem Abstand (`faktura.notification.poll-interval`, Standard 5 Minuten) und prüft Sendezeit und `last_run_date`; so überleben Neustart und eine geänderte Uhrzeit ohne neuen Cron-Ausdruck.
- Gesendet wird nie in einer Transaktion (SQLite-Pool mit einer Verbindung): `MailService.sendNotice` bricht bei laufender Transaktion ab, das Protokoll entsteht danach in einer kurzen eigenen Transaktion. Die Mail geht ohne Eintrag in `mail_log` (das kennt nur Belege); der Nachweis ist `notification_log`.
- Empfänger und Kategorien stehen in der Tabelle `notification_setting`, Takt, Standard-Uhrzeit, Fehlergrenze und Listenlänge in `faktura.notification.*`, die Adresse der Anwendung für den Link in `faktura.base-url` (`BASE_URL`).
- Tests: `NotificationServiceTest` (Sammelmail, Idempotenz, Randtage, Fehler und Wiederholung, nur Betreiber als Empfänger), `DigestBuilderTest`, `NotificationPagesTest`, `NotificationPostgresTest`; der Quelltext-Scan in `DocumentImmutabilityTest` deckt `notification_log` ab.

### Export-Ordner (ecoDMS)
- `faktura.archive.export-path` (`EXPORT_PATH`, leer = aus). `ArchiveService` stößt nach dem Commit einer PDF-Datei `ArchiveExportService.requestExport` an (Hintergrund-Thread, serialisiert); der Dienst kopiert alle archivierten PDFs ohne Eintrag in `file_export` aus dem hash-geprüften Archiv (`ExportFiles.write`: `.part`, Hash prüfen, Umbenennen, nie überschreiben, Pfad nur aus der Konfiguration, Name nur `[A-Za-z0-9._-]`).
- Fehler rollen nie zurück: Log ohne Personendaten, Eintrag fehlt in `file_export` = offen (Hinweis auf der Startseite über `ArchiveExportService.status()`), Nachholen beim Start, täglich (`ExportRetryScheduler`) und per `--faktura.maintenance=export-missing`.
- Der Export-Ordner wird weder gesichert noch gelöscht; `file_export` ist nur zum Einfügen da (Quelltext-Scan in `DocumentImmutabilityTest`). Tests: `ExportFilesTest`, `ArchiveExportServiceTest`, `EInvoiceFinalizeTest`.

### Konfiguration und Betrieb
- Oberflächentexte stehen in `messages_de.properties`; die leere `messages.properties` sorgt nur dafür, dass Spring Boot die
  MessageSource anlegt (Sprache fest `de`). Standardwerte neuer Steuerprofile stehen unter `faktura.tax.*` in `application.yml`.
- `tax` hängt von `company` ab (Firma für das Steuerprofil), deshalb liegt die Seite „Steuerprofile“ in `tax.web`
  (`/company/tax-profiles`) und nicht in `company.web`; so entsteht kein Modulzyklus.
- `application.yml` mit Umgebungsvariablen für Datenbank, Archivpfad und Port. Fristen, Sätze, Gebühren in der Datenbank.
- **Bindung:** `server.address` ist standardmäßig `127.0.0.1` (`SERVER_ADDRESS`), damit ein Start ohne Docker im Modus `NONE` nicht aus dem Netz erreichbar ist.
  Das Image setzt `SERVER_ADDRESS=0.0.0.0` (sonst erreicht die Port-Weiterleitung von Docker die Anwendung nicht), das Profil `dev` ebenfalls, damit ein Proxy in Docker
  die Anwendung auf dem Host erreicht; dort lehnt der Header-Modus jede Adresse außerhalb von `trusted-proxies` ab. `StartupSummary` nennt Adresse und Port.
- **Backup:** Datenbank (SQLite-Datei per `VACUUM INTO` oder `.backup`, nicht mit laufendem Schreiber kopieren) und das
  Archiv-Verzeichnis zusammen sichern. Aufbewahrungsfristen beachten (Belege sind aufbewahrungspflichtig). Runbook:
  `docs/betrieb.md`. Umsetzung: `BackupService` (Modul `archive`) schreibt mit `VACUUM INTO` eine konsistente Datenbank-Sicherung, spiegelt das
  Archiv (nur fehlende Dateien, nie überschreiben, nie löschen), schreibt ein Manifest und prüft den Spiegel gegen die Sicherung; nur ältere
  Datenbank-Sicherungen werden rotiert (`faktura.backup.*`, nächtlicher Job, standardmäßig aus). `ArchiveVerifier` vergleicht Größe und SHA-256
  aller Dateien aus der Tabelle `file` mit dem Archiv. Beide laufen auch als Kommando: `--faktura.maintenance=backup|verify-archive`
  (`MaintenanceRunner`, Exit-Code 0/1/2).
- **CSRF und Header:** Spring Security schützt alle `POST` mit einem Token (Modul `security`, Schritt 10a) und setzt eine strenge Content-Security-Policy;
  eine neue `POST`-Route braucht nichts Besonderes, `CsrfProtectionTest` findet sie selbst. Neue Seiten laden keine Inline-Skripte und keine Inline-Styles.
- **Zugriffsschutz** im Eigenbetrieb über Pangolin + Authentik; das Modul `security` (Schritt 10a, siehe docs/betrieb.md,
  „Anmeldung und Benutzer“) bringt eine zuschaltbare Anmeldung mit mehreren Benutzern und die Übernahme des Benutzernamens aus einem Proxy-Header.
  Mit Spring Security kommt ein Filter vor alle Anfragen; `/actuator/health` bleibt offen, der Modus `NONE` ist der Standard und ändert am
  Betrieb nichts außer dem CSRF-Token.

## Entwicklung: Profil `dev`
`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` startet die App mit eigener Datenbank `./data/dev.db` und eigenem Archiv
`./data/dev-archive` (`application-dev.yml`). Bei jedem Start setzt `DevDatabaseReset` die Datenbank zurück (Flyway `clean` + `migrate`),
danach legt `DevDataSeeder` über die öffentlichen Services erfundene Testdaten an: Firma mit beiden Steuerprofilen (Kleinunternehmer
bis 2026, Regelbesteuerung ab 2027), fünf Kunden (ZUGFeRD, Rabatt und Zahlungsziel, Ausland mit USt-IdNr., Privatperson, archiviert) mit
Ansprechpartnern, Leistungskatalog (auch inaktiv), Textvorlagen und Rechnungen (finalisiert, storniert, Entwurf, Rabatte, mehrere
Steuersätze, Umkehr der Steuerschuld, 40 Positionen). Die Daten entstehen nach dem Start der Web-Schicht und sind nach etwa einer
halben Minute vollständig. Schutz: Das Profil akzeptiert nur eine SQLite-Datei `dev*.db` und ein Archivverzeichnis `dev-archive*` (Pfad aufgelöst, vor jedem
Löschen geprüft); ein Archiv mit fremden Dateien ohne Markerdatei `.faktura-dev` wird nicht geleert. Eine PostgreSQL-Datenbank wird nie zurückgesetzt. Im Profil gelten Änderungen an Thymeleaf-Templates, statischen Dateien und Texten (`messages_de.properties`) ohne Neustart (Quelle `src/main/resources`, kein Cache, `DevMessages`; die App muss aus dem Projektverzeichnis gestartet werden); Java-Code braucht einen Neustart. `DevDataSeederTest` prüft, dass ein erneutes Zurücksetzen denselben Bestand liefert.
