# Rewind

Welt-Snapshots für **Minecraft 26.3** (Fabric). Speichere die ganze Welt unter einem Namen und kehre zu ihr
oder zu jedem anderen Snapshot zurück, wann immer du willst. Funktioniert im Einzelspielermodus, im LAN und auf
dedizierten Servern.

Online-Handbuch: https://mchamradio.antwire.net/handbook/rewind/
Quellcode: [github.com/Lamisator/mc_rewind](https://github.com/Lamisator/mc_rewind)

![Der Snapshot-Bildschirm aus dem Pausenmenü](docs/img/rewind_screen.png)

## Was ein Snapshot enthält

Alles im Weltordner: Gelände, Gebäude, Truhen, Objekte, Zeit und Wetter, das Inventar und die Position jedes
Spielers und die Daten, die andere Mods mit der Welt speichern (das Netz von Gridworks, die Quellen und Strahler
von Radiation, die Rufzeichen und Logbücher von Ham Radio, die Reaktoren von Fission). Die Rückkehr zu einem
Snapshot stellt all das genau so wieder her, wie es war.

**Immer alle Karten.** Ein Snapshot enthält jede Karte (Dimension) der Welt: die Oberwelt, den Nether, das Ende
und jede Welt, die eine Multiversum-Mod wie [Worlds](https://modrinth.com/mod/mc-worlds) hinzufügt, auch solche,
die erst nach der Installation von Rewind entstanden sind. Sie liegen alle im Weltordner
(`dimensions/<namespace>/<name>/`), und Rewind kopiert den ganzen Ordner, also braucht eine neue Karte keine
Einrichtung. Beim Speichern pausiert jede Karte, solange die Dateien kopiert werden, auch eine Karte, die während
des Kopierens erstellt oder aufgeweckt wird. `/snapshot info <name>` listet die Karten eines Snapshots auf, und der
Chat sagt beim Speichern, wie viele es sind. Eine Wiederherstellung bringt alle Karten gemeinsam zurück, so wie sie
waren.

Mod-Jars und Konfigurationsdateien gehören nicht zur Welt, daher sind sie auch nicht in Snapshots enthalten.

## Installation

1. Lege in Prism Launcher eine Minecraft-**26.3**-Instanz mit **Fabric** an (Loader 0.19.5 oder neuer) und
   installiere **Fabric API**.
2. **Add file**: `rewind-1.2.0.jar`.

Auf einem Server kommt die Jar in den `mods`-Ordner des Servers. Spieler brauchen sie auf ihrer Seite nur für den
Bildschirm im Pausenmenü; der Befehl `/snapshot` funktioniert auch ohne sie.

## Einen Snapshot erstellen

- **Pausenmenü → Snapshots** (unten links): Gib einen Namen und eine Beschreibung ein (beides optional) und drück
  **Speichern**.
- Oder per Befehl: `/snapshot save [name] [description]`. Ohne Namen heißt er `snapshot-<datum>-<uhrzeit>`.

Zuerst wird die Welt gespeichert. Dann pausiert das automatische Speichern, während die Dateien im Hintergrund
kopiert werden, damit die Kopie stimmig ist und das Spiel weiterläuft. Snapshots sind **inkrementell**: Jede Datei,
die sich seit dem vorigen Snapshot nicht geändert hat, wird per Hardlink verknüpft statt kopiert. Zehn Snapshots
einer großen Welt kosten kaum mehr als einer plus das, was sich dazwischen geändert hat.

Snapshot-Namen dürfen Buchstaben, Ziffern und `_ . + -` enthalten, bis zu 48 Zeichen.

## Zurückkehren

- Bildschirm **Snapshots**: **Laden**, dann innerhalb von vier Sekunden **Sicher?**.
- Oder `/snapshot restore <name>`, dann auf **[Bestätigen]** klicken (oder `/snapshot restore <name> confirm`
  eingeben).

Eine Welt kann nicht ausgetauscht werden, während sie läuft, deshalb wird sie bei einer Wiederherstellung immer kurz
geschlossen:

- **Einzelspieler / LAN-Host**: Das Spiel speichert, verlässt die Welt, setzt den Snapshot ein und öffnet die Welt
  von selbst wieder. LAN-Gäste werden getrennt und können sich wieder verbinden.
- **Dedizierter Server**: Alle bekommen eine Nachricht und werden nach fünf Sekunden getrennt. Der Server hält an,
  setzt den Snapshot ein und beendet sich mit Code **75**. Ein Supervisor, der bei Fehlern neu startet (systemd
  `Restart=on-failure`, die meisten Hosting-Panels), startet ihn mit der wiederhergestellten Welt neu. Mit
  `restartExitCode` auf 0 hält er einfach nur an. Wird er beendet, bevor er wiederherstellen kann, holt er die
  Wiederherstellung beim nächsten Start nach, bevor die Welt geladen wird.

**Jede Wiederherstellung lässt sich rückgängig machen.** Bevor der Snapshot eingesetzt wird, wird die aktuelle Welt
als Snapshot namens `before-restore-<datum>-<uhrzeit>` beiseitegelegt (eine Umbenennung, kostet also keine Zeit).
Stell diesen wieder her, um dorthin zurückzukommen, wo du warst. Die letzten fünf werden behalten.

| Vorher | Snapshot-Bildschirm | Nach der Wiederherstellung |
|---|---|---|
| ![](docs/img/rewind_diamond.png) | ![](docs/img/rewind_pause_menu.png) | ![](docs/img/rewind_restored.png) |

## Befehle

Alle brauchen Operatorrechte (Berechtigungsstufe 2) oder den Host im Einzelspielermodus.

| Befehl | |
|---|---|
| `/snapshot save [name] [description]` | Snapshot erstellen |
| `/snapshot list` | Alle Snapshots dieser Welt mit Datum, Größe und einem Link zum Wiederherstellen per Klick |
| `/snapshot info <name>` | Details: wer ihn erstellt hat, Spieltag, Dateien, Größe, damalige Mods |
| `/snapshot restore <name> [confirm]` | Zu einem Snapshot zurückkehren |
| `/snapshot delete <name> [confirm]` | Einen Snapshot löschen |
| `/snapshot auto <minutes> [keep]` | Automatische Snapshots alle soundso viele Minuten, die letzten `keep` werden behalten |
| `/snapshot auto off` | Keine automatischen Snapshots |

Namen lassen sich mit Tab vervollständigen. In der Liste sind manuelle Snapshots weiß, automatische grün und
solche vor einer Wiederherstellung gelb.

## Wo sie liegen

`<Spielverzeichnis>/snapshots/<Weltordner>/<Snapshot>/`. Im Einzelspielermodus ist das neben `saves/`, auf einem
Server neben dem Weltordner. Jeder Snapshot ist ein vollständiger, gewöhnlicher Weltordner plus `snapshot.json` und
`manifest.json`. Um eine neue Welt aus einem Snapshot zu starten, kopiere seinen Ordner nach `saves/`
(Einzelspieler) oder lass `level-name` darauf zeigen (Server).

Snapshots bleiben erhalten, wenn eine Welt wiederhergestellt wird, und gehören zum Namen des Weltordners.

## Konfiguration

`config/rewind.json` (auf einem Server die Kopie des Servers):

| Einstellung | Standard | |
|---|---|---|
| `snapshotDir` | `snapshots` | Ordner für Snapshots, relativ zum Spiel- oder Serververzeichnis |
| `autoIntervalMinutes` | 0 | Minuten zwischen automatischen Snapshots; 0 = aus |
| `autoKeep` | 12 | Anzahl der automatischen Snapshots, die behalten werden |
| `keepBeforeRestore` | 5 | Anzahl der Snapshots vor einer Wiederherstellung, die behalten werden |
| `restartExitCode` | 75 | Exit-Code eines dedizierten Servers nach einer Wiederherstellung; 0 = einfach anhalten |
| `restoreWarningSeconds` | 5 | Sekunden zwischen der Wiederherstellungsnachricht und dem Trennen auf einem Server |

## Aus dem Quellcode bauen

```
./gradlew build             # build/libs/rewind-1.2.0.jar
./gradlew runClientGameTest # takes, changes, restores and un-restores a test world
```

## Lizenz

MIT
