package claudiosoft.radiolocal.model;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Objects;

public final class Track {

    private final File file;
    private final String title;
    private final String artist;
    private final String album;
    private final int durationSec;   // opzionale, 0 se ignoto
    private String genre;
    private int playCount = 0;
    private LocalDateTime lastPlayed;
    private Integer preferenceScore; // 1-5
    private boolean isUnknown = false;

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

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public int getPlayCount() {
        return playCount;
    }

    public void setPlayCount(int playCount) {
        this.playCount = playCount;
    }

    public LocalDateTime getLastPlayed() {
        return lastPlayed;
    }

    public void setLastPlayed(LocalDateTime lastPlayed) {
        this.lastPlayed = lastPlayed;
    }

    public Integer getPreferenceScore() {
        return preferenceScore;
    }

    public void setPreferenceScore(Integer preferenceScore) {
        this.preferenceScore = preferenceScore;
    }

    public boolean isIsUnknown() {
        return isUnknown;
    }

    public void setIsUnknown(boolean isUnknown) {
        this.isUnknown = isUnknown;
    }

    @Override
    public String toString() {
        return String.format("%s – %s (%s)", artist, title, album);
    }
}
