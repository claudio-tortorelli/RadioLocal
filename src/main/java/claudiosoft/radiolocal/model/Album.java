package claudiosoft.radiolocal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Album {

    private final String name;
    private final Artist artist;
    private final List<Track> tracks = new ArrayList<>();

    public Album(String name, Artist artist) {
        this.name = Objects.requireNonNull(name);
        this.artist = Objects.requireNonNull(artist);
    }

    public String getName() {
        return name;
    }

    public Artist getArtist() {
        return artist;
    }

    public void addTrack(Track t) {
        tracks.add(t);
    }

    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }
}
