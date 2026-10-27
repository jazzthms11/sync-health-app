# Konzept: Essen aus Tandoor anpassen und aufschluesseln

Stand: 24.10.2026, umgesetzt auf dem Branch `essen-zutaten`.

## Ziel

- Sehen, welches Rezept welche Naehrwerte beitraegt, bis hinunter zur Zutat.
- Die geplanten Mahlzeiten fuer einen Tag anpassen: Gramm je Zutat, Faktor fuers
  ganze Gericht (halb, doppelt), Zutaten weglassen.
- Mahlzeiten und Zutaten lesbar in der Tagesnotiz ablegen, damit sie sich spaeter mit
  Haut, Laune usw. vergleichen lassen.

## Verworfen

- **In Samsung Health schreiben:** Das SDK kann es technisch, im Entwicklermodus ist
  aber nur Lesen erlaubt. Schreiben braucht eine Partnerschaft mit Samsung.
- **Umweg ueber Health Connect:** zu viel Umweg, Datenschutz nicht gewollt.
- **Anpassungen in Tandoor speichern:** muesste als Text in die Planeintrag-Notiz,
  waere beim geteilten Abendessen fuer alle sichtbar und braeuchte Schreibrechte.

## Ablauf

```
Tandoor ──(Plan + Zutaten)──▶ SyncHealth ◀── Samsung Health (Fruehstueck, Snacks, Extras)
                               │  anzeigen, anpassen
                               ▼
                            Obsidian (Tagesnotiz = Ablage und Wahrheit)
```

Samsung Health bleibt nur Quelle zum Lesen. Extras, die nicht im Rezept stehen
(z. B. noch Parmesan drueber), werden dort eingetragen.

## Tagesnotiz

Ein eigener Abschnitt `## Essen` zwischen `## Notizen` und `## Daten`, gesetzt wie die
anderen H2-Ueberschriften. Darin je Mahlzeit ein eingeklappter `[!mahlzeit]`-Callout. Den
Abschnitt selbst klappt `00 System/tagesuebersicht.js` im Vault beim Oeffnen zu, wie
`## Daten` (Obsidian speichert den Klappzustand einer Ueberschrift nicht in der Datei).
Zutaten stehen als Dataview-Felder in Klammer-Form, die Obsidian sauber als
"**Tomaten:** 120" darstellt.

Bis zum 25.10.2026 stand alles eine Ebene tiefer in einem `> [!info]- Essen`-Callout
unter `## Notizen`. Die App liest diese Form weiter und ersetzt sie beim naechsten Sync.

Der erster-sync-Kommentar steht im ersten Callout, nicht frei unter der Ueberschrift:
`%%` blendet Obsidian nur in der Leseansicht aus, im Bearbeitungsmodus stuende er grau da.

```markdown
## Essen

> [!mahlzeit]- Fruehstueck · 520 kcal
> %% synchealth erster-sync=2026-10-24 %%
> - Haferflocken mit Beeren · 380 kcal
> - Kaffee mit Milch · 140 kcal

> [!mahlzeit]- Mittag · 740 kcal
> **Bowl** · 660 kcal
> - [Vollkornreis:: 100]
> - [Tomate:: 100]
> - [Zutat Zucker:: 3.5]
> - [Parmesan:: 30] ⚠ ohne Naehrwerte
> - Fladenbrot ⚠ ohne Grammangabe
> %% synchealth eintrag=259 rezept=13
> Vollkornreis | 100 | 3.56 0.0889 0.0111 0.035 0.007
> ...
> %%
>
> **Samsung Health**
> - Apfel · 80 kcal

> [!mahlzeit]- Snacks · 300 kcal
> - Riegel · 300 kcal

## Daten
...
```

- **Zutaten stehen nur in Gramm und ohne Einheit** in Klammer-Form (`[Tomaten:: 120]`), damit Dataview
  sie als Zahl liest. Auch "4 Stueck" erscheinen als Gramm, ueber Tandoors
  `conversions`. Gewuerze ohne Menge (0 g) werden weggelassen.
- **Samsung-Eintraege** stehen mit Titel und kcal unter ihrer Mahlzeitenart aus
  Samsung Health. Ein Samsung-Eintrag "Mittag" steht also neben Tandoors Mittagessen.
- **Tandoor-Gerichte** bringen Zutaten in Gramm, schon auf den eigenen Anteil
  umgerechnet (Portionen / Tage / Esser wie bisher in `MealPlanMath`).
- **Naehrwerte je Gramm** stehen unsichtbar als `%% ... %%`-Kommentar im Abschnitt.
  Damit laesst sich eine Mahlzeit neu rechnen, ohne Tandoor zu fragen.
- Die Felder unter `# Daten` (Kalorien, Protein, ...) bleiben wie heute und tragen die
  Summe aus dem Callout.

## Regeln

### Planen und Anpassen

- **Ungeaendert = wie geplant.** Eine Mahlzeit zaehlt ohne Bestaetigung so, wie sie im
  Plan steht.
- **Anpassungen gelten nur fuer den einen Tag**, auch bei Abendessen ueber zwei Abende.
- **Mengen sind Rohmengen**, so wie sie in Tandoor stehen.
- **Alle Zutaten stehen in Gramm.** Tandoor rechnet "Stueck", "EL" usw. ueber die
  gepflegten Umrechnungen selbst in Gramm um. Fehlt eine Umrechnung, hat die Zutat
  keine Grammzahl und keine Naehrwerte, siehe naechster Punkt.
- **Zutat ohne Naehrwerte:** Sie zaehlt in der Summe nicht mit und wird mit ⚠
  markiert. Der Rest des Tages wird trotzdem geschrieben. Das loest die heutige Regel
  ab, nach der dann alle fuenf Felder leer bleiben.

### Aendern in App und Obsidian

- **Die Notiz ist die Wahrheit.** SyncHealth liest sie, bevor etwas in der App
  angezeigt oder geaendert wird, und schreibt beim Speichern sofort zurueck.
- **Aenderungen gehen an beiden Stellen.** Gramm, die in Obsidian von Hand geaendert
  werden, uebernimmt der naechste Sync.
- **Summen rechnet nur SyncHealth.** Nach einer Aenderung in Obsidian stimmen die kcal
  im Callout und die Felder unter `# Daten` erst nach dem naechsten Sync.
- **Unlesbare Zeilen werden nicht ueberschrieben.** SyncHealth meldet sie, korrigiert
  wird von Hand.

### Frisch und fest

- **Bis 7 Tage nach dem ersten Sync** eines Tages holt jeder Sync Tandoor neu.
  Korrekturen am Rezept kommen so von selbst an. Das Datum des ersten Syncs steht im
  Kommentar (`erster-sync`).
- **Danach ist der Tag fest.** Tandoor wird fuer ihn nicht mehr gefragt, spaetere
  Rezeptaenderungen wirken nicht rueckwirkend. Gramm lassen sich weiter aendern.
- **Knopf "Aus Tandoor neu laden"** je Mahlzeit in der App, auch fuer feste Tage.
- **Beim Neuladen bleiben eigene Anpassungen** an allen Zutaten erhalten, die es noch
  gibt. Neue Zutaten und alle Naehrwerte kommen frisch aus Tandoor.

## App

Eine Ansicht "Mahlzeiten" je Tag: Gerichte mit Anteil und kcal, darunter die Zutaten
mit Gramm zum Aendern, Knoepfe fuer ½× / 1× / 2×, Zutat entfernen, "Aus Tandoor neu
laden". Samsung-Eintraege nur zum Ansehen, Summe unten.

## Was Tandoor dafuer liefert

Am Tandoor-Quellcode geprueft (`cookbook/serializer.py`,
`cookbook/helper/property_helper.py`) und am 24.10.2026 an der eigenen Instanz mit
Bowl, Kokos-Curry und Sushi bestaetigt:

- Jede Zutat dieser drei Rezepte hat ueber `conversions` eine Grammzahl, auch bei
  "Stueck", "EL" oder "Blaetter" (z. B. 4 Stueck Tomate = 400 g). Gramm gibt es also
  schon heute fuer alle Zutaten, ohne die Rezepte umzustellen.
- Gewuerze ohne Menge stehen mit 0 g im Rezept und tragen nichts bei.
- Die Aufschluesselung zeigt Datenfehler: Senf steht mit 0 kcal und 2 EL = 90 g.

Felder:

- `food_properties[typ].food_values`: Beitrag je Lebensmittel zum ganzen Rezept.
  Ein Lebensmittel, das zweimal vorkommt, ist zusammengefasst.
- `steps[].ingredients[]`: `amount`, `unit`, `food` mit `properties`,
  `properties_food_amount` und `properties_food_unit` (z. B. je 100 g), dazu
  `conversions` mit der Menge in anderen Einheiten.
- Gramm gibt es nur, wenn die Zutat in g/kg steht oder eine Umrechnung gepflegt ist.

## Offen

Nichts. Entschieden am 24.10.2026:

- **Vorsatz `Zutat ` nur bei Kollision.** Heisst eine Zutat wie ein Feld der Notiz
  (Messwert, Habit, Kontextfeld), steht sie als `[Zutat Zucker:: 3.5]`, sonst ohne.
  Die belegten Namen liest die App aus der Notiz selbst.
- **Gleiche Zutat in zwei Mahlzeiten** steht zweimal da, Abfragen rechnen mit `sum()`.
- `tagesuebersicht.js` und `vault-check.py` sind nicht betroffen, beide lesen nur
  Zeilen, die mit `- ` beginnen.
- `Essen tracken` im Test-Template von `NotePatcherTest` ist veraltet, im Vault gibt es
  das Feld nicht.
- Unterrezepte werden seit dem 27.10.2026 aufgeklappt, siehe unten.

## Unterrezepte

Ergaenzt am 27.10.2026. "Pilzgulasch mit Kartoffelknödeln" besteht in Tandoor nur aus
zwei Zeilen, "1 Stück Pilzgulasch" und "1 Stück Kartoffelknödel", die jeweils auf ein
eigenes Rezept zeigen. Die App zeigte deshalb "0,33 Stück Kartoffelknödel" statt der
Zutaten.

- **Verlinkte Rezepte werden aufgeklappt** (`tandoor/SubRecipes.kt`): Die App laedt sie
  nach, bis zu drei Ebenen tief, und setzt ihre Zutaten an die Stelle der Zeile. Gleiche
  Zutaten (Kartoffel aus Gulasch und Knoedeln) stehen zusammengefasst da.
- **Wie viel vom Unterrezept gemeint ist**, sagt die Einheit der Zeile: `Stück` heisst
  das ganze Rezept (so rechnet Tandoor selbst), Portionen (`Portion`, `Personen` oder der
  `servings_text` des Unterrezepts) teilen durch dessen Portionen, Gramm durch sein
  Gewicht. Laesst sich nichts zuordnen, bleibt die Zeile wie bisher stehen.
- **Ein Schritt mit eingebundenem Rezept** (`step_recipe`) zaehlt das Unterrezept einmal
  ganz, so wie Tandoor es anzeigt.
- **Gerechnet wird aus den Zutaten des Unterrezepts**, nicht aus den Naehrwerten am
  verlinkten Lebensmittel. Die sind eine Momentaufnahme: Am 27.10.2026 stand
  Kartoffelknödel dort mit 1704 kcal, das Rezept selbst ergibt 1334 kcal.
- Laesst sich ein Unterrezept nicht abrufen, gibt der Tag keine Summe heraus, wie bei
  jedem anderen Rezept.

## Vor dem Bauen pruefen

1. ~~Ein Rezept von der eigenen Tandoor-Instanz lesen~~: erledigt, siehe oben.
2. ~~Testnotiz~~: am 24.10.2026 bestanden. Dataview findet Felder in verschachtelten
   Callouts, auch mit Leerzeichen im Namen (`rote-linsen`), liest sie als Zahl, und
   `[Parmesan:: 30] ⚠ ...` bleibt trotz Warnung eine Zahl. Der `%%`-Kommentar ist in
   der Leseansicht unsichtbar.

## Umsetzung

Auf eigenem Branch, nur auf dem eigenen Handy. Leo bekommt es erst, wenn es laeuft.

## Vault

Die Tagesnotiz ist laut `00 System/Tagesnotizsystem.md` eine Pipeline. Der neue
Essen-Callout muss dort beschrieben werden, bevor SyncHealth ihn schreibt.
