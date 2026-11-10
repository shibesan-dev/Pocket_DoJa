package opendoja.host;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Captures a small sample of non-ASCII strings drawn by a game so diagnostics can distinguish
 * "text was decoded with the wrong charset" (U+FFFD / Latin-1 mojibake in the string) from
 * "text was decoded correctly but the font has no glyph" (valid Japanese in the string).
 *
 * <p>Only the first few unique non-ASCII strings are logged, so the cost stays negligible even
 * for text-heavy titles.</p>
 */
public final class TextSampleLog {
    private static final int LIMIT = 60;
    private static final Set<String> SEEN = new LinkedHashSet<>();

    private TextSampleLog() {
    }

    public static synchronized void record(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean nonAscii = false;
        int replacement = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value > 0x7F) {
                nonAscii = true;
            }
            if (value == 0xFFFD) {
                replacement++;
            }
        }
        if (!nonAscii || SEEN.size() >= LIMIT || !SEEN.add(text)) {
            return;
        }
        OpenDoJaLog.info(TextSampleLog.class, "TEXT sample ufffd=" + replacement
                + " len=" + text.length() + " \"" + escape(text) + "\"");
    }

    private static String escape(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= 0x20 && value < 0x7F) {
                builder.append(value);
            } else {
                builder.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) value));
            }
        }
        return builder.toString();
    }
}
