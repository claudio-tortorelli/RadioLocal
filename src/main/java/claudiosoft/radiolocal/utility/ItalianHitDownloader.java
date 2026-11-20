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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public final class ItalianHitDownloader {

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "\\b(?:href|src)\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>\"']+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_PATTERN = Pattern.compile(
            "youtube\\.com/watch|youtu\\.be/|youtube\\.com/embed/");

    private final HttpClient http = HttpClient.newBuilder()
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
    public void downloadMp3(String videoUrl) throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                yt.getCanonicalPath(),
                "--no-playlist",
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "192K",
                "--ffmpeg-location", ffmpeg.getCanonicalPath(),
                "-o", outDir.resolve("%(title)s.%(ext)s").toString(),
                videoUrl
        ).redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("yt-dlp è uscito con codice " + exit + ":\n" + output);
        }
    }

    public static void main(String[] args) throws Exception {

        String webBase = "https://hitparadeitalia.it/hp_yends/hpe%04d.htm";

        int firstYear = 1947;
        for (int year = firstYear; year <= 2025; year++) {
            String curPage = String.format(webBase, year);
            System.out.println(curPage);

            String dirName = String.format("downloads/%d", year);
            File outDir = new File(dirName);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            var downloader = new ItalianHitDownloader(outDir.toPath());
            List<String> links = downloader.findYoutubeLinks(curPage);
            System.out.println("Trovati " + links.size() + " link YouTube.");

            var limiter = new Semaphore(4); // massimo 4 download contemporanei
            var counter = new AtomicInteger();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String link : links) {
                    executor.submit(() -> {
                        limiter.acquire();
                        try {
                            int n = counter.incrementAndGet();
                            System.out.printf("[%d/%d] %s%n", n, links.size(), link);
                            downloader.downloadMp3(link);
                        } catch (IOException e) {
                            System.err.println("  Errore su " + link + ": " + e.getMessage());
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
