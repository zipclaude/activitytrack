# Scrivere un plugin di integrazione

Guida pratica per collegare ActivityTrack a un gestionale con un plugin
dichiarativo. Il riferimento completo del formato è
[`docs/PLUGINS.md`](../docs/PLUGINS.md) (in inglese); l'esempio usato qui,
verificato a ogni build, è
[`docs/samples/plugins/timesheet-example.json`](../docs/samples/plugins/timesheet-example.json);
un plugin reale è [`antares.json`](antares.json).

## 1. Che cos'è un plugin

Un plugin è **un file JSON**, senza codice: descrive l'API del gestionale e
come i suoi dati corrispondono a quelli dell'app. L'app mette i mattoni
(autenticazione, paginazione, selettori, una libreria chiusa di
trasformazioni, raggruppamenti per l'invio) e controlla tutto il file
quando lo carica: un errore viene segnalato con percorso e riga.

Con un plugin si può:

- **scaricare** liste (servizi, progetti, tipi di attività, sedi…), clienti,
  contatti e voci *Da fare* (ticket, compiti);
- **aggiungere campi** alla scheda dell'attività (per esempio il progetto);
- **inviare** le attività chiuse, aggiornarle se cambiano e cancellarle se
  spariscono o vengono eliminate.

Che cosa serve prima di cominciare:

- la documentazione dell'API (indirizzi, autenticazione, formato delle
  risposte, paginazione);
- un ambiente di prova del gestionale: l'invio crea dati veri;
- l'app installata, con la cartella dati scelta.

## 2. Lo scheletro

```json
{
	"format": "activitytrack-plugin",
	"schema": 1,
	"id": "timesheet-example",
	"name": "Timesheet (example)",
	"version": "1"
}
```

`id` è il nome del file senza `.json`. Tutte le altre sezioni sono
facoltative: si aggiungono una alla volta, provando ogni passo.

## 3. Passo per passo

**Opzioni** (`options`): diventano la scheda dell'integrazione nella scheda
azienda → "Gestionale". Tipi: `string`, `url` (solo https), `int`, `bool`,
`choice`, `secret`. Un `secret` è cifrato sul telefono e non compare mai nel
file né nelle esportazioni: **mai credenziali nel plugin**.

**Autenticazione** (`auth`): `none`, `basic`, `apiKey` (in un'intestazione o
nella query), `bearer`, `oauth2Pkce` (login nel browser, come Antares; l'app
conserva solo il token di rinnovo, cifrato). Esempio:
`{ "type": "apiKey", "header": "X-Api-Key", "key": "@option.apiKey" }`.

**HTTP** (`http`): indirizzo di base (di solito un'opzione), intestazioni,
timeout e stile di paginazione (`none`, `odata`, `page`, `cursor`, `link`).
Se la risposta non è direttamente una lista, `items` dice dove si trova
(`"items": "$.items"`).

**Valori.** Ovunque serva un valore si può scrivere:

- un letterale (`2`, `true`, `"testo"`);
- un **selettore** sul record remoto (`"$.name"`, `"$.customer.code"`);
- un **riferimento** ai dati dell'app (`@entry.activity`, `@field.project`,
  `@option.apiUrl`, `@identity.userId`, `@block.from`);
- una **trasformazione** della libreria (`coalesce`, `concat`, `if`, `map`,
  `date`, `plainText`, …; elenco in `PLUGINS.md` §5).

Dentro un percorso si usa `${…}`: `"/timesheets/${remote.id}"`.

**Identità e liste.**
- `identity` legge chi è l'utente collegato (per filtrare i suoi compiti).
- `lookups` legge piccole tabelle di stati.
- `lists` tiene le liste per i menu della scheda attività. Una lista può
  avere un `parent` (i tipi di attività di ogni servizio) e le sue sorgenti
  un `when` (le sedi solo per i servizi).

**Importazione** (`import`): `clients`, `contacts`, `todos`.
- `key` è il codice esterno: ripetere il download non crea doppioni, e
  quando c'è un conflitto vincono le modifiche fatte nell'app.
- `skipNew` scarta i record chiusi che l'app non ha ancora.
- `fields` precompila i campi del plugin nelle attività che nascono da quella
  voce.

**Campi dell'attività** (`entryFields`): una lista (`list`, con `parent` e
`filter`) o un valore semplice (`type: "bool"`). Un campo può essere
`required` o `hidden`, anche in base a un'espressione
(`{ "eq": ["@todo", null] }`). Se manca un campo obbligatorio l'attività non
viene inviata, e il resoconto dice quale campo manca.

**Invio** (`export`):
- `group.by` decide quanti record mandare: uno per attività (`entry`), per
  giorno (`day`), per blocco di lavoro (`dayBlock`) o per tratto
  (`segment`). Il viaggio va nel blocco più vicino (`nearestBlock`), in un
  record suo (`separate`) o non viene inviato (`ignore`).
- `body` è il corpo JSON. Le chiavi con valore `null` vengono omesse.
- `create`, `update` e `delete` sono le chiamate. L'app ricorda l'id remoto e
  l'impronta del corpo inviato: quello che non cambia non viene rinviato.

**Pianificazione** (`schedule`): invio alla chiusura (chiedi / sempre / mai,
solo voci da gestionale o tutte, sincronizzazione completa o solo
l'attività) e download periodico (da 15 minuti a un giorno).

## 4. Provare il plugin

1. Copia il file in `plugins/` nella cartella dati dell'app (accanto a
   `backup`). L'app lo legge all'avvio e a ogni ritorno in primo piano.
2. Se c'è un errore arriva la notifica **"Plugin … rifiutato"** con percorso
   e riga: correggi il file e riapri l'app.
3. Scheda azienda → **Gestionale** → **Connettore**: scegli il plugin e
   compila le opzioni (quelle con `*` sono obbligatorie).
4. Comincia dal download: **Sincronizza ora** e leggi il resoconto (liste,
   clienti, voci da fare).
5. Solo dopo aggiungi `export` e prova l'invio **su un ambiente di prova**:
   crea un'attività, chiudila, invia, poi modificala ed eliminala e
   controlla nel gestionale che il record venga aggiornato e poi cancellato.

## 5. Firma

Con una policy firmata in vigore anche ogni plugin deve essere firmato con
lo stesso nome (CN):

```sh
scripts/sign-policy.sh antares.json chiave.pem certificato.pem [catena.pem]
```

Lo script scrive `antares.json.sig`, da copiare accanto al plugin. Firma il
file definitivo: qualunque modifica successiva, anche uno spazio, invalida
la firma.

## 6. Pubblicare

I plugin pubblicati stanno nella cartella `plugins/` di questo repository,
con una riga nella tabella di [`README.md`](README.md). Non includere
documentazione riservata del gestionale (il repository è pubblico).
