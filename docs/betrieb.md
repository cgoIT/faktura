# Betrieb (Runbook)

> **Betrieb auf eigenes Risiko.** faktura wird ohne Gewähr bereitgestellt (Apache-2.0). Sie sind selbst verantwortlich für
> Sicherheit des Betriebs, Backups, Aufbewahrung und die Prüfung jeder Rechnung. Das ist keine Rechts- oder Steuerberatung.

faktura läuft als ein Docker-Container mit SQLite (oder PostgreSQL) und einem Daten-Volume. Dieses Dokument beschreibt Start, Update,
Backup, Wiederherstellung, Prüfung der Archivintegrität, den Zugriffsschutz und eine Checkliste für den Umstieg aus einer anderen Software.

Alles Folgende ist für den Eigenbetrieb gedacht. Hostnamen, Zugangsdaten und Pfade sind Platzhalter; echte Werte gehören in die
lokale `.env` (nicht ins Repository).

## Wichtig vorab

- **Der Standardmodus `SECURITY_MODE=NONE` hat keine Anmeldung.** Er ist nur sicher, wenn ein vorgeschalteter Proxy (z. B. Pangolin mit
  Authentik, oder ein Reverse Proxy mit Basic-Auth/SSO) *alle* Zugriffe schützt und der Port nicht erreichbar ist. Steht kein solcher Proxy
  davor, **`SECURITY_MODE=FORM` verwenden** (Benutzername und Passwort, mindestens 12 Zeichen) und die Anwendung nur über HTTPS erreichbar machen.
- Die Anwendung gehört nie ungeschützt ins Internet.
- **Ohne Docker** (`java -jar`, `./mvnw spring-boot:run`) hört die Anwendung nur auf `127.0.0.1` und ist vom Netz aus nicht erreichbar. Läuft der
  Reverse Proxy auf einem anderen Rechner, `SERVER_ADDRESS` auf die Adresse des internen Netzes setzen (oder `0.0.0.0`) und den Port per Firewall
  auf den Proxy beschränken. Im Container setzt das Image `SERVER_ADDRESS=0.0.0.0`; dort begrenzt die Port-Bindung in `docker-compose.yml` den Zugriff.
- **Personendaten:** Datenbank, Archiv, Backups, CSV-Exporte und Mails enthalten Kundendaten (Name, Anschrift, E-Mail, Beträge). Backups
  verschlüsseln (z. B. verschlüsselte Platte oder Offsite-Ziel), Zugriff begrenzen, Löschfristen und Auskunftspflichten (DSGVO) beachten. Aufbewahrungspflichtige
  Belege dürfen nicht vorzeitig gelöscht werden (siehe „Aufbewahrungsfristen“).

## Überblick

```
Browser ──> Reverse Proxy (TLS, Anmeldung; hier Pangolin/Authentik) ──> faktura (Container, Port 8080) ──> /data  (Volume)
                                                                              ├─ faktura.db   SQLite-Datenbank
                                                                              ├─ archive/     PDF und XML finalisierter Belege
                                                                              ├─ backup/      Datenbank-Sicherungen und Archiv-Spiegel
                                                                              └─ logs/        Logdateien (rotiert)
```

Alles, was aufbewahrungspflichtig ist, liegt unter `/data`. **Dieses Verzeichnis ist das, was gesichert werden muss.**

## Start

Voraussetzung: Docker mit Compose. Gebraucht werden nur `docker-compose.yml` und `.env.example` aus dem Repository; das Image kommt
fertig aus der Registry (siehe „Fertiges Container-Image“).

```bash
curl -fsSLO https://raw.githubusercontent.com/cgoIT/faktura/main/docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/cgoIT/faktura/main/.env.example -o .env
                                  # Pflicht in .env: Administrator-Passwort eintragen; Port, Datenverzeichnis, Backup-Zeit anpassen
mkdir -p data && sudo chown 10001:10001 data   # der Container läuft als Nutzer 10001, nicht als root
docker compose up -d
docker compose ps                 # Status "healthy" nach etwa einer Minute
```

- Ohne vorhandene Datenbank legt Flyway beim ersten Start das Schema an (`/data/faktura.db`).
- Die Anwendung hört im Container auf Port 8080 und ist im Beispiel nur an `127.0.0.1` gebunden. Der Reverse Proxy
  (z. B. Pangolin, Caddy, nginx, Traefik) spricht diesen Port an; der Port gehört nicht ins Netz.
- Health-Check: `GET /actuator/health` liefert `{"status":"UP"}`. Es ist der einzige freigegebene Actuator-Endpunkt, ohne Details.
- Zeitzone: `Europe/Berlin` (im Image fest gesetzt, `TZ` und `-Duser.timezone`).
- Das Profil `dev` ist im Container **verboten**: Es setzt die Datenbank bei jedem Start zurück. Die Anwendung bricht mit
  einer Fehlermeldung ab, wenn `SPRING_PROFILES_ACTIVE=dev` im Container gesetzt ist. Nie setzen.

### Konfiguration (Umgebungsvariablen)

| Variable | Standard im Image | Bedeutung |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | Port im Container |
| `SERVER_ADDRESS` | `0.0.0.0` | Adresse, auf der die Anwendung hört; ohne Image ist der Standard `127.0.0.1` |
| `DB_URL` | `jdbc:sqlite:/data/faktura.db` | SQLite-Datei oder `jdbc:postgresql://…` |
| `DB_USER`, `DB_PASSWORD` | leer | nur für PostgreSQL |
| `DB_POOL_SIZE` | `1` | bei SQLite 1 lassen (ein Schreiber), bei PostgreSQL erhöhen |
| `ARCHIVE_PATH` | `/data/archive` | Archiv der finalisierten Belege |
| `BACKUP_ENABLED` | `false` (Beispiel-Compose: `true`) | nächtliche Sicherung durch die Anwendung |
| `BACKUP_CRON` | `0 30 2 * * *` | Zeitplan (Spring-Cron: Sekunde Minute Stunde Tag Monat Wochentag) |
| `BACKUP_PATH` | `/data/backup` | Ziel der Sicherung |
| `LOGGING_FILE_NAME` | `/data/logs/faktura.log` | Logdatei; leer = nur Konsole (`docker logs`). Logback rotiert täglich und nach Größe, ältere Dateien als `.gz` |
| `LOG_MAX_FILE_SIZE`, `LOG_MAX_HISTORY`, `LOG_TOTAL_SIZE_CAP` | `10MB`, `14`, `200MB` | Größe je Datei, Tage Aufbewahrung, Obergrenze aller Logdateien |
| `BACKUP_KEEP_COUNT` | `14` | so viele Datenbank-Sicherungen bleiben erhalten |
| `EXPORT_PATH` | leer (aus) | zweiter Ablagepfad für finalisierte PDFs, z. B. ein von ecoDMS überwachter Ordner (siehe „Export-Ordner für ecoDMS“) |
| `EXPORT_RETRY_CRON` | `0 45 5 * * *` | täglicher Nachholversuch für nicht kopierte PDFs |
| `LOGO_PATH` | leer | eigenes Logo für die PDFs (PNG, JPEG oder GIF; Datei im Container, z. B. `/data/logo.png`; siehe Handbuch „Eigenes Logo“) |
| `RECURRING_ENABLED`, `RECURRING_CRON` | `true`, `0 15 5 * * *` | täglicher Job für Wiedervorlagen |
| `FORWARD_HEADERS_STRATEGY` | `framework` | Schema und Host aus den `X-Forwarded-*`-Headern des Proxys |
| `MAIL_ENABLED` | `false` | Mailversand von Belegen einschalten (siehe „Mailversand“) |
| `MAIL_HOST`, `MAIL_PORT` | leer, `587` | SMTP-Server des Mailanbieters |
| `MAIL_USER`, `MAIL_PASSWORD` | leer | Zugangsdaten; nur über die Umgebung, nie ins Repository |
| `MAIL_STARTTLS`, `MAIL_SSL`, `MAIL_AUTH` | `true`, `false`, `true` | Verschlüsselung (Port 587: STARTTLS; Port 465: `MAIL_SSL=true`, `MAIL_STARTTLS=false`) |
| `MAIL_FROM` | leer | Absenderadresse; leer: E-Mail-Adresse des Unternehmens |
| `MAIL_REPLY_TO`, `MAIL_BCC` | leer | optionale Antwortadresse und optionale Blindkopie jeder Mail an dich selbst |
| `MAIL_MAX_ATTACHMENT_BYTES` | `10485760` | größere PDFs werden nicht gesendet |
| `MCP_ENABLED`, `MCP_TOKEN`, `MCP_TOKEN_HEADER` | `false`, leer, `X-API-Key` | MCP-Server für KI-Assistenten einschalten, optionaler Notzugang zum Lesen (mindestens 32 Zeichen; Tokens verwaltet der Administrator in der Oberfläche) und der Name des Headers, der den Token ohne `Bearer` trägt (Claude Desktop akzeptiert nur Namen, die Anthropic kennt), siehe „KI-Assistenten anbinden“ |
| `BASE_URL` | leer | Adresse, unter der du faktura erreichst (z. B. `https://faktura.example.org/`), für den Link in der Hinweis-Mail; leer: Mail ohne Link |
| `NOTIFICATION_ENABLED` | `true` | Prüfung der Hinweis-Mail ein/aus (die Kategorien selbst sind bis zum Einschalten in der Oberfläche aus) |
| `NOTIFICATION_POLL_INTERVAL` | `PT5M` | wie oft geprüft wird, ob die tägliche Mail fällig ist |

Fristen, Steuersätze, Mahnstufen und Gebühren stehen nicht hier, sondern in der Datenbank und werden in der Oberfläche gepflegt.

### Mailversand

Rechnungen, Stornos und Mahnungen lassen sich aus der Anwendung per Mail an den Kunden senden („Per E-Mail senden“ an der
Rechnung bzw. an der Mahnung). Es wird nie automatisch gesendet: jede Mail wird im Dialog geprüft und bestätigt.

1. Beim Mailanbieter ein Postfach oder SMTP-Zugang anlegen und `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD` setzen
   (in der `.env` neben der `docker-compose.yml`, Abschnitt „Mail“ einkommentieren; die Datei gehört nicht ins Repository), dazu `MAIL_ENABLED=true`.
2. Absender: `MAIL_FROM` oder die E-Mail-Adresse unter „Unternehmen“. Der Absender muss zum Postfach passen, sonst lehnt
   der Anbieter die Mail ab.
3. **SPF, DKIM und DMARC** für die Absenderdomain beim Mailanbieter bzw. im DNS einrichten, sonst landen Rechnungen
   leicht im Spamordner der Kunden. Das ist keine Einstellung dieser Anwendung.
4. Ohne Einrichtung bleibt die Funktion sichtbar ausgeschaltet („Per E-Mail senden“ ist gesperrt, mit Grund); alles andere läuft weiter.
5. Texte pflegst du unter „Einstellungen → Mailvorlagen“ (je Dokumenttyp beliebig viele Varianten, z. B. formell und informell).

Jeder Versuch steht in der Tabelle `mail_log` (Empfänger im Feld „An“, Betreff, Text, Anhang, Ergebnis; eine konfigurierte Blindkopie `MAIL_BCC` steht nicht darin) und wird nie geändert oder gelöscht;
`mail_log` gehört wie die übrigen Tabellen zur Datenbank-Sicherung. Schlägt das Senden fehl, steht das im Dialog und im Verlauf am
Beleg; es gibt keinen automatischen neuen Versuch.

Zum Ausprobieren ohne echten Versand dient im Entwicklungsprofil Mailpit (`compose.dev.yml`, siehe README).

### Hinweise per Mail (Benachrichtigungen)

faktura kann dir einmal am Tag eine Mail schicken, wenn es etwas zu tun gibt: neue Entwürfe aus Wiedervorlagen, fehlgeschlagene Wiedervorlagen,
überfällige Rechnungen, fällige Mahnungen, bald ablaufende Angebote. Die Mail geht nur an dich, nie an Kunden.

1. Mailversand einrichten wie oben („Mailversand“); ohne ihn passiert nichts, die Seite weist darauf hin.
2. Optional `BASE_URL` setzen, damit die Mail einen Link zur Anwendung enthält.
3. Unter „Einstellungen → Benachrichtigungen“ die gewünschten Kategorien einschalten (Standard: alle aus), Empfänger (leer: E-Mail-Adresse des Unternehmens) und Uhrzeit wählen (Standard 08:00).
4. Mit „Testmail senden“ prüfst du den Weg, mit „Jetzt prüfen“ löst du die Prüfung sofort aus.
5. Den Text pflegst du unter „Einstellungen → Mailvorlagen“, Abschnitt „Hinweise an den Betreiber“.

Gute Zeit für die Mail ist nach dem täglichen Lauf der Wiedervorlagen (`RECURRING_CRON`, Standard 05:15). Jeder Fall wird nur einmal gemeldet (Tabelle `notification_log`, gehört zur
Datenbank-Sicherung, wird nie geändert oder gelöscht). Scheitert das Senden, steht der Fall weiter aus und wird nach einer Stunde (`faktura.notification.retry-delay`) erneut versucht; nach fünf Fehlversuchen in Folge pausiert der
automatische Versand, bis du speicherst, „Jetzt prüfen“ oder „Testmail senden“ wählst. Stürzt faktura genau zwischen Senden und Merken ab, kommt dieselbe Mail ein zweites Mal.

### Export-Ordner für den Import in andere Systeme (z.B. Dokumentenarchive)

faktura kann jedes finalisierte PDF (Rechnung, Storno, Angebot, Mahnung) zusätzlich in einen zweiten Ordner kopieren. Ein Archivsystem überwacht diesen
Ordner und archiviert die PDFs selbst; mehr Anbindung gibt es nicht (keine API, keine Rückmeldung, keine Metadaten).

1. Ordner anlegen, auf den das Archivsystem zugreifen kann (z. B. ein Netzlaufwerk oder ein lokaler Ordner), und im Container einhängen:
   in `docker-compose.yml` die auskommentierte Zeile `/export` aktivieren, in `.env` `EXPORT_PATH=/export` und `FAKTURA_EXPORT=<Ordner auf dem Host>` setzen.
2. Der Ordner muss für den Container-Nutzer 10001 beschreibbar sein (`chown 10001:10001 <Ordner>`) und **bereits existieren**; faktura legt ihn nie an,
   damit ein fehlender Mount nicht unbemerkt in den Container schreibt.
3. Im Archivsystem diesen Ordner als Überwachungsordner einrichten. faktura schreibt jede Datei zuerst als versteckte `.<Nummer>.pdf.part` und benennt sie
   erst nach der Prüfung um; das Archivsystem sollte versteckte Dateien und `.part` ignorieren.

Verhalten:

- Dateiname ist die Belegnummer, z. B. `RE202700001.pdf`, flach ohne Unterordner. Bei ZUGFeRD ist das PDF/A-3 mit eingebettetem XML die Datei; das XML wird nicht
  zusätzlich abgelegt. Eine vorhandene Datei wird nie überschrieben: mit gleichem Inhalt passiert nichts, mit anderem Inhalt meldet faktura einen Fehler.
- Die Kopie entsteht nach dem Finalisieren (nach dem Commit, im Hintergrund). Ein Fehler (Ordner fehlt, nicht beschreibbar, voll) rollt das Finalisieren nicht zurück
  und blockiert keine Nummer. Er steht im Log (`Export of <Datei> failed`, ohne Kundendaten) und als roter Hinweis auf der Startseite, solange PDFs offen sind.
- Offene PDFs holt faktura beim Start, täglich (`EXPORT_RETRY_CRON`) und auf Kommando nach:
  `docker compose run --rm faktura --spring.main.web-application-type=none --faktura.maintenance=export-missing --faktura.recurring.enabled=false --faktura.backup.enabled=false`
  (Exit-Code 0 = alles kopiert, 1 = Probleme, 2 = Fehler).
- Gleiche Dateinamen in zwei Bereichen (z. B. dasselbe Nummernmuster für Rechnung und Mahnung) werden als Problem gemeldet; die Muster bitte unterscheidbar wählen. Scheitert 
  das Merken in der Datenbank nach dem Schreiben, kann dieselbe Datei nach einem ecoDMS-Import ein zweites Mal kopiert werden.
- Der Hinweis auf der Startseite erscheint erst, wenn ein PDF nach zehn Minuten noch nicht kopiert ist. Das Kommando `export-missing` am besten bei gestopptem Dienst ausführen.
- Die Tabelle `file_export` merkt sich, was kopiert wurde. ecoDMS löscht Dateien nach dem Import; eine fehlende Datei im Ordner wird deshalb **nicht** erneut geschrieben.
- **Beim erstmaligen Einschalten** werden alle schon vorhandenen PDFs des Archivs kopiert (Bestand). Wer das nicht will, schaltet ecoDMS erst nach dem ersten Lauf auf den Ordner
  oder importiert den Bestand getrennt.
- Das Archiv bleibt die Quelle der Wahrheit. Der Export-Ordner ist **keine Sicherung** und gehört nicht zum Backup; faktura ändert oder löscht dort nichts
  (nur die eigene `.part`-Datei eines gescheiterten Versuchs).

### Logs

Beim Start schreibt faktura den Banner mit der Version und danach den Block „Effective configuration“ ins Log (`docker compose logs faktura`): Version, Port, Datenbank, Archiv-, Export- und Backup-Pfad, Anmeldemodus, Mail, Hinweis-Mail, Wiedervorlagen-Job, Logo. Damit lässt sich prüfen, ob die `.env` ankommt. Passwörter, Benutzernamen und Mail-Zugangsdaten stehen nie darin; bei abgeschalteter Anmeldung oder unvollständiger Mail-Konfiguration erscheint eine Zeile `WARNING`.

Auf Standardausgabe (`docker compose logs faktura`). Kundendaten, Beträge und Dateiinhalte werden nicht protokolliert; Meldungen
nennen Nummern, Zähler und Fehlerklassen. Beim Weitergeben von Logs trotzdem kurz prüfen.

## Update

1. Backup anstoßen und prüfen (siehe unten): `docker compose run --rm faktura --faktura.maintenance=backup …`.
2. Neues Image holen: `docker compose pull` (bei festgeschriebener Version vorher den Tag in `docker-compose.yml` anheben).
3. `docker compose up -d`. Beim Start führt Flyway neue Migrationen aus. Migrationen werden nie nachträglich geändert.
4. `docker compose ps` zeigt `healthy`, danach Stichprobe in der Oberfläche (Rechnungsliste, ein PDF öffnen).
5. Bei einem Fehler: Container stoppen, das Backup zurückspielen (siehe „Wiederherstellung“), das vorherige Image starten.

## Fertiges Container-Image

Zu jedem Release veröffentlicht die CI ein Image in der GitHub Container Registry (`ghcr.io/cgoit/faktura`) für `linux/amd64` und `linux/arm64`,
getaggt mit der vollen Version (z. B. `0.2.0`), der Minor-Version (`0.2`), der Major-Version (`0`, später `1` usw.) und `latest`. Die `docker-compose.yml` nutzt
`latest`; wer selbst bauen will, baut mit `docker build -t faktura .` und setzt dort `image: faktura`. Für reproduzierbare Betriebe die volle Version oder den Digest festschreiben; `latest` und die Major-Version bringen mit einem Update auch größere Änderungen, vor dem Update gilt der Ablauf unter „Update“. Die Images sind
mit cosign (keyless) signiert und haben eine Herkunftsbescheinigung:

```bash
cosign verify ghcr.io/cgoit/faktura:0.2.0 \
  --certificate-identity-regexp 'https://github.com/cgoIT/faktura/\.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## Backup

Gesichert werden **Datenbank und Archiv zusammen**. Die Datenbank wird nie bei laufendem Schreiber kopiert, sondern von der
Anwendung selbst mit `VACUUM INTO` in eine konsistente Datei geschrieben.

**Nächtlicher Job** (`BACKUP_ENABLED=true`): schreibt nach `BACKUP_PATH`

- `faktura-<Zeitstempel>.db`: Datenbank-Sicherung (die letzten `BACKUP_KEEP_COUNT` bleiben, ältere werden gelöscht),
- `manifest-<Zeitstempel>.json`: Zeitpunkt, Hash der Datenbank-Sicherung, Anzahl der Archivdateien, Prüfergebnis,
- `archive/`: Spiegel des Archivs. Es werden nur fehlende Dateien kopiert; eine vorhandene Datei wird nie überschrieben und
  aus dem Spiegel wird nie etwas gelöscht (auch nicht aus dem Archiv selbst).

Nach dem Kopieren prüft der Job den Spiegel gegen die Sicherung der Datenbank (Größe und SHA-256 jeder Datei). Eine fehlgeschlagene
Prüfung steht im Manifest und als Fehler im Log (`Backup verification found problems`); in diesem Fall werden keine älteren
Datenbank-Sicherungen rotiert, damit die letzten guten erhalten bleiben.

**Von Hand:**

```bash
docker compose run --rm faktura --spring.main.web-application-type=none --faktura.maintenance=backup \
  --faktura.recurring.enabled=false --faktura.backup.enabled=false
```

Der Exit-Code ist 0, wenn die Sicherung geschrieben und geprüft ist, 1 bei Prüfproblemen, 2 bei Fehlern.

**Wichtig – das Backup im Volume ist keine Offsite-Sicherung.** Das Verzeichnis `backup/` liegt auf derselben Platte wie die
Daten. Es muss zusätzlich auf einen anderen Rechner oder Speicher kopiert werden (z. B. `rsync`/Restic/Kopie auf ein NAS oder
einen Cloud-Speicher mit Verschlüsselung). Das ist Aufgabe des Betreibers; die Anwendung versendet nichts. Mindestens
`backup/` sichern; wer lieber das ganze `/data` sichert, schließt die Datenbank-Datei besser mit einem Stopp des Containers
oder über die Sicherung in `backup/` ab, nicht durch Kopieren von `faktura.db` im laufenden Betrieb.

PostgreSQL: Die Anwendung sichert nur SQLite. Bei PostgreSQL die Datenbank mit `pg_dump` sichern und das Archiv wie oben
spiegeln; `faktura.maintenance=backup` bricht dort mit einer Meldung ab.

## Wiederherstellung

Ein Backup ist erst etwas wert, wenn die Wiederherstellung geübt ist. **Vor dem Produktivstart einmal durchspielen**, am besten auf
einem anderen Rechner.

1. Container stoppen: `docker compose stop faktura`.
2. Das aktuelle `/data` **nicht löschen**, sondern umbenennen (`mv data data.alt`) und ein leeres `data` anlegen
   (`chown 10001:10001`).
3. Die gewünschte Datenbank-Sicherung als `data/faktura.db` kopieren und den Spiegel `backup/archive/` nach `data/archive/`.
4. Container starten: `docker compose up -d`.
5. Archiv prüfen (nächster Abschnitt). Erst wenn die Prüfung fehlerfrei ist, gilt die Wiederherstellung als gelungen.
6. Erst danach `data.alt` aufbewahren oder, wenn alles stimmt und nicht mehr gebraucht wird, nach eigener Entscheidung löschen.

Die Tests `BackupServiceTest.restoredBackupHasTheSameHashesAndAMatchingSchema` und `…damagedMirror…` führen diesen Ablauf
automatisch mit einer Beispieldatenbank aus.

## Prüfung der Archivintegrität

Jede Archivdatei (PDF, XML) hat in der Tabelle `file` Größe und SHA-256. Die Prüfung vergleicht jede Datei im Archiv mit diesen
Werten. Sie ändert nichts.

```bash
docker compose run --rm faktura --spring.main.web-application-type=none --faktura.maintenance=verify-archive \
  --faktura.recurring.enabled=false --faktura.backup.enabled=false
```

Ausgabe: Anzahl geprüfter Dateien, Probleme (`MISSING`, `SIZE_MISMATCH`, `HASH_MISMATCH` mit Pfad) und Dateien ohne
Datenbankeintrag (Hinweis, kein Fehler). Exit-Code 0 = alles in Ordnung, 1 = Probleme.

Wann prüfen: nach jeder Wiederherstellung, nach einem Umzug auf einen anderen Rechner, vor dem Löschen eines alten Backups und
zusätzlich in größeren Abständen (z. B. vierteljährlich). Beim Öffnen einer Datei in der Oberfläche wird der Hash ohnehin geprüft.

Bei einem Fehler: **nichts überschreiben und nichts löschen.** Betroffene Datei aus dem Backup-Spiegel holen (dort vergleichen,
`verify-archive` auf der Sicherung oder Hash per `sha256sum`), die beschädigte Datei beiseitelegen.

## Zugriffsschutz: Reverse Proxy, Pangolin und Authentik

Standardmäßig hat faktura keine eigene Anmeldung (`SECURITY_MODE=NONE`); der Schutz kommt vom vorgeschalteten Proxy. Gegen Anfragen von
fremden Seiten schützen CSRF-Token und Content-Security-Policy in jedem Modus. Die Modi sind unter „Anmeldung und Benutzer“ beschrieben.
Bei `NONE` gilt:

- Die Anwendung hängt nur im internen Netz bzw. an `127.0.0.1`; nur Pangolin erreicht sie. Ohne Docker hört sie standardmäßig nur auf
  `127.0.0.1` (`SERVER_ADDRESS`).
- In Pangolin eine Ressource für `https://<faktura-hostname>` anlegen, Ziel `http://<docker-host>:8080`, und die Authentifizierung
  über Authentik (SSO/OIDC) für die **ganze** Ressource aktivieren, ohne Ausnahmen für Pfade. Der Health-Check läuft im
  Container, braucht also keine Freigabe nach außen.
- Nur die eigene Person (Authentik-Gruppe mit einem Mitglied) bekommt Zugriff; Mehrfaktor-Anmeldung in Authentik aktivieren.
- Die Anwendung vertraut den `X-Forwarded-*`-Headern des Absenders (`FORWARD_HEADERS_STRATEGY=framework`); das ist nur sicher, wenn ausschließlich
  der Proxy sie erreichen kann (Bindung an `127.0.0.1` oder internes Netz).
- TLS endet am Proxy. `FORWARD_HEADERS_STRATEGY=framework` sorgt dafür, dass Weiterleitungen `https` und den richtigen Host nutzen.
- Alle Funktionen stehen hinter derselben Anmeldung. Ohne den Proxy ist die Anwendung ungeschützt: sie niemals direkt ins Internet stellen.

### Andere Reverse Proxys (Beispiele)

Jeder Proxy genügt, der TLS beendet, **jede** Anfrage authentifiziert (SSO/OIDC oder Basic-Auth) und `X-Forwarded-Proto`/`-Host` setzt. Wer keinen
Proxy mit Anmeldung hat, nutzt `SECURITY_MODE=FORM`; der Proxy übernimmt dann nur TLS.

```
# Caddy: TLS und Basic-Auth (Hash mit "caddy hash-password" erzeugen)
faktura.example.org {
    basic_auth {
        anna <hash>
    }
    reverse_proxy 127.0.0.1:8080
}
```

```
# nginx: TLS-Teil weggelassen; der Proxy muss die Weiterleitungsheader setzen
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
}
```

Im Modus `PROXY_HEADER` muss der Proxy den Benutzer-Header selbst setzen und einen vom Client mitgeschickten Wert entfernen (siehe „Anmeldung und Benutzer“).

## Datenbank: SQLite oder PostgreSQL

SQLite (Standard) braucht nichts außer dem Volume und genügt für einen Betrieb mit wenigen Benutzern. PostgreSQL wird über `DB_URL`, `DB_USER`,
`DB_PASSWORD` und `DB_POOL_SIZE` gewählt; beide Datenbanken laufen mit demselben Flyway-Schema. Ein Umzug von SQLite nach PostgreSQL ist nicht
automatisiert (Neuaufbau und Datenübernahme per Skript). Beim Backup mit PostgreSQL sichert man die Datenbank mit `pg_dump` zusätzlich zum Archiv-Verzeichnis.

## Anmeldung und Benutzer

`SECURITY_MODE` wählt, wie Personen erkannt werden. Der Name steht im Audit-Log (`actor`) und am Entwurf (`created_by`); Jobs schreiben `system`.

| `SECURITY_MODE` | Verhalten |
| --- | --- |
| `NONE` (Standard) | keine Anmeldung, der Proxy schützt; der Akteur heißt `anonymous` |
| `FORM` | Anmeldeseite mit Benutzername und Passwort. Beim ersten Start legen `SECURITY_ADMIN_USER` und `SECURITY_ADMIN_PASSWORD` (mindestens 12 Zeichen) den Administrator an; ohne sie startet die Anwendung nicht. Danach verwaltet er die Benutzer unter dem Menüpunkt „Benutzer“ (nur Administratoren). Die beiden Variablen können nach dem ersten Start entfernt werden. |
| `PROXY_HEADER` | Der Benutzername kommt aus dem Header `SECURITY_HEADER` (Standard `X-Forwarded-User`, bei Authentik direkt `X-authentik-username`, bei Pangolin `Remote-User`); der Anzeigename optional aus `SECURITY_NAME_HEADER` (Pangolin: `Remote-Name`). Der Header gilt nur von Adressen in `SECURITY_TRUSTED_PROXIES`; alles andere ergibt 403. Benutzer entstehen beim ersten Zugriff, `SECURITY_ADMIN_USER` ist Administrator. |
| `PROXY_HEADER_ADMIN_FORM` | Alle kommen wie bei `PROXY_HEADER` über den Header. Das Administrator-Recht gibt es dort nie: Wer Administrator sein will, wechselt über „Admin-Modus“ (Link im Seitenmenü) zur Anmeldeseite und meldet sich mit dem Administrator-Konto und Passwort an. Diese Sitzung hat Vorrang vor dem Header, Der Admin-Modus ist nur für die Benutzerverwaltung da: Das Seitenmenü zeigt nur „Benutzer“, alle anderen Seiten leiten dorthin um, und die Rechnungsarbeit bleibt dem Header-Benutzer. „Admin-Modus beenden“ meldet die Sitzung ab, danach gilt wieder der Header. `SECURITY_ADMIN_USER` und `SECURITY_ADMIN_PASSWORD` sind Pflicht; das Konto entsteht beim Start, falls es fehlt. |

- **Der Proxy muss den Header selbst setzen und einen vom Client mitgeschickten Wert überschreiben oder entfernen** (bei Authentik/Pangolin so einrichten und
  prüfen). Der Bereich in `SECURITY_TRUSTED_PROXIES` soll nur den Proxy enthalten, kein ganzes Docker-Netz mit weiteren Containern.
- **`SECURITY_TRUSTED_PROXIES`** (Komma-getrennt, Adressen oder CIDR, Standard `127.0.0.1/32,::1/128`): im Container erscheint der Proxy mit der Adresse des
  Docker-Netzes, nicht mit `127.0.0.1`; dort das Netz eintragen (z. B. `172.18.0.0/16`) und prüfen, dass nur der Proxy die Anwendung erreicht. Wer den Header selbst
  setzen kann, ist jeder beliebige Benutzer.
- **Menü:** „Benutzer“ ist in allen Modi außer `NONE` ein Menüpunkt links (nur für Administratoren), nicht mehr ein Tab unter „Einstellungen“.
- **`PROXY_HEADER_ADMIN_FORM`:** Der Name des Administrator-Kontos (`SECURITY_ADMIN_USER`, z. B. `admin`) darf kein Name sein, den der Identity Provider liefert; der Header mit diesem Namen oder mit dem Namen eines Kontos mit Passwort ergibt 403. Gibt es unter dem Namen schon ein Konto ohne Passwort (vom Header angelegt), startet die Anwendung nicht, damit niemand unbemerkt zum Administrator wird. Die Anmeldeseite unterliegt derselben Regel für Absender und Header wie alle Seiten. Ein Konto mit Passwort, das ein Administrator unter dem Menüpunkt „Benutzer“ (nur Administratoren) anlegt, meldet sich ebenfalls nur über das Formular an. Der Akteur im Audit-Log heißt in der Admin-Sitzung `admin`.
- **Letzte Anmeldung:** Ein Besuch über den Header zählt als Anmeldung und setzt die Zeit in der Benutzerliste, aber höchstens alle 30 Minuten (`faktura.security.proxy-login-interval`), damit nicht jede Anfrage die Zeile schreibt.
- **Anzeigename:** Ist `SECURITY_NAME_HEADER` gesetzt, speichert faktura den Wert beim Anlegen des Benutzers und gleicht ihn bei jeder Anfrage ab, wenn der
  Identity Provider einen anderen meldet (er ist dort die Quelle). Ein leerer Wert ändert nichts, über 200 Zeichen wird gekürzt. Der Name erscheint in der Benutzerliste;
  im Audit-Log und an Entwürfen steht weiter nur der Benutzername.
- `SECURITY_ADMIN_USER` macht im Modus `PROXY_HEADER` den Benutzer nur beim Anlegen zum Administrator; später entscheiden die Administratoren.
- **Pangolin (Public Resource mit SSO, z. B. Authentik):** In der Resource die Weitergabe der Identitäts-Header einschalten. Pangolin sendet dann
  `Remote-User`, `Remote-Email`, `Remote-Name` und `Remote-Role`, aber nur, wenn es den Benutzer kennt (nicht bei PIN, Passwort oder E-Mail-OTP; dann antwortet faktura mit 403).
  Einstellung: `SECURITY_MODE=PROXY_HEADER`, `SECURITY_HEADER=Remote-User`, `SECURITY_NAME_HEADER=Remote-Name`, `SECURITY_ADMIN_USER=<Wert von Remote-User>`.
  `X-Forwarded-For` und `X-Real-Ip` zeigen die Client-Adresse und gelten nicht für die Prüfung; maßgeblich ist die Adresse der Verbindung zu faktura (`ProxyHeaderFilter` umgeht dafür den `ForwardedHeaderFilter`, der `getRemoteAddr()` sonst durch `X-Forwarded-For` ersetzt; `ProxyHeaderModeTest` prüft das). Das gilt nicht, wenn Tomcat selbst mit `SERVER_FORWARD_HEADERS_STRATEGY=native` die Adresse ersetzt; diese Einstellung im Modus `PROXY_HEADER` nicht verwenden.
- **Ablehnungen stehen im Log** (Stufe WARN, `ProxyHeaderFilter`): Grund (Adresse nicht in `SECURITY_TRUSTED_PROXIES`, Header fehlt oder ist leer, Benutzer deaktiviert) und die Adresse der Verbindung, nie ein Headerwert. Derselbe Grund von derselben Adresse erscheint höchstens einmal pro Minute. Eine ungestylte Seite „Nicht erlaubt“ mit „Angemeldet als anonymous“ bedeutet meist genau diesen Fall.
- **Welche Adresse sieht faktura?** Statt faktura kurz einen Echo-Dienst hinter dieselbe Resource hängen (`docker run --rm -p 8090:80 traefik/whoami`), anmelden und die
  Ausgabe lesen: `RemoteAddr` (ohne Port) ist die Adresse für `SECURITY_TRUSTED_PROXIES`, `Remote-User` der Wert für `SECURITY_ADMIN_USER`. Liegt die Adresse im
  Gateway eines Docker-Netzes (z. B. `192.168.x.1`), kommt der Proxy nicht als eigener Container an: dann das Netz so legen, dass nur der Proxy faktura erreicht, und kontrollieren,
  dass `curl -H 'Remote-User: x' http://<Host>:<Port>/` von einer anderen Adresse 403 ergibt.
- Ohne Anmeldung (`NONE`) steht im Audit-Log der Akteur `anonymous`, bei Jobs `system`.
- Im Modus `FORM` mit HTTPS am Proxy `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` setzen.
- Die Sperre nach fünf falschen Anmeldungen (15 Minuten) trifft den Benutzernamen und liegt im Speicher; ein Neustart hebt sie auf. Wer einen Namen kennt, kann das
  Konto damit kurz sperren (bei nur einem Administrator also auch diesen). Eine offene Sitzung endet bei der nächsten Anfrage, wenn der Benutzer deaktiviert oder
  sein Administratorrecht geändert wird (Sitzungen laufen sonst nach 8 Stunden ab); ein neues Passwort beendet andere Sitzungen nicht. Ein vergessenes Passwort setzt ein anderer Administrator
  zurück; war es der einzige, die Datenbank-Sicherung zurückspielen oder die Zeile in `app_user` von Hand ändern (Hash mit BCrypt erzeugen).
- Der Health-Check (`/actuator/health`) bleibt in allen Modi ohne Anmeldung erreichbar.

## Export für Buchhaltung und Steuerberater

Unter „Export“ in der Navigation lassen sich je Jahr zwei CSV-Dateien laden (Rechnungen und Stornos nach Rechnungsdatum, Zahlungen nach Zahlungsdatum).
Die Dateien entstehen beim Abruf aus der Datenbank, es liegt nichts auf dem Server. Sie enthalten Kundendaten und Verwendungszwecke: sicher aufbewahren und
nur an den Steuerberater geben. Der Export ersetzt weder das Backup noch das Archiv.

## KI-Assistenten anbinden (MCP)

faktura kann einem KI-Assistenten (z. B. Claude) über das Model Context Protocol Werkzeuge anbieten: Kunden und Katalog suchen, offene und überfällige Rechnungen,
Mahnvorschläge und ablaufende Angebote lesen sowie **Rechnungs- und Angebotsentwürfe anlegen**. Finalisieren, Stornieren, Zahlungen buchen, Mails senden und
Löschen gibt es dort nicht; das bleibt dir im Prüfdialog vorbehalten. Ein Entwurf kann Interne Bezeichnung, Kundenreferenz, Leistungszeitraum, Zahlungsziel,
Gesamtrabatt (Prozent) und Positionen tragen; ändern lässt er sich nur in der Oberfläche.

- Standardmäßig aus. Einschalten: `MCP_ENABLED=true`. Der Endpunkt ist `POST /mcp`, mit eigener Anmeldung per Token. **Voraussetzung ist ein Anmeldemodus** (`SECURITY_MODE` `FORM`, `PROXY_HEADER` oder `PROXY_HEADER_ADMIN_FORM`): Mit `NONE` startet die Anwendung nicht, weil `/mcp` am Proxy vorbei erreichbar sein muss und dann nur der Proxy die Seite schützen würde, auf der Tokens entstehen.
- **Tokens verwalten:** Unter „KI-Zugang“ (Menü links, `/settings/mcp-tokens`) legt ein Administrator Tokens an: Name, Umfang (**Nur lesen** oder **Lesen und Entwürfe anlegen**) und
  Gültigkeit (Auswahl aus `faktura.mcp.validity-days`, Standard 30, 90 oder 365 Tage, oder „Unbegrenzt“; ein unbegrenzter Token gilt, bis er widerrufen wird, kurze Fristen sind sicherer). Der Token erscheint **nur einmal** nach dem Anlegen; gespeichert wird
  nur sein SHA-256-Wert. „Widerrufen“ wirkt sofort. Die Seite zeigt eine Liste mit „Neuen Token erstellen“ darüber und darunter eine einklappbare Anleitung für Claude Desktop und Claude Code (Adresse, Header-Name, Kopier-Symbole). Die Liste zeigt Zustand (aktiv, abgelaufen, widerrufen), den Anfang des Tokens und die letzte Benutzung (auf die Minute genau).
  Tokens werden nie gelöscht. Anlegen und Widerrufen stehen im Audit-Log (ohne den Token). Entwürfe, die ein Assistent anlegt, tragen als Ersteller `mcp:<Name des Tokens>`.
  Die Seite ist nur für Administratoren.
- **Token senden:** als `Authorization: Bearer <token>` oder nur als Wert im Header `MCP_TOKEN_HEADER` (Standard `X-API-Key`), für Clients, die nur einen Header ohne Präfix
  senden, etwa der Konnektor-Dialog von Claude Desktop (der nur Header-Namen zulässt, die Anthropic kennt).
- **Notzugang:** `MCP_TOKEN` (mindestens 32 zufällige Zeichen, z. B. `openssl rand -hex 32`) ist optional. Er gilt unbegrenzt, darf nur lesen und erscheint nicht in der Verwaltung.
  Für den normalen Betrieb leer lassen und Tokens in der Oberfläche anlegen.
- Beispiel Claude Code: `claude mcp add --transport http faktura http://localhost:8090/mcp --header "Authorization: Bearer <token>"`.
- **Hinter Pangolin und Authentik** kann ein MCP-Client die Anmeldung nicht durchlaufen. Dafür braucht `/mcp` eine eigene Ressource oder eine Ausnahme, die nur am Token hängt.
  Das ist ein bewusst geöffneter Zugang: Tokens geheim halten, kurz gültig wählen und bei Verdacht widerrufen. Ein Reverse Proxy, der Request-Header mitschreibt (Traefik: Access-Log),
  schreibt den Token ins Log; Header `Authorization` und `X-API-Key` dort ausblenden.
- **Datenschutz:** Was die Werkzeuge zurückgeben (Kundenname, Ort, Rechnungsnummern, Beträge), geht an das Modell des Assistenten. Bankdaten, Telefonnummern und Notizen
  werden nicht geliefert. Parameter und Ergebnisse werden nicht geloggt.

## Aufbewahrungsfristen

Rechnungen, Stornobelege, Mahnungen und die zugehörigen Dateien sind aufbewahrungspflichtig. Nach heutigem Stand gelten für
Buchungsbelege (dazu zählen Rechnungen) acht Jahre, für Handels- und Geschäftsbriefe sechs Jahre (§ 147 AO, Frist beginnt am
Ende des Kalenderjahres der Ausstellung). **Bitte beim Steuerberater bestätigen**; im Zweifel länger aufbewahren.

- Die Anwendung löscht weder Belege noch Archivdateien (GoBD, Unveränderlichkeit).
- Backups und Spiegel nicht vorzeitig löschen. Nur die älteren Datenbank-Sicherungen rotieren (`BACKUP_KEEP_COUNT`); der
  Archiv-Spiegel wächst mit.
- Lesbarkeit sicherstellen: Beim Abschalten von faktura vorher alle PDF/XML und eine Datenbank-Sicherung exportieren und
  aufbewahren. Auch die Belege und die Datenbank einer früheren Rechnungssoftware bleiben aufbewahrungspflichtig.

## Umstieg aus einer anderen Software (Checkliste)

Vorher:

- [ ] Image gebaut, Container läuft hinter dem Proxy bzw. mit `SECURITY_MODE=FORM`, Anmeldung getestet (auch von unterwegs).
- [ ] Backup läuft (`BACKUP_ENABLED=true`), Offsite-Kopie eingerichtet, **Wiederherstellung auf einem anderen Rechner geübt**,
      `verify-archive` fehlerfrei.
- [ ] Firmendaten, Bankverbindung, Steuernummer bzw. USt-IdNr. (Pflicht für die E-Rechnung), Logo und Textvorlagen geprüft.
- [ ] Steuerprofil (z. B. Kleinunternehmer nach § 19 UStG oder Regelbesteuerung) mit Gültigkeit ab dem Starttag; Befreiungstext geprüft
      (am besten mit der Steuerberatung abstimmen).
- [ ] Nummernkreise: Rechnung z. B. `RE{jahr}{#####}` (der Zähler beginnt im neuen Jahr automatisch bei 1), Storno, Mahnung, Kunde, Leistung.
      Muster nach der ersten Nummer nicht mehr zwischen jährlich und durchgehend wechseln. Wer eine laufende Nummer fortsetzen muss, trägt sie vor dem
      ersten Beleg ein.
- [ ] Mahnstufen (Tage, Gebühren, Zinsen, Texte) geprüft und bewusst eingestellt.
- [ ] Wiedervorlagen (wiederkehrende Rechnungen) angelegt: Kunde, Intervall, Ankerdatum, Vorlaufzeit, Positionen. Der Job erzeugt nur Entwürfe.
- [ ] Stammdaten (Kunden, Leistungen) übernommen. faktura enthält keinen Import; eine Übernahme gelingt nur mit einem eigenen Skript gegen das Schema
      (vorher Backup). Die Dateien der Altsoftware mit Kundendaten sicher aufbewahren.
- [ ] Eine Test-Rechnung nur in der Entwicklungsumgebung (Profil `dev`) durchspielen, nicht in der Produktivdatenbank
      (finalisierte Belege lassen sich nicht löschen und verbrauchen Nummern).

Am Stichtag:

- [ ] Stand der Altsoftware sichern (Datenbank, alle PDF/Belege) und aufbewahren; sie nicht mehr für neue Rechnungen verwenden.
- [ ] Offene Rechnungen aus der Altsoftware dort weiter verfolgen (Zahlungseingang, Mahnung) oder bewusst übertragen; faktura kennt sie nicht,
      weil Alt-Belege nicht importiert werden.
- [ ] Erste Rechnung schreiben, im Prüfdialog kontrollieren und finalisieren: Nummer, PDF, bei ZUGFeRD-Kunden die E-Rechnung
      (Prüfung durch den Mustang-Validator läuft mit).
- [ ] Danach sofort ein Backup und eine Archivprüfung (`verify-archive`).
- [ ] Nach einer Woche: Backup-Manifeste lesen, Platz im Volume, Log auf Fehler durchsehen.
