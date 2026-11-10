package app.nimarkogram.messenger.preferences;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class AboutPreferencesActivity extends BasePreferencesActivity {
    @Override public String getTitle() { return LocaleController.getString(R.string.VeloraAbout); }

    @Override public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Velora"));
        String version = BuildConfig.BUILD_VERSION_STRING;
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            version = info.versionName + " (" + code + ")";
        } catch (Exception e) { org.telegram.messenger.FileLog.e(e); }
        items.add(UItem.asShadow(version));
        items.add(UItem.asHeader("Telegram Android"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.VeloraBaseVersion) + " "
                + BuildConfig.TELEGRAM_BASE_VERSION + "\n" + BuildConfig.TELEGRAM_BASE_COMMIT));
        items.add(UItem.asButton(1, R.drawable.msg_info_solar, LocaleController.getString(R.string.VeloraProject)));
        items.add(UItem.asButton(2, R.drawable.msg_info_solar, LocaleController.getString(R.string.VeloraAuthorsLicenses)));
        items.add(UItem.asShadow(null));
    }

    @Override public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) Browser.openUrl(getContext(), "https://github.com/AlekseyVinokurov/Velora");
        else if (item.id == 2) showDialog(new soundgram.ui.SoundGramBadgeBottomSheet(getContext(), getResourceProvider()));
    }
}
