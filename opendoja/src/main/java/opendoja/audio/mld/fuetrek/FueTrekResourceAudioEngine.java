package opendoja.audio.mld.fuetrek;

import opendoja.audio.mld.*;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * lib002/FueTrek implementation of top-level MLD resource audio.
 *
 * <p>This engine handles audio that lives outside the normal note stream:
 * top-level {@code ainf}/{@code adat} resources triggered by {@code 0x7f}
 * resource events. In practice this is the path used by titles such as Taiko's
 * top-level ADPCM effects and the FueTrek-routed stereo/3D resource playback
 * corpus.</p>
 *
 * <p>The generic MLD parser only surfaces the resource chunks and events.
 * Everything after that, including ADPCM decode shape, gain tables, route
 * defaults, and spatial motion handling, is FueTrek-specific behavior and
 * therefore lives in this synth package.</p>
 */
public final class FueTrekResourceAudioEngine
    implements MLDResourceAudioEngine {
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final int DEFAULT_LEVEL = 127;
    private static final int DEFAULT_PAN = 64;
    private static final int DEFAULT_BANK_LEVEL = 127;
    private static final int DEFAULT_BANK_PAN = 64;
    private static final int DEFAULT_GLOBAL_LEVEL = 64;
    private static final int PLUGIN_EXPORT_GAIN_SHIFT = 2;
    private static final int NATIVE_OUTPUT_SAMPLE_RATE = 32000;
    private static final float INV_MIX_SCALE = 1.0f / 8388608.0f;
    // lib002 creates the DS handle with packet[0x0c] = 8, so DSModule keeps the block size at 4096 frames.
    private static final int NATIVE_MOTION_BLOCK_FRAMES = 4096;
    private static final int MACHINE_SLOT_COUNT = 64;
    private static final int MACHINE_VOICE_LIMIT = 16;
    private static final int MACHINE_START_LEVEL = 127;
    private final DecodedResourceAudio[] decodedCatalog;
    private final ChannelState[] channelStates = new ChannelState[64];
    private final ArrayList<ActiveClip> activeClips = new ArrayList<>();
    private final MachineSlot[] machineSlots = new MachineSlot[MACHINE_SLOT_COUNT];
    private final ArrayList<MachineVoice> machineVoices = new ArrayList<>();
    private final int[] initialAudioRouteRaw = new int[64];
    private final int[] initialSynthRouteRaw = new int[64];
    private final int outputSampleRate;

    public FueTrekResourceAudioEngine(MLD mld, float sampleRate) {
        this.outputSampleRate = Math.max(1, Math.round(sampleRate));
        MLDADPCM[] adpcms = mld.resourceAdpcms();
        this.decodedCatalog = new DecodedResourceAudio[adpcms.length];
        Arrays.fill(this.initialAudioRouteRaw, -1);
        Arrays.fill(this.initialSynthRouteRaw, -1);
        for (int i = 0; i < this.channelStates.length; i++) {
            this.channelStates[i] = new ChannelState();
        }
        for (int i = 0; i < adpcms.length; i++) {
            this.decodedCatalog[i] = this.decodeCatalogEntry(adpcms[i]);
        }
        this.parseThrdAudioRoutes(mld.resourceThreadDefaults());
        this.reset();
    }

    public void reset() {
        this.activeClips.clear();
        this.machineVoices.clear();
        Arrays.fill(this.machineSlots, null);
        for (ChannelState state : this.channelStates) {
            state.level = DEFAULT_LEVEL;
            state.pan = DEFAULT_PAN;
            state.auxMotion.clear();
        }
        for (int i = 0; i < this.channelStates.length; i++) {
            this.channelStates[i].audioRouteRaw = this.initialAudioRouteRaw[i];
            this.channelStates[i].synthRouteRaw = this.initialSynthRouteRaw[i];
        }
    }

    public void handleEvent(long framePosition, MLDEvent event) {
        int logicalChannel = clamp(0, this.channelStates.length - 1,
            event.getChannel());
        switch (event.getId()) {
            case MLD.EVENT_RESOURCE_START:
                this.startClip(framePosition, logicalChannel, event);
                break;
            case MLD.EVENT_RESOURCE_STOP:
                this.stopClip(framePosition, logicalChannel,
                    event.getResourceIndex());
                break;
            case MLD.EVENT_RESOURCE_LEVEL:
                this.channelStates[logicalChannel].level = clamp(0, 127,
                    event.getValue2x() >= 0 ? event.getValue2x() :
                        DEFAULT_LEVEL);
                break;
            case MLD.EVENT_RESOURCE_PAN:
                this.channelStates[logicalChannel].pan = clamp(0, 127,
                    event.getValue2x() >= 0 ? event.getValue2x() :
                        DEFAULT_PAN);
                break;
            case MLD.EVENT_RESOURCE_CONFIG:
                if (event.getResourceAudioTarget()) {
                    int routeRaw = event.getResourceConfigClear() ? -1 :
                        Math.max(0, event.getResourceConfigRawValue());
                    this.channelStates[logicalChannel].audioRouteRaw = routeRaw;
                    this.updateLiveAudioRoute(framePosition, logicalChannel,
                        routeRaw, event.getResourceConfigClear());
                    if (event.getResourceConfigClear()) {
                        this.channelStates[logicalChannel].auxMotion.clear();
                    }
                } else {
                    int routeRaw = event.getResourceConfigClear() ? -1 :
                        Math.max(0, event.getResourceConfigRawValue());
                    this.channelStates[logicalChannel].synthRouteRaw = routeRaw;
                    this.updateLiveSynthRoute(logicalChannel, routeRaw);
                }
                break;
            case MLD.EVENT_RESOURCE_AUX:
                this.handleAuxMotion(framePosition, logicalChannel, event);
                break;
            default:
                break;
        }
    }

    /**
     * Consume a machine-dependent ext-info event (the {@code 0xFF 0xFF}
     * descriptor family).
     *
     * @param framePosition Current player frame position.
     * @param trackIndex Zero-based source track index.
     * @param message Descriptor prefix and body.
     * @return {@code true} if this engine recognized the descriptor.
     */
    public boolean handleMachineDependent(long framePosition, int trackIndex,
            byte[] message) {
        if (message == null || message.length < 3) {
            return false;
        }
        int prefix = message[0] & 0xFF;
        // FueTrek machine-dependent descriptors carry the 0x41 marker. Other
        // first bytes are raw channel SysEx (0x00-0x3f) or the 0x71 wrapper
        // family, both of which the sampler owns.
        if (prefix != 0x41) {
            return false;
        }
        switch (message[1] & 0xFF) {
            case 0x81:
                this.setMachineLevel(trackIndex, message[2] & 0xFF);
                return true;
            case 0x80:
            case 0x82:
                this.setMachinePan(trackIndex, message[2] & 0xFF);
                return true;
            case 0x83:
                this.handleMachineSlot(framePosition, trackIndex, message, 2,
                    false);
                return true;
            case 0x86:
                this.handleMachineSlot(framePosition, trackIndex, message, 2,
                    true);
                return true;
            default:
                return false;
        }
    }

    public void render(float[] samples, int offset, int frames, float left, float right, boolean clamp, long framePosition) {
        this.pruneFinished(framePosition);
        if (this.activeClips.isEmpty() && this.machineVoices.isEmpty()) {
            return;
        }

        float leftScale = left * INV_MIX_SCALE;
        float rightScale = right * INV_MIX_SCALE;
        long frameLimit = framePosition + frames;
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            long clipEnd = clip.stopFrame >= 0 ? clip.stopFrame : clip.endFrame();
            long mixStart = Math.max(framePosition, clip.startFrame);
            long mixEnd = Math.min(frameLimit, clipEnd);
            if (mixStart >= mixEnd) {
                continue;
            }

            int inputIndex = (int) (mixStart - clip.startFrame);
            int outputIndex = offset + (int) (mixStart - framePosition) * 2;
            long segmentStart = mixStart;
            while (segmentStart < mixEnd) {
                this.advanceClipMotion(clip, segmentStart);
                long segmentEnd = Math.min(mixEnd, this.nextSpatialBoundary(clip, segmentStart));
                int segmentFrames = (int) (segmentEnd - segmentStart);
                int segmentInputIndex = inputIndex + (int) (segmentStart - mixStart);
                int segmentOutputIndex = outputIndex + (int) (segmentStart - mixStart) * 2;
                StereoGain spatialGain = this.computeSpatialGain(clip);
                int leftGainQ16 = combineQ16(clip.leftGainQ16, spatialGain.leftQ16);
                int rightGainQ16 = combineQ16(clip.rightGainQ16, spatialGain.rightQ16);
                for (int frame = 0; frame < segmentFrames; frame++) {
                    int sample = clip.audio.frames[segmentInputIndex + frame];
                    int base = segmentOutputIndex + frame * 2;
                    samples[base] += scaleMixedSample(sample, leftGainQ16) * leftScale;
                    samples[base + 1] += scaleMixedSample(sample, rightGainQ16) * rightScale;
                    if (clamp) {
                        samples[base] = clampSample(samples[base]);
                        samples[base + 1] = clampSample(samples[base + 1]);
                    }
                }
                segmentStart = segmentEnd;
            }
        }
        this.renderMachineVoices(samples, offset, frames, leftScale,
            rightScale, clamp, framePosition, frameLimit);
        this.pruneFinished(frameLimit);
    }

    private void renderMachineVoices(float[] samples, int offset, int frames,
            float leftScale, float rightScale, boolean clamp,
            long framePosition, long frameLimit) {
        for (int i = 0; i < this.machineVoices.size(); i++) {
            MachineVoice voice = this.machineVoices.get(i);
            long mixStart = Math.max(framePosition, voice.startFrame);
            long mixEnd = Math.min(frameLimit, voice.endFrame());
            if (mixStart >= mixEnd) {
                continue;
            }
            StereoGain gain = this.computeStereoGain(
                this.channelStates[voice.logicalChannel],
                MACHINE_START_LEVEL);
            int inputIndex = (int) (mixStart - voice.startFrame);
            int outputIndex = offset + (int) (mixStart - framePosition) * 2;
            int mixFrames = (int) (mixEnd - mixStart);
            for (int frame = 0; frame < mixFrames; frame++) {
                int sample = voice.audio[inputIndex + frame];
                int base = outputIndex + frame * 2;
                samples[base] += scaleMixedSample(sample, gain.leftQ16) * leftScale;
                samples[base + 1] += scaleMixedSample(sample, gain.rightQ16) * rightScale;
                if (clamp) {
                    samples[base] = clampSample(samples[base]);
                    samples[base + 1] = clampSample(samples[base + 1]);
                }
            }
        }
    }

    public boolean hasLiveAudio(long framePosition) {
        this.pruneFinished(framePosition);
        return !this.activeClips.isEmpty() || !this.machineVoices.isEmpty();
    }

    public int framesUntilSilence(long framePosition) {
        this.pruneFinished(framePosition);
        int remaining = -1;
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            long end = clip.stopFrame >= 0 ? clip.stopFrame : clip.endFrame();
            if (end <= framePosition) {
                continue;
            }
            int frames = (int) Math.min(Integer.MAX_VALUE, end - framePosition);
            if (remaining == -1 || frames < remaining) {
                remaining = frames;
            }
        }
        for (int i = 0; i < this.machineVoices.size(); i++) {
            MachineVoice voice = this.machineVoices.get(i);
            long end = voice.endFrame();
            if (end <= framePosition) {
                continue;
            }
            int frames = (int) Math.min(Integer.MAX_VALUE, end - framePosition);
            if (remaining == -1 || frames < remaining) {
                remaining = frames;
            }
        }
        return Math.max(0, remaining);
    }

    private void setMachineLevel(int trackIndex, int packed) {
        int channel = clamp(0, this.channelStates.length - 1,
            trackIndex * 4 + ((packed >> 6) & 0x03));
        this.channelStates[channel].level = clamp(0, 127,
            2 * (packed & 0x3F));
    }

    private void setMachinePan(int trackIndex, int packed) {
        int channel = clamp(0, this.channelStates.length - 1,
            trackIndex * 4 + ((packed >> 6) & 0x03));
        this.channelStates[channel].pan = clamp(0, 127,
            2 * (packed & 0x3F));
    }

    private void handleMachineSlot(long framePosition, int trackIndex,
            byte[] message, int headerOffset, boolean durationPrefixed) {
        if (message.length < headerOffset + 3) {
            return;
        }
        int channelSlot = message[headerOffset] & 0xFF;
        int opFormat = message[headerOffset + 1] & 0xFF;
        int control = message[headerOffset + 2] & 0xFF;
        int operation = (opFormat >> 6) & 0x03;
        int formatCode = opFormat & 0x3F;
        int slotIndex = channelSlot & 0x3F;
        int localChannel = (channelSlot >> 6) & 0x03;
        int logicalChannel = clamp(0, this.channelStates.length - 1,
            trackIndex * 4 + localChannel);
        int payloadOffset = headerOffset + 3;
        int durationByteCount = -1;
        if (durationPrefixed) {
            if (message.length < headerOffset + 7) {
                return;
            }
            durationByteCount = readBigEndianInt(message, headerOffset + 3);
            payloadOffset += 4;
        }

        boolean load = operation == 0 || operation == 1;
        boolean start = operation == 1 || operation == 2;
        if ((control & 0x01) != 0 && operation != 2) {
            // A control bit of 1 asks the backend to refresh the slot
            // without consuming a new start operation.
            load = true;
            if (operation == 3) {
                start = false;
            }
        }
        if (!load && !start) {
            return;
        }

        if (load) {
            int sampleRate = machineSampleRate(formatCode);
            if (sampleRate <= 0 || machineCodedBits(formatCode) != 4
                    || payloadOffset >= message.length) {
                // Only the 4-bit ROHM variant is validated; 2-bit formats
                // (4/12/20) use a different codec and stay unsupported.
                return;
            }
            int[] pcm = MLDRohmADPCMDecoder.decode(message, payloadOffset,
                message.length - payloadOffset);
            int[] frames = new int[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                frames[i] = pcm[i] << 7;
            }
            int[] resampled = sampleRate == this.outputSampleRate
                ? frames
                : resampleLinear(frames, sampleRate, this.outputSampleRate);
            this.machineSlots[slotIndex] = new MachineSlot(formatCode,
                resampled);
            this.retireMachineVoices(logicalChannel, slotIndex);
        }

        if (!start) {
            return;
        }
        MachineSlot slot = this.machineSlots[slotIndex];
        if (slot == null || slot.audio.length == 0) {
            return;
        }
        int voiceFrames = slot.audio.length;
        if (durationByteCount >= 0) {
            long capped = machineDurationFrames(durationByteCount, formatCode);
            if (capped >= 0 && capped < voiceFrames) {
                voiceFrames = (int) capped;
            }
        }
        if (voiceFrames <= 0) {
            return;
        }
        this.startMachineVoice(framePosition, logicalChannel, slotIndex,
            slot.audio, voiceFrames);
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private long machineDurationFrames(int durationByteCount, int formatCode) {
        int sampleRate = machineSampleRate(formatCode);
        int codedBits = machineCodedBits(formatCode);
        if (sampleRate <= 0 || codedBits <= 0) {
            return -1L;
        }
        long samplesPerByte = 8L / codedBits;
        long sampleCount = ((long) durationByteCount * samplesPerByte)
            & 0xFFFF_FFFFL;
        long durationMillis = (sampleCount * 1000L + sampleRate / 2L)
            / sampleRate;
        return durationMillis * this.outputSampleRate / 1000L;
    }

    private static int machineSampleRate(int formatCode) {
        switch (formatCode) {
            case 4:
            case 5:
                return 8000;
            case 12:
            case 13:
                return 16000;
            case 20:
            case 21:
                return 32000;
            default:
                return -1;
        }
    }

    private static int machineCodedBits(int formatCode) {
        switch (formatCode) {
            case 4:
            case 12:
            case 20:
                return 2;
            case 5:
            case 13:
            case 21:
                return 4;
            default:
                return -1;
        }
    }

    private void startMachineVoice(long framePosition, int logicalChannel,
            int slotIndex, int[] audio, int length) {
        if (this.machineVoices.size() >= MACHINE_VOICE_LIMIT) {
            int oldestIndex = 0;
            long oldestStart = this.machineVoices.get(0).startFrame;
            for (int i = 1; i < this.machineVoices.size(); i++) {
                long start = this.machineVoices.get(i).startFrame;
                if (start < oldestStart) {
                    oldestStart = start;
                    oldestIndex = i;
                }
            }
            this.machineVoices.remove(oldestIndex);
        }
        this.machineVoices.add(new MachineVoice(logicalChannel, slotIndex,
            framePosition, audio, Math.min(length, audio.length)));
    }

    private void retireMachineVoices(int logicalChannel, int slotIndex) {
        for (int i = this.machineVoices.size() - 1; i >= 0; i--) {
            MachineVoice voice = this.machineVoices.get(i);
            if (voice.logicalChannel == logicalChannel &&
                voice.slotIndex == slotIndex) {
                this.machineVoices.remove(i);
            }
        }
    }

    private void startClip(long framePosition, int logicalChannel, MLDEvent event) {
        if (event.getResourceIndex() < 0 ||
            event.getResourceIndex() >= this.decodedCatalog.length) {
            return;
        }
        DecodedResourceAudio decoded =
            this.decodedCatalog[event.getResourceIndex()];
        if (decoded == null || decoded.frames.length == 0) {
            return;
        }

        this.retireMatchingClips(framePosition, logicalChannel,
            event.getResourceIndex());
        StereoGain gain = this.computeStereoGain(
            this.channelStates[logicalChannel], DEFAULT_LEVEL);
        AuxMotionPoint[] auxMotion = snapshotAuxMotion(
            this.channelStates[logicalChannel], event.getResourceIndex(),
            framePosition);
        ActiveClip clip = new ActiveClip(
                logicalChannel,
                event.getResourceIndex(),
                event.getResourcePitchByte(),
                framePosition,
                decoded,
                this.channelStates[logicalChannel].audioRouteRaw,
                this.channelStates[logicalChannel].synthRouteRaw,
                nativeRouteSlotId(event.getResourceIndex()),
                auxMotion.length,
                gain.leftQ16,
                gain.rightQ16);
        for (AuxMotionPoint point : auxMotion) {
            this.queueMotion(clip, point);
        }
        this.advanceClipMotion(clip, framePosition);
        this.activeClips.add(clip);
    }

    private void appendLiveMotion(long framePosition, int logicalChannel, AuxMotionPoint point) {
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            if (clip.stopFrame >= 0 || clip.logicalChannel != logicalChannel || clip.resourceIndex != point.resourceIndex) {
                continue;
            }
            this.advanceClipMotion(clip, framePosition);
            this.queueMotion(clip, point);
        }
    }

    private void updateLiveAudioRoute(long framePosition, int logicalChannel, int routeRaw, boolean clear) {
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            if (clip.stopFrame >= 0 || clip.logicalChannel != logicalChannel) {
                continue;
            }
            this.advanceClipMotion(clip, framePosition);
            clip.audioRouteRaw = routeRaw;
            if (!clear) {
                continue;
            }
            clip.liveMotion.clear();
            clip.nextMotionIndex = 0;
            clip.motionCurrentX = 0;
            clip.motionCurrentY = 0;
            clip.motionCurrentZ = 0;
            clip.motionTargetX = 0;
            clip.motionTargetY = 0;
            clip.motionTargetZ = 0;
            clip.motionDeltaX = 0;
            clip.motionDeltaY = 0;
            clip.motionDeltaZ = 0;
            clip.motionRemainingBlocks = 0;
            clip.nextMotionBlockFrame = Long.MAX_VALUE;
        }
    }

    private void updateLiveSynthRoute(int logicalChannel, int routeRaw) {
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            if (clip.stopFrame >= 0 || clip.logicalChannel != logicalChannel) {
                continue;
            }
            clip.synthRouteRaw = routeRaw;
        }
    }

    private void handleAuxMotion(long framePosition, int logicalChannel, MLDEvent event) {
        if (event.getResourceAuxStrength() < 0 ||
                event.getResourceAuxAzimuthDegrees() == Integer.MIN_VALUE ||
                event.getResourceAuxElevationDegrees() == Integer.MIN_VALUE ||
                event.getResourceAuxDurationRaw() < 0) {
            return;
        }
        AuxMotionPoint point = new AuxMotionPoint(
                framePosition,
                event.getResourceIndex(),
                event.getResourceAuxStrength(),
                event.getResourceAuxAzimuthDegrees(),
                event.getResourceAuxElevationDegrees(),
                event.getResourceAuxDurationRaw());
        this.channelStates[logicalChannel].auxMotion.add(point);
        this.appendLiveMotion(framePosition, logicalChannel, point);
    }

    private void stopClip(long framePosition, int logicalChannel, int resourceIndex) {
        for (int i = this.activeClips.size() - 1; i >= 0; i--) {
            ActiveClip clip = this.activeClips.get(i);
            if (clip.stopFrame >= 0 || clip.logicalChannel != logicalChannel) {
                continue;
            }
            if (resourceIndex < 0 || clip.resourceIndex == resourceIndex) {
                clip.stopFrame = Math.max(framePosition, clip.startFrame);
            }
        }
    }

    private void retireMatchingClips(long framePosition, int logicalChannel, int resourceIndex) {
        for (int i = this.activeClips.size() - 1; i >= 0; i--) {
            ActiveClip clip = this.activeClips.get(i);
            if (clip.stopFrame >= 0) {
                continue;
            }
            if (clip.logicalChannel != logicalChannel || clip.resourceIndex != resourceIndex) {
                continue;
            }
            clip.stopFrame = Math.max(framePosition, clip.startFrame);
        }
    }

    private static AuxMotionPoint[] snapshotAuxMotion(ChannelState state, int resourceIndex, long framePosition) {
        if (state.auxMotion.isEmpty()) {
            return new AuxMotionPoint[0];
        }
        ArrayList<AuxMotionPoint> matching = new ArrayList<>();
        for (int i = 0; i < state.auxMotion.size(); i++) {
            AuxMotionPoint point = state.auxMotion.get(i);
            if (point.framePosition > framePosition) {
                continue;
            }
            if (point.resourceIndex == resourceIndex) {
                matching.add(point);
            }
        }
        return matching.toArray(new AuxMotionPoint[0]);
    }

    private static int nativeMotionBlockCount(int durationMillis, int outputSampleRate) {
        if (durationMillis <= 0) {
            return 1;
        }
        long numerator = (long) outputSampleRate * durationMillis;
        long denominator = (long) NATIVE_MOTION_BLOCK_FRAMES * 1000L;
        return Math.max(1, (int) (numerator / denominator) + 1);
    }

    private void pruneFinished(long framePosition) {
        for (int i = 0; i < this.activeClips.size(); i++) {
            ActiveClip clip = this.activeClips.get(i);
            long end = clip.stopFrame >= 0 ? clip.stopFrame : clip.endFrame();
            if (end > framePosition) {
                continue;
            }
            this.activeClips.remove(i--);
        }
        for (int i = 0; i < this.machineVoices.size(); i++) {
            if (this.machineVoices.get(i).endFrame() > framePosition) {
                continue;
            }
            this.machineVoices.remove(i--);
        }
    }

    private void queueMotion(ActiveClip clip, AuxMotionPoint point) {
        clip.liveMotion.add(point);
    }

    private void advanceClipMotion(ActiveClip clip, long framePosition) {
        while (clip.nextMotionIndex < clip.liveMotion.size()) {
            AuxMotionPoint point = clip.liveMotion.get(clip.nextMotionIndex);
            if (point.framePosition > framePosition) {
                break;
            }
            this.advanceBlockMotion(clip, point.framePosition);
            int remainingBlocks = nativeMotionBlockCount(point.durationRaw, this.outputSampleRate);
            clip.motionTargetX = point.nativeXCentiUnits;
            clip.motionTargetY = point.nativeYCentiUnits;
            clip.motionTargetZ = point.nativeZCentiUnits;
            clip.motionRemainingBlocks = remainingBlocks;
            if (remainingBlocks <= 1) {
                clip.motionCurrentX = clip.motionTargetX;
                clip.motionCurrentY = clip.motionTargetY;
                clip.motionCurrentZ = clip.motionTargetZ;
                clip.motionDeltaX = 0;
                clip.motionDeltaY = 0;
                clip.motionDeltaZ = 0;
                clip.nextMotionBlockFrame = Long.MAX_VALUE;
            } else {
                clip.motionDeltaX = (clip.motionTargetX - clip.motionCurrentX) / remainingBlocks;
                clip.motionDeltaY = (clip.motionTargetY - clip.motionCurrentY) / remainingBlocks;
                clip.motionDeltaZ = (clip.motionTargetZ - clip.motionCurrentZ) / remainingBlocks;
                clip.nextMotionBlockFrame = point.framePosition + NATIVE_MOTION_BLOCK_FRAMES;
            }
            clip.nextMotionIndex++;
        }
        this.advanceBlockMotion(clip, framePosition);
    }

    private void advanceBlockMotion(ActiveClip clip, long framePosition) {
        while (clip.nextMotionBlockFrame != Long.MAX_VALUE && clip.nextMotionBlockFrame <= framePosition) {
            if (clip.motionRemainingBlocks <= 1) {
                clip.motionCurrentX = clip.motionTargetX;
                clip.motionCurrentY = clip.motionTargetY;
                clip.motionCurrentZ = clip.motionTargetZ;
                clip.motionDeltaX = 0;
                clip.motionDeltaY = 0;
                clip.motionDeltaZ = 0;
                clip.motionRemainingBlocks = 0;
                clip.nextMotionBlockFrame = Long.MAX_VALUE;
                continue;
            }
            clip.motionCurrentX = clampNativeCentiInt(clip.motionCurrentX + clip.motionDeltaX);
            clip.motionCurrentY = clampNativeCentiInt(clip.motionCurrentY + clip.motionDeltaY);
            clip.motionCurrentZ = clampNativeCentiInt(clip.motionCurrentZ + clip.motionDeltaZ);
            clip.motionRemainingBlocks--;
            clip.nextMotionBlockFrame += NATIVE_MOTION_BLOCK_FRAMES;
            if (clip.motionRemainingBlocks <= 1) {
                clip.motionCurrentX = clip.motionTargetX;
                clip.motionCurrentY = clip.motionTargetY;
                clip.motionCurrentZ = clip.motionTargetZ;
                clip.motionDeltaX = 0;
                clip.motionDeltaY = 0;
                clip.motionDeltaZ = 0;
                clip.motionRemainingBlocks = 0;
                clip.nextMotionBlockFrame = Long.MAX_VALUE;
            }
        }
    }

    private long nextSpatialBoundary(ActiveClip clip, long framePosition) {
        long next = Long.MAX_VALUE;
        if (clip.nextMotionIndex < clip.liveMotion.size()) {
            long eventFrame = clip.liveMotion.get(clip.nextMotionIndex).framePosition;
            if (eventFrame > framePosition) {
                next = Math.min(next, eventFrame);
            }
        }
        if (clip.nextMotionBlockFrame > framePosition) {
            next = Math.min(next, clip.nextMotionBlockFrame);
        }
        return next;
    }

    private StereoGain computeSpatialGain(ActiveClip clip) {
        return new StereoGain(0x10000, 0x10000);
    }

    private DecodedResourceAudio decodeCatalogEntry(MLDADPCM adat) {
        if (adat == null || adat.sampleRateHz() <= 0 ||
            adat.channelCount() != 1) {
            return null;
        }

        int[] slotFrames;
        int decodedSampleRate;
        if (adat.selectorId() == 0x81 &&
                !adat.variantBit() &&
                MLDNativeADPCMDecoder.supportsLivePath(adat.sampleRateHz(),
                    adat.codedBits(), adat.channelCount())) {
            int[] nativeFrames = MLDNativeADPCMDecoder.decodeLiveMonoNativeLane0(
                    adat.sampleRateHz(),
                    adat.codedBits(),
                    adat.payload());
            slotFrames = convertSlotFrames(nativeFrames);
            decodedSampleRate = NATIVE_OUTPUT_SAMPLE_RATE;
        } else if (adat.selectorId() == 0x80 && adat.codedBits() == 16) {
            slotFrames = decodePcm16Mono(adat.payload());
            decodedSampleRate = adat.sampleRateHz();
        } else {
            return null;
        }

        if (this.outputSampleRate == decodedSampleRate) {
            return new DecodedResourceAudio(slotFrames);
        }
        return new DecodedResourceAudio(resampleLinear(slotFrames, decodedSampleRate, this.outputSampleRate));
    }

    private static int[] decodePcm16Mono(byte[] payload) {
        if (payload.length < 2) {
            return new int[0];
        }
        int sampleCount = payload.length / 2;
        int[] frames = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int offset = i * 2;
            frames[i] = (short) ((payload[offset] & 0xFF) | (payload[offset + 1] << 8));
        }
        return frames;
    }

    private void parseThrdAudioRoutes(byte[] thrd) {
        if (thrd == null || thrd.length <= 1) {
            return;
        }
        boolean[] seenAudio = new boolean[this.initialAudioRouteRaw.length];
        boolean[] seenSynth = new boolean[this.initialSynthRouteRaw.length];
        for (int offset = 1; offset + 1 < thrd.length; offset += 2) {
            int logicalChannel = thrd[offset] & 0x0F;
            if (logicalChannel < 0 || logicalChannel >= this.initialAudioRouteRaw.length) {
                continue;
            }
            int packed = thrd[offset + 1] & 0xFF;
            boolean audioTarget = (packed & 0x20) != 0;
            if (audioTarget) {
                if (seenAudio[logicalChannel]) {
                    continue;
                }
                this.initialAudioRouteRaw[logicalChannel] = packed & 0x1F;
                seenAudio[logicalChannel] = true;
            } else {
                if (seenSynth[logicalChannel]) {
                    continue;
                }
                this.initialSynthRouteRaw[logicalChannel] = packed & 0x1F;
                seenSynth[logicalChannel] = true;
            }
        }
    }

    private StereoGain computeStereoGain(ChannelState state, int startLevel) {
        int combinedQ16 = combineNativeLevelQ16(startLevel, state.level, DEFAULT_BANK_LEVEL, DEFAULT_GLOBAL_LEVEL);
        int panIndex = clamp(0, 127, DEFAULT_BANK_PAN + state.pan - DEFAULT_PAN);
        int leftQ16 = combineQ16(combinedQ16,
            FueTrekResourceAudioTables.panLeftQ16(panIndex));
        int rightQ16 = combineQ16(combinedQ16,
            FueTrekResourceAudioTables.panRightQ16(panIndex));
        return new StereoGain(leftQ16, rightQ16);
    }

    private static int combineNativeLevelQ16(int slotLevel, int channelLevel, int bankLevel, int globalLevel) {
        int channelBankQ16 = combineQ16(nativeLevelQ16(channelLevel), nativeLevelQ16(bankLevel));
        int slotChannelBankQ16 = combineQ16(nativeLevelQ16(slotLevel), channelBankQ16);
        return combineQ16(nativeLevelQ16(globalLevel), slotChannelBankQ16);
    }

    private static int combineQ16(int leftQ16, int rightQ16) {
        return (leftQ16 * rightQ16) >>> 16;
    }

    private static int nativeLevelQ16(int value) {
        return FueTrekResourceAudioTables.levelQ16(value);
    }

    private static int[] convertSlotFrames(int[] decodedFrames) {
        if (decodedFrames.length == 0) {
            return new int[0];
        }
        int[] converted = new int[decodedFrames.length];
        for (int i = 4; i < decodedFrames.length; i++) {
            converted[i] = decodedFrames[i - 4] >> 1;
        }
        return converted;
    }

    private static int[] resampleLinear(int[] input, int inputRate, int outputRate) {
        if (input.length == 0 || inputRate == outputRate) {
            return Arrays.copyOf(input, input.length);
        }
        int outputLength = Math.max(1, (int) (((long) input.length * outputRate) / inputRate));
        int[] output = new int[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double sourcePosition = ((double) i * inputRate) / outputRate;
            int sourceIndex = (int) sourcePosition;
            double fraction = sourcePosition - sourceIndex;
            int left = input[Math.min(input.length - 1, sourceIndex)];
            int right = input[Math.min(input.length - 1, sourceIndex + 1)];
            output[i] = (int) Math.round(left + ((right - left) * fraction));
        }
        return output;
    }

    private static int scaleMixedSample(int sample, int gainQ16) {
        return (int) ((((long) sample) * gainQ16) >> (15 - PLUGIN_EXPORT_GAIN_SHIFT));
    }

    private static float clampSample(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static int nativeRouteSlotId(int resourceIndex) {
        return resourceIndex + 2;
    }

    private static final class ChannelState {
        int level;
        int pan;
        int audioRouteRaw;
        int synthRouteRaw;
        final ArrayList<AuxMotionPoint> auxMotion = new ArrayList<>();
    }

    private record StereoGain(int leftQ16, int rightQ16) {
    }

    private record DecodedResourceAudio(int[] frames) {
    }

    private static final class MachineSlot {
        final int formatCode;
        final int[] audio;

        MachineSlot(int formatCode, int[] audio) {
            this.formatCode = formatCode;
            this.audio = audio;
        }
    }

    private static final class MachineVoice {
        final int logicalChannel;
        final int slotIndex;
        final long startFrame;
        final int[] audio;
        final int length;

        MachineVoice(int logicalChannel, int slotIndex, long startFrame,
                int[] audio, int length) {
            this.logicalChannel = logicalChannel;
            this.slotIndex = slotIndex;
            this.startFrame = startFrame;
            this.audio = audio;
            this.length = length;
        }

        long endFrame() {
            return this.startFrame + this.length;
        }
    }

    private static final class ActiveClip {
        final int logicalChannel;
        final int resourceIndex;
        final int resourcePitchByte;
        final long startFrame;
        final DecodedResourceAudio audio;
        int audioRouteRaw;
        int synthRouteRaw;
        final int nativeRouteSlotId;
        final ArrayList<AuxMotionPoint> liveMotion;
        final int leftGainQ16;
        final int rightGainQ16;
        int nextMotionIndex;
        int motionCurrentX;
        int motionCurrentY;
        int motionCurrentZ;
        int motionTargetX;
        int motionTargetY;
        int motionTargetZ;
        int motionDeltaX;
        int motionDeltaY;
        int motionDeltaZ;
        int motionRemainingBlocks;
        long nextMotionBlockFrame = Long.MAX_VALUE;
        long stopFrame = -1L;

        ActiveClip(
                int logicalChannel,
                int resourceIndex,
                int resourcePitchByte,
                long startFrame,
                DecodedResourceAudio audio,
                int audioRouteRaw,
                int synthRouteRaw,
                int nativeRouteSlotId,
                int initialMotionCapacity,
                int leftGainQ16,
                int rightGainQ16) {
            this.logicalChannel = logicalChannel;
            this.resourceIndex = resourceIndex;
            this.resourcePitchByte = resourcePitchByte;
            this.startFrame = startFrame;
            this.audio = audio;
            this.audioRouteRaw = audioRouteRaw;
            this.synthRouteRaw = synthRouteRaw;
            this.nativeRouteSlotId = nativeRouteSlotId;
            this.liveMotion = new ArrayList<>(initialMotionCapacity);
            this.leftGainQ16 = leftGainQ16;
            this.rightGainQ16 = rightGainQ16;
        }

        long endFrame() {
            return this.startFrame + this.audio.frames.length;
        }
    }

    static final class AuxMotionPoint {
        final long framePosition;
        final int resourceIndex;
        final int strength;
        final int azimuthDegrees;
        final int elevationDegrees;
        final int durationRaw;
        final short nativeXCentiUnits;
        final short nativeYCentiUnits;
        final short nativeZCentiUnits;

        AuxMotionPoint(
                long framePosition,
                int resourceIndex,
                int strength,
                int azimuthDegrees,
                int elevationDegrees,
                int durationRaw) {
            this.framePosition = framePosition;
            this.resourceIndex = resourceIndex;
            this.strength = strength;
            this.azimuthDegrees = azimuthDegrees;
            this.elevationDegrees = elevationDegrees;
            this.durationRaw = durationRaw;
            this.nativeXCentiUnits = nativeCartesianXCentiUnits(strength, azimuthDegrees, elevationDegrees);
            this.nativeYCentiUnits = nativeCartesianYCentiUnits(strength, elevationDegrees);
            this.nativeZCentiUnits = nativeCartesianZCentiUnits(strength, azimuthDegrees, elevationDegrees);
        }
    }

    private static short nativeCartesianXCentiUnits(int strength, int azimuthDegrees, int elevationDegrees) {
        double cosElevation = Math.cos(elevationDegrees * DEG_TO_RAD);
        double distance = nativeDistance(strength);
        return clampNativeCentiUnits(Math.sin(azimuthDegrees * DEG_TO_RAD) * cosElevation * distance);
    }

    private static short nativeCartesianYCentiUnits(int strength, int elevationDegrees) {
        double distance = nativeDistance(strength);
        return clampNativeCentiUnits(Math.sin(elevationDegrees * DEG_TO_RAD) * distance);
    }

    private static short nativeCartesianZCentiUnits(int strength, int azimuthDegrees, int elevationDegrees) {
        double cosElevation = Math.cos(elevationDegrees * DEG_TO_RAD);
        double distance = nativeDistance(strength);
        return clampNativeCentiUnits(Math.cos(azimuthDegrees * DEG_TO_RAD) * cosElevation * distance);
    }

    private static double nativeDistance(int strength) {
        return clamp(0, 127, strength) * 0.05;
    }

    private static short clampNativeCentiUnits(double value) {
        int centiUnits = (int) Math.round(value * 100.0);
        return (short) clamp(Short.MIN_VALUE, Short.MAX_VALUE, centiUnits);
    }

    private static int clampNativeCentiInt(int value) {
        return clamp(Short.MIN_VALUE, Short.MAX_VALUE, value);
    }
}
