package opendoja.android;

import android.os.Build;
import android.view.View;

/**
 * Suppresses Android's default focus highlight for containers that should not
 * draw a section-sized ring. Items that keep a focus indicator use the blue
 * replacement in res/drawable/focus_highlight.xml (via the app theme).
 */
final class FocusHighlight {
    private FocusHighlight() {
    }

    static void disable(View view) {
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setDefaultFocusHighlightEnabled(false);
        }
    }
}
