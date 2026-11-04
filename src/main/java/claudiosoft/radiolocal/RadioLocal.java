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
import claudiosoft.radiolocal.tray.TrayIconManager;
import claudiosoft.radiolocal.ui.RequestDialog;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.swing.SwingUtilities;
import marytts.exceptions.MaryConfigurationException;

/**
 *
 * @author claudio.tortorelli
 */
public class RadioLocal {

    public static void main(String[] args) throws CTException, IOException, SQLException, MaryConfigurationException {

        System.setProperty("java.awt.headless", "false");

        Config radioConfig = new Config(new File("radiolocal.cfg"), false);

        File logFile = new File(radioConfig.get("logger", "file", "radiolocal.log"));
        LogLevel logLevel = LogLevel.valueOf(radioConfig.get("logger", "level", "NORMAL"));
        Radio.logger = BasicLogger.get(logLevel, "RadioLocal", logFile);

        Path rootPath = new File(radioConfig.get("main", "root")).toPath();
        LibraryScanner scanner = new LibraryScanner(rootPath);
        Library library = scanner.scan();

        PlaylistManager playlist = new PlaylistManager(library.getAllTracks());

        TrayIconManager trayManager = new TrayIconManager();

        AudioPlayer player = new JavaSoundAudioPlayer();

        SpeakerEngine speaker = null;
        boolean enableSpeaker = radioConfig.get("main", "enableSpeaker", "true").equalsIgnoreCase("true");
        if (enableSpeaker) {
            String speakerType = radioConfig.get("main", "speakerType", "marytts");
            if (speakerType.equalsIgnoreCase("marytts")) {
                speaker = new MarySpeaker(player, radioConfig);
            } else if (speakerType.equalsIgnoreCase("espeak")) {
                speaker = new EspeakSpeaker(player, radioConfig);
            }
        }

        RadioStationService service = new RadioStationService(radioConfig, playlist, player, speaker, trayManager, library);
        service.start();

        trayManager.addMenuItem("Richiesta", e -> SwingUtilities.invokeLater(() -> {
            RequestDialog dlg = new RequestDialog(library, service);
            dlg.setVisible(true);
        }));

        boolean enableHotKeys = radioConfig.get("main", "enableHotKeys", "true").equalsIgnoreCase("true");
        if (enableHotKeys) {
            GlobalHotkeyManager hotkeyManager = new GlobalHotkeyManager(service, radioConfig);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Radio.logger.info("Chiusura dell'applicazione, rimozione hot key e shutdown servizio");
                hotkeyManager.shutdown();
            }));
        }

        try {
            Thread.currentThread().join();          // blocca il thread principale
        } catch (InterruptedException e) {
            // Interruzione provvisoria: procede con la chiusura
            Thread.currentThread().interrupt();
        }
    }
}
