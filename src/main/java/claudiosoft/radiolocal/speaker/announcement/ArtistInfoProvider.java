package claudiosoft.radiolocal.speaker.announcement;

import claudiosoft.radiolocal.model.Track;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class ArtistInfoProvider implements AnnouncementProvider {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Cache: artist → (testo, timestamp)
    private final Map<String, CacheEntry> cache = new HashMap<>();
    private final long ttlSec;                     // es. 3600 (1 h)

    public ArtistInfoProvider(long ttlSec) {
        this.ttlSec = ttlSec;
    }

    @Override
    public Optional<String> getAnnouncement(Track currentTrack) {
        String artist = currentTrack.getArtist();
        if (artist == null || artist.isBlank()) {
            return Optional.empty();
        }

        CacheEntry entry = cache.get(artist);
        long now = Instant.now().getEpochSecond();

        if (entry != null && now - entry.timestamp <= ttlSec) {
            return Optional.of(entry.text);
        }

        // Nessuna cache valida → chiamiamo Wikipedia
        String summary = fetchWikipediaIntro(artist);
        if (summary != null) {
            cache.put(artist, new CacheEntry(summary, now));
            return Optional.of(summary);
        }
        return Optional.empty();
    }

    private String fetchWikipediaIntro(String artist) {
        try {
            // API: https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=Artist
            // Per l’italiano usiamo "it.wikipedia.org"
            String title = java.net.URLEncoder.encode(artist, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://it.wikipedia.org/w/api.php?"
                    + "action=query&prop=extracts&exintro&explaintext&format=json&titles=" + title;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("User-Agent", "RadioLocal/1.0 (+https://github.com/yourrepo)")
                    .build();

            HttpResponse<String> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode pages = root.path("query").path("pages");
            if (pages.isMissingNode()) {
                return null;
            }

            // La risposta contiene un nodo con chiave dinamica (pageid)
            Iterator<JsonNode> it = pages.iterator();
            if (!it.hasNext()) {
                return null;
            }
            JsonNode page = it.next();
            String extract = page.path("extract").asText();
            if (extract.isBlank()) {
                return null;
            }

            // Limitiamo la lunghezza a 300‑400 caratteri per evitare discorsi troppo lunghi
            extract = extract.length() > 300 ? extract.substring(0, 300) + "…" : extract;

            // Formuliamo una frase “radio‑friendly”
            return String.format("E ora qualche curiosità su %s: %s", artist, extract);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * contenitore interno per il cache
     */
    private static final class CacheEntry {

        final String text;
        final long timestamp;

        CacheEntry(String text, long timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }
    }
}
