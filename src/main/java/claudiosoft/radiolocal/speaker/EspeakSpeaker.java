package claudiosoft.radiolocal.speaker;

import claudiosoft.commons.BasicLogger;
import claudiosoft.commons.CTException;
import claudiosoft.commons.Config;
import claudiosoft.radiolocal.Radio;
import claudiosoft.radiolocal.model.Track;
import claudiosoft.radiolocal.player.AudioPlayer;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * TTS basato su eSpeak (binario nativo locale).
 *
 * <p>
 * Le impostazioni sono lette da una sezione <b>[espeak]</b> del file di
 * configurazione:
 * <ul>
 * <li><b>path</b> – percorso dell’eseguibile (default “espeak”) </li>
 * <li><b>voice</b> – voce da usare (es. “it+f2”) </li>
 * <li><b>speed</b> – velocità parole (default 130) </li>
 * </ul>
 * </p>
 */
public class EspeakSpeaker implements SpeakerEngine {

    private static final BasicLogger LOG = Radio.logger;

    private final AudioPlayer player;
    private final ExecutorService executor;
    private final Path tmpDir;

    private final String espeakCmd;   // percorso dell’eseguibile
    private final String voice;
    private final int speed;          // velocità parole

    /**
     * Crea un nuovo speaker basato su eSpeak.
     *
     * @param player player usato per la riproduzione audio
     * @param cfg configurazione già caricata dall’applicazione
     * @throws IOException se non è possibile creare la cartella temporanea
     * @throws CTException se la lettura della configurazione fallisce
     */
    public EspeakSpeaker(AudioPlayer player, Config cfg) throws IOException, CTException {
        this.player = player;
        this.executor = Executors.newSingleThreadExecutor();

        // -------------------------------------------------
        // 1) cartella temporanea per i wav generati
        // -------------------------------------------------
        this.tmpDir = Files.createTempDirectory("espeak-tts");
        LOG.info("EspeakSpeaker: cartella temporanea = " + tmpDir);

        // -------------------------------------------------
        // 2) lettura impostazioni da file .cfg
        // -------------------------------------------------
        this.espeakCmd = cfg.get("espeak", "path", "espeak");
        this.voice = cfg.get("espeak", "voice", "it+f2");
        String sSpeed = cfg.get("espeak", "speed", "130");
        int tmpSpeed;
        try {
            tmpSpeed = Integer.parseInt(sSpeed);
        } catch (NumberFormatException e) {
            LOG.warn("Valore speed non valido in config: " + sSpeed + ". Uso 130.");
            tmpSpeed = 130;
        }
        this.speed = tmpSpeed;
    }

    @Override
    public Future<?> speak(final String text) {
        /*
         * Creiamo un FutureTask che esegue la sintesi in modo **sincrono**.
         * Il task viene avviato con run() (non submit) quindi il thread che
         * chiama speak() resta bloccato fino al completamento della sintesi
         * e della riproduzione del wav.
         */
        FutureTask<Void> ft = new FutureTask<Void>(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                synthesizeAndPlay(text);                // crea wav + avvia playback
                // Attende che il player termini di suonare il wav appena creato
                while (player.isPlaying()) {
                    Thread.sleep(100);
                }
                return null;
            }
        });
        ft.run();      // esegue il Callable nel thread corrente (bloccante)
        return ft;     // ft.isDone() sarà true al ritorno
    }

    /**
     * Genera il file wav con eSpeak e lo passa al player.
     *
     * @param text testo da sintetizzare
     * @throws IOException problemi di I/O
     * @throws InterruptedException processo interrotto
     */
    private void synthesizeAndPlay(String text) throws IOException, InterruptedException {
        // 1) file wav temporaneo
        Path wavFile = Files.createTempFile(tmpDir, "speech-", ".wav");

        // 2) comando espeak
        ProcessBuilder pb = new ProcessBuilder(
                espeakCmd,
                "-w", wavFile.toString(),
                "-v", voice,
                "-s", Integer.toString(speed),
                text);
        pb.redirectErrorStream(true);

        LOG.debug("Esegue: " + String.join(" ", pb.command()));

        // 3) avvia il processo
        Process proc = pb.start();

        // Timeout di 10 s per evitare blocchi in caso di errori
        boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            LOG.warn("eSpeak non termina entro timeout – kill");
            return;
        }
        int rc = proc.exitValue();
        if (rc != 0) {
            LOG.error("eSpeak terminato con codice di errore: " + rc);
            return;
        }

        // 4) riproduci tramite player (il playback è asincrono)
        Track ttsTrack = new Track(
                wavFile.toFile(),
                "Speech",
                "System",
                "TTS",
                -1);               // durata ignota

        LOG.debug("Riproduco wav TTS: " + wavFile);
        player.play(ttsTrack);

        // 5) pulizia (cancella al termine della JVM)
        wavFile.toFile().deleteOnExit();
    }

    @Override
    public void stop() {
        // Interrompe eventuali attività pendenti
        executor.shutdownNow();
    }
}
