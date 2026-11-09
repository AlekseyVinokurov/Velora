#!/usr/bin/env python3
"""Host JVM tests of production launcher code. No SDK, Android build or Gradle."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java'
STUBS = {
'android/content/ComponentName.java': '''package android.content;
public class ComponentName {
 public final String pkg, cls;
 public ComponentName(String p, String c) {pkg=p; cls=c;}
 public String getClassName(){return cls;}
 public boolean equals(Object o){return o instanceof ComponentName && pkg.equals(((ComponentName)o).pkg) && cls.equals(((ComponentName)o).cls);}
 public int hashCode(){return java.util.Objects.hash(pkg,cls);}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 public static final String ACTION_MAIN="main", CATEGORY_LAUNCHER="launcher";
 public Intent(String action){} public Intent addCategory(String s){return this;} public Intent setPackage(String s){return this;}
}''',
'android/content/Context.java': '''package android.content; public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public abstract String getPackageName(); public abstract android.content.pm.PackageManager getPackageManager();
 public abstract SharedPreferences getSharedPreferences(String s,int mode);
 public <T> T getSystemService(Class<T> type){return null;}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
}''',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 String getString(String key,String fallback); boolean getBoolean(String key,boolean fallback); Editor edit();
 interface Editor {Editor putString(String k,String v); Editor putBoolean(String k,boolean v); boolean commit();}
}''',
'android/content/pm/ActivityInfo.java': '''package android.content.pm; public class ActivityInfo {public String name,packageName,targetActivity; public boolean enabled,exported; public int icon;}''',
'android/content/pm/ResolveInfo.java': '''package android.content.pm; public class ResolveInfo {public ActivityInfo activityInfo;}''',
'android/content/pm/ApplicationInfo.java': '''package android.content.pm; public class ApplicationInfo {public int icon; public ApplicationInfo(){} public ApplicationInfo(ApplicationInfo original){}}''',
'android/content/pm/ShortcutInfo.java': '''package android.content.pm;
import android.content.*;
public class ShortcutInfo {
 public String id; public ComponentName activity; public boolean enabled=true,immutable;
 public String label="retained",intent="retained",artwork="retained";
 public String getId(){return id;} public ComponentName getActivity(){return activity;}
 public boolean isEnabled(){return enabled;} public boolean isImmutable(){return immutable;}
 public static class Builder {private final ShortcutInfo info=new ShortcutInfo();
 public Builder(Context c,String id){info.id=id;} public Builder setActivity(ComponentName c){info.activity=c;return this;}
 public ShortcutInfo build(){return info;}}
}''',
'android/content/pm/ShortcutManager.java': '''package android.content.pm;
import java.util.*;
public class ShortcutManager {
 public List<ShortcutInfo> dynamic=new ArrayList<>(),pinned=new ArrayList<>();
 public List<ShortcutInfo> getDynamicShortcuts(){return dynamic;} public List<ShortcutInfo> getPinnedShortcuts(){return pinned;}
 public boolean updateShortcuts(List<ShortcutInfo> changes){
 Set<String> ids=new HashSet<>();
 for(ShortcutInfo change:changes){
 if(!ids.add(change.id))throw new AssertionError("duplicate shortcut update");
 for(List<ShortcutInfo> list:Arrays.asList(dynamic,pinned))for(ShortcutInfo old:list)if(old.id.equals(change.id))old.activity=change.activity;
 }return true;}
}''',
'android/content/pm/PackageManager.java': '''package android.content.pm;
import android.content.*; import java.util.List;
public abstract class PackageManager {
 public static final int COMPONENT_ENABLED_STATE_DEFAULT=0,COMPONENT_ENABLED_STATE_ENABLED=1,COMPONENT_ENABLED_STATE_DISABLED=2,DONT_KILL_APP=1,MATCH_DISABLED_COMPONENTS=512;
 public static class NameNotFoundException extends Exception {}
 public static class ComponentEnabledSetting {public final ComponentName component; public final int state,flags;
 public ComponentEnabledSetting(ComponentName c,int s,int f){component=c;state=s;flags=f;}}
 public abstract int getComponentEnabledSetting(ComponentName c);
 public abstract ActivityInfo getActivityInfo(ComponentName c,int flags) throws NameNotFoundException;
 public abstract List<ResolveInfo> queryIntentActivities(Intent i,int flags);
 public abstract void setComponentEnabledSetting(ComponentName c,int s,int flags);
 public abstract void setComponentEnabledSettings(List<ComponentEnabledSetting> settings);
}''',
'android/os/Build.java': '''package android.os; public class Build {public static class VERSION {public static int SDK_INT;}}''',
'android/os/Bundle.java': '''package android.os; public class Bundle {public void putParcelable(String key,Object value){}}''',
'android/graphics/Bitmap.java': '''package android.graphics; public class Bitmap {public enum Config{ARGB_8888} public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap();}}''',
'android/graphics/Canvas.java': '''package android.graphics; public class Canvas {public Canvas(Bitmap b){}}''',
'android/graphics/drawable/Drawable.java': '''package android.graphics.drawable; public class Drawable {public int getIntrinsicWidth(){return 48;} public int getIntrinsicHeight(){return 48;} public void setBounds(int a,int b,int c,int d){} public void draw(android.graphics.Canvas canvas){}}''',
'android/graphics/drawable/BitmapDrawable.java': '''package android.graphics.drawable; public class BitmapDrawable extends Drawable {public android.graphics.Bitmap getBitmap(){return new android.graphics.Bitmap();}}''',
'androidx/core/content/ContextCompat.java': '''package androidx.core.content; public class ContextCompat {public static android.graphics.drawable.Drawable getDrawable(android.content.Context c,int res){return new android.graphics.drawable.Drawable();}}''',
'androidx/core/app/NotificationCompat.java': '''package androidx.core.app; public class NotificationCompat {public static class Builder {public android.os.Bundle getExtras(){return new android.os.Bundle();}}}''',
'android/app/Notification.java': '''package android.app; public class Notification {public android.os.Bundle extras; public static class Builder {public android.os.Bundle getExtras(){return new android.os.Bundle();}}}''',
'org/telegram/messenger/Utilities.java': '''package org.telegram.messenger; public class Utilities {public static final Queue globalQueue=new Queue(); public static class Queue {public void postRunnable(Runnable r){r.run();}}}''',
'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger; public class ApplicationLoader {public static android.content.Context applicationContext;}''',
'org/telegram/messenger/AndroidUtilities.java': '''package org.telegram.messenger; public class AndroidUtilities {public static int dp(int n){return n;} public static void runOnUIThread(Runnable r){r.run();}}''',
'org/telegram/messenger/FileLog.java': '''package org.telegram.messenger; public class FileLog {public static void e(Throwable e){}}''',
'org/telegram/messenger/NotificationCenter.java': '''package org.telegram.messenger; public class NotificationCenter {public static int appIconChanged=1; public static NotificationCenter getGlobalInstance(){return new NotificationCenter();} public void postNotificationName(int event,Object... args){}}''',
'org/telegram/messenger/UserConfig.java': '''package org.telegram.messenger; public class UserConfig {public static final int MAX_ACCOUNT_COUNT=4; public static UserConfig getInstance(int i){return new UserConfig();} public boolean isClientActivated(){return false;}}''',
'org/telegram/messenger/NotificationsController.java': '''package org.telegram.messenger; public class NotificationsController {public static NotificationsController getInstance(int i){return new NotificationsController();} public void showNotifications(){}}''',
}


def main():
    text = CONTROLLER.read_text()
    ids = sorted(set(re.findall(r'R\.(\w+)\.(\w+)', text)) | {('drawable', 'player')})
    resources = 'package org.telegram.messenger; public class R {\n'
    for kind in sorted({kind for kind, _ in ids}):
        resources += f'public static class {kind} {{' + ''.join(f'public static final int {name}={i + 1};' for i, (group, name) in enumerate(ids) if group == kind) + '}\n'
    resources += '}\n'
    with tempfile.TemporaryDirectory(prefix='velora-icon-host-') as temporary:
        temp = Path(temporary)
        for name, source in {**STUBS, 'org/telegram/messenger/R.java': resources}.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        classes = temp / 'classes'
        subprocess.run(['javac', '--release', '17', '-d', str(classes), *map(str, temp.rglob('*.java')),
                        str(CONTROLLER), str(ROOT / 'tests/icons/LauncherIconControllerTest.java')], check=True)
        subprocess.run(['java', '-cp', str(classes), 'LauncherIconControllerTest'], check=True)


if __name__ == '__main__':
    main()
