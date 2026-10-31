package claudiosoft.radiolocal.model;

import java.util.*;

public final class Library {

    private final Map<String, Artist> artists = new HashMap<>();

    public Artist getOrCreateArtist(String name) {
        return artists.computeIfAbsent(name, Artist::new);
    }

    public Collection<Artist> getArtists() {
        return Collections.unmodifiableCollection(artists.values());
    }

    /**
     * Restituisce tutti i brani (flatten) per costruire la playlist
     */
    public List<Track> getAllTracks() {
        List<Track> result = new ArrayList<>();
        for (Artist a : artists.values()) {
            for (Album al : a.getAlbums()) {
                result.addAll(al.getTracks());
            }
        }
        return result;
    }

    /**
     * Restituisce tutti i brani dell’artista indicato (case‑insensitive).
     */
    public List<Track> getTracksByArtist(String artistName) {
        for (Artist a : artists.values()) {
            if (a.getName().equalsIgnoreCase(artistName)) {
                List<Track> list = new ArrayList<>();
                for (Album al : a.getAlbums()) {
                    list.addAll(al.getTracks());
                }
                return list;
            }
        }
        return Collections.emptyList();
    }

    /**
     * Restituisce tutti i brani il cui titolo contiene la stringa `query`
     * (case‑insensitive).
     */
    public List<Track> searchTracksByTitle(String query) {
        String low = query.toLowerCase(Locale.ROOT);
        List<Track> list = new ArrayList<>();
        for (Artist a : artists.values()) {
            for (Album al : a.getAlbums()) {
                for (Track t : al.getTracks()) {
                    if (t.getTitle().toLowerCase(Locale.ROOT).contains(low)) {
                        list.add(t);
                    }
                }
            }
        }
        return list;
    }

    /**
     * Restituisce i nomi di tutti gli artisti (ordinati).
     */
    public List<String> getArtistNames() {
        List<String> names = new ArrayList<>();
        for (Artist a : artists.values()) {
            names.add(a.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
