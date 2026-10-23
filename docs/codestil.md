# Codestil

Gilt für Java in `src/main` und `src/test`. Einrückung und Umbrüche, die IntelliJ automatisch kann, stehen in
`.editorconfig` (IntelliJ liest sie beim Öffnen des Projekts; „Reformat Code“ hält sie ein). Alles andere sind Regeln für
das Schreiben und für das Review. Die Regeln stammen aus dem Stil von clubbee,
mit zwei bewussten Abweichungen: Bezeichner sind durchgehend englisch (Abschnitt 6), und Javadoc ist nur dort Pflicht,
wo es etwas sagt (Abschnitt 4).

## 1. `var` für lokale Variablen

Lokale Variablen werden mit `var` deklariert, wenn der Typ dadurch derselbe bleibt. Sonst steht der Klassenname.

```java
var customer = customers.save(new Customer(number, name.strip()));
var items = new ArrayList<DocumentItem>();
for (var item : list) { … }
```

Expliziter Typ statt `var` nur, wenn `var` den Typ ändern oder unlesbar machen würde: Initialisierung mit `null`, Lambda oder
Methodenreferenz; Literale mit anderem Zieltyp (`long counter = 0;`); absichtlich allgemeinerer Typ
(`List<String> names = new ArrayList<>();`). Felder, Parameter und Rückgabetypen bleiben immer explizit.

## 2. Leerzeilen

Höchstens eine Leerzeile hintereinander, keine direkt nach `{` oder vor `}`. Gewollt sind Leerzeilen:

- **nach jeder Guard-Clause**, also nach jedem `if`, das nur wirft oder zurückkehrt
- **vor `return`**, außer es ist die einzige Anweisung im Block oder das `return` steht in einem `if` ohne weitere Zeile davor
- zwischen logischen Abschnitten einer Methode (Prüfen, Arbeiten, Ereignisse, Ergebnis)
- zwischen Methoden und zwischen Feldgruppen

## 3. Verkettete Aufrufe

Ab zwei Aufrufen in einer Kette steht jeder weitere Aufruf in einer eigenen Zeile, eingerückt um einen Fortsetzungsschritt
(8 Leerzeichen). Der erste Aufruf bleibt in der Zeile des Empfängers. Das gilt auch für kurze Ketten, Streams und Builder.

```java
return customers.findByNumber(number)
        .map(CustomerService::toInfo);
```

Boolesche und arithmetische Ausdrücke mit mehr als zwei Operanden stehen untereinander, der Operator beginnt die Zeile:

```java
return number != null
        && PATTERN.matcher(number).matches()
        && !RESERVED.contains(number);
```

Verschachtelte Aufrufe (ein Argument ist selbst ein Aufruf oder Konstruktor, dessen Argumente wieder Aufrufe sind) stehen
auf eigenen Zeilen, die schließende Klammer ebenfalls. Zeilenlänge höchstens 120 Zeichen.

## 4. Javadoc

Jede öffentliche Klasse, jedes öffentliche Interface, Record und Enum hat einen mehrzeiligen Javadoc-Kommentar, der den
Zweck beschreibt. Öffentliche Methoden bekommen Javadoc, wenn sie eine **fachliche Regel** oder eine **Zusage an andere
Module** ausdrücken (Services, die Module öffentlich anbieten, Berechnungen, Finalisieren, Nummernvergabe). Das ist
bewusst weniger streng als bei clubbee: Für Getter, einfache Delegationen und Methoden, deren Name alles sagt, ist ein
Kommentar Rauschen.

- Immer mehrzeilig, auch bei einem Satz (`/**` + Zeile + `*/`).
- Englisch; der erste Satz fasst zusammen und endet mit einem Punkt. Er beschreibt das Verhalten, nicht den Namen.
- `@param`, `@return`, `@throws` nur, wenn sie etwas hinzufügen (Einheiten, Rundung, Fachregeln, Ausnahmen).
- Ausgenommen: `@Override`, Testklassen und -methoden, nicht öffentliche Methoden (dort ein Kommentar, wenn das Warum nicht
  aus dem Code hervorgeht).

## 5. Imports

Keine vollqualifizierten Klassennamen im Code, immer importieren. Statische Methoden von AssertJ, Hamcrest, Mockito und
MockMvc per `import static`. Reihenfolge: statische Imports, Leerzeile, dann alle übrigen alphabetisch; keine Stern-Imports.

## 6. Benennung

**Alles ist englisch**: Klassen, Methoden, Variablen, Parameter, Tabellen, Spalten, Enum-Werte, URLs, CSS-Klassen,
Konfigurationsschlüssel, Testnamen. Das gilt auch für Fachbegriffe (`Invoice`, `Customer`, `CatalogItem`, `Dunning`,
`DraftInvoice`), weil das Projekt später öffentlich sein soll und der Code so für alle lesbar bleibt.

Ausnahmen:
- **Eigennamen und Normbegriffe:** ZUGFeRD, XRechnung, `Leitweg-ID` (`leitwegId`), GoBD, EPC, UN/ECE.
- **Platzhalter in Nummernmustern** (`{jahr}`, `{#####}`): Der Anwender tippt sie ein, sie sind Teil der Bedienung.
- **Oberflächentexte** stehen auf Deutsch in `messages_de.properties` und werden im Template mit `#{…}` geholt. Schlüssel
  sind englisch (`invoice.list.title`). Fehlermeldungen für Anwender ebenfalls über Schlüssel.
- **Javadoc, Code-Kommentare, Exception-Meldungen und Log-Texte** sind englisch. Die Dokumentation unter `docs/` bleibt deutsch.
- Daten in der Datenbank (Einheitennamen, Texte der Mahnstufen) sind Anwenderdaten und dürfen deutsch sein.

Regeln:
- Eine Variable heißt wie der Typ, den sie hält (`var builder = …`); ein Fachobjekt trägt seinen Fachbegriff
  (`invoice`, `customer`).
- Methoden, die ein Fachereignis ausdrücken, sind Verben im Domänenvokabular (`finalizeInvoice`, `cancel`, `release`,
  `skip`); Lesen, Suchen, Zählen heißt `find`, `search`, `count`, `list`.
- Repository-Methoden fachlich benennen (`findOverdue(LocalDate today)`), keine Ableitungs-Ungetüme.
- Records greifen ohne `get` zu (`invoice.number()`). Testnamen sind englische Sätze in camelCase
  (`finalizingAssignsNextNumber`) oder `@DisplayName` (englisch), wenn der Satz länger wird.
- Geld und Prozent: Felder und Spalten tragen die Einheit im Namen (`unitPriceCents`, `discountBp`, `rateBp`).

## Beispiel

```java
/**
 * Finalizes a draft: assigns the number, writes the snapshots and stores PDF and XML.
 * If any step fails, the invoice stays a draft and the number stays free.
 *
 * @throws IllegalStateException if the invoice is not a draft
 */
@Transactional
public InvoiceInfo finalizeInvoice(UUID invoiceId) {
    var invoice = invoices.findDraft(invoiceId)
            .orElseThrow(() -> new IllegalStateException("Not a draft: " + invoiceId));

    var profile = taxProfiles.resolve(invoice.serviceFrom());
    var number = numbering.next(DocumentScope.INVOICE, invoice.issueDate());

    return finalizer.complete(invoice, profile, number);
}
```
