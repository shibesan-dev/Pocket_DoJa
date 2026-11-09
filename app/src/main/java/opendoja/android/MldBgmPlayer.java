package opendoja.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;

import opendoja.audio.mld.MLD;
import opendoja.audio.mld.MLDPlayer;
import opendoja.audio.mld.MLDPlayerEvent;
import opendoja.audio.mld.MLDSynth;

/**
 * For homescreen bgm.
 * Emulate mld, use built in source.
 * Loop playback.
 */
final class MldBgmPlayer {
    private static final int BUFFER_FRAMES = 2048;
    private static final int CHANNELS = 2;

    private final MLDPlayer player;
    private final AudioTrack track;
    private final Object stateLock = new Object();
    private Thread renderThread;
    private boolean paused;
    private boolean closed;
    private volatile int currentTimeMillis;

    static boolean isMldPath(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".mld");
    }

    MldBgmPlayer(File file) throws Exception {
        byte[] data = readAllBytes(file);
        MLD mld = new MLD(data);
        MLDSynth synth = MLDSynth.resolveConfigured();
        int sampleRate = Math.round(synth.defaultSampleRate);
        player = new MLDPlayer(mld, synth.createSamplerProvider(), sampleRate);

        // Cuepoint MLDs loop internally; flat MLDs raise EVENT_END, which the
        // render loop handles by restarting the sequence. Keeping voices alive
        // across the loop boundary avoids a clipped seam.

        player.setLoopEnabled(true);
        player.setLoopStopAll(false);
        player.setPlaybackEventsEnabled(true);

        int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minBuffer, BUFFER_FRAMES * CHANNELS * 2) * 2;
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack initialization failed");
        }
    }

    void start(int startPositionMillis) {
        synchronized (stateLock) {
            if (closed || renderThread != null) {
                return;
            }
            paused = false;
            renderThread = new Thread(() -> renderLoop(startPositionMillis), "openDoJa-bgm-mld");
            renderThread.setDaemon(true);
            renderThread.start();
        }
    }

    void pause() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            paused = true;
            try {
                track.pause();
            } catch (Exception ignored) {
            }
        }
    }

    void resume() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            paused = false;
            try {
                track.play();
            } catch (Exception ignored) {
            }
            stateLock.notifyAll();
        }
    }

    boolean isPlaying() {
        synchronized (stateLock) {
            return !closed && !paused && renderThread != null;
        }
    }

    int getCurrentTimeMillis() {
        return currentTimeMillis;
    }

    void release() {
        Thread toJoin;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            paused = false;
            stateLock.notifyAll();
            toJoin = renderThread;
            renderThread = null;
        }
        try {
            track.pause();
            track.flush();
        } catch (Exception ignored) {
        }
        if (toJoin != null && toJoin != Thread.currentThread()) {
            try {
                toJoin.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            track.release();
        } catch (Exception ignored) {
        }
    }

    private void renderLoop(int startPositionMillis) {
        float[] mix = new float[BUFFER_FRAMES * CHANNELS];
        short[] pcm = new short[BUFFER_FRAMES * CHANNELS];
        boolean[] restarted = new boolean[1];
        try {
            player.reset();
            if (startPositionMillis > 0) {
        // Seeking past the end reports a finished sequence; loop
        // straight back to the start in that case.

                if (player.setTime(startPositionMillis / 1000.0d)) {
                    player.reset();
                }
            }
            track.play();
            while (true) {
                synchronized (stateLock) {
                    while (paused && !closed) {
                        try {
                            stateLock.wait();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (closed) {
                        return;
                    }
                }
                int rendered = player.render(mix, 0, BUFFER_FRAMES);
                restarted[0] = false;
                player.drainEvents(event -> {
                    if (event.type == MLDPlayer.EVENT_END) {
                        player.reset();
                        restarted[0] = true;
                    }
                });
                if (restarted[0]) {

    // Discard the chunk spanning the seam and re-render from
    // the fresh sequence so the loop point stays clean.

                    continue;
                }
                if (rendered < 0) {
                    player.reset();
                    continue;
                }
                if (rendered == 0) {
                    continue;
                }
                currentTimeMillis = (int) Math.round(player.getTime() * 1000.0d);
                for (int i = 0; i < rendered * CHANNELS; i++) {
                    float sample = Math.max(-1.0f, Math.min(1.0f, mix[i]));
                    pcm[i] = (short) Math.round(sample * 32767.0f);
                }
                int written = track.write(pcm, 0, rendered * CHANNELS, AudioTrack.WRITE_BLOCKING);
                if (written <= 0) {
                    return;
                }
            }
        } catch (Throwable throwable) {
            opendoja.host.OpenDoJaLog.error(MldBgmPlayer.class, "MLD BGM render failed", throwable);
        } finally {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
