# RadioLocal

Una piccola radio desktop con estensioni intelligenti, gestita via audio stream locale.

**Versione:** 1.0.0-SNAPSHOT  
**Lingua:** Italiano (predefinita per le impostazioni e le TTS)  
**Licenza:** Proprietaria (claudiosoft)

---

## 1. Descrizione del Progetto

**RadioLocal** è un'applicazione desktop Java costruita per replicare l'esperienza di una radio locale. Le sue funzioni principali includono:

*   **Streaming Audio:** Gestione nativa di flussi MP3 e OGG tramite `com.googlecode.soundlibs`.
*   **Sintesi Vocale (TTS):** Due implementazioni disponibili:
    *   **MaryTTS:** Utilizza il motore del progetto DFKI (in-process per l'audio).
    *   **eSpeak:** Opzione alternativa per la sintesi vocale (nativo).
*   **Comunicazione:** La radio non è solo musica. È progettata per annunciare:
    *   Le nuove tracce (nome e album).
    *   Curiosità sull'artista (API Wikipedia).
    *   La situazione meteo (OpenWeather API).
    *   Le notizie di tendenza (News API).
*   **Gestione Richieste:** L'utente può scegliere il proprio brano tramite una finestra dialoguale o tramite hot-key.
*   **Controllo Global:** Tasti di sistema (via `JNativeHook`) per pause, volume, skip e richiesta.
*   **Tray Icon:** Icona in taskbar con notifiche (balloons) per gli annunci.

---

## 2. Architettura e Dipendenze

### Struttura del Progetto (Maven)
Il progetto utilizza Maven e Java 17. La struttura principale segue uno standard `claudiosoft`.

### Dipendenze Principali

*   **Audio (Playback e Decodifica):**
    *   `mp3spi` / `jlayer` / `vorbisspi`: Decodifica MP3/Ogg.
    *   `jaudiotagger`: Lettura metadati ID3/OGG dai file locali.
    *   `sqlite-jdbc`: Persistenza dei dati.
*   **TTS (Sintesi Vocale):**
    *   `marytts-runtime` / `marytts-lang-it`: TTS locale di alta qualità.
    *   `voice-cmu-slt-hsmm` / `voice-istc-lucia-hsmm` (Dipendenze Mary).
    *   `jna`: Utilizzata indirettamente per eSpeak (o nativo su Linux).
*   **UI e Interfacce:**
    *   `swing`: Per finestre e dialoghi.
    *   `jnativehook`: Per hot-key globali (tasti ESC combinati).
    *   `tray`: Icona di sistema.
*   **Utility e Logging:**
    *   `slf4j` / `logback`: Logging (file locale).
    *   `jackson-databind`: Parsing API JSON.
    *   `commons-io`: File I/O.
    *   `ctutils` (Dipendenza interna): Utilità generiche (Config, Logger, Exception).

---

## 3. Componenti Chiave e Logica

### `RadioLocal` (Main)
Punto di ingresso.
*   Carica la configurazione (`radiolocal.cfg`).
*   Avvia lo scanner della libreria (`LibraryScanner`).
*   Imposta il logger.
*   Avvia il servizio principale (`RadioStationService`) in modo asincrono (thread dedicato).
*   Registra un `shutdown-hook` per pulire risorse.

### `RadioStationService` (Core)
È il motore del sistema.
*   **Gestione Coda:** Tiene traccia delle richieste (`BlockingQueue`).
*   **Ciclo di Gioco (PlayLoop):**
    1.  **Forza (Esc+Left):** Predisponga la traccia per il prossimo brano.
    2.  **Richiesta:** Prende la prossima traccia dalla coda.
    3.  **Shuffle:** Prende un brano casuale dalla libreria.
*   **Speaker:** Costruisce il testo da dire e invia il `Future` al `MarySpeaker`/`EspeakSpeaker`.
*   **Hotkey Wrapper:** Delega le interazioni (volume, pausa) a `GlobalHotkeyManager`.
*   **Tray Icon:** Aggiorna lo status e le notifiche.

### `AnnouncementEngine` + Provider
Il cervello dei messaggi vocali.
*   **Logic:** Decide se fare un annuncio (probabilistico) e seleziona il provider (Meteo, Artist, News).
*   **Provider:**
    *   `ArtistInfoProvider`: Chiama Wikipedia API.
    *   `WeatherProvider`: Chiama OpenWeather API.
    *   `NewsProvider`: Chiama NewsAPI.

### `JavaSoundAudioPlayer`
Gestisce il playback MP3/OGG e le voci TTS.
*   Supporta pause, volume, stop.
*   Converte il TTS in WAV tramite MaryTTS.
*   Utilizza `SourceDataLine` per la gestione del mixer.

### `GlobalHotkeyManager`
Usa `JNativeHook` per intercettare tasti globali.
*   Gestisce combinazioni tipo `ESC + UP` (Volume up) o `ESC + H` (Help).
*   Supporta anche `Ctrl + Shift` se configurato.

---

## 4. Configurazione e Setup

La configurazione avviene tramite il file `radiolocal.cfg`.

### Esempio di `radiolocal.cfg`

```properties
# -------------------------------------------------
#  Main
# -------------------------------------------------
[main]
root = E:\Music
 
startVolume = 50
volumeStep = 5

enableSpeaker = true
speakerType = marytts
enableHotKeys = true

# -------------------------------------------------
#  Logger
# -------------------------------------------------
[logger]
file = radiolocal.log
level = NORMAL

# -------------------------------------------------
#  TTS –  eSpeak (locale, binario “light”)
# -------------------------------------------------
[espeak]
# Percorso assoluto o relativo dell’eseguibile espeak (se non è nel PATH)
path = "C:\Program Files\eSpeak NG\espeak-ng.exe"

# Voce da usare (it, en-us, …). Il formato è quello di eSpeak:
#   <lang>+<variant>   (es. it+f2 = voce femminile italiana)
voice = it+f2

# Velocità parole (default 130; valori 80‑450)
speed = 130

# -------------------------------------------------
#  TTS –  MaryTTS (server locale HTTP)
# -------------------------------------------------
[marytts]
# URL del server MaryTTS (di default è il server che parte con “java -jar marytts-5.2.jar”)
url = http://localhost:59125/process

# Voce da usare (es. it_IT+marc oppure en_US+alan)
voice = istc-lucia-hsmm

# Tipo di output (WAVE_FILE è quello più semplice da riprodurre)
output = audio/WAVE_FILE


# -----------------------------------------------------------------
#  Global announcements
# -----------------------------------------------------------------
global_min_interval = 600

# -----------------------------------------------------------------
#  Meteo (OpenWeatherMap)
# -----------------------------------------------------------------
[weather]
enabled = true
location = Arezzo,IT          
apiKey = ************
interval = 3600

# -----------------------------------------------------------------
#  Artist info
# -----------------------------------------------------------------
[artist]
enabled = false

# -----------------------------------------------------------------
#  News
# -----------------------------------------------------------------
[news]
enabled = false

```

### Avvio
```bash
mvn clean package
java -jar target/RadioLocal-1.0.0-SNAPSHOT.jar
```

---

## 5. Guide per Sviluppatori

### Compilazione
Se si modifica il codice sorgente (es. `src/main/java`), è necessario eseguire:
```bash
mvn clean install
```
Il build utilizza Java 17 (Maven Compiler Plugin).

### Struttura del Codice
Il codice è modulare e diviso per responsabilità.
*   **Controller:** `RadioStationService`, `GlobalHotkeyManager`.
*   **Service:** `LibraryScanner`, `PlaylistManager`.
*   **Model:** `Track`, `Artist`, `Album`, `Library`.
*   **Speaker:** `MarySpeaker`, `EspeakSpeaker`, `SpeakerEngine`.
*   **UI:** `TrayIconManager`, `RequestDialog`.
*   **Utilità:** `BasicLogger`, `CTException`, `Config`.

### Debug
Per risolvere problemi di audio o libreria:
1.  Controllare il logger di default: `radiolocal.log`.
2.  Verificare che le API (`https://api.openweathermap.org...`) siano raggiungibili.
3.  Verificare che la libreria locale (`C:\Music`) sia leggibile.
4.  Controllare i permessi di Elevation se richiesto per `JNativeHook`.

### Modifica delle Probabilità di Annuncio
Modificare `AnnouncementEngine.java` per cambiare i valori di probabilità nei filtri `if (roll < ...)` (attualmente Meteo 15%, Artist 25%, News 10%, Null 50%).
