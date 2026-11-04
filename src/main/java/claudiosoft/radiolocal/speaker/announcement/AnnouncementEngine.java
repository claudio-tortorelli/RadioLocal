package claudiosoft.radiolocal.speaker.announcement;

import claudiosoft.radiolocal.model.Track;
import java.util.*;
import java.util.concurrent.*;

public class AnnouncementEngine {

    private final List<AnnouncementProvider> providers;
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * intervallo minimo in secondi tra due annunci di *qualsiasi* tipo
     */
    private final long globalMinIntervalSec;
    private long lastAnnouncement = 0;

    public AnnouncementEngine(List<AnnouncementProvider> providers, long globalMinIntervalSec) {
        this.providers = providers;
        this.globalMinIntervalSec = globalMinIntervalSec;
    }

    /**
     * Chiamato dal thread principale ogni volta che la radio cambia brano.
     *
     * @param currentTrack traccia che sta per essere riprodotta (può servire al
     * provider artista)
     * @return Optional con il testo da dire (vuoto → nessun annuncio)
     */
    public Optional<String> maybeGenerate(Track currentTrack) {
        long now = System.currentTimeMillis() / 1000;
        if (now - lastAnnouncement < globalMinIntervalSec) {
            return Optional.empty(); // troppo presto per un nuovo annuncio
        }

        // Decidiamo casualmente se fare un annuncio
        // (puoi rendere più sofisticato, es. pesi differenti per ogni provider)
        int roll = random.nextInt(100);
        // Esempio di probabilità:
        // 0‑15 → meteo (15 %), 16‑40 → artista (25 %), 41‑50 → news (10 %), resto nessuno
        AnnouncementProvider chosen = null;
        if (roll < 15) {
            chosen = providers.stream()
                    .filter(p -> p instanceof WeatherProvider).findFirst().orElse(null);
        } else if (roll < 40) {
            chosen = providers.stream()
                    .filter(p -> p instanceof ArtistInfoProvider).findFirst().orElse(null);
        } else if (roll < 50) {
            chosen = providers.stream()
                    .filter(p -> p instanceof NewsProvider).findFirst().orElse(null);
        }

        if (chosen == null) {
            return Optional.empty();
        }

        Optional<String> maybeText = chosen.getAnnouncement(currentTrack);
        maybeText.ifPresent(t -> lastAnnouncement = now);
        return maybeText;
    }

    public Optional<String> forceAnnouncement(Class<? extends AnnouncementProvider> providerClass, Track currentTrack) {
        return providers.stream()
                .filter(providerClass::isInstance)
                .findFirst()
                .flatMap(p -> p.getAnnouncement(currentTrack));
    }

    /**
     * Metodo di clean‑up da chiamare al shutdown della radio
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
