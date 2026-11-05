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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;

/**
 * Scansiona una directory, popola una {@link Library} e la persiste su SQLite.
 * Utilizza path relativi nel DB per permettere lo spostamento della cartella
 * root. Implementa un sistema di fallback per i metadati: Tag ID3 -> Path
 * Inference.
 */
public class LibraryScanner {

    private static final BasicLogger LOG = RadioLocal.logger;
    private final Path root;               // cartella da scansire
    private final Path dbFile;             // <root>/library.db
    private PrintWriter exceptionsWriter;   // file per i brani incompleti

    public LibraryScanner(Path root) {
        this.root = root;
        this.dbFile = root.resolve("library.db");
    }

    public Library scan() throws IOException, SQLException {
        return scan(false);
    }

    public Library scan(boolean forced) throws IOException, SQLException {
        if (forced || !Files.exists(dbFile)) {
            LOG.info("Forced scan avviata – la libreria sarà ricostruita da zero");

            // Inizializzazione file eccezioni
            setupExceptionsFile();

            Library lib = new Library();
            scanFileSystem(lib);

            if (exceptionsWriter != null) {
                exceptionsWriter.close();
            }

            LOG.info("Scansione terminata: %d artisti, %d tracce"
                    .formatted(lib.getArtists().size(), lib.getAllTracks().size()));

            persistLibrary(lib);
            return lib;
        } else {
            LOG.info("Caricamento libreria dal DB locale %s".formatted(dbFile));
            return loadFromDb();
        }
    }

    private void setupExceptionsFile() throws IOException {
        File excFile = root.resolve("libraryScanExceptions.txt").toFile();
        // Sovrascrive il file ad ogni forced scan
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

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.error("Impossibile accedere al file %s".formatted(file), exc);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void processFile(Path curPath, Library lib) {
        String title = null;
        String artist = null;
        String album = null;
        int duration = 0;

        // --- STEP 1: Prova con jaudiotagger ---
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
            LOG.debug("Tagging fallito per %s, provo inferenza da path".formatted(curPath.getFileName()));
        }

        // --- STEP 2: Fallback su Path Inference ---
        // Path relativo: Letter/Artist/Album/title.mp3 (nameCount=4) oppure Letter/Artist/title.mp3 (nameCount=3)
        Path relativePath = root.relativize(curPath);
        int nameCount = relativePath.getNameCount();

        if (artist == null || artist.isBlank()) {
            if (nameCount >= 2) {
                artist = relativePath.getName(1).toString();
            }
        }

        if (album == null || album.isBlank()) {
            // L'album è presente solo se ci sono 4 elementi (Lettera, Artista, Album, File)
            if (nameCount == 4) {
                album = relativePath.getName(2).toString();
            }
        }

        if (title == null || title.isBlank()) {
            String filename = curPath.getFileName().toString();
            int lastDot = filename.lastIndexOf('.');
            title = (lastDot != -1) ? filename.substring(0, lastDot) : filename;
        }

        // --- STEP 3: Normalizzazione e Valori di Default ---
        artist = cleanValue(artist, "unknownArtist");
        album = cleanValue(album, "unknownAlbum");
        title = cleanValue(title, "unknownTitle");

        // Log delle eccezioni se mancano informazioni
        logMetadataExceptions(curPath, artist, album, title);

        // --- STEP 4: Inserimento in Library ---
        Artist art = lib.getOrCreateArtist(artist);

        Album alb = null;
        List<Album> artistAlbums = art.getAlbums();
        for (Album existing : artistAlbums) {
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
        alb.addTrack(track);
    }

    private String getTagValue(org.jaudiotagger.tag.Tag tag, org.jaudiotagger.tag.FieldKey key) {
        String val = tag.getFirst(key);
        return (val == null) ? null : val.trim();
    }

    private String cleanValue(String val, String defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        String trimmed = val.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
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
       PERSISTENZA SQLITE
       --------------------------------------------------------------- */
    private void initialiseDb(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("CREATE TABLE IF NOT EXISTS artist (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
            stmt.execute("CREATE TABLE IF NOT EXISTS album (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, artist_id INTEGER NOT NULL, UNIQUE(name, artist_id), FOREIGN KEY(artist_id) REFERENCES artist(id) ON DELETE CASCADE);");
            stmt.execute("CREATE TABLE IF NOT EXISTS track (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, title TEXT, duration INTEGER, album_id INTEGER NOT NULL, FOREIGN KEY(album_id) REFERENCES album(id) ON DELETE CASCADE);");
        }
    }

    private void persistLibrary(Library lib) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            conn.setAutoCommit(false);
            initialiseDb(conn);

            Map<String, Long> artistIdMap = new HashMap<>();

            // 1) Artisti
            String insertArtistSql = "INSERT INTO artist(name) VALUES(?)";
            try (PreparedStatement insertArtist = conn.prepareStatement(insertArtistSql, Statement.RETURN_GENERATED_KEYS); PreparedStatement selectArtist = conn.prepareStatement("SELECT id FROM artist WHERE name = ?")) {

                for (Artist artist : lib.getArtists()) {
                    Long aId = findArtistId(selectArtist, artist.getName());
                    if (aId == null) {
                        insertArtist.setString(1, artist.getName());
                        insertArtist.executeUpdate();
                        try (ResultSet rs = insertArtist.getGeneratedKeys()) {
                            if (rs.next()) {
                                aId = rs.getLong(1);
                            }
                        }
                    }
                    artistIdMap.put(artist.getName(), aId);
                }
            }

            // 2) Album
            String insertAlbumSql = "INSERT INTO album(name, artist_id) VALUES(?,?)";
            Map<String, Long> albumIdMap = new HashMap<>();
            try (PreparedStatement insertAlbum = conn.prepareStatement(insertAlbumSql, Statement.RETURN_GENERATED_KEYS); PreparedStatement selectAlbum = conn.prepareStatement("SELECT id FROM album WHERE name = ? AND artist_id = ?")) {

                for (Artist artist : lib.getArtists()) {
                    Long aId = artistIdMap.get(artist.getName());
                    for (Album album : artist.getAlbums()) {
                        Long alId = findAlbumId(selectAlbum, album.getName(), aId);
                        if (alId == null) {
                            insertAlbum.setString(1, album.getName());
                            insertAlbum.setLong(2, aId);
                            insertAlbum.executeUpdate();
                            try (ResultSet rs = insertAlbum.getGeneratedKeys()) {
                                if (rs.next()) {
                                    alId = rs.getLong(1);
                                }
                            }
                        }
                        albumIdMap.put(aId + "|" + album.getName(), alId);
                    }
                }
            }

            // 3) Tracce (SALVATAGGIO PATH RELATIVO)
            String insertTrackSql = "INSERT INTO track(path, title, duration, album_id) VALUES(?,?,?,?)";
            try (PreparedStatement insertTrack = conn.prepareStatement(insertTrackSql)) {
                for (Artist artist : lib.getArtists()) {
                    Long aId = artistIdMap.get(artist.getName());
                    for (Album album : artist.getAlbums()) {
                        Long alId = albumIdMap.get(aId + "|" + album.getName());
                        for (Track track : album.getTracks()) {
                            // Calcolo path relativo alla root
                            String relPath = root.relativize(track.getFile().toPath()).toString();
                            insertTrack.setString(1, relPath);
                            insertTrack.setString(2, track.getTitle());
                            insertTrack.setInt(3, track.getDurationSec());
                            insertTrack.setLong(4, alId);
                            insertTrack.addBatch();
                        }
                    }
                }
                insertTrack.executeBatch();
            }
            conn.commit();
            LOG.info("Library salvata su SQLite (path relativi): %s".formatted(dbFile));
        }
    }

    private Long findArtistId(PreparedStatement stmt, String name) throws SQLException {
        stmt.setString(1, name);
        try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("id");
            }
        }
        return null;
    }

    private Long findAlbumId(PreparedStatement stmt, String name, Long artistId) throws SQLException {
        stmt.setString(1, name);
        stmt.setLong(2, artistId);
        try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("id");
            }
        }
        return null;
    }

    private Library loadFromDb() throws SQLException {
        Library lib = new Library();
        if (!Files.exists(dbFile)) {
            return lib;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            initialiseDb(conn);

            Map<Long, Artist> artistById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name FROM artist")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Artist artist = lib.getOrCreateArtist(rs.getString("name"));
                    artistById.put(id, artist);
                }
            }

            Map<Long, Album> albumById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name, artist_id FROM album")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    Artist artist = artistById.get(rs.getLong("artist_id"));
                    if (artist != null) {
                        Album album = new Album(name, artist);
                        artist.addAlbum(album);
                        albumById.put(id, album);
                    }
                }
            }

            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT path, title, duration, album_id FROM track")) {
                while (rs.next()) {
                    String relPath = rs.getString("path");
                    // RICOSTRUZIONE PATH ASSOLUTO
                    File file = root.resolve(relPath).toFile();
                    String title = rs.getString("title");
                    int duration = rs.getInt("duration");
                    Album album = albumById.get(rs.getLong("album_id"));

                    if (album != null) {
                        Track track = new Track(file, title, album.getArtist().getName(), album.getName(), duration);
                        album.addTrack(track);
                    }
                }
            }
        }
        LOG.info("Library caricata dal DB (path ripristinati): %d artisti, %d tracce"
                .formatted(lib.getArtists().size(), lib.getAllTracks().size()));
        return lib;
    }
}
