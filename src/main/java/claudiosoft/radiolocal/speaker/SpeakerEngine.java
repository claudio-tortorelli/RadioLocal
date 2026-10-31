package claudiosoft.radiolocal.speaker;

import java.util.concurrent.Future;

/**
 * Interfaccia per i motori di sintesi vocale (TTS) usati dalla Radio.
 *
 * <p>
 * Un'implementazione deve produrre l’audio in maniera <b>asincrona</b>
 * (non bloccare il thread di riproduzione) e restituire un {@link Future} per
 * poter eventualmente annullare l’operazione.</p>
 */
public interface SpeakerEngine {

    /**
     * Sintetizza il testo ricevuto e lo inoltra all'
     * {@link claudiosoft.radiolocal.player.AudioPlayer}.
     *
     * @param text testo da parlare (UTF‑8)
     * @return Future che completa quando l’audio è stato inviato al player
     */
    Future<?> speak(String text);

    /**
     * Ferma eventuali sintesi in corso (se supportato).
     */
    void stop();
}
