# NewPipe External API - Guida per Kodular

Questa guida spiega come utilizzare l'API esterna di NewPipe per cercare video e ottenere le URL audio/video da un'applicazione Kodular.

## Panoramica

NewPipe ora espone un'API che permette ad applicazioni esterne di:
- Eseguire ricerche su YouTube e altri servizi supportati
- Ottenere le URL dei video (sia audio che video)
- Ricevere i risultati in formato JSON

## Metodi Disponibili

### 1. Metodo BroadcastReceiver (Semplice)

Utilizza un sistema di broadcast per comunicare con NewPipe.

**Vantaggi:**
- Semplice da implementare
- Non richiede permessi speciali

**Svantaggi:**
- Meno robusto in caso di errori
- Asincrono senza garanzia di consegna

### 2. Metodo Service (Consigliato)

Utilizza un servizio Android per una comunicazione più affidabile.

**Vantaggi:**
- Più robusto e affidabile
- Supporta timeout e gestione errori
- Permette limitare il numero di risultati

**Svantaggi:**
- Leggermente più complesso da implementare

## Implementazione in Kodular

### Metodo 1: BroadcastReceiver

In Kodular, puoi utilizzare i blocchi "Broadcast" per comunicare con NewPipe:

1. **Invia la richiesta di ricerca:**
   - Usa il blocco "Send Broadcast"
   - Action: `org.schabi.newpipe.SEARCH_REQUEST`
   - Extra:
     - `query`: la tua parola di ricerca
     - `service_id`: 0 per YouTube (opzionale)
     - `result_receiver`: il nome del tuo pacchetto (opzionale)

2. **Ricevi i risultati:**
   - Usa il blocco "When Broadcast Received"
   - Action: `org.schabi.newpipe.SEARCH_RESPONSE`
   - Controlla l'extra `success` (true/false)
   - Se success=true, leggi l'extra `results` (JSON string)
   - Se success=false, leggi l'extra `error`

### Metodo 2: Service (più avanzato)

Per il metodo service, potresti aver bisogno di componenti aggiuntivi in Kodular o utilizzare l'extension Taifun.

## Formato dei Risultati

I risultati vengono restituiti in formato JSON array:

```json
[
  {
    "title": "Titolo del video",
    "url": "https://youtube.com/watch?v=...",
    "thumbnailUrl": "https://i.ytimg.com/vi/.../hqdefault.jpg",
    "uploaderName": "Nome del canale",
    "duration": 300,
    "viewCount": 1000000,
    "uploadDate": "2023-12-01"
  }
]
```

**Campi disponibili:**
- `title`: Titolo del video
- `url`: URL del video (quello che ti serve)
- `thumbnailUrl`: URL dell'immagine di anteprima
- `uploaderName`: Nome del canale che ha caricato il video
- `duration`: Durata in secondi
- `viewCount`: Numero di visualizzazioni
- `uploadDate`: Data di caricamento

## Esempio Pratico

### Ricerca semplice con BroadcastReceiver:

```
Quando Button1 viene cliccato:
  Invia Broadcast con:
    Action: "org.schabi.newpipe.SEARCH_REQUEST"
    Extra "query": Textbox1.Text
    Extra "service_id": 0  // YouTube
    Extra "result_receiver": "com.tuopacchetto"

Quando Broadcast ricevuto con Action "org.schabi.newpipe.SEARCH_RESPONSE":
  Se extra "success" = true:
    Imposta Label1.Text = "Trovati risultati!"
    Imposta Label2.Text = extra "results"  // JSON con i risultati
  Altrimenti:
    Imposta Label1.Text = "Errore: " + extra "error"
```

### Elaborazione dei risultati:

Dopo aver ricevuto il JSON, puoi elaborarlo per estrarre le URL:

```
Quando ricevi JSON results:
  Usa blocco "Json Text Decode" per parsare il JSON
  Per ogni elemento nell'array:
    Estrai "url" e aggiungilo a una lista
    Mostra "title" e "url" in una ListView
```

## Codici Servizio

- `0`: YouTube
- `1`: SoundCloud
- `2`: Media CCC
- `3`: PeerTube
- `4`: Bandcamp

## Note Importanti

1. **Permessi:** NewPipe ha già i permessi necessari per accedere a internet
2. **Timeout:** Le ricerche possono richiedere alcuni secondi
3. **Limitazioni:** Rispetta i termini di servizio delle piattaforme
4. **Errori:** Gestisci sempre i casi di errore (network, parsing, etc.)

## Esempio di Codice Java (per riferimento)

Se preferisci sviluppare un'estensione Kodular in Java:

```java
// Metodo semplice con broadcast
NewPipeSearchAPI.searchWithBroadcast(context, "musica italiana", 
    new NewPipeSearchAPI.SearchCallback() {
        @Override
        public void onSuccess(List<VideoResult> results) {
            for (VideoResult result : results) {
                Log.d("RESULT", result.title + ": " + result.url);
            }
        }
        
        @Override
        public void onError(String error) {
            Log.e("ERROR", "Search failed: " + error);
        }
    });

// Metodo robusto con service
NewPipeSearchAPI.searchWithService(context, "musica italiana", 
    NewPipeSearchAPI.SERVICE_YOUTUBE, 20,
    new NewPipeSearchAPI.SearchCallback() {
        // ... stessa implementazione
    });
```

## Troubleshooting

### Problemi comuni:

1. **Nessun risultato ricevuto:**
   - Controlla che NewPipe sia installato
   - Verifica l'Action nel broadcast
   - Controlla la connessione internet

2. **Errore di parsing:**
   - Verifica che il JSON sia valido
   - Controlla i nomi dei campi nell'extra

3. **Timeout:**
   - Le ricerche possono richiedere tempo
   - Implementa un indicatore di caricamento

4. **Permessi negati:**
   - NewPipe deve avere i permessi internet
   - L'app chiamante potrebbe aver bisogno di permessi per inviare broadcast

## Supporto

Per problemi o domande:
- Controlla i log di NewPipe per errori
- Verifica la configurazione del broadcast in Kodular
- Assicurati che le versioni siano compatibili
