import android.content.*;
import android.content.pm.*;
import android.os.Build;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.LauncherIconController;
import org.telegram.ui.LauncherIconController.LauncherIcon;

import java.lang.reflect.Field;
import java.util.*;

/** Host-only execution of the production controller against a small Android API fake. */
public class LauncherIconControllerTest {
    static int checks;
    static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    static class Prefs implements SharedPreferences, SharedPreferences.Editor {
        Map<String, Object> values = new HashMap<>(), pending = new HashMap<>();
        boolean failCommit;
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public SharedPreferences.Editor edit() { pending.clear(); return this; }
        public SharedPreferences.Editor putString(String key, String value) { pending.put(key, value); return this; }
        public SharedPreferences.Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        public boolean commit() { if (failCommit) return false; values.putAll(pending); return true; }
    }
    static class PM extends PackageManager {
        Map<ComponentName, ActivityInfo> activities = new LinkedHashMap<>();
        Map<ComponentName, Integer> states = new HashMap<>();
        ComponentName failOnce;
        boolean failBatch;
        int batches, singles;
        PM() {
            for (LauncherIcon icon : LauncherIcon.values()) {
                ActivityInfo info = new ActivityInfo();
                info.name = "org.telegram.messenger." + icon.key;
                info.packageName = "io.github.alekseyvinokurov.velora";
                info.targetActivity = "org.telegram.ui.LaunchActivity";
                info.exported = true;
                info.enabled = icon == LauncherIcon.VELORA;
                activities.put(new ComponentName(info.packageName, info.name), info);
            }
        }
        boolean enabled(ComponentName component) {
            return states.getOrDefault(component, activities.get(component).enabled ? 1 : 2) == 1;
        }
        int count() { return (int) activities.keySet().stream().filter(this::enabled).count(); }
        public int getComponentEnabledSetting(ComponentName component) { return states.getOrDefault(component, 0); }
        public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
            if (!activities.containsKey(component)) throw new NameNotFoundException();
            return activities.get(component);
        }
        public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            check((flags & MATCH_DISABLED_COMPONENTS) != 0, "query includes disabled aliases");
            List<ResolveInfo> result = new ArrayList<>();
            for (ActivityInfo info : activities.values()) {
                ResolveInfo resolve = new ResolveInfo(); resolve.activityInfo = info; result.add(resolve);
            }
            // An unrelated real Activity must never be disabled by the controller.
            ActivityInfo real = new ActivityInfo(); real.exported = true;
            real.packageName = "io.github.alekseyvinokurov.velora"; real.name = "org.telegram.ui.LaunchActivity";
            ResolveInfo resolve = new ResolveInfo(); resolve.activityInfo = real; result.add(resolve);
            return result;
        }
        public void setComponentEnabledSetting(ComponentName component, int state, int flags) {
            singles++;
            check(activities.containsKey(component), "only launcher aliases change");
            check(flags == DONT_KILL_APP, "switch preserves running process");
            if (component.equals(failOnce)) { failOnce = null; throw new IllegalArgumentException("injected failure"); }
            states.put(component, state);
            check(count() >= 1, "legacy switch never leaves zero launchers");
        }
        public void setComponentEnabledSettings(List<ComponentEnabledSetting> changes) {
            batches++;
            if (failBatch) { failBatch = false; throw new IllegalArgumentException("injected atomic failure"); }
            Set<ComponentName> names = new HashSet<>();
            for (ComponentEnabledSetting change : changes) {
                check(activities.containsKey(change.component), "batch changes only launcher aliases");
                check(names.add(change.component), "batch has no duplicate component");
                check(change.flags == DONT_KILL_APP, "batch preserves process");
            }
            for (ComponentEnabledSetting change : changes) states.put(change.component, change.state);
            check(count() == 1, "atomic switch has exactly one launcher");
        }
    }
    static class Ctx extends Context {
        final Prefs prefs = new Prefs();
        final PM pm = new PM();
        public String getPackageName() { return "io.github.alekseyvinokurov.velora"; }
        public PackageManager getPackageManager() { return pm; }
        public SharedPreferences getSharedPreferences(String key, int mode) { return prefs; }
    }
    static void clearCache() throws Exception {
        Field field = LauncherIconController.class.getDeclaredField("sCachedActiveIcon");
        field.setAccessible(true); field.set(null, null);
    }
    static Ctx fresh(int sdk) throws Exception {
        clearCache(); Build.VERSION.SDK_INT = sdk;
        Ctx context = new Ctx(); ApplicationLoader.applicationContext = context; return context;
    }
    static void selected(Ctx ctx, LauncherIcon icon) {
        check(ctx.pm.count() == 1, "only one enabled launcher");
        check(ctx.pm.enabled(icon.getComponentName(ctx)), "expected alias enabled: " + icon);
        check(LauncherIconController.getActiveIcon() == icon, "cached selected icon: " + icon);
        check(ctx.prefs.getString("active_icon", "").equals(icon.name()), "selection persisted: " + icon);
        check(LauncherIconController.getActiveLauncherComponent(ctx).equals(icon.getComponentName(ctx)), "notification launch intent follows choice");
    }
    public static void main(String[] args) throws Exception {
        for (int sdk : new int[]{24, 26, 32, 33, 36, 37}) {
            Ctx ctx = fresh(sdk);
            LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, LauncherIcon.VELORA);
            check(LauncherIconController.getNotificationIcon() == R.drawable.velora_notification, "fresh notification default");
            for (LauncherIcon icon : LauncherIcon.values()) {
                check(LauncherIconController.setIcon(icon), "switch succeeds: " + icon);
                selected(ctx, icon);
                clearCache(); LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, icon);
            }
            check(LauncherIconController.setVeloraNotificationIcon(false), "classic notification saved");
            check(LauncherIconController.getNotificationIcon() == R.drawable.notification, "classic messages restored");
            check(LauncherIconController.getNotificationIcon(R.drawable.player) == R.drawable.player, "classic music restored");
            clearCache(); LauncherIconController.tryFixLauncherIconIfNeeded();
            check(!LauncherIconController.useVeloraNotificationIcon(), "notification preference survives restart");
            LauncherIconController.setVeloraNotificationIcon(true);
            check(LauncherIconController.getNotificationIcon(R.drawable.player) == R.drawable.velora_notification, "music uses selected brand");
            check(sdk >= 33 ? ctx.pm.batches > 0 && ctx.pm.singles == 0 : ctx.pm.singles > 0 && ctx.pm.batches == 0, "correct API path");
        }
        // Upgrade with explicit preference while the new alias is manifest-enabled.
        for (LauncherIcon icon : LauncherIcon.values()) {
            Ctx ctx = fresh(37); ctx.prefs.values.put("active_icon", icon.name());
            ctx.pm.states.put(icon.getComponentName(ctx), 1);
            LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, icon);
        }
        for (String name : new String[]{"DEFAULT", "BlueIcon", "DefaultIcon"}) {
            Ctx ctx = fresh(37); ctx.prefs.values.put("active_icon", name);
            LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, LauncherIcon.DEFAULT);
        }
        Ctx ctx = fresh(37); ctx.pm.states.put(LauncherIcon.ANIME.getComponentName(ctx), 1);
        LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, LauncherIcon.ANIME);
        ctx = fresh(37); ctx.pm.states.put(LauncherIcon.DEFAULT.getComponentName(ctx), 1);
        LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, LauncherIcon.VELORA);
        // No alias is enabled: startup repair must recover.
        ctx = fresh(37); for (ComponentName c : ctx.pm.activities.keySet()) ctx.pm.states.put(c, 2);
        LauncherIconController.tryFixLauncherIconIfNeeded(); selected(ctx, LauncherIcon.VELORA);
        // A variant absent from this build must not appear or disable the active launcher.
        ctx = fresh(37); LauncherIconController.tryFixLauncherIconIfNeeded();
        ctx.pm.activities.remove(LauncherIcon.VINTAGE.getComponentName(ctx));
        check(!LauncherIconController.getAvailableIcons(ctx).contains(LauncherIcon.VINTAGE), "missing aliases filtered");
        check(!LauncherIconController.setIcon(LauncherIcon.VINTAGE), "missing alias fails safely"); selected(ctx, LauncherIcon.VELORA);
        for (int sdk : new int[]{24, 37}) {
            ctx = fresh(sdk); LauncherIconController.tryFixLauncherIconIfNeeded();
            if (sdk >= 33) ctx.pm.failBatch = true;
            else ctx.pm.failOnce = LauncherIcon.ANIME.getComponentName(ctx);
            check(!LauncherIconController.setIcon(LauncherIcon.ANIME), "enable failure reported"); selected(ctx, LauncherIcon.VELORA);
            if (sdk < 33) {
                ctx.pm.failOnce = LauncherIcon.VELORA.getComponentName(ctx);
                check(!LauncherIconController.setIcon(LauncherIcon.ANIME), "disable failure rolls back"); selected(ctx, LauncherIcon.VELORA);
            }
            ctx.prefs.failCommit = true;
            check(!LauncherIconController.setIcon(LauncherIcon.ANIME), "persistence failure rolls back"); selected(ctx, LauncherIcon.VELORA);
        }
        System.out.println("Production LauncherIconController host checks passed: " + checks);
    }
}
