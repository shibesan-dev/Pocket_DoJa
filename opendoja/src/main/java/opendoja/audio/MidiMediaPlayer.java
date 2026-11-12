package opendoja.audio;

import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;

import opendoja.host.OpenDoJaLog;

import java.io.IOException;

/**
 * Standard MIDI playback backed by the Android platform synthesizer.
 *
 * <p>DoJa MIDI media is a Standard MIDI File, but Android has no
 * {@code javax.sound.midi} implementation. The platform media framework can
 * render SMF data through the built-in MIDI extractor and Sonivox synthesizer,
 * so this class adapts {@link MediaPlayer} to the operations an
 * {@code AudioPresenter} needs: start/stop, pause/resume, looping, seeking,
 * volume, and position reporting.</p>
 */
public final class MidiMediaPlayer implements AutoCloseable {
    /** Callback invoked when playback finishes or fails. */
    public interface Listener {
        void onComplete(MidiMediaPlayer source, long playbackToken);

        void onFailure(MidiMediaPlayer source, long playbackToken, Exception error);
    }

    private static final int VOLUME_MAX = 100;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private MediaPlayer player;
    private long playbackToken = Long.MIN_VALUE;
    private int volumeLevel = VOLUME_MAX;
    private float playbackRate = 1.0f;
    private int durationMillis;
    private int remainingRepeats;
    private boolean loopingForever;
    private boolean active;
    private boolean paused;
    private boolean closed;

    public MidiMediaPlayer(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts playback of a Standard MIDI File.
     *
     * @param midiBytes           complete SMF data
     * @param loopCount           DoJa loop semantics: total number of
     *                            playbacks, or {@code -1} for continuous
     * @param startPositionMillis start position in milliseconds
     * @param playbackToken       token forwarded to completion callbacks
     */
    public void start(byte[] midiBytes, int loopCount, int startPositionMillis,
                      long playbackToken) throws IOException {
        if (midiBytes == null || midiBytes.length == 0) {
            throw new IOException("empty MIDI data");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            releaseLocked();
            this.playbackToken = playbackToken;
            this.active = true;
            this.paused = false;
            this.durationMillis = 0;
            this.loopingForever = loopCount < 0;
            // DoJa counts total playbacks, so loopCount=1 means "play once".
            this.remainingRepeats = loopCount < 0 ? 0 : Math.max(0, loopCount - 1);
            try {
                MediaPlayer candidate = new MediaPlayer();
                candidate.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                candidate.setDataSource(new ByteArrayMediaDataSource(midiBytes));
                candidate.setOnCompletionListener(mp -> handleCompletion(playbackToken));
                candidate.setOnErrorListener((mp, what, extra) -> {
                    handleFailure(playbackToken, new IOException(
                            "MIDI playback error (what=" + what + ", extra=" + extra + ")"));
                    return true;
                });
                candidate.prepare();
                this.player = candidate;
                this.durationMillis = Math.max(0, candidate.getDuration());
                applyVolumeLocked(candidate);
                applyRateLocked(candidate);
                if (loopingForever) {
                    candidate.setLooping(true);
                }
                if (startPositionMillis > 0) {
                    int position = durationMillis > 0
                            ? Math.min(startPositionMillis, durationMillis)
                            : startPositionMillis;
                    candidate.seekTo(position);
                }
                candidate.start();
                OpenDoJaLog.info(MidiMediaPlayer.class,
                        () -> "MIDI playback started: " + midiBytes.length + " bytes, duration="
                                + durationMillis + " ms, loop=" + loopCount
                                + ", start=" + startPositionMillis + " ms");
                if (durationMillis > 0 && startPositionMillis >= durationMillis) {
                    // Seeking to the end can complete synchronously; post it so
                    // the presenter's play() has returned before it sees the
                    // completion callback.
                    final long token = playbackToken;
                    mainHandler.post(() -> handleCompletion(token));
                }
            } catch (Exception e) {
                releaseLocked();
                active = false;
                this.playbackToken = Long.MIN_VALUE;
                if (e instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("MIDI playback failed", e);
            }
        }
    }

    public void pause() {
        synchronized (lock) {
            MediaPlayer current = player;
            if (current == null || !active || paused) {
                return;
            }
            try {
                current.pause();
                paused = true;
            } catch (IllegalStateException e) {
                OpenDoJaLog.debug(MidiMediaPlayer.class, "MIDI pause failed: " + e);
            }
        }
    }

    public void restart() {
        synchronized (lock) {
            MediaPlayer current = player;
            if (current == null || !active) {
                return;
            }
            try {
                current.start();
                paused = false;
            } catch (IllegalStateException e) {
                OpenDoJaLog.debug(MidiMediaPlayer.class, "MIDI restart failed: " + e);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            active = false;
            paused = false;
            playbackToken = Long.MIN_VALUE;
            releaseLocked();
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    public int getCurrentTimeMillis() {
        synchronized (lock) {
            MediaPlayer current = player;
            if (current == null) {
                return 0;
            }
            try {
                return Math.max(0, current.getCurrentPosition());
            } catch (IllegalStateException e) {
                return 0;
            }
        }
    }

    public int getTotalTimeMillis() {
        synchronized (lock) {
            return durationMillis;
        }
    }

    public void setVolumeLevel(int volumeLevel) {
        synchronized (lock) {
            this.volumeLevel = Math.max(0, Math.min(VOLUME_MAX, volumeLevel));
            MediaPlayer current = player;
            if (current != null) {
                applyVolumeLocked(current);
            }
        }
    }

    /**
     * Applies a tempo factor. Speeds other than {@code 1.0} use the platform
     * playback-rate adjustment; failure is non-fatal and leaves the previous
     * rate in effect.
     */
    public void setPlaybackRate(float rate) {
        synchronized (lock) {
            playbackRate = rate > 0.0f ? rate : 1.0f;
            MediaPlayer current = player;
            if (current != null) {
                applyRateLocked(current);
            }
        }
    }

    private void applyVolumeLocked(MediaPlayer target) {
        try {
            float gain = volumeLevel / (float) VOLUME_MAX;
            target.setVolume(gain, gain);
        } catch (IllegalStateException e) {
            OpenDoJaLog.debug(MidiMediaPlayer.class, "MIDI volume update failed: " + e);
        }
    }

    private void applyRateLocked(MediaPlayer target) {
        if (Math.abs(playbackRate - 1.0f) < 0.001f) {
            return;
        }
        try {
            PlaybackParams params = target.getPlaybackParams();
            params.setSpeed(playbackRate);
            target.setPlaybackParams(params);
        } catch (Exception e) {
            OpenDoJaLog.debug(MidiMediaPlayer.class,
                    "MIDI tempo change to " + playbackRate + "x unsupported: " + e);
        }
    }

    private void handleCompletion(long token) {
        boolean complete = false;
        synchronized (lock) {
            if (!active || token != playbackToken || player == null) {
                return;
            }
            if (loopingForever) {
                // setLooping(true) keeps restarting; completion should not fire.
                return;
            }
            if (remainingRepeats > 0) {
                remainingRepeats--;
                try {
                    player.seekTo(0);
                    player.start();
                    return;
                } catch (IllegalStateException e) {
                    OpenDoJaLog.debug(MidiMediaPlayer.class, "MIDI loop restart failed: " + e);
                }
            }
            active = false;
            paused = false;
            complete = true;
            playbackToken = Long.MIN_VALUE;
            releaseLocked();
        }
        if (complete) {
            listener.onComplete(this, token);
        }
    }

    private void handleFailure(long token, Exception error) {
        synchronized (lock) {
            if (token != playbackToken) {
                return;
            }
            active = false;
            paused = false;
            playbackToken = Long.MIN_VALUE;
            releaseLocked();
        }
        listener.onFailure(this, token, error);
    }

    private void releaseLocked() {
        MediaPlayer current = player;
        player = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isPlaying()) {
                current.stop();
            }
        } catch (IllegalStateException ignored) {
        }
        try {
            current.reset();
        } catch (IllegalStateException ignored) {
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            active = false;
            paused = false;
            playbackToken = Long.MIN_VALUE;
            releaseLocked();
        }
    }

    /** In-memory source so MIDI playback needs no cache files or context. */
    private static final class ByteArrayMediaDataSource extends MediaDataSource {
        private final byte[] data;

        ByteArrayMediaDataSource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position < 0 || position >= data.length) {
                return -1;
            }
            int available = data.length - (int) position;
            int length = Math.min(size, available);
            System.arraycopy(data, (int) position, buffer, offset, length);
            return length;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
        }
    }
}
