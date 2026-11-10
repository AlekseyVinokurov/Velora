package app.nimarkogram.messenger.updater;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class NimarkoUpdaterSheet extends BottomSheet implements NimarkoUpdater.DownloadUiOwner {

    private BaseFragment fragment;
    private Theme.ResourcesProvider resourcesProvider;
    private boolean downloadButtonClicked = false;
    private boolean downloadFinished = false;
    private boolean bindingDetached;
    private long downloadBindingToken;
    private ButtonWithCounterView downloadButton;
    private Runnable refreshControls;

    public NimarkoUpdaterSheet(Context context, Theme.ResourcesProvider resourcesProvider, boolean available, NimarkoUpdater.Update update) {
        super(context, false, resourcesProvider);
        setOpenNoDelay(true);
        fixNavigationBar();
        setCanDismissWithSwipe(false);
        setCanDismissWithTouchOutside(true);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        UpdateContentScrollView scrollView = new UpdateContentScrollView(context, available);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(AndroidUtilities.dp(16));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16),
                AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.velora_icon_foreground);
        mark.setBackgroundResource(R.drawable.velora_icon_background);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, LayoutHelper.createLinear(56, 56));
        TextView title = new TextView(context);
        title.setText(getString(R.string.VeloraUpdateTitle));
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1));
        title.setPadding(AndroidUtilities.dp(16), 0, 0, 0);
        contentLayout.addView(header);

        TextView version = new TextView(context);
        version.setTextSize(15);
        version.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        version.setText(available
                ? NimarkoUpdater.getCurrentVersionName() + " → " + update.version.replaceFirst("^v", "")
                : NimarkoUpdater.getCurrentVersionName());
        contentLayout.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 24, 0, 24, 12));

        if (available) {
            HeaderCell changelogHeader = new HeaderCell(context, resourcesProvider);
            changelogHeader.setText(getString(R.string.VeloraWhatsNew));
            contentLayout.addView(changelogHeader);
            TextView notes = new TextView(context);
            notes.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            notes.setTextSize(15);
            notes.setLineSpacing(AndroidUtilities.dp(4), 1f);
            String summary = VeloraReleaseNotes.summary(update.changelog);
            notes.setText(summary.isEmpty() ? getString(R.string.VeloraNoUpdateNotes) : summary);
            contentLayout.addView(notes, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 24, 0, 24, 16));
        }

        LinearLayout footerLayout = new LinearLayout(context);
        footerLayout.setOrientation(LinearLayout.VERTICAL);
        footerLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        if (available) {
            downloadButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            downloadButton.setFilled(true);
            downloadButton.setText(getUpdateSizeString(update), false);
            if (NimarkoUpdater.isUpdateDownloaded()) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            }
            downloadBindingToken = NimarkoUpdater.bindDownloadUi(downloadButton, this);
            NimarkoUpdater.DownloadUiState downloadState = NimarkoUpdater.getDownloadUiState();
            if (downloadState.finished) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            } else if (downloadState.downloading || downloadState.paused) {
                downloadButtonClicked = true;
                downloadButton.setText(downloadState.paused ? getString(R.string.NM_UpdateResume)
                        : LocaleController.formatString(R.string.AppUpdateDownloading, downloadState.progress), false);
            }
            downloadButton.setOnClickListener(v -> {
                if (downloadFinished) {
                    if (NimarkoUpdater.apkFile != null) {
                        NimarkoUpdater.installApk(getContext(), NimarkoUpdater.apkFile.getAbsolutePath());
                    }
                    return;
                }
                NimarkoUpdater.DownloadUiState currentState = NimarkoUpdater.getDownloadUiState();
                if (currentState.paused) {
                    downloadButtonClicked = true;
                    NimarkoUpdater.resumeDownload(getContext().getApplicationContext());
                    return;
                }
                if (currentState.downloading) {
                    NimarkoUpdater.pauseDownload();
                    downloadButton.setText(getString(R.string.NM_UpdateResume), false);
                    return;
                }
                if (!downloadButtonClicked) {
                    downloadButtonClicked = true;
                    NimarkoUpdater.downloadApk(getContext(), update.downloadURL,
                            "Velora " + update.version, downloadBindingToken);
                }
            });
            footerLayout.addView(downloadButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 14, 16, 0));
            LinearLayout downloadControls = new LinearLayout(context);
            downloadControls.setVisibility(View.GONE);
            footerLayout.addView(downloadControls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 0));
            ButtonWithCounterView cancelButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            cancelButton.setText(getString(R.string.Cancel), false);
            cancelButton.setVisibility(View.GONE);
            cancelButton.setOnClickListener(v -> {
                NimarkoUpdater.DownloadUiState state = NimarkoUpdater.getDownloadUiState();
                if (!state.downloading && !state.paused) { dismiss(); return; }
                NimarkoUpdater.cancelDownload(context, NimarkoUpdater.id);
                downloadButtonClicked = false;
                downloadFinished = false;
                downloadButton.setText(getUpdateSizeString(update), false);
            });
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(48), 1f);
            cancelParams.leftMargin = AndroidUtilities.dp(8);
            downloadControls.addView(cancelButton, cancelParams);

            ButtonWithCounterView pauseButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            pauseButton.setOnClickListener(v -> {
                NimarkoUpdater.DownloadUiState state = NimarkoUpdater.getDownloadUiState();
                if (state.paused) {
                    NimarkoUpdater.resumeDownload(getContext().getApplicationContext());
                } else if (state.downloading) {
                    NimarkoUpdater.pauseDownload();
                }
                refreshControls.run();
            });
            pauseButton.setVisibility(View.GONE);
            downloadControls.addView(pauseButton, 0,
                    new LinearLayout.LayoutParams(0, AndroidUtilities.dp(48), 1f));
            refreshControls = () -> {
                rootLayout.removeCallbacks(refreshControls);
                if (bindingDetached) return;
                NimarkoUpdater.DownloadUiState state = NimarkoUpdater.getDownloadUiState();
                boolean active = state.downloading || state.paused;
                downloadControls.setVisibility(active ? View.VISIBLE : View.GONE);
                pauseButton.setVisibility(active ? View.VISIBLE : View.GONE);
                cancelButton.setVisibility(active ? View.VISIBLE : View.GONE);
                pauseButton.setText(getString(state.paused ? R.string.NM_UpdateResume : R.string.NM_UpdatePause), false);
                if (state.paused) downloadButton.setText(getString(R.string.NM_UpdateResume), false);
                rootLayout.postDelayed(refreshControls, 500);
            };
            rootLayout.post(refreshControls);

            ButtonWithCounterView scheduleButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            scheduleButton.setText(getString(R.string.VeloraUpdateLater), false);
            scheduleButton.setOnClickListener(v -> {
                NimarkoUpdateConfig.setUpdateScheduleTimestamp(System.currentTimeMillis());
                dismiss();
            });
            footerLayout.addView(scheduleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 12));
        } else {
            TextCell checkOnLaunch = new TextCell(context, 23, false, true, resourcesProvider);
            checkOnLaunch.setBackground(createRowPressedBackground(resourcesProvider));
            checkOnLaunch.setTextAndCheckAndIcon(getString(R.string.UP_Auto_OTA), NimarkoUpdateConfig.getAutoOTA(), R.drawable.msg_retry, false);
            checkOnLaunch.setOnClickListener(v -> {
                NimarkoUpdateConfig.setAutoOTA(!NimarkoUpdateConfig.getAutoOTA());
                checkOnLaunch.setChecked(!checkOnLaunch.isChecked());
            });
            contentLayout.addView(checkOnLaunch);

            TextCell clearUpdates = new TextCell(context, resourcesProvider);
            clearUpdates.setBackground(createRowPressedBackground(resourcesProvider));
            clearUpdates.setTextAndIcon(getString(R.string.UP_ClearUpdatesCache), R.drawable.msg_clear, false);
            clearUpdates.setOnClickListener(v -> {
                if (NimarkoUpdater.getOtaDirSize().replaceAll("\\D+", "").equals("0")) {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(getString(R.string.UP_NothingToClear)).show();
                } else {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(LocaleController.formatString(R.string.UP_ClearedUpdatesCache, NimarkoUpdater.getOtaDirSize())).show();
                    NimarkoUpdater.cleanOtaDir();
                }
                NimarkoUpdater.cancelDownload(getContext(), NimarkoUpdater.id);
                NimarkoUpdateConfig.setUpdateAvailable(false);
            });
            contentLayout.addView(clearUpdates);

            ButtonWithCounterView checkUpdatesButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            checkUpdatesButton.setText(getString(R.string.UP_CheckForUpdates), true);
            checkUpdatesButton.setOnClickListener(v ->
                    NimarkoUpdater.checkUpdates(fragment, true,
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(
                                    NimarkoUpdater.noPublishedRelease ? R.string.VeloraNoRelease : R.string.UP_Not_Found)).show(),
                            this::dismiss,
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(R.string.UP_CheckFailed)).show()));
            footerLayout.addView(checkUpdatesButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 16));
        }

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        rootLayout.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        rootLayout.addView(footerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(rootLayout);
        setOnDismissListener(() -> {
            bindingDetached = true;
            if (refreshControls != null) rootLayout.removeCallbacks(refreshControls);
            long token = downloadBindingToken;
            downloadBindingToken = 0L;
            if (token != 0L) NimarkoUpdater.unbindDownloadUi(token);
        });
    }

    private static final class UpdateContentScrollView extends NestedScrollView {
        private final int maxHeight;

        UpdateContentScrollView(Context context, boolean updateAvailable) {
            super(context);
            int reservedHeight = AndroidUtilities.dp(updateAvailable ? 244 : 116);
            maxHeight = Math.min(
                    AndroidUtilities.dp(640),
                    Math.max(AndroidUtilities.dp(48), AndroidUtilities.displaySize.y - reservedHeight));
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setNestedScrollingEnabled(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int limit = maxHeight;
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                limit = Math.min(limit, MeasureSpec.getSize(heightMeasureSpec));
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    private static Drawable createRowPressedBackground(
            Theme.ResourcesProvider resourcesProvider) {
        return Theme.createRadSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), 12, 12);
    }

    @Override
    public void onDownloadComplete() {
        if (bindingDetached || downloadButton == null || downloadBindingToken == 0L) return;
        downloadFinished = true;
        downloadButtonClicked = true;
        downloadButton.setText(getString(R.string.UP_Install), true);
    }

    @Override
    public void onDownloadError() {
        if (bindingDetached || downloadBindingToken == 0L) return;
        downloadButtonClicked = false;
    }

    private StringBuilder getUpdateSizeString(NimarkoUpdater.Update update) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.VeloraUpdateAction));
        if (!TextUtils.isEmpty(update.size)) {
            sb.append(" (").append(update.size).append(")");
        }
        return sb;
    }

    public void setFragmentParams(BaseFragment fragment) {
        this.fragment = fragment;
        this.resourcesProvider = fragment.getResourceProvider();
    }

    public static void showAlert(BaseFragment fragment, boolean available, NimarkoUpdater.Update update) {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        if (fragment == null || fragment.getParentActivity() == null || fragment.getContext() == null) {
            return;
        }
        if (!NimarkoUpdater.isStableUpdaterEnabled()) {
            fragment.showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(LocaleController.getString(R.string.VeloraProject))
                    .setMessage(LocaleController.getString(R.string.VeloraUpdateUnavailable))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .create());
            return;
        }
        if (available && update != null && update.isNew()) {
            showPreparedAlert(fragment, true, update);
            return;
        }
        NimarkoUpdater.Update cached = NimarkoUpdater.getOrRestoreLastUpdate();
        if (cached != null) NimarkoUpdater.checkDirs();
        NimarkoUpdaterSheet sheet = new NimarkoUpdaterSheet(fragment.getContext(), fragment.getResourceProvider(), cached != null, cached);
        sheet.setFragmentParams(fragment);
        fragment.showDialog(sheet);
        NimarkoUpdater.checkUpdates(fragment, true,
                () -> BulletinFactory.of(sheet.getContainer(), sheet.resourcesProvider)
                        .createSimpleBulletin(R.raw.info, getString(NimarkoUpdater.noPublishedRelease ? R.string.VeloraNoRelease : R.string.UP_Not_Found)).show(),
                sheet::dismiss,
                () -> BulletinFactory.of(sheet.getContainer(), sheet.resourcesProvider)
                        .createErrorBulletin(getString(R.string.UP_CheckFailed)).show());
    }

    private static void showPreparedAlert(BaseFragment fragment, boolean available, NimarkoUpdater.Update update) {
        NimarkoUpdaterSheet alert = new NimarkoUpdaterSheet(fragment.getContext(), fragment.getResourceProvider(), available, update);
        alert.setFragmentParams(fragment);
        fragment.showDialog(alert);
    }
}
