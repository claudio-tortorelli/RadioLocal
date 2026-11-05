package claudiosoft.radiolocal.scanner;

import claudiosoft.commons.BasicLogger;
import claudiosoft.radiolocal.RadioLocal;
import claudiosoft.radiolocal.model.Album;
import claudiosoft.radiolocal.model.Artist;
import claudiosoft.radiolocal.model.Library;
import claudiosoft.radiolocal.model.Track;
import java.io.File;
import java.io.IOException;
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
 *
 * <p>
 * Il concetto di “forced scan” permette di rinfrescare la libreria (o di
 * crearla la prima volta) mentre la modalità di default legge la struttura già
 * salvata, evitando costosi I/O su filesystem.</p>
 *
 * <p>
 * Le lambda expression sono state eliminate a favore di strutture tradizionali
 * (for‑loop, SimpleFileVisitor, ecc.).</p>
 */
public class LibraryScanner {

    private static final BasicLogger LOG = RadioLocal.logger;
    private final Path root;               // cartella da scansire
    private final Path dbFile;             // <root>/library.db

    /**
     * Costruttore.
     *
     * @param root
     */
    public LibraryScanner(Path root) {
        this.root = root;
        this.dbFile = root.resolve("library.db");
    }

    /**
     * Ritorna la libreria, scegliendo la modalità di operazione.
     *
     * @param forced se {@code true} forza una scansione del file‑system (oppure
     * se il DB non esiste); se {@code false} carica la libreria dal DB se
     * presente.
     * @return la {@link Library} pronta all’uso
     * @throws IOException se fallisce la lettura dei file audio
     * @throws SQLException se falliscono le operazioni su SQLite
     */
    public Library scan() throws IOException, SQLException {
        return scan(false);
    }

    public Library scan(boolean forced) throws IOException, SQLException {
        if (forced || !Files.exists(dbFile)) {
            LOG.info("Forced scan avviata – la libreria sarà ricostruita da zero");
            Library lib = new Library();
            scanFileSystem(lib);
            LOG.info("Scansione terminata: %d artisti, %d tracce"
                    .formatted(lib.getArtists().size(), lib.getAllTracks().size()));
            persistLibrary(lib);
            return lib;
        } else {
            LOG.info("Caricamento libreria dal DB locale %s".formatted(dbFile));
            return loadFromDb();
        }
    }

    /* ---------------------------------------------------------------
       1)  SCANSIONE DEL FILE‑SYSTEM (senza lambda)
       --------------------------------------------------------------- */
    private void scanFileSystem(Library lib) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isDirectory() && hasAudioExtension(file)) {
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

    /**
     * Verifica se il nome di un file termina con .mp3 o .ogg
     * (case‑insensitive).
     */
    private boolean hasAudioExtension(Path p) {
        String s = p.getFileName().toString().toLowerCase();
        return s.endsWith(".mp3") || s.endsWith(".ogg");
    }

    /**
     * Legge i metadati di un singolo file audio e lo inserisce nella Library.
     */
    private void processFile(Path curPath, Library lib) {
        try {
            AudioFile af = AudioFileIO.read(curPath.toFile());
            org.jaudiotagger.tag.Tag tag = af.getTag();

            String title = (tag != null) ? tag.getFirst(FieldKey.TITLE) : inferTitleFromPath(curPath);
            String artist = (tag != null) ? tag.getFirst(FieldKey.ARTIST) : inferArtistFromPath(curPath);
            String album = (tag != null) ? tag.getFirst(FieldKey.ALBUM) : inferAlbumFromPath(curPath);
            int duration = (int) af.getAudioHeader().getTrackLength();

            //  ----  Costruzione della gerarchia Artist → Album → Track  ----
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
        } catch (Exception e) {
            LOG.error("Impossibile leggere il file %s".formatted(curPath), e);
        }
    }

    private String inferTitleFromPath(Path p) {
        // /root/Letter/Artist/Album?/title.mp3 
        String fileName = p.getFileName().toString();
        String[] split = fileName.split("\\.(?=[^\\.]+$)");
        return split[0];
    }

    private String inferArtistFromPath(Path p) {
        // /root/Letter/Artist/Album?/title.mp3 
        int nFolders = p.getNameCount() - 1;
        for (int i = 0; i < nFolders; i++) {
            if (p.getName(i).toString().length() == 1 && i < nFolders) {
                return p.getName(i + 1).toString();
            }
        }
        return "Artista sconosciuto";
    }

    private String inferAlbumFromPath(Path p) {
        // /root/Letter/Artist/Album?/title.mp3 
        int nFolders = p.getNameCount() - 1;
        for (int i = 0; i < nFolders; i++) {
            if (p.getName(i).toString().length() == 1 && i < nFolders - 2) {
                return p.getName(i + 2).toString();
            }
        }
        return "Album sconosciuto";
    }

    /* ---------------------------------------------------------------
       2)  PERSISTENZA SU SQLITE (senza lambda)
       --------------------------------------------------------------- */
    /**
     * Crea le tabelle, se non esistono già.
     */
    private void initialiseDb(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS artist ("
                    + "  id   INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  name TEXT NOT NULL UNIQUE"
                    + ");");
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS album ("
                    + "  id        INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  name      TEXT NOT NULL,"
                    + "  artist_id INTEGER NOT NULL,"
                    + "  UNIQUE(name, artist_id),"
                    + "  FOREIGN KEY(artist_id) REFERENCES artist(id) ON DELETE CASCADE"
                    + ");");
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS track ("
                    + "  id        INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  path      TEXT NOT NULL,"
                    + "  title     TEXT,"
                    + "  duration  INTEGER,"
                    + "  album_id  INTEGER NOT NULL,"
                    + "  FOREIGN KEY(album_id) REFERENCES album(id) ON DELETE CASCADE"
                    + ");");
        }
    }

    /**
     * Salva l’intera Library nel file SQLite.
     */
    private void persistLibrary(Library lib) throws SQLException {
        // Se esiste un DB vecchio lo sovrascriviamo (contenuto completamente ricostruito)
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            conn.setAutoCommit(false);
            initialiseDb(conn);

            // Mappa nome artista → id (per evitare query ripetute)
            Map<String, Long> artistIdMap = new HashMap<>();

            // 1) Inserimento artisti
            String insertArtistSql = "INSERT INTO artist(name) VALUES(?)";
            try (PreparedStatement insertArtist = conn.prepareStatement(insertArtistSql,
                    Statement.RETURN_GENERATED_KEYS); PreparedStatement selectArtist = conn.prepareStatement(
                            "SELECT id FROM artist WHERE name = ?")) {

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

            // 2) Inserimento album
            String insertAlbumSql = "INSERT INTO album(name, artist_id) VALUES(?,?)";
            Map<String, Long> albumIdMap = new HashMap<>(); // chiave: artistId|albumName
            try (PreparedStatement insertAlbum = conn.prepareStatement(insertAlbumSql,
                    Statement.RETURN_GENERATED_KEYS); PreparedStatement selectAlbum = conn.prepareStatement(
                            "SELECT id FROM album WHERE name = ? AND artist_id = ?")) {

                for (Artist artist : lib.getArtists()) {
                    Long aId = artistIdMap.get(artist.getName());
                    for (Album album : artist.getAlbums()) {
                        String key = aId + "|" + album.getName();
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
                        albumIdMap.put(key, alId);
                    }
                }
            }

            // 3) Inserimento tracce
            String insertTrackSql = "INSERT INTO track(path, title, duration, album_id) VALUES(?,?,?,?)";
            try (PreparedStatement insertTrack = conn.prepareStatement(insertTrackSql)) {

                for (Artist artist : lib.getArtists()) {
                    Long aId = artistIdMap.get(artist.getName());
                    for (Album album : artist.getAlbums()) {
                        String key = aId + "|" + album.getName();
                        Long alId = albumIdMap.get(key);
                        for (Track track : album.getTracks()) {
                            insertTrack.setString(1, track.getFile().getAbsolutePath());
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
            LOG.info("Library salvata su SQLite: %s".formatted(dbFile));
        }
    }

    /**
     * Cerca l’id dell’artista dato il suo nome; ritorna {@code null} se non
     * trovato.
     */
    private Long findArtistId(PreparedStatement stmt, String name) throws SQLException {
        stmt.setString(1, name);
        try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("id");
            }
        }
        return null;
    }

    /**
     * Cerca l’id dell’album dato nome + artist_id; ritorna {@code null} se non
     * trovato.
     */
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

    /**
     * Carica l’intera Library dal file SQLite.
     */
    private Library loadFromDb() throws SQLException {
        Library lib = new Library();

        if (!Files.exists(dbFile)) {
            LOG.warn("DB non trovato: %s – ritorna una library vuota".formatted(dbFile));
            return lib;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString())) {
            initialiseDb(conn);   // garantisce che le tabelle esistano

            // 1) Caricamento artisti
            Map<Long, Artist> artistById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name FROM artist")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    Artist artist = lib.getOrCreateArtist(name);
                    artistById.put(id, artist);
                }
            }

            // 2) Caricamento album
            Map<Long, Album> albumById = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, name, artist_id FROM album")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    long artistId = rs.getLong("artist_id");
                    Artist artist = artistById.get(artistId);
                    if (artist == null) {
                        LOG.warn("Album con artist_id %d non trovato – lo salto".formatted(artistId));
                        continue;
                    }
                    Album album = new Album(name, artist);
                    artist.addAlbum(album);
                    albumById.put(id, album);
                }
            }

            // 3) Caricamento tracce
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                    "SELECT path, title, duration, album_id FROM track")) {
                while (rs.next()) {
                    String pathStr = rs.getString("path");
                    String title = rs.getString("title");
                    int duration = rs.getInt("duration");
                    long albumId = rs.getLong("album_id");

                    Album album = albumById.get(albumId);
                    if (album == null) {
                        LOG.warn("Track con album_id %d non trovato – lo salto".formatted(albumId));
                        continue;
                    }

                    Artist artist = album.getArtist();          // assumiamo getter getArtist()
                    String artistName = artist.getName();      // e getName()
                    String albumName = album.getName();

                    Track track = new Track(new File(pathStr), title, artistName, albumName, duration);
                    album.addTrack(track);
                }
            }
        }

        LOG.info("Library caricata da SQLite: %d artisti, %d tracce"
                .formatted(lib.getArtists().size(), lib.getAllTracks().size()));
        return lib;
    }
}
