package claudiosoft.radiolocal.speaker.announcement;

import claudiosoft.radiolocal.model.Track;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class WeatherProvider implements AnnouncementProvider {

    private final String apiKey;
    private final String location;          // es. "Milano,IT"
    private final long intervalSec;      // refresh minima
    private long lastFetch = 0;
    private String cachedAnnouncement = null;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WeatherProvider(String apiKey, String location, long intervalSec) {
        this.apiKey = apiKey;
        this.location = location;
        this.intervalSec = intervalSec;
    }

    @Override
    public Optional<String> getAnnouncement(Track currentTrack) {
        long now = Instant.now().getEpochSecond();
        if (cachedAnnouncement == null || now - lastFetch > intervalSec) {
            cachedAnnouncement = fetchWeather();
            lastFetch = now;
        }
        return Optional.ofNullable(cachedAnnouncement);
    }

    private String fetchWeather() {
        try {
            // Convertiamo "Milano,IT" in "Milano,IT" URL‑encoded
            String query = java.net.URLEncoder.encode(location, java.nio.charset.StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?q=%s&units=metric&lang=it&appid=%s",
                    query, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null; // fallback silenzioso
            }

            JsonNode root = MAPPER.readTree(response.body());
            double temp = root.path("main").path("temp").asDouble();
            String desc = root.path("weather").get(0).path("description").asText();

            // Costruiamo una frase breve “in stile radio”
            return String.format(
                    "E ora, a %s la temperatura è di %.1f gradi e il cielo è %s.",
                    location.split(",")[0], temp, desc);
        } catch (Exception e) {
            // Log di debug (non interrompe la radio)
            return null;
        }
    }
}
