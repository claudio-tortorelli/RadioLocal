package claudiosoft.radiolocal.speaker.announcement;

import claudiosoft.radiolocal.model.Track;

/**
 * Un provider restituisce una frase (opzionale) pronta per la TTS. Se non c’è
 * nulla da dire, restituisce {@code Optional.empty()}.
 */
public interface AnnouncementProvider {

    /**
     * Restituisce (se presente) il testo da annunciare.
     *
     * @param currentTrack traccia che è attualmente in riproduzione
     * @return testo da sintetizzare oppure {@code Optional.empty()}
     */
    java.util.Optional<String> getAnnouncement(Track currentTrack);
}
