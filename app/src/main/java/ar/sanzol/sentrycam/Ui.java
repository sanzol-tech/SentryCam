package ar.sanzol.sentrycam;

import android.app.UiModeManager;
import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/** Small helpers shared by the two screens. */
final class Ui {

    private Ui() {
    }

    /** Pads {@code root} so content stays clear of the system bars, cutout and keyboard. */
    static void padForSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    /** Applies the theme for this app only; Android keeps it across restarts. */
    static void applyTheme(Context context, int theme) {
        UiModeManager manager = context.getSystemService(UiModeManager.class);
        if (manager == null) {
            return;
        }
        int mode;
        if (theme == SettingsStore.THEME_LIGHT) {
            mode = UiModeManager.MODE_NIGHT_NO;
        } else if (theme == SettingsStore.THEME_DARK) {
            mode = UiModeManager.MODE_NIGHT_YES;
        } else {
            mode = UiModeManager.MODE_NIGHT_AUTO;
        }
        manager.setApplicationNightMode(mode);
    }
}
