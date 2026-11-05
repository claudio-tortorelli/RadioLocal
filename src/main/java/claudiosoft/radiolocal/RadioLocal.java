package claudiosoft.radiolocal;

import claudiosoft.commons.BasicLogger;
import claudiosoft.commons.BasicLogger.LogLevel;
import claudiosoft.commons.CTException;
import claudiosoft.commons.Config;
import claudiosoft.radiolocal.hotkey.GlobalHotkeyManager;
import claudiosoft.radiolocal.model.Library;
import claudiosoft.radiolocal.player.AudioPlayer;
import claudiosoft.radiolocal.player.JavaSoundAudioPlayer;
import claudiosoft.radiolocal.player.PlaylistManager;
import claudiosoft.radiolocal.scanner.LibraryScanner;
import claudiosoft.radiolocal.service.RadioStationService;
import claudiosoft.radiolocal.speaker.EspeakSpeaker;
import claudiosoft.radiolocal.speaker.MarySpeaker;
import claudiosoft.radiolocal.speaker.SpeakerEngine;
import claudiosoft.radiolocal.speaker.announcement.AnnouncementEngine;
import claudiosoft.radiolocal.speaker.announcement.AnnouncementProvider;
import claudiosoft.radiolocal.speaker.announcement.ArtistInfoProvider;
import claudiosoft.radiolocal.speaker.announcement.NewsProvider;
import claudiosoft.radiolocal.speaker.announcement.WeatherProvider;
import claudiosoft.radiolocal.tray.TrayIconManager;
import claudiosoft.radiolocal.ui.RequestDialog;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import marytts.exceptions.MaryConfigurationException;

/**
 *
 * @author claudio.tortorelli
 */
public class RadioLocal {

    // --- Componenti Statiche Globali ---
    public static BasicLogger logger;
    private static Config config;
    private static Library library;
    private static PlaylistManager playlist;
    private static TrayIconManager trayManager;
    private static AudioPlayer player;
    private static SpeakerEngine speaker;
    private static AnnouncementEngine announcementEngine;
    private static RadioStationService service;

    public static void main(String[] args) {
        try {
            System.setProperty("java.awt.headless", "false");

            // Inizializzazione di tutti i componenti
            init();

            // Avvio del servizio radio
            service.start();

            // Configurazione Menu System Tray
            addSystemTrayMenu();

            // Gestione HotKeys
            setupHotKeys();

            // Blocca il thread principale per mantenere l'app attiva
            Thread.currentThread().join();

        } catch (CTException | IOException | SQLException | MaryConfigurationException e) {
            // Se il logger è già attivo, lo usiamo, altrimenti usiamo l'err standard
            if (logger != null) {
                logger.error("Errore fatale durante l'avvio dell'applicazione", e);
            } else {
                e.printStackTrace();
            }
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Metodo di inizializzazione che istanzia tutte le dipendenze del progetto.
     */
    private static void init() throws CTException, IOException, SQLException, MaryConfigurationException {
        // 1. Configurazione e Logger
        config = new Config(new File("radiolocal.cfg"), false);
        File logFile = new File(config.get("logger", "file", "radiolocal.log"));
        LogLevel logLevel = LogLevel.valueOf(config.get("logger", "level", "NORMAL"));
        logger = BasicLogger.get(logLevel, "RadioLocal", logFile);

        // 2. Libreria e Playlist
        Path rootPath = new File(config.get("main", "root")).toPath();
        LibraryScanner scanner = new LibraryScanner(rootPath);
        library = scanner.scan();
        playlist = new PlaylistManager(library.getAllTracks());

        // 3. UI e Audio
        trayManager = new TrayIconManager();
        player = new JavaSoundAudioPlayer();

        // 4. Speaker TTS (MaryTTS o eSpeak)
        boolean enableSpeaker = config.get("main", "enableSpeaker", "true").equalsIgnoreCase("true");
        if (enableSpeaker) {
            String speakerType = config.get("main", "speakerType", "marytts");
            if (speakerType.equalsIgnoreCase("marytts")) {
                speaker = new MarySpeaker(player, config);
            } else if (speakerType.equalsIgnoreCase("espeak")) {
                speaker = new EspeakSpeaker(player, config);
            }
        }

        // 5. Motore Annunci
        List<AnnouncementProvider> providers = new ArrayList<>();

        if (config.get("weather", "enabled", "true").equalsIgnoreCase("true")) {
            providers.add(new WeatherProvider(
                    config.get("weather", "apiKey", ""),
                    config.get("weather", "location", "Roma,IT"),
                    config.getLong("weather", "interval", "3600")
            ));
        }

        if (config.get("artist", "enabled", "true").equalsIgnoreCase("true")) {
            providers.add(new ArtistInfoProvider(
                    config.getLong("announcements", "artist_ttl", "3600")
            ));
        }

        if (config.get("news", "enabled", "true").equalsIgnoreCase("true")) {
            providers.add(new NewsProvider(
                    config.get("announcements", "news_api_key", ""),
                    config.getLong("announcements", "news_interval", "3600")
            ));
        }

        long globalMinInterval = config.getLong("announcements", "global_min_interval", "600");
        announcementEngine = new AnnouncementEngine(providers, globalMinInterval);

        // 6. Servizio Radio (Il coordinatore)
        service = new RadioStationService(config, playlist, player, speaker, trayManager, library, announcementEngine);
    }

    /**
     * Configura le HotKeys globali e il relativo shutdown hook.
     */
    private static void setupHotKeys() throws CTException {
        boolean enableHotKeys = config.get("main", "enableHotKeys", "true").equalsIgnoreCase("true");
        if (enableHotKeys) {
            final GlobalHotkeyManager hotkeyManager = new GlobalHotkeyManager(service, config);

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    logger.info("Chiusura dell'applicazione, rimozione hot key e shutdown servizio");
                    hotkeyManager.shutdown();
                }
            }));
        }
    }

    /**
     * Aggiunge le voci di menu alla System Tray utilizzando classi anonime (no
     * lambda).
     */
    public static void addSystemTrayMenu() {
        // Volume +
        trayManager.addMenuItem("Volume +", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    service.increaseVolume();
                } catch (CTException ex) {
                    logger.error("Errore volume+", ex);
                }
            }
        });

        // Volume -
        trayManager.addMenuItem("Volume -", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    service.decreaseVolume();
                } catch (CTException ex) {
                    logger.error("Errore volume-", ex);
                }
            }
        });

        trayManager.addMenuItem("--------------------", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
            }
        });

        // Playback
        trayManager.addMenuItem("Brano Successivo", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.nextTrack();
            }
        });

        trayManager.addMenuItem("Brano Precedente", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.previousTrack();
            }
        });

        trayManager.addMenuItem("Pausa / Riprendi", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.togglePause();
            }
        });

        trayManager.addMenuItem("Richiesta", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        RequestDialog dlg = new RequestDialog(library, service);
                        dlg.setVisible(true);
                    }
                });
            }
        });

        trayManager.addMenuItem("--------------------", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
            }
        });

        // Annunci Forzati
        trayManager.addMenuItem("Forza Meteo", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.forceWeather();
            }
        });

        trayManager.addMenuItem("Forza Info Artista", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.forceArtistInfo();
            }
        });

        trayManager.addMenuItem("Forza News", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.forceNews();
            }
        });

        trayManager.addMenuItem("Informazioni", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                service.info();
            }
        });

        trayManager.addMenuItem("--------------------", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
            }
        });

        // Esci
        trayManager.addMenuItem("Esci", new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                logger.info("Richiesta di chiusura dalla TrayIcon");
                System.exit(0);
            }
        });
    }
}
