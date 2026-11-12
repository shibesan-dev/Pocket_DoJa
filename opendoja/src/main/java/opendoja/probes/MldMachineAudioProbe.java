package opendoja.probes;

import opendoja.audio.mld.MLD;
import opendoja.audio.mld.MLDPlayer;
import opendoja.audio.mld.MLDSynth;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders MLD files that carry machine-dependent {@code 0x8000} sample slots
 * and reports whether any audible output is produced.
 */
public final class MldMachineAudioProbe {
    private MldMachineAudioProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                "usage: MldMachineAudioProbe <mld-file> [...]");
        }

        MLDSynth synth = MLDSynth.FUETREK;
        int failures = 0;
        for (String arg : args) {
            byte[] source = Files.readAllBytes(Path.of(arg));
            MLD mld = new MLD(firstMeloChunk(source));
            MLDPlayer player = new MLDPlayer(mld,
                synth.createSamplerProvider(), synth.defaultSampleRate);
            player.setLoopEnabled(false);

            int maxFrames = (int) Math.ceil(
                (mld.getDuration(true) + 1.0) * synth.defaultSampleRate);
            float[] buffer = new float[512];
            int totalFrames = 0;
            int audibleFrames = 0;
            float peak = 0.0f;
            double energy = 0.0;
            int spins = 0;
            while (totalFrames < maxFrames) {
                int frames = player.render(buffer, 0, 256);
                if (frames < 0) {
                    break;
                }
                if (frames == 0) {
                    if (++spins > 16) {
                        break;
                    }
                    continue;
                }
                spins = 0;
                totalFrames += frames;
                for (int i = 0; i < frames * 2; i++) {
                    float sample = buffer[i];
                    float magnitude = Math.abs(sample);
                    energy += sample * sample;
                    if (magnitude > peak) {
                        peak = magnitude;
                    }
                    if (magnitude > 0.001f) {
                        audibleFrames++;
                    }
                }
            }
            double rms = totalFrames == 0 ? 0.0
                : Math.sqrt(energy / (totalFrames * 2.0));
            System.out.println(arg + " frames=" + totalFrames
                + " audibleSamples=" + audibleFrames
                + " rms=" + rms + " peak=" + peak
                + " duration=" + mld.getDuration(true));
            if (peak < 0.01f) {
                failures++;
            }
        }
        if (failures != 0) {
            throw new AssertionError(
                failures + " MLD file(s) produced no audible machine audio");
        }
        System.out.println("MldMachineAudioProbe OK");
    }

    private static byte[] firstMeloChunk(byte[] source) {
        for (int i = 0; i <= source.length - 8; i++) {
            if (source[i] != 'm' || source[i + 1] != 'e'
                || source[i + 2] != 'l' || source[i + 3] != 'o') {
                continue;
            }
            int length = ((source[i + 4] & 0xFF) << 24)
                | ((source[i + 5] & 0xFF) << 16)
                | ((source[i + 6] & 0xFF) << 8)
                | (source[i + 7] & 0xFF);
            int end = Math.min(source.length, i + 8 + Math.max(length, 0));
            byte[] mld = new byte[end - i];
            System.arraycopy(source, i, mld, 0, mld.length);
            return mld;
        }
        return source;
    }
}
