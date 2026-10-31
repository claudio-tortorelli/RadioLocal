package claudiosoft.radiolocal.speaker.announcement;

import claudiosoft.radiolocal.model.Track;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class NewsProvider implements AnnouncementProvider {

    private final String apiKey;
    private final long intervalSec;            // ogni X secondi
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private long lastFetch = 0;
    private String cachedText = null;

    public NewsProvider(String apiKey, long intervalSec) {
        this.apiKey = apiKey;
        this.intervalSec = intervalSec;
    }

    @Override
    public Optional<String> getAnnouncement(Track currentTrack) {
        long now = Instant.now().getEpochSecond();
        if (cachedText == null || now - lastFetch > intervalSec) {
            cachedText = fetchHeadlines();
            lastFetch = now;
        }
        return Optional.ofNullable(cachedText);
    }

    private String fetchHeadlines() {
        try {
            // Recuperiamo le prime 3 notizie in italiano
            String url = String.format(
                    "https://newsapi.org/v2/top-headlines?language=it&pageSize=3&apiKey=%s",
                    apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("User-Agent", "RadioLocal/1.0")
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode articles = root.path("articles");
            if (!articles.isArray() || articles.isEmpty()) {
                return null;
            }

            List<String> titles = new ArrayList<>();
            for (JsonNode art : articles) {
                String title = art.path("title").asText();
                if (!title.isBlank()) {
                    titles.add(title.replaceAll("[\\r\\n]+", " "));
                }
            }

            if (titles.isEmpty()) {
                return null;
            }

            // Creiamo una frase “ecco le notizie del giorno”
            return "E ora le notizie del giorno: " + String.join("; ", titles) + ".";
        } catch (Exception e) {
            return null;
        }
    }
}
