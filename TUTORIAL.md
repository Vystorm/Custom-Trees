# CustomTrees – Anleitung

Öffne `/vmenu`, wähle **Tutorials** und danach **CustomTrees · Anleitung**. Direkt geht es mit `/vmenu tutorials`; der direkte Schlüssel `/vmenu customtrees:tutorial` bleibt ebenfalls verfügbar. Für den Eintrag brauchst du `customtrees.grow`.

## Eigene Bäume pflanzen

CustomTrees ersetzt das Wachstum konfigurierter Setzlinge in normalen Welten durch eigene Baumformen. Standardmäßig sind alle NORMAL-Welten aktiv. Pflanze gleichartige Setzlinge mit freiem Raum darüber. Ein einzelner Setzling wächst beim normalen Wachstum oder mit Knochenmehl zum eigenen Baum. Ein vollständiges 2×2-Quadrat gleicher Setzlinge wird zu einem mittleren bis großen Baum. Knochenmehl löst die konfigurierte Größe sofort aus und verbraucht außerhalb des Kreativmodus genau ein Stück. Das Wachstum benötigt `customtrees.grow`.

## Sträucher und Hecken

Verbinde zwei oder drei gleiche Setzlinge auf gleicher Höhe, auch diagonal, über Ecken oder verzweigt, und löse normales Wachstum oder Knochenmehl auf einem Setzling aus: Die Gruppe wird zu Sträuchern. Vier oder mehr gleiche Setzlinge ergeben eine durchgehende Hecke. Vollständige 2×2-, 3×3- und 5×5-Quadrate bleiben Baumgrößen.

## Riesenbaum aus 3×3

Ein vollständiges 3×3-Feld gleicher Setzlinge erzeugt einen extra großen Baum. Pflanze neun Setzlinge lückenlos auf gleicher Höhe und lass über der Fläche genug Platz. Warte auf natürliches Wachstum oder nutze Knochenmehl auf einem der Setzlinge. Standardgröße: 36–48 Blöcke Höhe, Kronenradius 11. Natürliches Wachstum und Knochenmehl-Auslöser für 3×3 sind konfigurierbar.

## Koloss aus 5×5

Ein vollständiges 5×5-Feld gleicher Setzlinge erzeugt einen kolossalen Baum mit rundem Stamm und breiter Krone. Pflanze 25 Setzlinge lückenlos auf einer Höhe und halte über der Grundfläche Bauplatz frei. Warte auf natürliches Wachstum oder nutze Knochenmehl auf einem Mitglied des Felds. Standardgröße: 52–68 Blöcke Höhe, Kronenradius 17. Auslöser und Maße sind konfigurierbar.

## Leuchtbaum mit Glühsteinstaub

Rechtsklick mit Glühsteinstaub auf einen Setzling lässt eine geschützte leuchtende Variante wachsen; Leuchtstein wird bevorzugt in dunklem Innenlaub verteilt. Halte Glühsteinstaub und rechtsklicke einen gültigen Setzling. Sorge für genügend freien Platz. Im Überlebensmodus werden ein Glühsteinstaub und 100 echte XP-Punkte verbraucht. Bei aktivem CoreProtect werden Stämme als geschützte CustomTrees-Platzierungen erfasst. Kosten und Leuchtmenge sind konfigurierbar.

## Kohlebaum

Mit Kohle wächst dieselbe eigene Baumform, jedoch ohne CoreProtect-Schutz der Stämme. Halte Kohle und rechtsklicke einen gültigen Setzling. Sorge für Platz. Im Überlebensmodus wird ein Stück Kohle verbraucht. Diese Variante ist für Stämme gedacht, die nicht als geschützte CustomTrees-Platzierungen registriert werden sollen.

## Natürliche Weltgeneration

CustomTrees kann auch generierte Bäume ersetzen. Die Standard-Ersatzchance beträgt 100 %. Erkunde neue Gebiete in einer aktivierten normalen Welt. In Iris-Welten läuft nach der Chunk-Erzeugung ein kurzer einmaliger Nachlauf, damit Iris-Objektbäume ebenfalls ersetzt werden können. Bereits existierende Chunks werden beim erneuten Laden nicht umgeschrieben.

## Admin: Einstellungen, Status und Reload — `customtrees.admin`

Alle `settings.*`-Schalter und -Zahlen (außer `enabled-worlds`) sind im Vystorm-Menü unter **Einstellungen → Server → CustomTrees** (`/einstellungen`, `/vadmin`) als nativer Dialog änderbar und greifen sofort. Ungültige Kombinationen werden abgelehnt und auf die vorherigen Werte zurückgesetzt.

`/customtrees status` zeigt aktive Welten, Schalter und Zähler für Bäume, Riesen, Kolosse, Sträucher, Hecken sowie Glühstein- und Kohlebäume. Ändere bei Bedarf die Serverkonfiguration und führe `/customtrees reload` aus. Bei ungültiger Konfiguration bleibt die zuvor aktive Einstellung erhalten und der Reload meldet den Fehler.
