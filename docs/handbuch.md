# Benutzerhandbuch

> **Ohne Gewähr, Nutzung auf eigenes Risiko.** faktura ist keine Rechts-, Steuer- oder Buchhaltungsberatung und garantiert keine
> Konformität mit GoBD, Steuerrecht oder E-Rechnungs-Vorgaben. Prüfen Sie jede Rechnung vor dem Finalisieren selbst und stimmen Sie
> steuerliche Fragen (z. B. Kleinunternehmerregelung, Befreiungstexte, Aufbewahrung) mit Ihrer Steuerberatung ab.

Dieses Handbuch beschreibt die tägliche Arbeit mit faktura. Installation, Konfiguration, Backup und Update stehen in
[betrieb.md](betrieb.md). Die Menünamen entsprechen der Navigation links; die Einstellungsseiten sind als Reiter unter „Einstellungen“ erreichbar.
Alle Beispielnamen und -daten sind erfunden.

## 1. Erste Einrichtung

Auf der Startseite zeigt die Einrichtungsliste, was noch fehlt. Reihenfolge:

1. **Unternehmen** (Einstellungen → Unternehmen): Name, Inhaber, Anschrift, Kontakt, **Steuernummer oder USt-IdNr.** (für die E-Rechnung Pflicht),
   IBAN, BIC, Bank, Standard-Zahlungsziel, Gültigkeit von Angeboten, Logo (siehe Abschnitt „Eigenes Logo“).
2. **Steuerprofil** (Einstellungen → Steuerprofile): Kleinunternehmer (§ 19 UStG) oder Regelbesteuerung, jeweils mit Gültigkeit ab einem Datum.
   Das **Leistungsdatum** eines Belegs bestimmt, welches Profil gilt. Beim Kleinunternehmer wird keine Umsatzsteuer ausgewiesen, der
   Hinweistext steht auf der Rechnung. Ein Wechsel (z. B. zur Regelbesteuerung) ist ein neues Profil mit Datum, kein Umbau. Befreiungstexte
   für Steuerkategorien ohne Steuer pflegen Sie auf derselben Seite; ohne Text bricht das Finalisieren einer E-Rechnung ab.
3. **Nummernkreise** (Einstellungen → Nummernkreise): Muster je Belegart (`{jahr_kurz}` ergibt das zweistellige Jahr, z. B. `RE0{jahr_kurz}{###}` → `RE027042`); der Zähler lässt sich dort erhöhen, um unterjährig an eine Altsoftware anzuschließen (nie senken); z. B. `RE{jahr}{#####}` ergibt `RE202600001`. Bei Mustern mit `{jahr}`
   beginnt der Zähler jedes Jahr neu. Ändern Sie das Muster nach der ersten vergebenen Nummer nicht zwischen jährlich und durchgehend.
4. **Einheiten** (Einstellungen → Einheiten): Stunde, Stück, Pauschale usw. Der UN/ECE-Code der Einheit geht in die E-Rechnung.
5. **Textvorlagen** (Kopf- und Fußtexte), **Mailvorlagen**, **Mahnstufen** und **Versandarten** bei Bedarf anpassen (Abschnitte unten).

### Eigenes Logo für Belege

Das Logo erscheint im Briefkopf von Rechnungen, Stornos, Angeboten und Mahnungen. Ohne Einstellung druckt faktura ein Platzhalter-Logo. Eigenes Logo, ohne Änderung am Programm:

1. Logodatei (PNG, JPEG oder GIF; kein SVG) in das Datenverzeichnis legen, z. B. `./data/logo.png`. Ein Querformat mit etwa 600–900 px Höhe passt am besten;
   gedruckt wird es 22 mm hoch. Transparenz wird auf Weiß aufgefüllt, das Logo muss also auf weißem Grund gut aussehen.
2. In der `.env` eintragen: `LOGO_PATH=/data/logo.png` (im Container liegt das Datenverzeichnis unter `/data`).
3. Container neu starten: `docker compose up -d`.

Die Datei wird bei jedem Erzeugen neu gelesen: Eine ausgetauschte Datei gilt sofort für neue Vorschauen und neue Belege. Bereits finalisierte PDFs ändern sich nie.
Ein falscher Pfad lässt das Erzeugen von PDFs mit einer Fehlermeldung scheitern.

## 2. Kunden und Leistungen

- **Kunden**: Nummer (automatisch), Name, Anschrift, Land, USt-IdNr., Rechnungs-E-Mail, Zahlungsziel und Rabatt (optional), Kundenart,
  **E-Rechnung** (keine oder ZUGFeRD; bei neuen Kunden ist ZUGFeRD vorausgewählt). Ansprechpartner (Anrede, Vor- und Nachname, E-Mail) liefern die
  Anrede in Texten und die Adresse für den Mailversand. Kunden werden **archiviert**, nicht gelöscht.
- **Leistungen** (Katalog): Nummer, Name, Beschreibung, Einheit, Preis netto, Steuerkategorie, aktiv/inaktiv. Der Katalog ist eine **Vorlagenquelle**: Beim
  Einfügen in eine Rechnung wird der Eintrag kopiert, danach sind alle Werte in der Position frei änderbar. Spätere Preisänderungen
  berühren bestehende Rechnungen nicht.

## 3. Rechnung schreiben

Rechnungen → „Neue Rechnung“. Eine Rechnung ist im Status **Entwurf** frei änderbar.

- Kunde wählen (Ansprechpartner optional), Rechnungsdatum, Leistungszeitraum, Zahlungsziel, Betreff, optional Kundenreferenz.
- **Positionen** aus dem Katalog oder als Freitext; Menge, Einheit, Einzelpreis (netto), **Rabatt je Position** in Prozent.
- **Gesamtrabatt** in Prozent oder als Betrag; **Versandkosten** (Einstellungen → Versandarten) werden nach dem Gesamtrabatt addiert, der Rabatt mindert sie nicht.
- Die Summen werden live berechnet. Geld wird in ganzen Cent gerechnet und kaufmännisch gerundet; Reihenfolge der Berechnung: Zeilenbetrag,
  Positionsrabatt, Zwischensumme, Gesamtrabatt, Steuer (bei Regelbesteuerung je Satz), Versandkosten.
- Kopf- und Fußtext sind einfacher Text mit Platzhaltern (z. B. `{anrede}`, `{leistungszeitraum}`, `{rechnungsnummer}`, `{betrag}`) und
  Auszeichnung (`**fett**`, `*kursiv*`, Listen mit `- `). Die Platzhalter werden beim Finalisieren ersetzt. Zahlungsangaben stehen im Fußtext.
- **Vorschau** zeigt das PDF des Entwurfs. Zwei Benutzer überschreiben einen Entwurf nicht unbemerkt: Speichert jemand anderes zwischendurch, meldet
  der Editor einen Konflikt und lädt den neuen Stand.

### Prüfen und finalisieren

„Finalisieren“ öffnet zuerst den **Prüfdialog** (speichert nichts). Er zeigt die Rechnung, wie sie finalisiert würde, erlaubt Rechnungsdatum,
Zahlungsziel und Leistungszeitraum zu ändern, bietet das Vorschau-PDF und führt für ZUGFeRD-Kunden einen Probelauf der E-Rechnung aus. Erst mit
dem Haken „geprüft“ ist „Jetzt finalisieren“ möglich.

Beim Finalisieren passiert in **einer Transaktion**: Steuerprofil zum Leistungsdatum wird bestimmt, Absender, Empfänger und Steuerangaben werden als
**Snapshot** in den Beleg kopiert, die **Nummer** wird vergeben, Summen werden gespeichert, PDF/A (bei ZUGFeRD mit XML) wird erzeugt und mit
SHA-256 archiviert. Schlägt ein Schritt fehl, bleibt die Rechnung ein Entwurf und die Nummer frei.

**Danach ist die Rechnung unveränderlich.** Sie kann weder bearbeitet noch gelöscht werden; auch spätere Änderungen an Kunde, Katalog oder Firma
verändern sie nicht. Entwürfe lassen sich löschen.

### Stornieren

Fehler korrigieren Sie über **Storno**: Auf der finalisierten Rechnung „Stornieren“. Es entsteht ein eigener Beleg mit eigener Nummer, der die Rechnung
mit negativen Beträgen ausgleicht und auf das Original verweist (bei E-Rechnung als Gutschrift). Das Original bleibt unverändert. Je Rechnung gibt es
höchstens einen Storno. Anschließend schreiben Sie bei Bedarf eine neue Rechnung.

## 4. E-Rechnung (ZUGFeRD)

Bei Kunden mit der E-Rechnungs-Einstellung „ZUGFeRD“ erzeugt faktura beim Finalisieren ein PDF/A-3 mit eingebettetem XML (EN 16931), vergleicht
die Summen im XML mit den gespeicherten Beträgen und prüft das Ergebnis mit dem Mustang-Validator. Fehler brechen das Finalisieren ab; die Meldung nennt
die Regel-IDs in verständlichem Deutsch (häufig fehlt die Steuernummer des Verkäufers oder ein Befreiungsgrund). Angebote und Mahnungen sind nie
E-Rechnungen. Ob das Ergebnis für Ihren Fall ausreicht, prüfen Sie selbst.

## 5. Angebote

Angebote → „Neues Angebot“. Gleicher Editor, Prüfdialog und Finalisieren wie bei der Rechnung (eigene Nummer, z. B. `AN{jahr}{#####}`), aber mit
**Gültig bis** statt Fälligkeit, ohne E-Rechnung, Zahlungen und Mahnungen. Auf der Angebotsseite („Verlauf“) stehen der Zustand (offen, gesendet,
angenommen, abgelehnt, abgelaufen) und die Ereignisse. Sie können das Angebot **per Mail senden**, **als abgelehnt vermerken** oder
**„Rechnung erstellen“**: Das legt einen Rechnungsentwurf mit den kopierten Positionen an und vermerkt das Angebot als angenommen. Aus einem Angebot
können mehrere Rechnungen (Teilrechnungen) entstehen.

## 6. Zahlungen und Mahnwesen

- **Zahlung erfassen**: auf der finalisierten Rechnung Datum (nicht in der Zukunft), Betrag, Art und Verwendungszweck. Der Zustand (offen, teilbezahlt,
  bezahlt, storniert) und „überfällig“ werden **berechnet**, nicht gespeichert. Ein Irrtum wird durch eine **Gegenbuchung** korrigiert; Zahlungen
  werden nie gelöscht.
- **Mahnwesen**: Die Seite schlägt vor, welche überfällige Rechnung welche Stufe braucht (Zahlungserinnerung, 1. und 2. Mahnung). Tage, Gebühren,
  Zinsen und Texte pflegen Sie unter Einstellungen → Mahnstufen. Eine Mahnung erstellen Sie aus der Vorschau; sie hat eine eigene Nummer und ein PDF. **faktura
  versendet nichts von allein**; die Mahnung wird, wenn gewünscht, per Mail aus dem Versanddialog verschickt. Mit der Zahlung enden die Vorschläge.
  Verzugszinsen und Mahngebühren sind Standard „aus“; prüfen Sie rechtliche Grenzen selbst.

## 7. Wiedervorlagen (wiederkehrende Rechnungen)

Wiedervorlagen → „Neue Wiedervorlage“: Kunde, Intervall (Monat bis Jahr), **Ankerdatum**, Vorlaufzeit, Enddatum (optional) und Positionsvorlagen. Ein
täglicher Job erzeugt rechtzeitig **nur Entwürfe** für den nächsten Leistungszeitraum (immer im Voraus). Sie erscheinen unter „Zur Freigabe“ (Badge in der
Navigation, Kachel auf der Startseite). Von dort „Prüfen und finalisieren“ (normaler Prüfdialog) oder „Überspringen“ (löscht den Entwurf). Verpasste
Zeiträume holt der Job innerhalb von 31 Tagen nach, ältere erscheinen als übersprungen. Änderungen am Plan wirken nur auf künftige Entwürfe. Fehler (z. B.
kein Steuerprofil) stehen am Plan in der Liste.

Eine Wiedervorlage lässt sich auch aus einer bestehenden Rechnung anlegen: auf der Seite einer finalisierten Rechnung „Wiedervorlage anlegen“. Der Editor öffnet
sich mit Kunde, Positionen und Rabatt der Rechnung; das Ankerdatum ist der Tag nach ihrem Leistungsende. Intervall prüfen, dann speichern. Steuerkategorien
und Versandkosten werden nicht übernommen.

## 8. Mailversand

Finalisierte Rechnungen, Stornos, Angebote und Mahnungen senden Sie über den Versanddialog an den Kunden (Mailversand muss vom Betreiber eingerichtet sein, siehe
betrieb.md). Der Dialog zeigt Empfänger (vorbelegt mit dem Ansprechpartner oder der Rechnungsadresse, bis zu fünf Adressen), Betreff, Text, Vorschau und Anhang; gesendet
wird erst nach der Bestätigung „geprüft“. Der Anhang ist das archivierte PDF unverändert. Texte kommen aus den **Mailvorlagen** (Einstellungen → Mailvorlagen; je
Dokumenttyp mehrere Varianten, z. B. formell und informell, mit Platzhaltern) und sind im Dialog für diese eine Mail änderbar. Jeder Versuch steht im Versandprotokoll
am Beleg („Zuletzt gesendet am … an …“). Entwürfe lassen sich nie versenden.

## 9. Hinweis-Mail an Sie selbst

Einstellungen → Benachrichtigungen: Einmal am Tag kann faktura Ihnen (nie Kunden) eine Sammelmail schicken über neue Entwürfe aus Wiedervorlagen,
fehlgeschlagene Wiedervorlagen, überfällige Rechnungen, fällige Mahnungen und bald ablaufende Angebote. Jede Kategorie schalten Sie einzeln ein (Standard: aus);
Empfänger und Uhrzeit sind einstellbar, „Jetzt prüfen“ und „Testmail senden“ prüfen den Weg. Es gibt nur dann eine Mail, wenn es etwas Neues gibt.

## 10. Benutzer und Anmeldung

Je nach Konfiguration (betrieb.md): keine Anmeldung (nur hinter einem schützenden Proxy), Anmeldeseite mit Benutzername und Passwort (mindestens 12 Zeichen) oder
Benutzername aus einem Proxy-Header (dazu ein vierter Modus, bei dem Administratoren sich zusätzlich mit Passwort anmelden; siehe betrieb.md). Alle angemeldeten Benutzer dürfen alles, nur Administratoren verwalten Benutzer (Menüpunkt „Benutzer“, nur für Administratoren: anlegen, Passwort
setzen, Administrator-Merkmal, deaktivieren). Benutzer werden nie gelöscht, nur deaktiviert. Nach fünf falschen Anmeldungen ist der Name 15 Minuten gesperrt. Es gibt
kein „Passwort vergessen“ per Mail: Ein Administrator setzt das Passwort zurück. Das Protokoll (`audit_log`) vermerkt, wer finalisiert, storniert, gebucht, gemahnt
oder gesendet hat.

## 11. Export für Buchhaltung und Steuerberatung

Export → Jahr wählen → zwei CSV-Dateien: **Rechnungen** (inkl. Stornos, nach Rechnungsdatum) und **Zahlungen** (nach Zahlungsdatum, Zufluss). Format: UTF-8 mit BOM,
Semikolon, Dezimalkomma. Der Export ist rein lesend und ersetzt keine Buchhaltung und kein Backup. Die Dateien enthalten Kundendaten: sicher aufbewahren. Optional kopiert
faktura finalisierte PDFs zusätzlich in einen **Export-Ordner**, den ein Dokumentenarchiv (z. B. ecoDMS) überwacht (betrieb.md).

## 12. Backup und Wiederherstellung

Aufbewahrungspflichtig sind Datenbank **und** Archiv (PDF/XML). Der Betreiber richtet die nächtliche Sicherung ein (`BACKUP_ENABLED`); sie schreibt eine konsistente
Datenbank-Sicherung und spiegelt das Archiv. Kopieren Sie das Backup-Verzeichnis zusätzlich an einen anderen Ort, verschlüsseln Sie es (es enthält Personendaten) und **üben Sie die
Wiederherstellung** auf einem anderen Rechner. Die Archivprüfung (`verify-archive`) vergleicht Größe und SHA-256 aller archivierten Dateien. Die genauen Befehle stehen in
[betrieb.md](betrieb.md). Beachten Sie die gesetzlichen Aufbewahrungsfristen (Rechnungen und Buchungsbelege i. d. R. acht Jahre, Geschäftsbriefe sechs Jahre; bitte bei der
Steuerberatung bestätigen). faktura löscht weder Belege noch Archivdateien.

## 13. Häufige Fragen

- **Warum kann ich eine finalisierte Rechnung nicht ändern?** Aus Gründen der Unveränderlichkeit (GoBD). Stornieren Sie sie und schreiben Sie eine neue.
- **Eine Nummer ist „verloren“?** Nummern werden erst beim erfolgreichen Finalisieren vergeben und sind lückenlos; bei einem Fehler bleibt sie frei.
- **Das Finalisieren der E-Rechnung bricht ab.** Meldung lesen (Regel-ID), meist fehlen Steuernummer/USt-IdNr. der Firma oder der Befreiungstext der Kategorie.
- **Test mit Probedaten?** Nicht in der Produktivdatenbank (finalisierte Belege sind nicht löschbar). Dafür gibt es das Profil `dev` mit erfundenen Daten (siehe README).
