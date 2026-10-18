# ActivityTrack — manuale d'uso

Versione 0.1.0 (sviluppo fino alla milestone M9, 18 ottobre 2026).
Le funzioni di M4–M9 (importazione/esportazione, calendario, agenda, grafica,
attività pianificate) sono state scritte ma non ancora provate sul telefono: segnalare ogni
differenza tra questo manuale e l'app.

I nomi tra virgolette sono le voci dell'interfaccia in italiano; con il
telefono in un'altra lingua l'app usa inglese, tedesco o spagnolo.

---

## 1. Concetti

**Attività** — un intervento per un cliente (o per un'azienda, in viaggio
senza cliente noto). Ha cliente, azienda, ruolo, descrizione, località,
note, riferimenti esterni (ticket, URL, codici), spese e allegati.

**Segmenti** — il tempo di un'attività è fatto di tratti:
- **Lavoro** (verde), **Viaggio** (blu), **Pausa** (giallo).
- Sono registrati al minuto esatto; arrotondamenti e attribuzione ai giorni
  si applicano solo nei report e nelle esportazioni.

**Stati di un'attività**
| Stato | Significato |
|---|---|
| In corso (lavoro / trasferta / pausa) | il tempo scorre |
| Sospesa (arancione) | interrotta, da riprendere più tardi |
| Da completare (rosso) | fermata, mancano i dettagli |
| Chiusa | completata |

Più attività possono essere in corso insieme (es. un viaggio e una telefonata
di lavoro per un altro cliente).

**Solare e rendicontato**
- *Solare*: il tempo di orologio coperto da lavoro o viaggio di qualsiasi
  attività (le sovrapposizioni contano una volta).
- *Rendicontato*: la somma di lavoro e viaggio arrotondati di tutte le attività.

**Regole del report** (modificabili in Impostazioni → "Rendicontazione")
- Lavoro: arrotondato al quarto d'ora per eccesso, con tolleranza di 5 minuti
  (es. 1 h 04 → 1 h; 1 h 05 → 1 h 15); un lavoro breve vale almeno 15 min.
- Viaggio: per eccesso a 5 minuti.
- Pausa: per difetto a 5 minuti, minimo 5.
- Notte: un'attività che finisce entro le 06:00 del giorno dopo resta sul
  giorno di inizio; oltre, viene divisa a mezzanotte.

---

## 2. Primo avvio

1. **Cartella dati** — l'app chiede una cartella (es. `Documenti/ActivityTrack`)
   per allegati, backup ed esportazioni. I file restano anche se l'app viene
   disinstallata. Se la cartella viene spostata o rinominata l'app lo segnala
   ("L'accesso alla cartella dati è stato perso") e chiede di sceglierla di nuovo.
2. **Permessi** — notifiche e posizione vengono chiesti all'apertura. In
   Impostazioni → "Posizione":
   - "Accesso alla posizione" → **"Consenti sempre"**: serve perché il
     tracciamento riparta anche dopo un riavvio o da una notifica;
   - "Ottimizzazione batteria" → **"Consenti"**: su OxygenOS evita che il
     sistema interrompa il tracciamento.
3. **Anagrafiche minime** (scheda "Altro" → "Anagrafiche"): almeno un'azienda
   e un cliente. Utili anche "Orario di lavoro e pausa pranzo" (per l'avviso
   di inizio giornata e il riepilogo serale) e una sede per i clienti
   frequenti (proposta automatica del cliente).

---

## 3. La barra in basso

| Scheda | Contenuto |
|---|---|
| **Home** | attività aperte, "Da fare oggi", pulsante verde di avvio |
| **Agenda** | consultazione: lista, giorno, settimana, mese |
| **Report** | il report di un giorno: solare, rendicontato, righe per attività, esportazione |
| **Altro** | "Da fare", "Importa / esporta", "Anagrafiche", "Impostazioni" |

Da una scheda diversa dalla Home, il tasto Indietro torna alla Home.

---

## 4. Avviare un'attività

Pulsante **verde con il play** in fondo alla Home ("Avvia attività").

Nella schermata "Nuova attività":
- **Lavoro / Viaggio** in alto: un viaggio può partire senza cliente.
- **Azienda** filtra i clienti; **Cliente** è una casella in cui scrivere per
  cercare (maiuscole e accenti non contano) oppure scegliere dall'elenco, con i
  più recenti in cima.
- Se ti trovi entro il raggio di una sede, cliente e azienda sono già proposti;
  altrimenti l'app propone l'azienda dell'ultima attività di oggi.
- **Descrizione** e **ruolo** vengono proposti dall'ultima attività sullo
  stesso cliente negli ultimi 30 giorni. Il microfono nel campo detta il testo.
- Il pulsante verde in basso avvia (blu con l'auto se è un viaggio).

**Se un'altra attività è già in corso**, l'app chiede cosa farne:
- in lavoro: chiudere, sospendere o lasciarla andare;
- in viaggio: "il viaggio è finito?" (sì = ferma, no = lascia, oppure sospendi);
- in pausa: chiudere o lasciarla.

**Altri modi per partire**
- dalla notifica dell'avviso di inizio giornata ("Avvia attività");
- da una voce **Da fare** (cliente, descrizione e riferimenti già compilati);
- dal **widget** 4×1 o dalle **scorciatoie** dell'icona (clienti recenti): si apre
  la schermata di avvio con il cliente già scelto.

---

## 5. Durante l'attività

### Comandi
Nella card della Home e nella notifica:
| Icona | Comando |
|---|---|
| ▶ verde | Lavoro / Riprendi |
| ⏸ giallo | Pausa |
| 🚗 blu | Viaggio |
| 🌙 arancione | Sospendi |
| ⏹ rosso | Stop |

La card mostra il tempo del tratto in corso in grande e lo stato in
un'etichetta colorata. "Stop" mentre lavori chiede **"Ferma ora"** oppure
**"Avvia rientro"** (il viaggio di ritorno fa parte della stessa attività).

### Notifica fissa
Una notifica per attività in corso, con cronometro e comandi. Mentre la
posizione è attiva non si può eliminare con uno swipe; negli altri casi
ricompare quando riapri l'app.

### Promemoria
- **Conferma** ogni 30 minuti di lavoro o viaggio: "Ancora su …?" con
  "Sì", "Pausa" o "Stop alle hh:mm" (ferma all'ultima conferma, se te ne eri
  dimenticato). Se non rispondi l'attività continua.
- **Pausa** ogni 15 minuti ("Ancora in pausa?"), muto durante la pausa pranzo.
- **Riepilogo** a fine orario di lavoro: solare e rendicontato del giorno.

### Posizione (automatica)
- Un punto ogni 5 minuti, più uno quando il telefono si muove; mai esportati.
- **"Lontano da …?"** — durante il lavoro, se ti allontani più di 100 m:
  "Viaggio dalle hh:mm" (il viaggio parte dall'ora in cui sei partito),
  "Ignora", "Stop alle hh:mm".
- **"Arrivo?"** — durante un viaggio, dopo 15 minuti fermo entro 100 m:
  presso la sede di un cliente propone "Lavoro da … dalle hh:mm" (assegna quel
  cliente al viaggio se non ne aveva uno); a casa propone lo stop; altrimenti
  "Lavoro dalle hh:mm" o "Sono ancora in viaggio".
- I km del viaggio sono calcolati sulla strada (OSRM) e la località proposta
  dal servizio di indirizzi (Nominatim), quando la rete è disponibile.

Tocca i **pulsanti** delle notifiche, non il testo: il testo apre l'app e
chiude la notifica senza eseguire l'azione. Se i pulsanti non si vedono,
trascina la notifica verso il basso per espanderla.

### Spese
Nella schermata dell'attività, sezione "Spese" → "Aggiungi spesa": importo in
euro, descrizione, "Foto dello scontrino" facoltativa. Spesa veloce: pulsante
**€** del widget o scorciatoia **"Spesa"** dell'icona (va all'attività in corso
più recente).

### Allegati
Sezione "Allegati": "Foto" (fotocamera), "Nota vocale" (registra finché non
tocchi "Ferma e salva"), "File" (qualsiasi documento). Toccando un allegato:
"Apri" o "Elimina". I file stanno nella cartella dati, in
`attachments/<anno>/<attività>/`.

---

## 6. Fermare e completare

Dopo lo Stop l'attività è **da completare** (notifica persistente e card rossa).
Nella schermata dell'attività:
- controlla cliente, descrizione (dettabile), ruolo, località, luogo, note;
- "Tempi": "+ Viaggio" e "+ Pausa" aggiungono tratti dimenticati (anche con i
  km); i tratti aggiunti a mano si possono eliminare, quelli registrati no;
- "Riferimenti esterni": ticket, URL o codici (riconosciuti automaticamente);
- **"Completa"** (verde).

Se manca la descrizione l'app chiede: **"Lascia vuoto"**, **"Copia l'ultima: …"**
(l'ultima descrizione sullo stesso cliente) o **"Torna a modificare"**.
Se l'attività era partita da una voce *Da fare*, chiede **"L'attività da fare è
conclusa?"** ("Sì" la segna come fatta, "No, ci tornerò" la lascia aperta).

"Riapri" riporta un'attività chiusa o da completare allo stato sospeso.

---

## 7. Consultare: Agenda e Report

### Agenda
In alto le modalità e il periodo ("‹ ›" per scorrere, "Oggi" per tornare a
oggi; a destra il totale rendicontato del periodo).
- **Lista** — il mese, giorno per giorno, con ricerca per cliente o
  descrizione; le voci *Da fare* pianificate o in scadenza compaiono con ☐.
- **Giorno** — linea del tempo: ogni attività è un blocco dall'inizio alla
  fine; attività contemporanee affiancate; le *Da fare* pianificate sono
  blocchi con il bordo colorato.
- **Settimana** — sette colonne da lunedì; toccando un giorno si apre la vista Giorno.
- **Mese** — griglia con le ore rendicontate per giorno, un pallino per
  azienda e uno per le *Da fare*; toccando un giorno si apre la vista Giorno.

Ogni azienda ha il suo colore. Toccando un'attività si apre la sua scheda;
toccando una *Da fare* si aprono le sue azioni (avvia, posticipa, fatta,
ripianifica).

**Pianificare dall'agenda**: nelle viste Giorno e Settimana tocca uno spazio
libero: si apre una nuova *Da fare* pianificata a quell'ora (a passi di
mezz'ora). "Aggiungi" in alto fa lo stesso per il giorno mostrato (oggi: la
prossima mezz'ora; altri giorni: le 09:00).

### Report
Il report di un giorno: totale solare e rendicontato, una riga per attività
con lavoro, viaggio e pausa reali → arrotondati; da qui anche l'esportazione.

---

## 8. Da fare

Attività da fare, importate da un gestionale o create a mano ("Altro" →
"Da fare" → "Aggiungi", oppure dall'Agenda): titolo, cliente (o azienda),
**data e ora pianificate**, **durata**, scadenza e ora, priorità
(1 = massima), note, riferimenti (uno per riga).

Stati: **aperta** (senza pianificazione) → **pianificata** (data e ora) →
**fatta**. La pianificazione è separata dalla scadenza: ripianificare non
cambia la scadenza del gestionale.
- **Durata**: se la indichi, il blocco dura quella; se la lasci vuota dura
  un'ora e, finché la voce resta aperta, si allunga di ora in ora (fino a fine
  giornata).
- **Promemoria**: 15 minuti prima dell'ora pianificata arriva la notifica
  "Titolo alle HH:MM" con "Avvia attività", "Posticipa 30 min" (sposta la
  pianificazione di 30 minuti dall'ora pianificata o, se è già passata, da
  adesso; il promemoria torna prima della nuova ora) e "Ripianifica"
  (apre la voce). Arriva anche fuori orario e nei giorni liberi; sparisce se
  la voce viene avviata, segnata come fatta, ripianificata o se il blocco è
  passato.
- In Home, **"Da fare oggi"** mostra quelle in scadenza o pianificate oggi o
  prima; quelle pianificate ancora aperte dopo il loro blocco sono segnate
  "in ritardo".
- Toccando una voce: "Avvia attività", "Posticipa", "Segna come fatta" /
  "Segna come da fare", "Ripianifica" (o "Modifica" se non pianificata).
- Una voce può avere più attività (es. un lavoro su più giorni).

---

## 9. Inizio giornata, assenze, giorni liberi

- 15 minuti dopo l'inizio dell'orario di lavoro, se non hai avviato nulla,
  arriva **"Nessuna attività oggi"**: "Assente oggi", "Entrata in ritardo…"
  (chiede l'ora) o "Avvia attività".
- Non arriva nei **festivi nazionali** (Pasquetta compresa, 4 ottobre dal 2026)
  né nei **giorni liberi** ("Anagrafiche" → "Giorni liberi": data e nota, es. il
  santo patrono o le ferie).

---

## 10. Anagrafiche

- **Aziende** — nella scheda: sezione **"Gestionale"** (codice nel gestionale,
  connettore; per ora il generico, a file) e **"Calendario di questa azienda"**;
  sotto, le **sedi** dell'azienda.
- **Clienti** — appartengono a un'azienda (non si può cambiare dopo la prima
  attività); hanno le loro sedi.
- **Sedi** — etichetta, coordinate, raggio, indirizzo. Coordinate da
  "Posizione attuale" o da "Mappa" (sposta la mappa sotto il mirino e "Usa
  questo punto").
- **Ruoli**, **Attività predefinite** (testi ricorrenti, con ruolo proposto).
- **Luoghi** — casa e altri luoghi (casa serve per l'arrivo a fine viaggio).
- **Orario di lavoro e pausa pranzo** — per giorno della settimana.
- **Giorni liberi**.

---

## 11. Importa / esporta, backup

"Altro" → "Importa / esporta".

**Esporta**
- **CSV**: una riga per attività e giorno, arrotondata; serve un periodo
  (massimo un anno).
- **JSON / XML** (formato generico, `docs/INTERCHANGE.md`): scegli il
  contenuto (anagrafiche, da fare, attività, assenze e giorni liberi), il
  periodo (vuoto = tutto), azienda e cliente; "Coordinate delle sedi" solo se
  servono.
- "Esporta" salva nella cartella `export`; "Esporta e condividi" apre anche
  il menu Condividi (email, Drive…).

**Importa** — un file JSON o XML generico (anche parziale, es. solo clienti o
solo *Da fare*). Il rapporto mostra cosa è stato aggiunto, cosa era già
presente, i **conflitti** (i dati dell'app restano invariati) e ciò che non è
stato importato con il motivo. Reimportare lo stesso file non crea doppioni.

**Backup** — ogni giorno l'app salva il database nella cartella `backup`
(ne conserva 14, modificabile); "Esegui backup ora" in Impostazioni.
**"Ripristina backup"** sostituisce tutti i dati con un backup scelto: prima
salva i dati attuali come backup `…-before-restore.zip`, poi riavvia l'app.

---

## 12. Calendario

Impostazioni → "Calendario":
1. consenti l'accesso al calendario;
2. "Calendario delle attività": un calendario del telefono (Google o CalDAV
   tramite DAVx5) oppure "Nuovo calendario ActivityTrack sul telefono";
3. un'azienda può avere il suo calendario (scheda azienda), che sostituisce
   quello generico.

Ogni attività diventa un evento per giorno, dall'avvio: titolo "Cliente –
Descrizione", nella descrizione i tempi arrotondati, km, riferimenti e note.
Le *Da fare* pianificate e aperte diventano eventi "provvisori" (Cliente –
Titolo) fino a un anno avanti; quando sono fatte escono dal calendario.
L'ultima riga della descrizione porta dei tag per tipo di tempo: `#lavoro`,
`#viaggio`, `#pausa`, `#pianificata` (cercali nell'app calendario).
La sincronizzazione è solo dall'app al calendario: modifiche fatte nel
calendario vengono sovrascritte. Gli ultimi 14 giorni si aggiornano da soli;
"Sincronizza 90 giorni" riallinea più indietro.

---

## 13. Widget, riquadro, scorciatoie

- **Widget "Stato attività" (4×1)** — l'attività in corso con i comandi e "€";
  senza attività, i clienti recenti e il pulsante di avvio.
- **Widget "Pausa / riprendi" (1×1)** — mette in pausa o riprende l'attività
  più recente; senza attività apre l'avvio.
- **Riquadro delle Impostazioni rapide** — stessa funzione del widget 1×1. Su
  OxygenOS si aggiunge da Impostazioni → "Riquadro Impostazioni rapide" →
  "Aggiungi".
- **Scorciatoie** (pressione lunga sull'icona) — i 4 clienti più recenti e "Spesa".

---

## 14. Impostazioni

| Sezione | Voce | Predefinito |
|---|---|---|
| Promemoria | Intervallo di conferma | 30 min |
| | Numero massimo di conferme | 0 (illimitato) |
| | Intervallo promemoria pausa | 15 min |
| | Ritardo avviso inizio giornata | 15 min |
| Posizione | accesso, batteria, riquadro rapido | — |
| | Soglia avviso spostamento | 100 m |
| | Raggio / tempo di arrivo | 100 m / 15 min |
| | Fattore distanza stradale (senza OSRM) | 1,25 |
| Calendario | calendario delle attività, sincronizza 90 giorni | nessuno |
| Dettatura | Lingua di dettatura | Italiano |
| Rendicontazione | attività notturne fino alle | 06:00 |
| | arrotondamenti lavoro / viaggio / pausa | §1 |
| Servizi online | Nominatim, OSRM, mappe OpenStreetMap (attivabili e con endpoint) | attivi |
| Archiviazione | cartella dati, backup conservati, backup ora | 14 |
| Avanzate (▼) | Promemoria attività pianificate (min prima) | 15 |
| | Posticipo (min) | 30 |

I valori fuori intervallo vengono corretti al salvataggio.

---

## 15. Dati e privacy

- Tutto resta sul telefono e nella cartella dati che hai scelto.
- Le **coordinate** dei tuoi spostamenti non vengono mai esportate né mandate
  al calendario; solo la località (es. "Milano").
- I servizi online (indirizzi, distanze, mappe) ricevono solo coordinate di
  singoli punti; si possono disattivare o puntare a server propri.
- Le credenziali dei gestionali sono cifrate con il keystore del telefono.

---

## 16. Problemi frequenti

| Problema | Soluzione |
|---|---|
| Il tracciamento si ferma | Impostazioni → Posizione: "Consenti sempre" e batteria "Senza restrizioni" |
| La notifica dell'attività è sparita | riapri l'app: ricompare |
| "L'accesso alla cartella dati è stato perso" | la cartella è stata spostata o rinominata: sceglila di nuovo |
| Dettatura: "lingua non disponibile" | Impostazioni → Dettatura: scegli la lingua; per l'uso offline scarica il pacchetto della lingua nelle impostazioni vocali del telefono |
| Il riquadro rapido non è nell'elenco | Impostazioni → "Riquadro Impostazioni rapide" → "Aggiungi" |
| Un'azione della notifica non fa nulla | tocca il pulsante, non il testo; espandi la notifica se i pulsanti non si vedono |
| Importazione: "Non importati" | il rapporto indica il motivo (es. azienda sconosciuta, attività in corso su un altro telefono) |
