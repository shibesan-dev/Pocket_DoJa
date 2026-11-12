package opendoja.audio.mld;

/**
 * Decoder for the ROHM 4-bit ADPCM stream used by machine-dependent
 * {@code 0x8000} sample slots.
 *
 * <p>The coded payload starts with the native two-byte stream header; the
 * nibble-coded audio begins after it. The codec itself is the ROHM variant
 * also used by the AWC family: each nibble updates a 16-bit predictor through
 * a step-size delta, with the high nibble consumed first.</p>
 */
public final class MLDRohmADPCMDecoder
{
    /**
     * Number of payload bytes before the coded nibbles.
     */
    private static final int PAYLOAD_HEADER_BYTES = 2;

    /**
     * Initial predictor value.
     */
    private static final int INITIAL_PREDICTOR = 0;

    /**
     * Initial step size.
     */
    private static final int INITIAL_STEP = 127;

    /**
     * Minimum and maximum step size.
     */
    private static final int MIN_STEP = 127;

    private static final int MAX_STEP = 24576;

    /**
     * Per-nibble step-size multipliers, indexed by the magnitude bits.
     */
    private static final double[] STEP_FACTORS = {
        0.8984375, 0.8984375, 0.8984375, 0.8984375,
        1.19921875, 1.59765625, 2.0, 2.3984375
    };

    private MLDRohmADPCMDecoder()
    {
    }

    /**
     * Decode a complete {@code 0x8000} slot payload.
     *
     * @param payload The coded payload, including its two-byte stream header.
     * @return Signed 16-bit PCM samples.
     */
    public static int[] decode(byte[] payload)
    {
        if (payload == null)
            throw new NullPointerException("A payload is required.");
        return decode(payload, 0, payload.length);
    }

    /**
     * Decode a {@code 0x8000} slot payload region.
     *
     * @param payload The byte array containing the payload.
     * @param offset The position of the first payload byte.
     * @param length The number of payload bytes.
     * @return Signed 16-bit PCM samples.
     */
    public static int[] decode(byte[] payload, int offset, int length)
    {
        if (payload == null)
            throw new NullPointerException("A payload is required.");
        if (offset < 0 || length < 0 || offset + length > payload.length)
            throw new ArrayIndexOutOfBoundsException("Invalid payload range.");

        int start = offset + PAYLOAD_HEADER_BYTES;
        int end = offset + length;
        if (start >= end)
            return new int[0];

        int[] samples = new int[(end - start) * 2];
        int predictor = INITIAL_PREDICTOR;
        int step = INITIAL_STEP;
        int sampleIndex = 0;

        for (int i = start; i < end; i++)
        {
            int value = payload[i] & 0xFF;

            // High nibble is decoded before the low nibble.
            for (int shift = 4; shift >= 0; shift -= 4)
            {
                int nibble = (value >> shift) & 0x0F;
                int delta = ((nibble & 4) != 0 ? step : 0) +
                    ((nibble & 2) != 0 ? step >> 1 : 0) +
                    ((nibble & 1) != 0 ? step >> 2 : 0) +
                    (step >> 3);
                if ((nibble & 8) != 0)
                    delta = -delta;

                if (delta < -65536)
                    delta = -65536;
                else if (delta > 65535)
                    delta = 65535;

                predictor += delta;
                if (predictor < -32768)
                    predictor = -32768;
                else if (predictor > 32767)
                    predictor = 32767;

                samples[sampleIndex++] = predictor;

                step = (int)(step * STEP_FACTORS[nibble & 7]);
                if (step < MIN_STEP)
                    step = MIN_STEP;
                else if (step > MAX_STEP)
                    step = MAX_STEP;
            }
        }

        return samples;
    }
}
