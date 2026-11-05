package claudiosoft.radiolocal.speaker;

import claudiosoft.commons.BasicLogger;
import claudiosoft.commons.CTException;
import claudiosoft.commons.Config;
import claudiosoft.radiolocal.RadioLocal;
import claudiosoft.radiolocal.model.Track;
import claudiosoft.radiolocal.player.AudioPlayer;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import javax.sound.sampled.*;
import marytts.LocalMaryInterface;
import marytts.exceptions.MaryConfigurationException;
import marytts.exceptions.SynthesisException;

/**
 * Speaker basato su MaryTTS.
 *
 * <p>
 * Alla prima istanza di {@link LocalMaryInterface} il metodo
 * {@code MaryRuntimeUtils.ensureMaryStarted()} avvia in‑processo il server
 * MaryTTS, quindi **non è necessario lanciare un processo esterno**. Il
 * costruttore legge la voce da configurare nella sezione <b>[marytts]</b>
 * del file di configurazione.</p>
 *
 * <p>
 * Il metodo {@code speak} è sincrono: sintetizza l’audio in un
 * {@link AudioInputStream}, lo scrive su file wav temporaneo e lo passa al
 * {@link AudioPlayer}. Dopo aver avviato il playback attende che il wav termini
 * prima di restituire.</p>
 */
public class MarySpeaker implements SpeakerEngine {

    private static final BasicLogger LOG = RadioLocal.logger;

    private final AudioPlayer player;
    private final Path tmpDir;
    private final LocalMaryInterface mary;      // API locale di MaryTTS

    /**
     * Crea l’engine MaryTTS.
     *
     * @param player player condiviso con la radio
     * @param cfg configurazione (radiolocal.cfg)
     * @throws IOException se non si riesce a creare la cartella temporanea
     * @throws CTException se la lettura della config fallisce
     * @throws MaryConfigurationException se Mary non può essere avviato
     */
    public MarySpeaker(AudioPlayer player, Config cfg)
            throws IOException, CTException, MaryConfigurationException {

        this.player = player;

        // ------------ cartella temporanea per i wav -------------
        this.tmpDir = Files.createTempDirectory("mary-tts");
        LOG.info("MarySpeaker: temp dir = " + tmpDir);

        // ------------ avvia il server interno --------------------
        this.mary = new LocalMaryInterface();   // chiama MaryRuntimeUtils.ensureMaryStarted()
        LOG.info("Voci disponibili: " + mary.getAvailableVoices());

        // ------------ impostazioni vocali da file .cfg ----------
        String voiceName = cfg.get("marytts", "voice", "dfki-poppy");
        try {
            mary.setVoice(voiceName);
        } catch (IllegalArgumentException e) {
            LOG.warn("Voce Mary non trovata '" + voiceName + "' → uso default");
        }
        // (il tipo di output è WAV di default, non serve configurarlo)
    }

    @Override
    public Future<?> speak(final String text) {
        FutureTask<Void> ft = new FutureTask<Void>(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                synthesizeAndPlay(text);
                while (player.isPlaying()) {
                    Thread.sleep(100);
                }
                return null;
            }
        });
        ft.run();           // blocca finché il wav non è terminato
        return ft;          // già completato
    }

    /**
     * Synthesize the text, write a temporary wav and hand it to the player.
     */
    private void synthesizeAndPlay(String text) throws IOException, SynthesisException {
        // ---- 1) genera l'AudioInputStream (PCM) -------------------------
        AudioInputStream ais = mary.generateAudio(text);   // può lanciare SynthesisException

        // ---- 2) scrive il wav temporaneo ---------------------------------
        Path wavFile = Files.createTempFile(tmpDir, "mary-", ".wav");
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile.toFile());

        // ---- 3) passa al player -----------------------------------------
        Track ttsTrack = new Track(
                wavFile.toFile(),
                "Speech",
                "System",
                "TTS",
                -1);
        LOG.debug("Riproduco wav MaryTTS: " + wavFile);
        player.play(ttsTrack);

        // ---- 4) cleanup (cancella al termine della JVM) -----------------
        wavFile.toFile().deleteOnExit();
    }

    @Override
    public void stop() {
        // MaryTTS non espone un metodo di stop per una sintesi in corso,
        // ma se vuoi interrompere la riproduzione del wav basta invocare
        // player.stop().
        LOG.info("MarySpeaker.stop() – non c’è nulla da chiudere");
    }
}
