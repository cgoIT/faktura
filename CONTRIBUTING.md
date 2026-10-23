# Mitwirken

Danke für das Interesse an faktura. Das Projekt ist klein und wird in der Freizeit gepflegt; Rückmeldungen können dauern.

**Wichtig:** Poste in Issues, Pull Requests, Logs und Screenshots **keine echten Kundendaten** (Namen, Anschriften, E-Mail-Adressen,
IBAN, USt-IdNr., Rechnungsinhalte) und keine Zugangsdaten. Verwende erfundene Beispiele (`example.org`, „Erika Beispiel“).
Sicherheitslücken bitte nicht öffentlich melden, siehe [SECURITY.md](SECURITY.md).

## Bauen und testen

```bash
./mvnw verify                      # alles, wie in der CI (Java 25; PostgreSQL-Tests brauchen Docker)
./mvnw test -Dtest=ModularityTests # nur Modulgrenzen
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # Entwicklung mit erfundenen Testdaten
```

## Regeln

- Nicht verhandelbar: finalisierte Belege sind unveränderlich (GoBD), Nummern sind lückenlos und entstehen erst beim Finalisieren, Beleg-Snapshots statt
  Verweisen, Geld in ganzen Cent und Prozente in Basispunkten (nie `double`), Schema nur über Flyway-Migrationen (portabel für SQLite und PostgreSQL),
  strenge Content-Security-Policy ohne Inline-Skripte/-Styles, CSRF-Schutz für jedes `POST`, keine Kundendaten in Logs. Codestil: [docs/codestil.md](docs/codestil.md).
- Code, Kommentare und Bezeichner sind englisch, Oberflächentexte stehen deutsch in `messages_de.properties`, die Dokumentation unter `docs/` ist deutsch.
- Jede fachliche Regel bekommt einen Test; Änderungen am Schema laufen auf SQLite und PostgreSQL; die Modulgrenzen (`ModularityTests`) müssen grün bleiben.
- Commits nach [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`); daraus erzeugt Release Please Versionen und Changelog.
- Mit einem Beitrag stimmst du zu, dass er unter der [Apache License 2.0](LICENSE) steht.
