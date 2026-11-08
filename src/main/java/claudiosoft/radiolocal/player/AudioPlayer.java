package claudiosoft.radiolocal.player;

import claudiosoft.radiolocal.model.Track;

public interface AudioPlayer {

    void play(Track track);

    void pause();

    void stop();                 // interrompe il brano corrente

    void setVolume(int percent); // 0‑100

    int getVolume();

    boolean isPlaying();

    void resume();

    Track getCurrentTrack();
}
