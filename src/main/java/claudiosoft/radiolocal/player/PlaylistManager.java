package claudiosoft.radiolocal.player;

import claudiosoft.commons.BasicLogger;
import claudiosoft.radiolocal.RadioLocal;
import claudiosoft.radiolocal.model.Track;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coda di brani in shuffle continuo.
 */
public class PlaylistManager {

    private static final BasicLogger LOG = RadioLocal.logger;

    private final List<Track> source;
    private List<Track> shuffled;
    private int index = -1;      // -1 → nessun brano ancora

    public PlaylistManager(List<Track> source) {
        this.source = List.copyOf(source);
        reshuffle();
    }

    private void reshuffle() {
        shuffled = new ArrayList<>(source);
        Collections.shuffle(shuffled);
        index = -1;
        LOG.info("Playlist reshuffled (%d tracks)".formatted(shuffled.size()));
    }

    public synchronized Track next() {
        index++;
        if (index >= shuffled.size()) {
            reshuffle();
            index = 0;
        }
        return shuffled.get(index);
    }

    public synchronized Track previous() {
        index--;
        if (index < 0) {
            reshuffle();
            index = shuffled.size() - 1;
        }
        return shuffled.get(index);
    }

    public synchronized Track current() {
        if (index < 0) {
            return null;
        }
        return shuffled.get(index);
    }
}
