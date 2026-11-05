package claudiosoft.radiolocal.model;

import java.util.*;

public final class Library {

    private final Map<String, Artist> artists = new HashMap<>();

    public Artist getOrCreateArtist(String name) {
        String normalized = normalize(name);
        return artists.computeIfAbsent(normalized, k -> new Artist(name));
    }

    private String normalize(String input) {
        if (input == null) {
            return "unknownArtist";
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? "unknownArtist" : trimmed.toLowerCase(Locale.ROOT);
    }

    public Collection<Artist> getArtists() {
        return Collections.unmodifiableCollection(artists.values());
    }

    public List<Track> getAllTracks() {
        List<Track> result = new ArrayList<>();
        for (Artist a : artists.values()) {
            for (Album al : a.getAlbums()) {
                result.addAll(al.getTracks());
            }
        }
        return result;
    }

    public List<Track> getTracksByArtist(String artistName) {
        String normalized = normalize(artistName);
        Artist a = artists.get(normalized);
        if (a == null) {
            return Collections.emptyList();
        }

        List<Track> list = new ArrayList<>();
        for (Album al : a.getAlbums()) {
            list.addAll(al.getTracks());
        }
        return list;
    }

    public List<String> getArtistNames() {
        List<String> names = new ArrayList<>();
        for (Artist a : artists.values()) {
            names.add(a.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
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
}
