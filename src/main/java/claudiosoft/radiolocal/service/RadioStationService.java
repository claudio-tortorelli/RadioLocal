package claudiosoft.radiolocal.service;

import claudiosoft.commons.BasicLogger;
import claudiosoft.commons.CTException;
import claudiosoft.commons.Config;
import claudiosoft.radiolocal.Radio;
import claudiosoft.radiolocal.model.Library;
import claudiosoft.radiolocal.model.Track;
import claudiosoft.radiolocal.player.AudioPlayer;
import claudiosoft.radiolocal.player.PlaylistManager;
import claudiosoft.radiolocal.speaker.SpeakerEngine;
import claudiosoft.radiolocal.speaker.announcement.*;
import claudiosoft.radiolocal.tray.TrayIconManager;
import java.awt.EventQueue;
import java.awt.TrayIcon;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinatore di tutti i componenti (playback, hot‑key, speaker, UI e coda
 * richieste).
 */
public class RadioStationService {

    private static final BasicLogger LOG = Radio.logger;

    private final Config config;
    private final PlaylistManager playlist;
    private final AudioPlayer player;
    private final SpeakerEngine speaker;
    private final TrayIconManager tray;
    private final Library library;                       // *** nuova ***
    private final BlockingQueue<Track> requestQueue = new LinkedBlockingQueue<>(); // *** nuova ***
    private final AnnouncementEngine announcementEngine;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    /* -------------------------------------------------------------
       Stato di pausa / forzatura brano
       ------------------------------------------------------------- */
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicReference<Track> forcedTrack = new AtomicReference<>(null);
    private final AtomicLong lastEsc = new AtomicLong(0);

    /* ------------------- Costruttori ------------------- */
    // 1) costruttore legacy (senza tray e senza library)
    public RadioStationService(Config config, PlaylistManager pl,
            AudioPlayer plr, SpeakerEngine speaker) throws CTException {
        this(config, pl, plr, speaker, null, null, null);
    }

    // 2) costruttore con tray ma senza library
    public RadioStationService(Config config, PlaylistManager pl,
            AudioPlayer plr, SpeakerEngine speaker,
            TrayIconManager tr) throws CTException {
        this(config, pl, plr, speaker, tr, null, null);
    }

    // 3) costruttore completo (inclusa la Library)
    public RadioStationService(Config config, PlaylistManager pl,
            AudioPlayer plr, SpeakerEngine speaker,
            TrayIconManager tr, Library lib, AnnouncementEngine engine) throws CTException {
        this.config = config;
        this.playlist = pl;
        this.player = plr;
        this.speaker = speaker; // può essere null (senza funzionalità richiesta)
        this.tray = tr;
        this.library = lib;
        this.announcementEngine = engine; // può essere null (senza funzionalità richiesta)
        this.player.setVolume(config.getInt("main", "startVolume", "50"));
    }

    /* ------------------- Interfaccia per le richieste ------------------- */
    /**
     * Inserisce la traccia nella coda delle richieste.
     */
    public void addRequestedTrack(Track track) {
        if (track != null) {
            requestQueue.offer(track);
            LOG.info("Traccia richiesta aggiunta in coda: " + track);
        }
    }

    /* ------------------- Avvio del servizio ------------------- */
    public void start() {
        exec.submit(this::playLoop);
    }

    private void playLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {

                /* -------------------------------------------------
                 * 1) Brano forzato (hot‑key “prev”) → priorità più alta
                 * ------------------------------------------------- */
                Track forced = forcedTrack.getAndSet(null);
                Track track;
                if (forced != null) {
                    track = forced;
                } else {
                    /* -------------------------------------------------
                     * 2) Brano dalla coda di richieste (se presente)
                     * ------------------------------------------------- */
                    track = requestQueue.poll();
                    if (track == null) {
                        /* -------------------------------------------------
                         * 3) Normal playback (shuffle)
                         * ------------------------------------------------- */
                        track = playlist.next();
                    }
                }

                /* -------------------------------------------------
                 * Aggiornamento UI della System‑Tray
                 * ------------------------------------------------- */
                updateTray("In riproduzione: " + track.getArtist() + " – " + track.getTitle());
                showBalloon(track);

                LOG.info("-- RIPRODOTTO: %s | %s (%s)".formatted(track.getArtist(), track.getTitle(), track.getAlbum()));

                if (announcementEngine != null && speaker != null) {
                    Optional<String> announcement = announcementEngine.maybeGenerate(track);
                    if (announcement.isPresent()) {
                        LOG.info("Generato annuncio automatico: " + announcement.get());
                        speaker.speak(announcement.get());
                        // Attendiamo che l'annuncio finisca prima di partire con la musica
                        while (player.isPlaying()) {
                            Thread.sleep(100);
                        }
                    }
                }

                if (speaker != null) {
                    speaker.speak("%s in %s".formatted(track.getArtist(), track.getTitle()));
                }

                player.play(track);

                /* -------------------------------------------------
                 * Attesa fine traccia o pausa utente
                 * ------------------------------------------------- */
                while (player.isPlaying() || paused.get()) {
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }

    /* ------------------- UI Tray ------------------- */
    private void showBalloon(Track track) {
        if (tray != null) {
            final String title = track.getTitle();
            final String msg = "Artist: " + track.getArtist();
            EventQueue.invokeLater(()
                    -> tray.displayMessage(title, msg, TrayIcon.MessageType.INFO));
        }
    }

    private void updateTray(String txt) {
        if (tray != null) {
            EventQueue.invokeLater(() -> tray.setTooltip(txt));
        }
    }

    /* ------------------- Controlli hot‑key ------------------- */
    public void increaseVolume() throws CTException {
        int vol = Math.min(100, player.getVolume() + config.getInt("main", "volumeStep", "5"));
        player.setVolume(vol);
        LOG.info("volume is %d".formatted(vol));
    }

    public void decreaseVolume() throws CTException {
        int vol = Math.max(0, player.getVolume() - config.getInt("main", "volumeStep", "5"));
        player.setVolume(vol);
        LOG.info("volume is %d".formatted(vol));
    }

    public void nextTrack() {
        player.stop();                // skip immediato
        LOG.info("next track");
    }

    public void previousTrack() {
        Track prev = playlist.previous();
        forcedTrack.set(prev);
        player.stop();
        LOG.info("previous track");
    }

    public void togglePause() {
        boolean wasPaused = paused.getAndSet(!paused.get());
        if (wasPaused) {
            player.resume();
            LOG.info("play (ripresa)");
        } else {
            player.pause();
            LOG.info("pause");
        }
    }

    public void handleEsc() {
        LOG.info("ESC + Q -> chiusura");
        shutdown();
    }

    public void info() {
        // eventuali dati di stato da mostrare
    }

    public void forceWeather() {
        triggerForcedAnnouncement(WeatherProvider.class);
    }

    public void forceArtistInfo() {
        triggerForcedAnnouncement(ArtistInfoProvider.class);
    }

    public void forceNews() {
        triggerForcedAnnouncement(NewsProvider.class);
    }

    private void triggerForcedAnnouncement(Class<? extends AnnouncementProvider> providerClass) {
        if (announcementEngine == null || speaker == null) {
            return;
        }

        // Recuperiamo la traccia corrente dal player (se possibile) o dalla playlist
        Track current = playlist.current();
        announcementEngine.forceAnnouncement(providerClass, current).ifPresent(text -> {
            LOG.info("Annuncio forzato via hotkey: " + text);
            speaker.speak(text);
        });
    }

    public void showWindow() {
        if (tray != null) {
            updateTray("Radio‑Station è attiva");
        }
    }

    public void shutdown() {
        LOG.info("Arresto della RadioStation");
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
        System.exit(0);
    }
}
