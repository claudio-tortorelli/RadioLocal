package claudiosoft.radiolocal.hotkey;

import claudiosoft.commons.BasicLogger;
import claudiosoft.commons.CTException;
import claudiosoft.commons.Config;
import claudiosoft.radiolocal.Radio;
import claudiosoft.radiolocal.service.RadioStationService;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.util.logging.Level;

/**
 * Gestisce i tasti di controllo (global).
 */
public class GlobalHotkeyManager implements NativeKeyListener {

    private static final BasicLogger LOG = Radio.logger;
    private final RadioStationService service;

    /* --------------------------------------------------------------
     * Stato interno per riconoscere le combinazioni ESC+qualcosa.
     * -------------------------------------------------------------- */
    private volatile boolean escPressed = false; // ESC è attualmente tenuto
    private volatile boolean escComboUsed = false; // è già stato usato in combinazione

    /**
     * Costruttore. Registra il native hook e stampa a log le combinazioni
     * supportate.
     */
    public GlobalHotkeyManager(RadioStationService service, Config config) throws CTException {
        this.service = service;
        try {
            /* Disattiva i log di JNativeHook (troppi) */
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.OFF);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            LOG.info("Hot key globali attivati");

            printHotKeys();
        } catch (NativeHookException e) {
            LOG.error("Impossibile registrare il native hook", e);
        }
    }

    public static void printHotKeys() {
        /* Log delle hot‑key disponibili */
        LOG.info("=== Hot key disponibili (prefisso ESC) ===");
        LOG.info("  ESC + Up     -> volume +");
        LOG.info("  ESC + Down   -> volume -");
        LOG.info("  ESC + Right  -> brano successivo");
        LOG.info("  ESC + Left   -> brano precedente");
        LOG.info("  ESC + P   -> pausa / riprendi");
        LOG.info("  ESC + I   -> informazioni / stato corrente");
        LOG.info("  ESC + W   -> forza meteo");
        LOG.info("  ESC + A   -> forza info artista");
        LOG.info("  ESC + N   -> forza news");
        LOG.info("  ESC + H   -> visualizza questo help");
        LOG.info("  ESC + Q   -> exit");
    }

    /* --------------------------------------------------------------
     *  EVENTI NATIVI
     * -------------------------------------------------------------- */
    @Override
    public void nativeKeyPressed(NativeKeyEvent ev) {

        /*-----------------------------------------------------------
         * Gestione del prefisso ESC.
         *-----------------------------------------------------------*/
        if (ev.getKeyCode() == NativeKeyEvent.VC_ESCAPE) {
            escPressed = true;
            escComboUsed = false;               // inizio di un nuovo ciclo ESC
            LOG.debug("ESC premuto (prefisso attivo)");
            // Non eseguiamo ancora alcuna azione: potrebbe trattarsi di ESC solo.
            return;
        }

        /*-----------------------------------------------------------
         * Se ESC è tenuto oppure ctrl+shift sono attivi, interpretiamo
         * il tasto corrente come parte di una hot‑key.
         *-----------------------------------------------------------*/
        if (escPressed /*|| isCtrlShift(ev)*/) { //TODO valutare se reintrodurre una combo come ctrl+shift
            escComboUsed = true;                // segnala che ESC non è “solo”
            try {
                handleCombo(ev);
            } catch (CTException ex) {

            }
        }
        // Altrimenti (nessun prefisso) il gestore resta in silenzio: non
        // vogliamo hot‑key “globali” su frecce o P/H raw.
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent ev) {

        /*-----------------------------------------------------------
         * Rilascio di ESC -> decidiamo se era “solo” oppure usato in una
         * combinazione.
         *-----------------------------------------------------------*/
        if (ev.getKeyCode() == NativeKeyEvent.VC_ESCAPE) {
            escPressed = false;
            if (escComboUsed) {
                LOG.debug("ESC rilasciato dopo combinazione -> nessuna azione singola");
            }
        }
        // Non è necessario tracciare Ctrl/Shift perché i loro bit compaiono
        // già nei prossimi eventi (ev.getModifiers()).
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent ev) {
        // Non necessario per la logica di hot‑key.
    }

    /* --------------------------------------------------------------
     *  METODI DI SUPPORTO
     * -------------------------------------------------------------- */
    /**
     * Gestione delle combinazioni riconosciute (ESC+… o Ctrl+Shift+…).
     */
    private void handleCombo(NativeKeyEvent ev) throws CTException {
        switch (ev.getKeyCode()) {
            case NativeKeyEvent.VC_UP -> {
                LOG.debug("Hot‑key: ESC+UP -> volume +");
                service.increaseVolume();
            }
            case NativeKeyEvent.VC_DOWN -> {
                LOG.debug("Hot‑key: ESC+DOWN -> volume -");
                service.decreaseVolume();
            }
            case NativeKeyEvent.VC_RIGHT -> {
                LOG.debug("Hot‑key: ESC+RIGHT -> brano successivo");
                service.nextTrack();
            }
            case NativeKeyEvent.VC_LEFT -> {
                LOG.debug("Hot‑key: ESC+LEFT -> brano precedente");
                service.previousTrack();
            }
            case NativeKeyEvent.VC_P -> {
                LOG.debug("Hot‑key: ESC+P -> pausa/riprendi");
                service.togglePause();
            }
            case NativeKeyEvent.VC_I -> {
                LOG.debug("Hot‑key: ESC+I -> informazioni");
                service.info();
            }
            case NativeKeyEvent.VC_H -> {
                LOG.debug("Hot‑key: ESC+H -> help");
                printHotKeys();
            }
            case NativeKeyEvent.VC_Q -> {
                LOG.debug("Hot‑key: ESC+Q -> Quit");
                service.handleEsc();
            }
            case NativeKeyEvent.VC_W -> {
                LOG.debug("Hot‑key: ESC+W -> Forza Meteo");
                service.forceWeather();
            }
            case NativeKeyEvent.VC_A -> {
                LOG.debug("Hot‑key: ESC+A -> Forza Info Artista");
                service.forceArtistInfo();
            }
            case NativeKeyEvent.VC_N -> {
                LOG.debug("Hot‑key: ESC+N -> Forza News");
                service.forceNews();
            }
        }
    }

    /**
     * Ritorna true se l'evento contiene contemporaneamente i modificatori Ctrl
     * e Shift.
     */
    private boolean isCtrlShift(NativeKeyEvent ev) {
        int mod = ev.getModifiers();
        return (mod & NativeKeyEvent.CTRL_MASK) != 0
                && (mod & NativeKeyEvent.SHIFT_MASK) != 0;
    }

    /**
     * Rimuove il native hook (chiamato tipicamente dallo shutdown hook).
     */
    public void shutdown() {
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            LOG.error("Errore nella rimozione del native hook", e);
        }
    }
}
