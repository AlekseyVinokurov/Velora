package app.nimarkogram.messenger.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.text.SpannableString;
import android.view.View;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.nimarkogram.messenger.utils.AppRestartHelper;
import app.nimarkogram.messenger.utils.NimarkoConfigBackup;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public class MainPreferencesActivity extends BasePreferencesActivity {

    public static final int ID_GENERAL      = 1;
    public static final int ID_APPEARANCE   = 2;
    public static final int ID_CHATS        = 3;
    public static final int ID_CAMERA       = 4;
    public static final int ID_PRIVACY      = 6;
    public static final int ID_GHOST        = 7;

    public static final int ID_RESTART      = 10;

    public static final int ID_PLUGINS        = 20;
    public static final int ID_DEBUG          = 21;
    private static final int ID_EXPERIMENTAL   = 22;
    public static final int ID_NIMARKO_MEDIA  = 23;
    private static final int ID_CHERRYGRAM_FORK = 24;
    public static final int ID_BANNERS        = 25;
    public static final int ID_UPDATES        = 26;
    public static final int ID_WSBYPASS       = 27;
    public static final int ID_TEXTANIM       = 28;
    public static final int ID_PILLSTACK      = 29;
    public static final int ID_SUPPORT_DEVELOPMENT    = 30;
    public static final int ID_EXPORT         = 31;
    public static final int ID_IMPORT         = 32;

    private static final int REQ_EXPORT_CONFIG = 49801;
    private static final int REQ_IMPORT_CONFIG = 49802;
    private static final int MAX_CONFIG_IMPORT_BYTES = 5 * 1024 * 1024;

    private static final String SOURCE_REPOSITORY_URL = "https://github.com/AlekseyVinokurov/Velora";

    private UItem category(int id, IconBackgroundColors colors, int icon, int title, String alias) {
        return SettingsActivity.SettingCell.Factory.of(
                        id, colors.top, colors.bottom, icon,
                        LocaleController.getString(title))
                .setSearchable(this)
                .setLinkAlias(alias, this);
    }

    @Override
    public void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter) {
        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.AP_Header_General)));
        arrayList.add(category(ID_GENERAL, IconBackgroundColors.BLUE,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, "nimarko_general"));
        arrayList.add(category(ID_APPEARANCE, IconBackgroundColors.PURPLE,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, "nimarko_appearance"));
        arrayList.add(category(ID_CHATS, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, "nimarko_chats"));
        arrayList.add(category(ID_CAMERA, IconBackgroundColors.CYAN,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, "nimarko_camera"));
        arrayList.add(category(ID_PRIVACY, IconBackgroundColors.GREEN,
                R.drawable.msg_secret_solar, R.string.NM_Cat_Privacy, "nimarko_privacy"));
        arrayList.add(category(ID_GHOST, IconBackgroundColors.PURPLE,
                R.drawable.ghost_solar, R.string.GhostMode, "nimarko_ghost"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Misc)));
        arrayList.add(category(ID_WSBYPASS, IconBackgroundColors.GREEN,
                R.drawable.msg_secret_solar, R.string.NM_WSB_Title, "nimarko_wsbypass"));
        arrayList.add(category(ID_TEXTANIM, IconBackgroundColors.PURPLE,
                R.drawable.msg_edit_solar, R.string.NM_TA_Title, "nimarko_textanim"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Advanced)));
        arrayList.add(category(ID_DEBUG, IconBackgroundColors.GRAY,
                R.drawable.msg_info_solar, R.string.NM_HUB_Debug, "nimarko_debug"));
        arrayList.add(category(ID_SUPPORT_DEVELOPMENT, IconBackgroundColors.ORANGE_DEEP,
                R.drawable.msg_gift_premium_solar, R.string.VeloraProject, "nimarko_support_development"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_HUB_Header_Other)));
        arrayList.add(category(ID_EXPORT, IconBackgroundColors.GREEN,
                R.drawable.msg_shareout_solar, R.string.NM_Config_Export, "nimarko_export"));
        arrayList.add(category(ID_IMPORT, IconBackgroundColors.BLUE,
                R.drawable.msg_download, R.string.NM_Config_Import, "nimarko_import"));
        arrayList.add(category(ID_RESTART, IconBackgroundColors.ORANGE_DEEP,
                R.drawable.msg_retry_solar, R.string.NM_HUB_Restart, "nimarko_restart"));
        arrayList.add(category(ID_UPDATES, IconBackgroundColors.BLUE,
                R.drawable.msg_info_solar, R.string.UP_CheckForUpdates, "nimarko_updates"));
        arrayList.add(UItem.asShadow(null));

        SpannableString forkedSub;
        String forkedSubRaw = LocaleController.getString(R.string.VeloraCredits);
        if (Build.VERSION.SDK_INT >= 24) {
            forkedSub = new SpannableString(Html.fromHtml(forkedSubRaw, Html.FROM_HTML_MODE_LEGACY));
        } else {
            forkedSub = new SpannableString(Html.fromHtml(forkedSubRaw));
        }
        arrayList.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(forkedSub)));

    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NimarkoGramSettings);
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        switch (uItem.id) {
            case ID_GENERAL:
                presentFragment(new GeneralPreferencesActivity());
                break;
            case ID_APPEARANCE:
                presentFragment(new AppearancePreferencesActivity());
                break;
            case ID_CHATS:
                presentFragment(new ChatsPreferencesActivity());
                break;
            case ID_CAMERA:
                presentFragment(new CameraPreferencesActivity());
                break;
            case ID_PRIVACY:
                presentFragment(new PrivacyPreferencesActivity());
                break;
            case ID_GHOST:
                presentFragment(new GhostPreferencesActivity());
                break;
            case ID_WSBYPASS:
                presentFragment(new soundgram.wsproxy.NekoWsProxyActivity());
                break;
            case ID_TEXTANIM:
                presentFragment(new soundgram.animation.TextAnimationSettingsActivity());
                break;
            case ID_UPDATES:
                app.nimarkogram.messenger.updater.NimarkoUpdaterSheet.showAlert(this, false, null);
                break;
            case ID_DEBUG:
                presentFragment(new DebugPreferencesActivity());
                break;
            case ID_EXPERIMENTAL:
                presentFragment(new ExperimentalPreferencesActivity());
                break;
            case ID_EXPORT:
                exportConfig();
                break;
            case ID_IMPORT:
                importConfig();
                break;
            case ID_RESTART:
                AppRestartHelper.triggerRebirth(getParentActivity() != null ? getParentActivity() : getContext());
                break;
            case ID_SUPPORT_DEVELOPMENT:
                showDialog(new soundgram.ui.SoundGramBadgeBottomSheet(
                        getParentActivity() != null ? getParentActivity() : getContext(),
                        getResourceProvider()));
                break;
            case ID_CHERRYGRAM_FORK:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),
                        "https://github.com/arslan4k1390/Cherrygram");
                break;
            default:
                break;
        }
    }

    private void exportConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "velora_config.json");
            startActivityForResult(intent, REQ_EXPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(getContext(), LocaleController.getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
        }
    }

    private void importConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(getContext(), LocaleController.getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Activity parentActivity = getParentActivity();
        Context context = getContext();
        if (parentActivity == null || context == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_CONFIG) {
            try (OutputStream os = parentActivity.getContentResolver().openOutputStream(uri)) {
                os.write(NimarkoConfigBackup.exportJson().getBytes(StandardCharsets.UTF_8));
                os.flush();
                Toast.makeText(context, LocaleController.getString(R.string.NM_Config_Exported), Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(context, LocaleController.getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_IMPORT_CONFIG) {
            try (InputStream is = parentActivity.getContentResolver().openInputStream(uri)) {
                if (is == null) throw new java.io.IOException("Unable to open config");
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    if (n > MAX_CONFIG_IMPORT_BYTES - total) {
                        throw new java.io.IOException("Config is too large");
                    }
                    bos.write(buf, 0, n);
                    total += n;
                }
                String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (NimarkoConfigBackup.looksLikeBackup(json) && NimarkoConfigBackup.importJson(json)) {
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                    showRestartBulletin();
                } else {
                    Toast.makeText(context, LocaleController.getString(R.string.NM_Config_ImportEmpty), Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(context, LocaleController.getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
