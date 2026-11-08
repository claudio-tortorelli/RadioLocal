package claudiosoft.radiolocal.scanner;

import claudiosoft.commons.BasicLogger;
import claudiosoft.radiolocal.RadioLocal;
import claudiosoft.radiolocal.model.Album;
import claudiosoft.radiolocal.model.Artist;
import claudiosoft.radiolocal.model.Library;
import claudiosoft.radiolocal.model.Track;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;

/**
 * Scansiona una directory, popola una {@link Library} e la persiste su SQLite.
 * Implementa relazioni N:N tra brani, artisti e album e gestisce la
 * classificazione "ignoto".
 */
public class LibraryScanner {

    private static final BasicLogger LOG = RadioLocal.logger;
    private final Path root;
    private final Path dbFile;
    private PrintWriter exceptionsWriter;

    public LibraryScanner(Path root) {
        this.root = root;
        this.dbFile = root.resolve("library.db");
    }

    public Library scan() throws IOException, SQLException {
        return scan(false);
    }

    public Library scan(boolean forced) throws IOException, SQLException {
        if (forced || !Files.exists(dbFile)) {
            LOG.info("Forced scan avviata – ricostruzione libreria");
            //setupExceptionsFile();
            Library lib = new Library();
            scanFileSystem(lib);
            if (exceptionsWriter != null) {
                exceptionsWriter.close();
            }
            persistLibrary(lib);
            return lib;
        } else {
            LOG.info("Caricamento libreria dal DB locale %s".formatted(dbFile));
            return loadFromDb();
        }
    }

    private void setupExceptionsFile() throws IOException {
        File excFile = root.resolve("libraryScanExceptions.txt").toFile();
        this.exceptionsWriter = new PrintWriter(new BufferedWriter(new FileWriter(excFile, false)));
    }

    private void scanFileSystem(Library lib) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (hasAudioExtension(file)) {
                    processFile(file, lib);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void processFile(Path curPath, Library lib) {
        String title = null;
        String artist = null;
        String album = null;
        int duration = 0;

        // 1. Prova con jaudiotagger
        try {
            AudioFile af = AudioFileIO.read(curPath.toFile());
            org.jaudiotagger.tag.Tag tag = af.getTag();
            if (tag != null) {
                title = getTagValue(tag, FieldKey.TITLE);
                artist = getTagValue(tag, FieldKey.ARTIST);
                album = getTagValue(tag, FieldKey.ALBUM);
            }
            duration = (int) af.getAudioHeader().getTrackLength();
        } catch (Exception e) {
            LOG.debug("Tagging fallito per %s".formatted(curPath.getFileName()));
        }

        // 2. Fallback su Path Inference
        Path relativePath = root.relativize(curPath);
        int nameCount = relativePath.getNameCount();
        if (artist == null || artist.isBlank()) {
            if (nameCount >= 2) {
                artist = relativePath.getName(1).toString();
            }
        }
        if (album == null || album.isBlank()) {
            if (nameCount == 4) {
                album = relativePath.getName(2).toString();
            }
        }

        // Gestione Titolo e Flag Ignoto
        String fileNameNoExt = getFileNameWithoutExtension(curPath);
        boolean isUnknown = false;
        if (title == null || title.isBlank()) {
            title = fileNameNoExt;
            // Se titolo è il nome file E artista/album mancano -> Ignoto
            if ((artist == null || artist.isBlank()) && (album == null || album.isBlank())) {
                isUnknown = true;
            }
        }

        // 3. Normalizzazione (Pulizia caratteri speciali)
        artist = cleanValue(artist, "unknownArtist");
        album = cleanValue(album, "unknownAlbum");
        title = cleanValue(title, "unknownTitle");

        //logMetadataExceptions(curPath, artist, album, title);

        // 4. Inserimento in Library
        Artist art = lib.getOrCreateArtist(artist);
        Album alb = null;
        for (Album existing : art.getAlbums()) {
            if (existing.getName().equalsIgnoreCase(album)) {
                alb = existing;
                break;
            }
        }
        if (alb == null) {
            alb = new Album(album, art);
            art.addAlbum(alb);
        }

        Track track = new Track(curPath.toFile(), title, artist, album, duration);
        track.setIsUnknown(isUnknown);
        alb.addTrack(track);
    }

    private String getFileNameWithoutExtension(Path path) {
        String name = path.getFileName().toString();
        int lastDot = name.lastIndexOf('.');
        return (lastDot != -1) ? name.substring(0, lastDot) : name;
    }

    private String getTagValue(org.jaudiotagger.tag.Tag tag, org.jaudiotagger.tag.FieldKey key) {
        String val = tag.getFirst(key);
        return (val == null) ? null : val.trim();
    }

    private String cleanValue(String val, String defaultValue) {
        if (val == null) {
            return defaultValue;
        }

        String result = val.trim();

        result = result.replace("(", "");
        result = result.replace(")", "");
        result = result.replace("[", "");
        result = result.replace("]", "");

        // 3. Rimuove separatori comuni di "tag" che rimangono appesi (trattini, underscore)
        // Esempio: "U2 - " -> "U2"
        result = result.replaceAll("\\s*[-_]\\s*$", "");
        result = result.replaceAll("^\\s*[-_]\\s*", "");

        // 4. Pulizia finale: rimuove caratteri speciali/punteggiatura residui all'inizio e alla fine
        result = result.trim().replaceAll("^[^a-zA-Z0-9\\s]+|[^a-zA-Z0-9\\s]+$", "");

        return (result == null || result.isEmpty()) ? defaultValue : result;
    }

    private void logMetadataExceptions(Path path, String art, String alb, String tit) {
        if (exceptionsWriter == null) {
            return;
        }
        if (art.equals("unknownArtist") || alb.equals("unknownAlbum") || tit.equals("unknownTitle")) {
            exceptionsWriter.println("File: " + root.relativize(path).toString());
            if (art.equals("unknownArtist")) {
                exceptionsWriter.println("  -> missing artist");
            }
            if (alb.equals("unknownAlbum")) {
                exceptionsWriter.println("  -> missing album");
            }
            if (tit.equals("unknownTitle")) {
                exceptionsWriter.println("  -> missing title");
            }
            exceptionsWriter.println();
        }
    }

    private boolean hasAudioExtension(Path p) {
        String s = p.getFileName().toString().toLowerCase();
        return s.endsWith(".mp3") || s.endsWith(".ogg");
    }

    /* ---------------------------------------------------------------
       PERSISTENZA SQLITE (Aggiornata al nuovo schema N:N)
       --------------------------------------------------------------- */
    private void initialiseDb(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("CREATE TABLE IF NOT EXISTS artist (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
            stmt.execute("CREATE TABLE IF NOT EXISTS album (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
            stmt.execute("CREATE TABLE IF NOT EXISTS track (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE, title TEXT NOT NULL, duration INTEGER, genre TEXT, play_count INTEGER DEFAULT 0, last_played TEXT, preference_score INTEGER CHECK(preference_score >= 1 AND preference_score <= 5), is_unknown BOOLEAN DEFAULT 0);");
            stmt.execute("CREATE TABLE IF NOT EXISTS track_artist (track_id INTEGER NOT NULL, artist_id INTEGER NOT NULL, PRIMARY KEY (track_id, artist_id), FOREIGN KEY(track_id) REFERENCES track(id) ON DELETE CASCADE, FOREIGN KEY(artist_id) REFERENCES artist(id) ON DELETE CASCADE);");
            stmt.execute("CREATE TABLE IF NOT EXISTS track_album (track_id INTEGER NOT NULL, album_id INTEGER NOT NULL, PRIMARY KEY (track_id, album_id), FOREIGN KEY(track_id) REFERENCES track(id) ON DELETE CASCADE, FOREIGN KEY(album_id) REFERENCES album(id) ON DELETE CASCADE);");
        }
    }

    private void persistLibrary(Library lib) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            conn.setAutoCommit(false);
            initialiseDb(conn);

            // 1. Inserimento Artisti
            Map<String, Long> artistIdMap = new HashMap<>();
            String insArt = "INSERT OR IGNORE INTO artist(name) VALUES(?)";
            String selArt = "SELECT id FROM artist WHERE name = ?";
            try (PreparedStatement pIns = conn.prepareStatement(insArt); PreparedStatement pSel = conn.prepareStatement(selArt)) {
                for (Artist a : lib.getArtists()) {
                    pIns.setString(1, a.getName());
                    pIns.executeUpdate();
                    pSel.setString(1, a.getName());
                    try (ResultSet rs = pSel.executeQuery()) {
                        if (rs.next()) {
                            artistIdMap.put(a.getName(), rs.getLong(1));
                        }
                    }
                }
            }

            // 2. Inserimento Album
            Map<String, Long> albumIdMap = new HashMap<>();
            String insAlb = "INSERT OR IGNORE INTO album(name) VALUES(?)";
            String selAlb = "SELECT id FROM album WHERE name = ?";
            try (PreparedStatement pIns = conn.prepareStatement(insAlb); PreparedStatement pSel = conn.prepareStatement(selAlb)) {
                for (Artist a : lib.getArtists()) {
                    for (Album alb : a.getAlbums()) {
                        pIns.setString(1, alb.getName());
                        pIns.executeUpdate();
                        pSel.setString(1, alb.getName());
                        try (ResultSet rs = pSel.executeQuery()) {
                            if (rs.next()) {
                                albumIdMap.put(alb.getName(), rs.getLong(1));
                            }
                        }
                    }
                }
            }

            // 3. Inserimento Tracce e Giunzioni
            String insTrk = "INSERT INTO track(path, title, duration, is_unknown) VALUES(?,?,?,?)";
            String insTrkArt = "INSERT INTO track_artist(track_id, artist_id) VALUES(?,?)";
            String insTrkAlb = "INSERT INTO track_album(track_id, album_id) VALUES(?,?)";

            try (PreparedStatement pTrk = conn.prepareStatement(insTrk, Statement.RETURN_GENERATED_KEYS); PreparedStatement pTrkArt = conn.prepareStatement(insTrkArt); PreparedStatement pTrkAlb = conn.prepareStatement(insTrkAlb)) {

                for (Artist a : lib.getArtists()) {
                    Long aId = artistIdMap.get(a.getName());
                    for (Album alb : a.getAlbums()) {
                        Long albId = albumIdMap.get(alb.getName());
                        for (Track track : alb.getTracks()) {
                            String relPath = root.relativize(track.getFile().toPath()).toString();
                            pTrk.setString(1, relPath);
                            pTrk.setString(2, track.getTitle());
                            pTrk.setInt(3, track.getDurationSec());
                            pTrk.setBoolean(4, track.isIsUnknown());
                            pTrk.executeUpdate();

                            try (ResultSet rs = pTrk.getGeneratedKeys()) {
                                if (rs.next()) {
                                    long trackId = rs.getLong(1);
                                    // Lega brano ad artista
                                    pTrkArt.setLong(1, trackId);
                                    pTrkArt.setLong(2, aId);
                                    pTrkArt.executeUpdate();
                                    // Lega brano ad album
                                    pTrkAlb.setLong(1, trackId);
                                    pTrkAlb.setLong(2, albId);
                                    pTrkAlb.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
            conn.commit();
            LOG.info("Library salvata su SQLite con relazioni N:N");
        }
    }

    private Library loadFromDb() throws SQLException {
        Library lib = new Library();
        if (!Files.exists(dbFile)) {
            return lib;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            initialiseDb(conn);

            // 1. Carico Artisti
            Map<Long, Artist> artistById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name FROM artist")) {
                while (rs.next()) {
                    Artist a = lib.getOrCreateArtist(rs.getString("name"));
                    artistById.put(rs.getLong("id"), a);
                }
            }

            // 2. Carico Album
            Map<Long, Album> albumById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name FROM album")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    // Gli album vengono creati senza artista inizialmente, poi legati tramite track
                    Album alb = new Album(name, lib.getOrCreateArtist("Unknown"));
                    albumById.put(id, alb);
                }
            }

            // 3. Carico Brani
            Map<Long, Track> trackById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, path, title, duration, is_unknown FROM track")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    File file = root.resolve(rs.getString("path")).toFile();
                    Track t = new Track(file, rs.getString("title"), "Unknown", "Unknown", rs.getInt("duration"));
                    t.setIsUnknown(rs.getBoolean("is_unknown"));
                    trackById.put(id, t);
                }
            }

            // 4. Ripristino Relazioni N:N (Track -> Artist)
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT track_id, artist_id FROM track_artist")) {
                while (rs.next()) {
                    Track t = trackById.get(rs.getLong("track_id"));
                    Artist a = artistById.get(rs.getLong("artist_id"));
                    if (t != null && a != null) {
                        // Qui aggiorneresti l'oggetto track se avesse una List<Artist>
                        // Per compatibilità con il tuo modello attuale:
                        // t.setArtist(a.getName());
                    }
                }
            }

            // 5. Ripristino Relazioni N:N (Track -> Album)
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT track_id, album_id FROM track_album")) {
                while (rs.next()) {
                    Track t = trackById.get(rs.getLong("track_id"));
                    Album alb = albumById.get(rs.getLong("album_id"));
                    if (t != null && alb != null) {
                        alb.addTrack(t);
                    }
                }
            }
        }
        return lib;
    }
}
