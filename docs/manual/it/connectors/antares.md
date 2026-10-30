<!-- title: Integrazione Antares (Saidea) -->
Versione 0.9.1 · guida al collegamento tra ActivityTrack e il gestionale
Antares di Saidea. L'uso generale dell'app è nel *Manuale*.

## 1. Cosa fa

Per un'azienda che rendiconta su Antares, l'integrazione:

- scarica **servizi**, **progetti** e i loro **tipi di attività**, e i
  **clienti**;
- trasforma in voci *Da fare* i **ticket** di cui sei **tecnico** o
  **richiedente**;
- invia ad Antares le **attività chiuse** e le aggiorna o le cancella se le
  modifichi dopo l'invio.

L'invio parte con **Sincronizza ora** o, se lo scegli, alla chiusura di
un'attività; gli aggiornamenti si scaricano anche in automatico (§5).

## 2. Requisiti

- Un utente Antares con il diritto di rendicontare.
- Il file del plugin `antares.json`, dalla cartella `plugins` del repository
  di ActivityTrack: va copiato nella cartella **`plugins`** della cartella dati
  dell'app (accanto a `backup`). L'app lo legge all'avvio e ogni volta che
  torna in primo piano.
- Da Saidea: l'indirizzo dell'API e quello del login (l'app propone
  l'ambiente demo `demo.goantares.uno`) e il **Client ID** pubblico,
  `activitytrack`, già proposto dall'app.

## 3. Configurazione

Scheda azienda → **"Gestionale"** → **Connettore**: **Antares (Saidea)**.
Compaiono le opzioni del plugin; quelle con `*` sono obbligatorie e finché
mancano sono elencate in rosso.

| Opzione | Contenuto | Predefinito |
|---|---|---|
| URL API `*` | indirizzo dell'API pubblica v3 | ambiente demo |
| Identity provider `*` | indirizzo del login | ambiente demo |
| Client ID `*` | client pubblico di Saidea | `activitytrack` |
| Pausa che divide l'attività (min) | una pausa più lunga divide l'attività in due | 60 |
| Invio alla chiusura | Chiedi / Sempre / Mai | Chiedi |
| Attività da inviare alla chiusura | Solo ticket / Tutte | Solo ticket |
| Alla chiusura | Sincronizzazione completa / Solo l'attività | completa |
| Aggiornamento in background | da 15 minuti a 1 giorno | 1 ora |

## 4. Accesso

- **Accedi** apre la pagina di login di Antares nel browser; dopo il login il
  browser torna all'app con il messaggio "Accesso a Antares (Saidea)
  eseguito" e la scheda mostra **Esci · Accesso eseguito**.
- L'app non conserva la password: solo un token di rinnovo, cifrato con il
  keystore del telefono. Se scade o viene revocato basta accedere di nuovo.
- **Esci** cancella il token.

## 5. Sincronizzazione

**Sincronizza ora** (scheda azienda) esegue in ordine:

1. chi sei in Antares (la persona collegata al tuo utente);
2. **servizi e progetti** attivi e i loro **tipi di attività**;
3. **clienti**: quelli nuovi vengono creati nell'azienda, con il codice di
   Antares; quelli esistenti restano come li hai tu;
4. **ticket** di cui sei tecnico o richiedente → voci *Da fare* (titolo,
   cliente, descrizione in testo semplice nelle note, priorità, codice del
   ticket nei riferimenti); quelli chiusi in Antares diventano fatti, e una
   voce che hai segnato fatta non viene riaperta;
5. **attività chiuse** con i dati Antares → create, aggiornate o cancellate.

Alla fine un **resoconto** mostra quanto è stato scaricato e inviato e le
attività **non inviate**, con il campo che manca. Al primo errore
dell'API la sincronizzazione si ferma: quanto già fatto resta e la volta
successiva riprende. Ripeterla non crea doppioni.

### Aggiornamenti automatici
Con la rete disponibile, all'intervallo scelto (minimo 15 minuti, limite di
Android) l'app scarica servizi, clienti e ticket **senza inviare nulla**.
Ogni nuovo ticket arriva come notifica (più di tre: una sola notifica con il
numero).

### Alla chiusura di un'attività
Quando un'attività dell'azienda si chiude, secondo le opzioni:
- **Chiedi**: con l'app aperta compare "Inviare a Antares (Saidea)?"; con
  l'app in secondo piano arriva una notifica con **Invia**;
- **Sempre**: parte subito; **Mai**: niente, si invia con la sincronizzazione.

Senza rete l'invio resta **in coda** e parte appena torna; in caso di errore
riprova a intervalli crescenti e alla fine lo segnala. Le notifiche usano il
canale **"Gestionali"**.

## 6. Nell'attività

Nella scheda di un'attività dell'azienda compare la sezione **Antares
(Saidea)**:

- **Servizio o progetto**, filtrato sul cliente dell'attività (nascosto se
  l'attività nasce da un ticket: vale quello del ticket);
- **Sede** del cliente presso cui hai lavorato, tra quelle collegate al
  servizio (obbligatoria per un servizio; nascosta se l'attività nasce da un
  ticket: vale la sede del ticket);
- **Tipo di attività**, tra quelli del servizio o progetto (obbligatorio);
- **Incluso nel contratto**, proposto dal tipo di attività.

## 7. Come viene inviata un'attività

- **Una attività Antares per giorno** (con la regola delle attività notturne
  del *Manuale*): inizio = inizio del primo tratto di lavoro del giorno, fine
  = inizio più la durata **arrotondata** con la regola del lavoro; le pause
  più brevi della soglia restano comprese.
- Una **pausa più lunga della soglia** divide l'attività in due.
- Il **viaggio** non diventa un'attività a sé: minuti e km vanno nel blocco
  più vicino.
- Vanno ad Antares la **descrizione** (rapportino), i **materiali** (in coda
  alla descrizione) e le **note**; per un ticket anche il cliente e la sua
  sede.
- Se modifichi un'attività già inviata, la sincronizzazione successiva la
  aggiorna (senza sovrascrivere modifiche fatte nel frattempo in Antares);
  se un blocco sparisce, viene cancellato in Antares.
- Se **elimini** un'attività già inviata, l'app lo segnala e la cancella
  anche in Antares alla sincronizzazione successiva.

## 8. Problemi frequenti

| Problema | Cosa fare |
|---|---|
| Il connettore Antares non compare | copia `antares.json` nella cartella `plugins` dei dati e riapri l'app; con una policy firmata anche il plugin va firmato |
| "Accesso non riuscito" | controlla gli indirizzi e il Client ID |
| Il browser non torna all'app dopo il login | il client di Saidea non conosce l'indirizzo di ritorno dell'app: segnalalo a Saidea |
| Nessun ticket | i ticket arrivano solo se sei tecnico o richiedente |
| Attività "non inviate" nel resoconto | scegli il tipo di attività (e servizio e sede, se non nasce da un ticket) e risincronizza |
| Gli aggiornamenti arrivano in ritardo | Android rinvia i lavori periodici in standby; **Sincronizza ora** li scarica subito |
