package claudiosoft.radiolocal.model;

import java.io.File;
import java.util.Objects;

public final class Track {

    private final File file;
    private final String title;
    private final String artist;
    private final String album;
    private final int durationSec;   // opzionale, 0 se ignoto

    public Track(File file, String title, String artist, String album, int durationSec) {
        this.file = Objects.requireNonNull(file);
        this.title = title != null ? title : file.getName();
        this.artist = artist != null ? artist : "Artista sconosciuto";
        this.album = album != null ? album : "Album sconosciuto";
        this.durationSec = durationSec;
    }

    public File getFile() {
        return file;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public int getDurationSec() {
        return durationSec;
    }

    @Override
    public String toString() {
        return String.format("%s – %s (%s)", artist, title, album);
    }
}
