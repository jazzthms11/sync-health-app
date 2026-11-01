# Konzept: Essen aus Tandoor anpassen und aufschluesseln

Stand: 01.11.2026, Branch `zutaten`. Die ausfuehrliche Ablage in der Tagesnotiz
(`## Essen` mit Callouts) kommt auf dem Branch `obsidian-essen` dazu.

## Ziel

- Sehen, welches Rezept welche Naehrwerte beitraegt, bis hinunter zur Zutat.
- Die geplanten Mahlzeiten fuer einen Tag anpassen: Gramm je Zutat, Faktor fuers
  ganze Gericht (halb, doppelt), Zutaten weglassen.
- Die Tagesnotiz bekommt wie bisher nur die Summen.

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
                               ├──▶ Handy (Stand je Tag, mit eigenen Anpassungen)
                               ▼
                            Obsidian (Tagesnotiz: nur die Summen, wie bisher)
```

Samsung Health bleibt nur Quelle zum Lesen. Extras, die nicht im Rezept stehen
(z. B. noch Parmesan drueber), werden dort eingetragen.

## Speichern

- **Die Tagesnotiz bleibt, wie sie war.** SyncHealth schreibt nur die Summen unter
  `# Daten` (Kalorien, Protein, ...). Gerichte und Zutaten stehen nicht in der Notiz.
- **Der Stand je Tag liegt auf dem Handy** (`essen/MealStore.kt`), eine JSON-Datei je
  Tag im App-Speicher: Gerichte, Zutaten mit geplanter und eigener Menge, Naehrwerte je
  Einheit und der Tag des ersten Syncs.
- **Gespeichert wird beim Sync**, und nur wenn die Summe in der Notiz angekommen ist. Bis
  dahin zeigt die App "Ungespeicherte Aenderungen".
- Wird die App deinstalliert oder ihr Speicher geloescht, ist der Stand weg. Die Summen
  in der Notiz bleiben; ein neuer Sync rechnet wieder nach Plan.

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

### Frisch und fest

- **Bis 7 Tage nach dem ersten Sync** eines Tages holt jeder Sync Tandoor neu.
  Korrekturen am Rezept kommen so von selbst an. Das Datum des ersten Syncs steht im
  gespeicherten Stand.
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
- **Es zaehlt die Einheit der Zeile, nicht Tandoors Umrechnung.** "1 Stück Bolognese"
  ist in Tandoor als 1300 g gepflegt, das Gewicht nach dem Kochen. Gegen das Rohgewicht
  der Zutaten (2626 g) geteilt, kam nur die halbe Sosse an. Gramm gelten nur, wenn die
  Zeile selbst in g oder kg steht.
- **`Stück` mit Stueckzahl am Unterrezept:** Steht beim Unterrezept als Portionsangabe
  `Stück` (Fladenbrot: 4 Stück), ist ein Stueck eine Portion davon. Seit dem 27.10.2026
  so in Tandoor gepflegt. Vorher zaehlte "1 Stück Fladenbrot" im Doener den ganzen Teig,
  2039 statt 510 kcal; betroffen waren der 24., 25. und 26.10.2026. Ob der Teig in 4 oder
  8 Stuecke geteilt wird, aendert pro Person nichts: ein grosses oder zwei kleine sind
  beide ein Viertel des Teigs.
- **Ein Schritt mit eingebundenem Rezept** (`step_recipe`) zaehlt das Unterrezept einmal
  ganz, so wie Tandoor es anzeigt. Zeigt im selben Rezept auch eine Zutat darauf (Lasagne,
  Burger, Wraps), zaehlt nur die Zutat mit ihrer Menge, sonst kaeme es doppelt hinein.
- **Gerechnet wird aus den Zutaten des Unterrezepts**, nicht aus den Naehrwerten am
  verlinkten Lebensmittel. Die sind eine Momentaufnahme: Am 27.10.2026 stand
  Kartoffelknödel dort mit 1704 kcal, das Rezept selbst ergibt 1334 kcal.
- Laesst sich ein Unterrezept nicht abrufen, gibt der Tag keine Summe heraus, wie bei
  jedem anderen Rezept.
- **In Tandoor nachgezogen am 27.10.2026**, damit jede Sosse mit einer Mengenzeile drinsteht:
  Burgerbaguette und Burgerbaguette vegetarisch je 0.5 Stück Burgersoße (eine Portion pro
  Baguette), Crispy-Chickenburger 1 Stück Burgersoße, Cannelloni und Moussaka je 1 Stück
  Bechamelsauce, grüner Salat 0.5 Stück Salatsoße (eine Portion pro Person, statt 353 kcal
  Dressing), Lasagne mit Hackfleisch je 1 Stück Bolognese und Bechamel (stand auf 0),
  Butter Chicken 4 Stück Naan. Am Lebensmittel Naan gelten die Werte jetzt je 2 Stück.

