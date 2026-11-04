package soundgram.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Project and upstream credits, also opened from the inherited supporter badges.
 * The class name stays compatible with existing callers. Velora has no configured
 * donation service and does not offer paid badges or early releases.
 */
public class SoundGramBadgeBottomSheet extends BottomSheet {

    public SoundGramBadgeBottomSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16),
                AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.VeloraProject));
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView credits = new TextView(context);
        credits.setText(LocaleController.getString(R.string.VeloraCredits));
        credits.setTextSize(15);
        credits.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        root.addView(credits, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 12, 0, 16));

        addLink(root, R.string.VeloraProject, "https://github.com/AlekseyVinokurov/Velora");
        addLink(root, R.string.VeloraUpstreamSource, "https://github.com/soundgram-project/SoundGram");
        setCustomView(root);
    }

    private void addLink(LinearLayout root, int textRes, String url) {
        TextView link = new TextView(getContext());
        link.setText(LocaleController.getString(textRes));
        link.setTextSize(15);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setTextColor(getThemedColor(Theme.key_dialogTextBlue));
        link.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        link.setOnClickListener(v -> {
            Browser.openUrl(getContext(), url);
            dismiss();
        });
        root.addView(link, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }
}
