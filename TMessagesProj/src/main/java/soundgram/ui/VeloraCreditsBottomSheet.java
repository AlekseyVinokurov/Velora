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

/** Project, upstream authors and GPL links, opened from About Velora. */
public class VeloraCreditsBottomSheet extends BottomSheet {

    public VeloraCreditsBottomSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16),
                AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.VeloraAuthorsLicenses));
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
        addLink(root, R.string.VeloraTelegramSource, "https://github.com/DrKLO/Telegram");
        addLink(root, R.string.VeloraAuthorsLicenses, "https://github.com/AlekseyVinokurov/Velora/blob/main/README.md#license-and-credits");
        addLink(root, R.string.VeloraLicense, "https://github.com/AlekseyVinokurov/Velora/blob/main/LICENSE");
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
