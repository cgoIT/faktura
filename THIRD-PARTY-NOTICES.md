# Drittanbieter-Software / Third-party notices

faktura selbst steht unter der [Apache License 2.0](LICENSE). Es nutzt die folgenden Bibliotheken und Dateien, die
jeweils unter ihren eigenen Lizenzen stehen und **unverändert** eingebunden werden. Maßgeblich sind die Lizenztexte der
jeweiligen Projekte. Die Angaben sind nach bestem Wissen gemacht und ohne Gewähr; die vollständige Liste der
Laufzeitabhängigkeiten liefert `./mvnw dependency:list`.

| Komponente | Version | Lizenz | Verwendung |
| --- | --- | --- | --- |
| Spring Boot, Spring Framework, Spring Security, Spring Modulith | 4.1.1 / 2.1.1 | Apache-2.0 | Anwendungsgerüst |
| Thymeleaf | 3.1.5 | Apache-2.0 | Templates |
| Flyway Community | 12.4.0 | Apache-2.0 | Schema-Migration |
| sqlite-jdbc (Xerial; SQLite selbst ist gemeinfrei) | 3.50.3.0 | Apache-2.0 | SQLite-Treiber |
| PostgreSQL JDBC | 42.7.13 | BSD-2-Clause | PostgreSQL-Treiber |
| OpenHTMLtoPDF (Fork `io.github.openhtmltopdf`) | 1.1.97 | **LGPL-3.0-or-later** | HTML zu PDF |
| Apache PDFBox, FontBox, XmpBox | 3.0.7 | Apache-2.0 | PDF/A |
| Mustang Project (library, validator) | 2.25.0 | Apache-2.0 | ZUGFeRD / EN 16931, Validierung |
| Saxon-HE (über Mustang) | 12.10 | MPL-2.0 | XSLT der Validierung |
| Apache FOP, Batik (über Mustang) | 2.11 / 1.19 | Apache-2.0 | Abhängigkeiten von Mustang |
| ZXing | 3.5.3 | Apache-2.0 | Girocode (EPC-QR) |
| Logback, SLF4J | 1.5.x / 2.0.x | EPL-1.0 oder LGPL-2.1 / MIT | Logging |
| htmx | 2.0.4 | 0BSD | `static/js/htmx.min.js` |
| Inter (woff2) | latin 400, 600 | SIL OFL 1.1 | Oberflächenschrift, Lizenztext: `src/main/resources/static/fonts/LICENSE-Inter.txt` |
| DejaVu Sans | 2.x | Bitstream Vera License (frei) | eingebettete PDF-Schrift, Lizenztext: `src/main/resources/fonts/LICENSE-DejaVu.txt` |

Nur Test bzw. Entwicklung (nicht ausgeliefert): JUnit, AssertJ, Mockito (MIT/EPL-2.0), Testcontainers (MIT),
GreenMail (Apache-2.0), Mailpit (MIT, nur `compose.dev.yml`).

## Hinweise

- **OpenHTMLtoPDF (LGPL-3.0):** Die Bibliothek wird unverändert als eigenes Jar eingebunden und kann durch eine
  andere Version ersetzt werden (Versionsangabe in `pom.xml`, Neubau mit `./mvnw package`; das Container-Image
  enthält die Jars einzeln in den Layern). Der Lizenztext liegt im Upstream-Repository
  <https://github.com/openhtmltopdf/openhtmltopdf> (Datei `LICENSE`) und im jeweiligen Jar.
- **Saxon-HE (MPL-2.0):** unverändert; Quellcode unter <https://github.com/Saxonica/Saxon-HE>.
- **Schriften:** Die Lizenztexte von Inter (OFL) und DejaVu liegen bei den Schriftdateien und werden mit ausgeliefert.
- Das Logo `render/logo-placeholder.png` ist ein eigener Platzhalter und steht wie der übrige Code unter Apache-2.0.
