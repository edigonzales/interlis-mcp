# interlis-mcp

Eigenständige Spring-Boot-Anwendung für INTERLIS mit MCP über STDIO
und Streamable HTTP. Das Projekt liefert ein ausführbares Java-JAR und
ein Docker-Image. Es verwendet ili2c und ilivalidator.

Mit Java 21 und Gradle Groovy DSL arbeiten.
`./gradlew --no-daemon clean check e2eTest bootJar` prüft die Anwendung
und baut `build/libs/interlis-mcp.jar`. `./gradlew buildImage` baut das
Docker-Image. Der Server verändert keine Benutzerdateien. Eingabe- und
Ressourcenlimits, Pfadgrenzen und isolierte temporäre Arbeitsbereiche erhalten.

Fachliche Tests prüfen das Produkt über tatsächliche Compiler- und
Validatorergebnisse. Kompilierbarkeit allein belegt keine fachliche
Korrektheit. Strukturvalides XTF allein belegt keinen Constraint. Eine
erwartete Verletzung zählt, wenn der Validator sie aus dem vorgesehenen
Grund meldet. Nicht unterstützte Semantik gilt als ungeprüft.

Direkte Implementierungen und Standardwerkzeuge bevorzugen. Jede neue Klasse
braucht eine konkrete Verantwortung. Hypothetische Modulgrenzen, Legacy-
Kompatibilität, eigene Evidence- oder Reviewplattformen und redundante
Hash- oder Fingerprintketten vermeiden.

Vor grösseren Änderungen [simplify-change](.agents/skills/simplify-change/SKILL.md)
anwenden. Vor PR-Abschluss [review-change](.agents/skills/review-change/SKILL.md)
und simplify-change unabhängig mit OpenCode 2.x und DeepSeek Flash auf den
konkreten Stand anwenden. Prüfungen und tatsächliche Ergebnisse dokumentieren.

Git-Toplevel, Ausgangscommit und Status prüfen. Vorhandene lokale Arbeit
schützen und auf einem eigenen Branch arbeiten. Push, Merge und produktive
Veröffentlichungen brauchen einen ausdrücklichen Auftrag.
