package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

public class LauncherIconController {
    private static final String PREF_NAME = "launcher_icon_pref";
    private static final String KEY_ACTIVE_ICON = "active_icon";
    private static volatile LauncherIcon sCachedActiveIcon = null;

    public static final String[] LEGACY_LAUNCHER_KEYS = new String[] {
        "DefaultIcon",
        "TelegramIcon",
        "VintageIcon",
        "AquaIcon",
        "PremiumIcon",
        "TurboIcon",
        "NoxIcon",
        "LavenderIcon",
        "SolarIcon"
    };

    public static ComponentName getActiveLauncherComponent(Context ctx) {
        if (ctx == null) ctx = ApplicationLoader.applicationContext;
        LauncherIcon active = getActiveIcon();
        if (active != null) {
            return active.getComponentName(ctx);
        }
        return new ComponentName(ctx.getPackageName(), "org.telegram.messenger.VeloraIcon");
    }

    private static SharedPreferences preferences(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // Query only launcher aliases. Never disable LaunchActivity (deep links) or services.
    private static List<ComponentName> launcherComponents(Context ctx) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(ctx.getPackageName());
        List<ComponentName> result = new ArrayList<>();
        for (ResolveInfo info : ctx.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DISABLED_COMPONENTS)) {
            ActivityInfo activity = info.activityInfo;
            if (activity != null && activity.exported && activity.targetActivity != null
                    && ctx.getPackageName().equals(activity.packageName)) {
                ComponentName component = new ComponentName(activity.packageName, activity.name);
                if (!result.contains(component)) result.add(component);
            }
        }
        return result;
    }

    public static List<LauncherIcon> getAvailableIcons(Context ctx) {
        List<ComponentName> components = launcherComponents(ctx);
        List<LauncherIcon> result = new ArrayList<>();
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (components.contains(icon.getComponentName(ctx))) result.add(icon);
        }
        return result;
    }

    private static boolean componentEnabled(Context ctx, ComponentName component) {
        PackageManager pm = ctx.getPackageManager();
        int state = pm.getComponentEnabledSetting(component);
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        }
        try {
            return pm.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static synchronized void tryFixLauncherIconIfNeeded() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;
        LauncherIcon selected = getActiveIcon();
        if (applyLauncherIcon(ctx, selected)) {
            // Also records the one-time migration, retaining names from older versions.
            preferences(ctx).edit().putString(KEY_ACTIVE_ICON, selected.name()).commit();
            sCachedActiveIcon = selected;
        }
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx != null && componentEnabled(ctx, icon.getComponentName(ctx));
    }

    public static synchronized LauncherIcon getActiveIcon() {
        if (sCachedActiveIcon != null) return sCachedActiveIcon;
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return LauncherIcon.VELORA;
        List<LauncherIcon> available = getAvailableIcons(ctx);
        String name = preferences(ctx).getString(KEY_ACTIVE_ICON, null);
        if (name != null) {
            for (LauncherIcon icon : available) {
                if (icon.name().equalsIgnoreCase(name) || icon.key.equalsIgnoreCase(name)
                        || icon == LauncherIcon.DEFAULT && "DefaultIcon".equalsIgnoreCase(name)) {
                    return sCachedActiveIcon = icon;
                }
            }
        }
        // Older versions sometimes recorded only PackageManager state. Preserve an
        // explicitly enabled alternate alias; the old automatically enabled BlueIcon
        // without a saved preference is not an explicit user choice.
        for (LauncherIcon icon : available) {
            if (icon != LauncherIcon.VELORA && icon != LauncherIcon.DEFAULT
                    && ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx))
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return sCachedActiveIcon = icon;
            }
        }
        return sCachedActiveIcon = LauncherIcon.VELORA;
    }

    private static boolean applyLauncherIcon(Context ctx, LauncherIcon icon) {
        PackageManager pm = ctx.getPackageManager();
        ComponentName selected = icon.getComponentName(ctx);
        try {
            List<ComponentName> components = launcherComponents(ctx);
            if (!components.contains(selected)) return false;
            if (Build.VERSION.SDK_INT >= 33) {
                // Pixel: one atomic transaction, without an intermediate duplicate.
                List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
                for (ComponentName component : components) {
                    int state = component.equals(selected) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                    if (pm.getComponentEnabledSetting(component) != state) {
                        changes.add(new PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP));
                    }
                }
                if (!changes.isEmpty()) pm.setComponentEnabledSettings(changes);
            } else {
                // On older Android, enable and verify first. Never leave zero launchers.
                pm.setComponentEnabledSetting(selected, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                if (!componentEnabled(ctx, selected)) return false;
                for (ComponentName component : components) {
                    if (!component.equals(selected)) {
                        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return false;
        }
    }

    public static int getActiveIconRes() {
        LauncherIcon icon = getActiveIcon();
        try {
            Context ctx = ApplicationLoader.applicationContext;
            // Standalone manifests override some classic artwork in the app module.
            int resource = ctx.getPackageManager().getActivityInfo(icon.getComponentName(ctx),
                    PackageManager.MATCH_DISABLED_COMPONENTS).icon;
            if (resource != 0) return resource;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        switch (icon) {
            case VELORA:
                return R.mipmap.velora_launcher;
            case VINTAGE:
                return R.mipmap.icon_6_launcher;
            case AQUA:
                return R.mipmap.icon_4_launcher;
            case PREMIUM:
                return R.mipmap.icon_3_launcher;
            case TURBO:
                return R.mipmap.icon_5_launcher;
            case NOX:
                return R.mipmap.icon_2_launcher;
            case COSMOS:
                return R.mipmap.icon_cosmos_launcher;
            case ANIME:
                return R.mipmap.icon_anime_launcher;
            case MATRIX:
                return R.mipmap.icon_matrix_launcher;
            case NEON:
                return R.mipmap.icon_neon_launcher;
            case SUNSET:
                return R.mipmap.icon_sunset_launcher;
            case EMERALD:
                return R.mipmap.icon_emerald_launcher;
            case MIDNIGHT:
                return R.mipmap.icon_midnight_launcher;
            case GOLD:
                return R.mipmap.icon_gold_launcher;
            case CRIMSON:
                return R.mipmap.icon_crimson_launcher;
            case DEFAULT:
            default:
                return R.mipmap.ic_launcher;
        }
    }

    public static Drawable getActiveIconDrawable(Context context) {
        if (context == null) context = ApplicationLoader.applicationContext;
        int res = getActiveIconRes();
        return ContextCompat.getDrawable(context, res);
    }

    public static Bitmap getActiveIconBitmap(Context context) {
        if (context == null) context = ApplicationLoader.applicationContext;
        Drawable d = getActiveIconDrawable(context);
        if (d instanceof BitmapDrawable) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int width = d != null && d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : AndroidUtilities.dp(48);
        int height = d != null && d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : AndroidUtilities.dp(48);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (d != null) {
            d.setBounds(0, 0, width, height);
            d.draw(canvas);
        }
        return bitmap;
    }

    public static synchronized boolean setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        LauncherIcon previous = getActiveIcon();
        if (!applyLauncherIcon(ctx, icon)) {
            applyLauncherIcon(ctx, previous);
            return false;
        }
        if (!preferences(ctx).edit().putString(KEY_ACTIVE_ICON, icon.name()).commit()) {
            applyLauncherIcon(ctx, previous);
            return false;
        }
        sCachedActiveIcon = icon;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appIconChanged, icon);
        refreshNotifications();
        return true;
    }

    public static boolean useVeloraNotificationIcon() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx == null || preferences(ctx).getBoolean("velora_notification_icon", true);
    }

    public static int getNotificationIcon() {
        return getNotificationIcon(R.drawable.notification);
    }

    // Preserve the original service-specific icon in Classic mode.
    public static int getNotificationIcon(int classicResource) {
        return useVeloraNotificationIcon() ? R.drawable.velora_notification : classicResource;
    }

    public static boolean setVeloraNotificationIcon(boolean velora) {
        Context ctx = ApplicationLoader.applicationContext;
        if (!preferences(ctx).edit().putBoolean("velora_notification_icon", velora).commit()) return false;
        // Existing observers redraw music and resident notifications without restarting playback.
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appIconChanged, getActiveIcon());
        refreshNotifications();
        return true;
    }

    public static int getActiveStartThemeRes() {
        LauncherIcon icon = getActiveIcon();
        if (icon == null) {
            return R.style.Theme_TMessages_Start_BlueIcon;
        }
        switch (icon) {
            case VELORA:
                return R.style.Theme_TMessages_Start_VeloraIcon;
            case VINTAGE:
                return R.style.Theme_TMessages_Start_VintageIcon;
            case AQUA:
                return R.style.Theme_TMessages_Start_AquaIcon;
            case PREMIUM:
                return R.style.Theme_TMessages_Start_PremiumIcon;
            case TURBO:
                return R.style.Theme_TMessages_Start_TurboIcon;
            case NOX:
                return R.style.Theme_TMessages_Start_NoxIcon;
            case COSMOS:
                return R.style.Theme_TMessages_Start_CosmosIcon;
            case ANIME:
                return R.style.Theme_TMessages_Start_AnimeIcon;
            case MATRIX:
                return R.style.Theme_TMessages_Start_MatrixIcon;
            case NEON:
                return R.style.Theme_TMessages_Start_NeonIcon;
            case SUNSET:
                return R.style.Theme_TMessages_Start_SunsetIcon;
            case EMERALD:
                return R.style.Theme_TMessages_Start_EmeraldIcon;
            case MIDNIGHT:
                return R.style.Theme_TMessages_Start_MidnightIcon;
            case GOLD:
                return R.style.Theme_TMessages_Start_GoldIcon;
            case CRIMSON:
                return R.style.Theme_TMessages_Start_CrimsonIcon;
            case DEFAULT:
            default:
                return R.style.Theme_TMessages_Start_BlueIcon;
        }
    }

    public static void applyNotificationAppInfo(androidx.core.app.NotificationCompat.Builder builder, Context context) {
        if (builder == null) return;
        if (context == null) context = ApplicationLoader.applicationContext;
        try {
            android.content.pm.ApplicationInfo appInfo = new android.content.pm.ApplicationInfo(context.getApplicationInfo());
            appInfo.icon = getActiveIconRes();
            builder.getExtras().putParcelable("android.appInfo", appInfo);
        } catch (Throwable ignore) {}
    }

    public static void applyNotificationAppInfo(android.app.Notification.Builder builder, Context context) {
        if (builder == null) return;
        if (context == null) context = ApplicationLoader.applicationContext;
        try {
            android.content.pm.ApplicationInfo appInfo = new android.content.pm.ApplicationInfo(context.getApplicationInfo());
            appInfo.icon = getActiveIconRes();
            builder.getExtras().putParcelable("android.appInfo", appInfo);
        } catch (Throwable ignore) {}
    }

    public static void applyNotificationAppInfo(android.app.Notification notification, Context context) {
        if (notification == null) return;
        if (context == null) context = ApplicationLoader.applicationContext;
        try {
            android.content.pm.ApplicationInfo appInfo = new android.content.pm.ApplicationInfo(context.getApplicationInfo());
            appInfo.icon = getActiveIconRes();
            if (notification.extras != null) {
                notification.extras.putParcelable("android.appInfo", appInfo);
            }
        } catch (Throwable ignore) {}
    }

    public static void refreshNotifications() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                    if (UserConfig.getInstance(i).isClientActivated()) {
                        NotificationsController.getInstance(i).showNotifications();
                    }
                }
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }

        });
    }

    public enum LauncherIcon {
        VELORA("VeloraIcon", R.drawable.velora_icon_background, R.drawable.velora_icon_foreground, R.string.VeloraAppIcon),
        DEFAULT("BlueIcon", R.mipmap.ic_launcher_background, R.mipmap.ic_launcher_foreground, R.string.VeloraClassicIcon),
        ANIME("AnimeIcon", R.mipmap.icon_anime_background, R.mipmap.icon_anime_foreground, R.string.AppIconAnime),
        COSMOS("CosmosIcon", R.mipmap.icon_cosmos_background, R.mipmap.icon_cosmos_foreground, R.string.AppIconCosmos),
        MATRIX("MatrixIcon", R.mipmap.icon_matrix_background, R.mipmap.icon_matrix_foreground, R.string.AppIconMatrix),
        NEON("NeonIcon", R.mipmap.icon_neon_background, R.mipmap.icon_neon_foreground, R.string.AppIconCyberNeon),
        SUNSET("SunsetIcon", R.mipmap.icon_sunset_background, R.mipmap.icon_sunset_foreground, R.string.AppIconSunset),
        EMERALD("EmeraldIcon", R.mipmap.icon_emerald_background, R.mipmap.icon_emerald_foreground, R.string.AppIconEmerald),
        MIDNIGHT("MidnightIcon", R.mipmap.icon_midnight_background, R.mipmap.icon_midnight_foreground, R.string.AppIconMidnight),
        GOLD("GoldIcon", R.mipmap.icon_gold_background, R.mipmap.icon_gold_foreground, R.string.AppIconGold),
        CRIMSON("CrimsonIcon", R.mipmap.icon_crimson_background, R.mipmap.icon_crimson_foreground, R.string.AppIconCrimson),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_4_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_2_foreground_sa, R.string.AppIconNox);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
