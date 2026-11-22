package claudiosoft.radiolocal.utility;

/**
 * Massive mp3 downloader from youtube
 *
 * It uses yt as external resource https://github.com/yt-dlp/yt-dlp/releases
 *
 * @author claudio.tortorelli
 */
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ItalianHitDownloader {

    private static final Pattern HREF_PATTERN = Pattern.compile("\\b(?:href|src)\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>\"']+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_PATTERN = Pattern.compile("youtube\\.com/watch|youtu\\.be/|youtube\\.com/embed/");

    private static final Pattern LI_START = Pattern.compile("(?i)<li\\b");
    private static final Pattern ROW_END = Pattern.compile("(?i)</li>|</ul>|</ol>");
    private static final Pattern YT_ANCHOR = Pattern.compile("(?is)<a\\b[^>]*href=['\"]?(https?://[^\\s>'\"]*(?:youtube\\.com/watch|youtu\\.be/|youtube\\.com/embed/)[^\\s>'\"]*)[^>]*>.*?</a>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern BRACKETS = Pattern.compile("\\s*\\[[^\\]]*\\]");
    private static final Pattern YT_ID = Pattern.compile("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{11})");

    private record Track(String id, String url, String artist, String title) {}

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path outDir;
    private final File ffmpeg;
    private final File yt;

    public ItalianHitDownloader(Path outDir) throws IOException {
        this.outDir = Files.createDirectories(outDir);
        this.ffmpeg = new File("./resource/ffmpeg/bin/ffmpeg.exe");
        this.yt = new File("./resource/yt-dlp.exe");
        if (!this.yt.exists()) {
            return;
        }
    }

    /**
     * Scarica la pagina e restituisce i link YouTube unici trovati in href/src.
     */
    public List<String> findYoutubeLinks(String pageUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(pageUrl))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        String html = http.send(req, HttpResponse.BodyHandlers.ofString()).body();

        URI base = URI.create(pageUrl);
        Set<String> links = new TreeSet<>();
        Matcher m = HREF_PATTERN.matcher(html);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2)
                    : m.group(3);
            String resolved = base.resolve(raw).toString();
            if (YT_PATTERN.matcher(resolved).find()) {
                links.add(resolved);
            }
        }
        return List.copyOf(links);
    }

    /**
     * Scarica l'audio del video e lo converte in MP3 a 192 kbps usando yt-dlp.
     */
    public void downloadMp3(Track t, String videoUrl) throws IOException, InterruptedException {

        if (alreadyDownloaded(outDir, t.id())) {
            System.out.println("Già presente, salto: " + t.title());
            return;
        }
        Path dir = outDir.resolve(sanitize(t.artist()));
        Files.createDirectories(dir);

        Path log = dir.resolve(t.id() + ".log");
        ProcessBuilder pb = new ProcessBuilder(
                yt.getCanonicalPath(),
                "--no-playlist",
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "192K",
                "--ffmpeg-location", ffmpeg.getCanonicalPath(),
                "-o", dir.resolve("%(title)s.%(ext)s").toString(),
                videoUrl
        ).redirectErrorStream(true).redirectOutput(log.toFile());


        Process p = pb.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("timeout yt-dlp");
        }
        if (p.exitValue() == 0) {
            Files.writeString(dir.resolve(t.id() + ".txt"),
                    t.url() + System.lineSeparator()
                    + t.artist() + " - " + t.title() + System.lineSeparator());
        }
    }

    static synchronized boolean alreadyDownloaded(Path outDir, String id) throws IOException {
        try (Stream<Path> files = Files.walk(outDir)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(id + ".txt"));
        }
    }

    static synchronized String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static List<Track> parseTracks(String curPage) throws IOException, InterruptedException {

        HttpRequest req = HttpRequest.newBuilder(URI.create(curPage))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "it-IT,it;q=0.9")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String html = resp.body();

        List<Track> tracks = new ArrayList<>();
        String[] rows = LI_START.split(html);
        for (int i = 1; i < rows.length; i++) {
            // Taglio alla fine della riga logica, se presente
            String block = ROW_END.split(rows[i], 2)[0];

            Matcher yt = YT_ANCHOR.matcher(block);
            if (!yt.find()) {
                continue;
            }
            String url = yt.group(1);
            Matcher id = YT_ID.matcher(url);
            if (!id.find()) {
                continue;
            }

            String text = block.substring(yt.end());
            text = TAGS.matcher(text).replaceAll("")
                    .replace("&amp;", "&")
                    .trim();

            String title;
            String artist;
            String[] parts = text.split(" - ", 2);
            if (parts.length == 2) {
                title = parts[0].trim();
                artist = BRACKETS.matcher(parts[1]).replaceAll("").trim();
            } else {
                title = BRACKETS.matcher(text).replaceAll("").trim();
                artist = "UnknownArtist";
            }

            if (title.isEmpty()) {
                continue;
            }

            tracks.add(new Track(id.group(1), url, artist, title));
        }
        return tracks;
    }

    public static void main(String[] args) throws Exception {

        String webBase = "https://hitparadeitalia.it/hp_yends/hpe%04d.htm";

        int slowDownMs = 2000;
        int multi = 4;
        int firstYear = 1945;
        int lastYear = 2025;
        for (int year = firstYear; year <= lastYear; year++) {
            String curPage = String.format(webBase, year);
            System.out.println(curPage);

            String dirName = String.format("downloads/%d", year);
            File outDir = new File(dirName);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            var downloader = new ItalianHitDownloader(outDir.toPath());
            //List<String> links = downloader.findYoutubeLinks(curPage);
            List<Track> tracks = parseTracks(curPage);
            System.out.println("Trovati " + tracks.size() + " link YouTube.");

            var limiter = new Semaphore(multi);
            var counter = new AtomicInteger();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Track track : tracks) {
                    executor.submit(() -> {
                        limiter.acquire();
                        try {
                            int n = counter.incrementAndGet();
                            System.out.printf("[%d/%d] %s%n", n, tracks.size(), track.url());

                            downloader.downloadMp3(track, track.url());

                            Thread.sleep(slowDownMs);
                        } catch (IOException e) {
                            System.err.println("  Errore su " + track.url() + ": " + e.getMessage());
                            System.out.println("  RETRY " + track.url());
                            try {
                                downloader.downloadMp3(track, track.url());
                            } catch (Exception e2) {

                            }
                        } finally {
                            limiter.release();
                        }
                        return null;
                    });
                }
            } // try-with-resources attende il completamento di tutti i task            
        }
    }

}
