package opendoja.compat.awt;

import android.media.ToneGenerator;
import android.media.AudioManager;

public class Toolkit {
    private static ToneGenerator toneGenerator;

    public Toolkit() { }

    public static opendoja.compat.awt.Toolkit getDefaultToolkit()  { return null; }

    public void beep()  {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 50);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
        } catch (Throwable ignored) {
        }
    }

    public opendoja.compat.awt.Insets getScreenInsets(opendoja.compat.awt.GraphicsConfiguration p0)  throws opendoja.compat.awt.HeadlessException { return null; }
}

