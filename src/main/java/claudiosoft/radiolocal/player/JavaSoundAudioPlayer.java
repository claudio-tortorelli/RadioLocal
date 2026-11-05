package claudiosoft.radiolocal.player;

import claudiosoft.commons.BasicLogger;
import claudiosoft.radiolocal.RadioLocal;
import claudiosoft.radiolocal.model.Track;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.*;

public class JavaSoundAudioPlayer implements AudioPlayer {

    private static final BasicLogger LOG = RadioLocal.logger;

    /* ---------- Stato di riproduzione ---------- */
    private volatile Thread playbackThread;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /* ---------- Volume ---------- */
    private volatile int volume = 50;               // 0‑100, valore di default
    private final AtomicReference<SourceDataLine> activeLine = new AtomicReference<>(null);

    private Track currentTrack;
    private Track previousTrack;

    /* ==============================================================
       PUBLIC API
       ============================================================== */
    @Override
    public void play(Track track) {
        if (track == null) {
            LOG.warn("Richiesta di play con Track NULL – ignorata");
            return;
        }

        File file = track.getFile();
        if (!file.exists()) {
            LOG.error("File audio non trovato: %s".formatted(file.getAbsolutePath()));
            return;
        }

        previousTrack = currentTrack;
        currentTrack = track;

        // Ferma eventuale riproduzione in corso
        stop();

        stopRequested.set(false);
        paused.set(false);

        playbackThread = new Thread(() -> streamAudio(file), "JavaSoundPlay-" + file.getName());
        playbackThread.start();
    }

    /**
     * Legge l’audio dal file e lo invia al mixer.
     */
    private void streamAudio(File audioFile) {
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(audioFile)) {

            AudioFormat baseFormat = sourceStream.getFormat();
            AudioFormat decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false);   // little‑endian

            // Converte (es. MP3/OGG → PCM) usando il service provider installato
            try (AudioInputStream decodedStream = AudioSystem.getAudioInputStream(decodedFormat, sourceStream)) {

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, decodedFormat);
                try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                    line.open(decodedFormat);
                    // Salviamo la linea così che setVolume() possa intervenire anche
                    // mentre il brano è in riproduzione.
                    activeLine.set(line);
                    applyVolume(line);               // imposta il volume iniziale
                    line.start();

                    byte[] buffer = new byte[4096];
                    int bytesRead;

                    while (!stopRequested.get()
                            && (bytesRead = decodedStream.read(buffer, 0, buffer.length)) != -1) {

                        // Gestione pausa
                        while (paused.get() && !stopRequested.get()) {
                            Thread.sleep(100);
                        }
                        if (stopRequested.get()) {
                            break;
                        }

                        line.write(buffer, 0, bytesRead);
                    }

                    // Drain e chiusura
                    line.drain();
                    line.stop();
                } finally {
                    // La linea è chiusa; rimuoviamo il riferimento così setVolume non tenta più di usarla
                    activeLine.set(null);
                }
            }

        } catch (UnsupportedAudioFileException e) {
            LOG.error("Formato audio non supportato: %s".formatted(audioFile.getName()), e);
        } catch (IOException e) {
            LOG.error("Errore I/O durante la riproduzione di %s".formatted(audioFile.getName()), e);
        } catch (LineUnavailableException e) {
            LOG.error("Linea audio non disponibile", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LOG.debug("Playback terminato per %s".formatted(audioFile.getName()));
        }
    }

    /**
     * Aggiorna il volume sul mixer (se disponibile). Il metodo è chiamato sia
     * all’avvio della traccia sia da {@link #setVolume(int)}.
     */
    private void applyVolume(SourceDataLine line) {   // <<< MOD – rimane identico, ma è ora invocabile da più punti
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);

            // Converte 0‑100 → dB. 0% → valore minimo consentito.
            float linear = volume / 100.0f;
            float dB;
            if (linear <= 0.0f) {
                dB = gain.getMinimum();                     // gestione volume zero
            } else {
                dB = (float) (20.0 * Math.log10(linear));
                if (dB < gain.getMinimum()) {
                    dB = gain.getMinimum();
                }
                if (dB > gain.getMaximum()) {
                    dB = gain.getMaximum();
                }
            }

            gain.setValue(dB);
        } else {
            LOG.debug("Controllo MASTER_GAIN non disponibile – volume non regolabile");
        }
    }

    @Override
    public void pause() {
        paused.set(true);
        LOG.debug("Playback in pausa");
    }

    @Override
    public void stop() {
        if (playbackThread != null && playbackThread.isAlive()) {
            stopRequested.set(true);
            paused.set(false);
            try {
                playbackThread.join(500);   // aspetta un po’
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.debug("Playback interrotto");
    }

    @Override
    public void setVolume(int percent) {
        // Normalizza a 0‑100 e salva
        this.volume = Math.max(0, Math.min(100, percent));
        LOG.debug("Volume impostato a %d".formatted(this.volume));

        // Se la riproduzione è in corso, aggiorna subito la linea
        SourceDataLine line = activeLine.get();               // lettura thread‑safe
        if (line != null) {
            // Evitiamo di bloccare il thread di riproduzione: FloatControl è thread‑safe
            applyVolume(line);                               // applicazione immediata
        }
        // altrimenti il nuovo valore verrà usato al prossimo `applyVolume` chiamato dal thread di playback
    }

    @Override
    public int getVolume() {
        return volume;
    }

    @Override
    public boolean isPlaying() {
        return playbackThread != null && playbackThread.isAlive() && !paused.get();
    }

    @Override
    public void resume() {
        if (paused.compareAndSet(true, false)) {
            LOG.debug("Playback ripreso");
        } else {
            LOG.debug("Resume chiamato ma il player non era in pausa");
        }
    }
}
