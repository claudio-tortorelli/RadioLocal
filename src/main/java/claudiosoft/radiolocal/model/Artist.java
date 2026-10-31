package claudiosoft.radiolocal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Artist {

    private final String name;
    private final List<Album> albums = new ArrayList<>();

    public Artist(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public String getName() {
        return name;
    }

    public void addAlbum(Album a) {
        albums.add(a);
    }

    public List<Album> getAlbums() {
        return Collections.unmodifiableList(albums);
    }
}
