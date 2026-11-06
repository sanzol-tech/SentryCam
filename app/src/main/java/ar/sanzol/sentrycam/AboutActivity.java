package ar.sanzol.sentrycam;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.Locale;

/**
 * The About screen, following TimerPlus' AboutFragment: the app's name and version, the kind of
 * build, the license and the device it runs on, read on the device. Kept short: one closing note
 * says where photos go and that nothing leaves the phone. The values can be selected.
 */
public final class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((Toolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());

        PackageInfo info = packageInfo();
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version, info.versionName));

        ViewGroup app = findViewById(R.id.about_app_rows);
        addRow(app, R.string.about_row_version, info.versionName);
        addRow(app, R.string.about_row_build,
                getString(debuggable ? R.string.about_build_debug : R.string.about_build_release));
        addRow(app, R.string.about_row_license, getString(R.string.about_license_value));

        ViewGroup device = findViewById(R.id.about_device_rows);
        addRow(device, R.string.about_row_device, deviceName());
        addRow(device, R.string.about_row_android,
                getString(R.string.about_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
    }

    private void addRow(ViewGroup card, int label, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_about_row, card, false);
        ((TextView) row.findViewById(R.id.about_row_label)).setText(label);
        ((TextView) row.findViewById(R.id.about_row_value)).setText(value);
        card.addView(row);
    }

    // "Google Pixel 8"; the model alone when it already starts with the maker ("Samsung Galaxy ...").
    private static String deviceName() {
        String maker = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            return model;
        }
        String capitalized = maker.isEmpty() ? maker
                : maker.substring(0, 1).toUpperCase(Locale.ROOT) + maker.substring(1);
        return capitalized + " " + model;
    }

    // The int-flags overload is deprecated from Android 13; 12 and 12L have only that one.
    @SuppressWarnings("deprecation")
    private PackageInfo packageInfo() {
        PackageManager packages = getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packages.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
            }
            return packages.getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            // The app's own package is always installed while it runs.
            throw new IllegalStateException(e);
        }
    }
}
