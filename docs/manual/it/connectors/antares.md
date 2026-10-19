<!-- title: Connettore Antares (Saidea) -->
Versione 0.6.0 · guida al collegamento tra ActivityTrack e il gestionale
Antares di Saidea. L'uso generale dell'app è nel *Manuale*.

## 1. Cosa fa

Per un'azienda che rendiconta su Antares, il connettore:

- scarica **servizi**, **progetti** e **tipi di attività** dell'azienda;
- trasforma i **ticket assegnati a te** in voci *Da fare*;
- invia ad Antares le **attività chiuse**, una per giorno, e le aggiorna o le
  cancella se le modifichi dopo l'invio.

Lo scambio parte solo quando tocchi **Sync**: nulla viene inviato in
automatico.

## 2. Requisiti

- Un utente Antares con il diritto di rendicontare.
- Da Saidea: l'**indirizzo dell'API**, quello dell'**identity provider** e un
  **Client ID** di tipo *pubblico* (applicazione nativa, senza segreto), con
  registrato il *Redirect URI* che l'app mostra nella scheda dell'azienda
  (`it.infraconsulting.activitytrack:/oauth2redirect`; per l'app di prova
  ActivityTrackDbg `it.infraconsulting.activitytrack.debug:/oauth2redirect`).
- Nei clienti di ActivityTrack, il **codice nel gestionale** uguale al codice
  cliente di Antares (serve a filtrare servizi e progetti per cliente).

## 3. Configurazione

Scheda azienda → **"Gestionale"** → connettore **Antares (Saidea)**:

| Campo | Contenuto | Predefinito |
|---|---|---|
| API URL | indirizzo dell'API pubblica v3 | ambiente demo |
| Identity provider | indirizzo del login | ambiente demo |
| Client ID | fornito da Saidea | — |
| Pausa che spezza l'attività | minuti oltre i quali una pausa divide l'attività in due | 60 |

Per le prove l'app propone l'ambiente demo (`demo.goantares.uno`).

## 4. Accesso

- **Accedi** apre la pagina di login di Antares nel browser; dopo il login il
  browser torna all'app e la scheda mostra **"Collegato come …"**.
- L'app non conserva la password né segreti: solo un token di rinnovo,
  cifrato con il keystore del telefono. Se scade o viene revocato, basta
  accedere di nuovo.
- **Esci** cancella il token.

## 5. Sync

**Sync** (scheda azienda) esegue in ordine:

1. **Servizi e progetti** attivi dell'azienda, con i loro tipi di attività;
   quelli non più attivi non si possono più scegliere.
2. **Ticket** aperti assegnati a te → voci *Da fare* (titolo, cliente,
   descrizione nelle note, priorità, codice del ticket come riferimento); quelli chiusi in Antares
   vengono segnati come fatti.
3. **Attività chiuse** dell'azienda non ancora inviate o modificate dopo
   l'invio → inviate, aggiornate o cancellate in Antares.

Alla fine un **resoconto** mostra quanti servizi e progetti, ticket e
attività (inviate, aggiornate, eliminate) e l'elenco di quelle **non
inviate**, con il motivo (manca il tipo di attività, manca il servizio o il
progetto). Al primo errore dell'API il Sync si ferma; quanto già fatto resta
registrato e il Sync successivo riprende da lì.

## 6. Nell'attività

Nella scheda di un'attività di un'azienda Antares compare la sezione
**Antares**:

- **Servizio o progetto** (filtrato sul cliente, se il cliente ha il codice);
- **Tipo attività** (quelli abilitati su quel servizio o progetto);
- **Compresa nel contratto** (proposta dal tipo di attività).

Un'attività nata da un ticket viene rendicontata sul ticket: basta il tipo.
All'inizio vengono proposti servizio, progetto e tipo dell'ultima attività
sullo stesso cliente. La sezione mostra anche se l'attività è già stata
inviata e quando.

## 7. Come viene inviata un'attività

- **Una attività Antares per giorno di report** (con la regola delle attività
  notturne del *Manuale*): inizio = inizio del primo tratto di lavoro del
  giorno, fine = inizio più la durata **arrotondata** con la regola del
  lavoro; le pause più brevi della soglia restano comprese.
- Una **pausa più lunga della soglia** divide l'attività in due blocchi.
- Il **viaggio** non diventa un'attività a sé: minuti e km di viaggio vanno
  nel blocco più vicino.
- Vanno ad Antares la **descrizione** (rapportino) e le **note** dell'attività.
- Se modifichi un'attività già inviata, il Sync successivo la aggiorna; se un
  blocco sparisce (es. tolta una pausa lunga), viene cancellato in Antares.

## 8. Problemi frequenti

| Problema | Cosa fare |
|---|---|
| "Accesso ad Antares non riuscito" | controlla Client ID e indirizzi; il Redirect URI deve essere registrato da Saidea |
| Il browser non torna all'app dopo il login | Redirect URI non registrato o scritto diversamente |
| Nessun servizio o progetto per un cliente | il codice nel gestionale del cliente non corrisponde al codice Antares |
| Attività "non inviate" nel resoconto | completa la sezione Antares dell'attività e rifai Sync |
