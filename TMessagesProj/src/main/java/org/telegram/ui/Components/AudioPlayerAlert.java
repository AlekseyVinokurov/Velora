/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static soundgram.music.ChannelMusicFragment.setAudioCover;

import soundgram.music.PublicLibraryManager;
import soundgram.music.SmartRadioEngine;
import soundgram.ui.SoundGramStyleManager;
import soundgram.ui.SoundGramStyleSettingsFragment;
import android.Manifest;
import android.graphics.Path;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.media3.common.C;
import com.google.android.gms.cast.framework.CastContext;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.chromecast.ChromecastController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import soundgram.music.ChannelMusicFragment;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSlider;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.CastSync;
import org.telegram.ui.Cells.AudioPlayerCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChooseQualityLayout;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import soundgram.music.MusicWebSearcher;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import soundgram.music.SoundGramEQAlert;

import androidx.recyclerview.widget.LinearSmoothScroller;

public class AudioPlayerAlert extends BottomSheet
        implements NotificationCenter.NotificationCenterDelegate, DownloadController.FileDownloadProgressListener {
    public static final int PLAY_ONCE = 3;
    public static AudioPlayerAlert instance;

    // Blacklist удалённых треков — фильтруется при каждом обновлении audioMessages из канала.
    // Static чтобы пережить пересоздание плеера.
    public static final java.util.Set<String> deletedTrackMessages =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    public static final java.util.Set<Integer> deletedTrackIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    // Blacklist по ключу "artist|||title" — для фильтрации в mergeLocalOnlyTracks().
    // Нужен для нативных файловых треков, у которых нет SG_STREAM-идентификатора.
    public static final java.util.Set<String> deletedTrackKeys =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private TextView forwardButton;
    private TextView backwardButton;
    private int currentLyricsReqId = 0;

    private ActionBar actionBar;
    private View navBarFiller;
    private View actionBarShadow;
    private View playerShadow;
    private boolean searchWas;
    private boolean searching;
    // Источник воспроизведения — ставится снаружи ДО открытия плеера
    public static String currentSource = "untitled";
    public static String currentSourceLabel = ""; // доп. текст (название альбома, плейлиста и т.д.)

    public static final String SOURCE_LIBRARY = "library";
    public static final String SOURCE_WAVE = "wave";
    public static final String SOURCE_ALBUM = "album";
    public static final String SOURCE_PLAYLIST = "playlist";
    public static final String SOURCE_ARTIST = "artist";
    public static final String SOURCE_SEARCH = "search";
    public static final String SOURCE_UNTITLED = "untitled";

    // View, которую будем обновлять при смене трека
    private TextView sourceChipTextView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter listAdapter;
    private BottomSheet currentQueueSheet;
    private QueueAdapter currentQueueAdapter;
    private ArrayList<MessageObject> currentQueueDisplayList;
    private RecyclerListView currentQueueListView;
    private LinearLayout emptyView;
    private ImageView emptyImageView;
    private TextView emptyTitleTextView;
    private TextView emptySubtitleTextView;
    private FrameLayout lyricsContainer;
    private RecyclerListView lyricsListView; // unused, kept for compat
    @SuppressWarnings("unused")
    private Object lyricsAdapter; // unused, kept for compat
    private LyricsView lyricsView;
    private ArrayList<LrcLine> lyricsLines = new ArrayList<>();
    private boolean isLyricsVisible = false;
    private ValueAnimator lyricsAnimator;
    private int currentLyricIndex = -1;
    private boolean isFetchingLyrics = false;

    private View borderView; // <--- ДОБАВЬ ЭТУ СТРОКУ
    private soundgram.ui.RadioWaveProgressView waveProgressView;

    private FrameLayout playerLayout;
    private ButtonWithCounterView saveToProfileButton;
    private LinkSpanDrawable.LinksTextView unsaveFromProfileTextView;
    private ImageView favButton; // кнопка-закладка (добавить в библиотеку)
    private ItemTouchHelper itemTouchHelper;
    private CoverContainer coverContainer;
    private TextView lyricsChip;
    private ClippingTextViewSwitcher titleTextView;
    private RLottieImageView prevButton;
    private RLottieImageView queueButton;
    private RLottieImageView nextButton;
    private ClippingTextViewSwitcher authorTextView;
    private ActionBarMenuItem optionsButton;
    private ActionBarMenuItem topOptionsButton;
    private ChooseQualityLayout.QualityIcon optionsIcon;
    private ActionBarMenuSubItem castItem;
    private CastMediaRouteButton castItemButton;
    private boolean castAvailable;
    private LineProgressView progressView;
    private SeekBarView seekBarView;
    private SimpleTextView timeTextView;
    private ActionBarMenuItem playbackSpeedButton;
    private SpeedIconDrawable speedIcon;
    private ActionBarMenuSlider.SpeedSlider speedSlider;
    private boolean slidingSpeed;
    private ActionBarMenuSubItem[] speedItems = new ActionBarMenuSubItem[6];
    private TextView durationTextView;
    private ActionBarMenuItem repeatButton;
    private ActionBarMenuItem modeMenuButton; // кнопка режимов в правом нижнем углу
    private ActionBarMenuSubItem playOnceItem;
    private ActionBarMenuSubItem repeatSongItem;
    private ActionBarMenuSubItem repeatListItem;
    private ActionBarMenuSubItem shuffleListItem;
    private ActionBarMenuSubItem reverseOrderItem;
    private ImageView playButton;
    private PlayPauseDrawable playPauseDrawable;
    private Drawable lastCoverDrawable;
    private ImageView eqBtn;
    private ImageView lyricsBtnBottom;
    private ImageView topOptionsBtn;
    private ImageView moreBtn;
    private TextView nextTrackTitleView;
    private TextView nextTrackDurationView;
    private FrameLayout blurredView;
    private BackupImageView bigAlbumConver;
    private ActionBarMenuItem searchItem;
    private boolean blurredAnimationInProgress;
    private View[] buttons = new View[7];
    private SpringAnimation seekBarBufferSpring;

    private boolean draggingSeekBar;

    private long lastBufferedPositionCheck;
    private boolean currentAudioFinishedLoading;

    private boolean scrollToSong = true;

    private int searchOpenPosition = -1;
    private int searchOpenOffset;

    private boolean padWithItem;

    public static class LrcLine {
        public long timeMs;
        public String text;

        public LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private MessagesController.SavedMusicList savedMusicList;

    private boolean isMyList() {
        return savedMusicList != null
                && savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private ArrayList<MessageObject> playlist;
    private MessageObject lastMessageObject;
    private boolean noforwards;

    private int scrollOffsetY = Integer.MAX_VALUE;
    private int topBeforeSwitch;

    private boolean inFullSize;

    private String currentFile;

    private AnimatorSet actionBarAnimation;

    private int lastTime;
    private int lastDuration;

    private int TAG;

    private LaunchActivity parentActivity;
    int rewindingState;
    float rewindingProgress = -1;

    int rewindingForwardPressedCount;
    long lastRewindingTime;
    long lastUpdateRewindingPlayerTime;

    private boolean wasLight;

    private final static float[] speeds = new float[] {
            .5f, 1f, 1.2f, 1.5f, 1.7f, 2f
    };

    private String getSourceDisplayName() {
        switch (currentSource) {
            case SOURCE_LIBRARY:
                return getString(R.string.sg_source_library);
            case SOURCE_WAVE:
                return getString(R.string.sg_source_wave);
            case SOURCE_SEARCH:
                return getString(R.string.sg_source_search);
            case SOURCE_ALBUM:
                return TextUtils.isEmpty(currentSourceLabel) ? getString(R.string.sg_source_album) : currentSourceLabel;
            case SOURCE_PLAYLIST:
                return TextUtils.isEmpty(currentSourceLabel) ? getString(R.string.sg_source_playlist)
                        : currentSourceLabel;
            case SOURCE_ARTIST:
                return TextUtils.isEmpty(currentSourceLabel) ? getString(R.string.sg_source_artist)
                        : currentSourceLabel;
            default:
                return getString(R.string.sg_source_untitled);
        }
    }

    private final Runnable forwardSeek = new Runnable() {
        @Override
        public void run() {
            long duration = MediaController.getInstance().getDuration();
            if (duration == 0 || duration == C.TIME_UNSET) {
                lastRewindingTime = System.currentTimeMillis();
                return;
            }
            float currentProgress = rewindingProgress;

            long t = System.currentTimeMillis();
            long dt = t - lastRewindingTime;
            lastRewindingTime = t;
            long updateDt = t - lastUpdateRewindingPlayerTime;
            if (rewindingForwardPressedCount == 1) {
                dt = dt * 3 - dt;
            } else if (rewindingForwardPressedCount == 2) {
                dt = dt * 6 - dt;
            } else {
                dt = dt * 12 - dt;
            }
            long currentTime = (long) (duration * currentProgress + dt);
            currentProgress = currentTime / (float) duration;
            if (currentProgress < 0) {
                currentProgress = 0;
            }
            rewindingProgress = currentProgress;
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null && (messageObject.isMusic() || messageObject.isVoice())) {
                if (!MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().getPlayingMessageObject().audioProgress = rewindingProgress;
                }
                updateProgress(messageObject);
            }
            if (rewindingState == 1 && rewindingForwardPressedCount > 0
                    && MediaController.getInstance().isMessagePaused()) {
                if (updateDt > 200 || rewindingProgress == 0) {
                    lastUpdateRewindingPlayerTime = t;
                    MediaController.getInstance()
                            .seekToProgress(MediaController.getInstance().getPlayingMessageObject(), currentProgress);
                }
                if (rewindingForwardPressedCount > 0 && rewindingProgress > 0) {
                    AndroidUtilities.runOnUIThread(forwardSeek, 16);
                }
            }
        }
    };

    private void openSourceChat(MessageObject messageObject) {
        if (messageObject == null || parentActivity == null) return;
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }

        Bundle args = new Bundle();
        long did = messageObject.getDialogId();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        if (messageObject.getId() > 0) {
            args.putInt("message_id", messageObject.getId());
        }
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
        parentActivity.presentFragment(new ChatActivity(args), false, false);
        dismiss();
    }

    /** Меню опций ··· через ItemOptions */
    private void showPlayerMoreOptions(View anchor) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) return;

        ItemOptions o = ItemOptions.makeOptions(container, resourcesProvider, anchor, true);

        // 1. Добавить в профиль / Открепить от профиля
        long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(clientUserId);
        final TLRPC.Document document = mo.getDocument();
        final long documentId = document != null ? document.id : 0;
        final boolean isSaved = (documentId != 0 && userFull != null && userFull.saved_music != null && userFull.saved_music.id == documentId);

        if (isSaved) {
            o.add(R.drawable.msg_delete, "Открепить от профиля", () -> {
                o.dismiss();
                if (document != null) {
                    boolean isPublic = PublicLibraryManager.isLibraryPublic(currentAccount);
                    boolean shouldWarn = false;
                    TLRPC.Document replacementDoc = null;
                    if (isPublic) {
                        MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount).getSavedMusicIds();
                        java.util.HashSet<Long> remainingIds = new java.util.HashSet<>();
                        if (musicIds != null && musicIds.ids != null) {
                            for (Long id : musicIds.ids) {
                                if (id != null && id != documentId) {
                                    remainingIds.add(id);
                                }
                            }
                        }
                        if (remainingIds.isEmpty()) {
                            shouldWarn = true;
                        } else {
                            long channelId = ChannelMusicFragment.getChannelIdFromFile(currentAccount);
                            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
                            String channelUsername = chat != null ? ChatObject.getPublicUsername(chat) : null;

                            if (savedMusicList != null && savedMusicList.list != null) {
                                for (MessageObject m : savedMusicList.list) {
                                    if (m != null && m.getDocument() != null && m.getDocument().id != documentId && remainingIds.contains(m.getDocument().id)) {
                                        replacementDoc = m.getDocument();
                                        break;
                                    }
                                }
                            }
                            if (replacementDoc == null && playlist != null) {
                                for (MessageObject m : playlist) {
                                    if (m != null && m.getDocument() != null && m.getDocument().id != documentId && remainingIds.contains(m.getDocument().id)) {
                                        replacementDoc = m.getDocument();
                                        break;
                                    }
                                }
                            }
                            if (replacementDoc == null) {
                                ArrayList<MessageObject> myTracks = ChannelMusicFragment.getAudioMessages(currentAccount);
                                if (myTracks != null) {
                                    for (MessageObject m : myTracks) {
                                        if (m != null && m.getDocument() != null && m.getDocument().id != documentId && remainingIds.contains(m.getDocument().id)) {
                                            replacementDoc = m.getDocument();
                                            break;
                                        }
                                    }
                                }
                            }

                            if (replacementDoc == null) {
                                shouldWarn = true;
                            } else {
                                String docUser = PublicLibraryManager.extractLibraryUsername(replacementDoc);
                                if (TextUtils.isEmpty(docUser) || (channelUsername != null && !channelUsername.equalsIgnoreCase(docUser))) {
                                    shouldWarn = true;
                                }
                            }
                        }
                    }

                    if (shouldWarn) {
                        final TLRPC.Document nextDoc = replacementDoc;
                        Context alertCtx = getContext() != null ? getContext() : ApplicationLoader.applicationContext;
                        AlertDialog.Builder builder = new AlertDialog.Builder(alertCtx);
                        builder.setTitle(LocaleController.getString(R.string.sg_public_library_title));
                        builder.setMessage(LocaleController.getString(R.string.sg_public_library_unpin_warning));
                        builder.setPositiveButton(LocaleController.getString(R.string.sg_public_library_keep_public), (dialog, which) -> {
                            Bulletin.ProgressTwoLineAnimatedTitleLottieLayout layout = new Bulletin.ProgressTwoLineAnimatedTitleLottieLayout(alertCtx, resourcesProvider);
                            layout.titleTextView.setText("Публикация библиотеки");
                            layout.subtitleTextView.setText("Обновление трека... 0%");
                            layout.setAnimation(R.raw.ic_save_to_music, "Box", "Arrow");
                            layout.setProgress(0.01f);

                            Bulletin progressBulletin = containerView != null ? Bulletin.make((FrameLayout) containerView, layout, 60000) : null;
                            if (progressBulletin != null) {
                                progressBulletin.setCanHide(false);
                                progressBulletin.show();
                            }

                            PublicLibraryManager.saveDocumentToProfile(currentAccount, document, false, unpinned -> {
                                PublicLibraryManager.publishLibraryWithTrack(currentAccount, alertCtx, nextDoc, new PublicLibraryManager.PublishListener() {
                                    @Override
                                    public void onProgress(float progress) {
                                        AndroidUtilities.runOnUIThread(() -> {
                                            layout.setProgress(progress);
                                            layout.subtitleTextView.setText("Обновление трека... " + (int) (progress * 100) + "%");
                                        });
                                    }

                                    @Override
                                    public void onDone(boolean success) {
                                        AndroidUtilities.runOnUIThread(() -> {
                                            if (progressBulletin != null) {
                                                progressBulletin.setCanHide(true);
                                                progressBulletin.hide();
                                            }
                                            if (containerView != null) {
                                                if (success) {
                                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                                            .createSimpleBulletin(R.raw.saved_messages, "Библиотека обновлена")
                                                            .show();
                                                } else {
                                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                                            .createErrorBulletin("Не удалось обновить публичную библиотеку")
                                                            .show();
                                                }
                                            }
                                        });
                                    }
                                });
                            });
                        });
                        builder.setNegativeButton(LocaleController.getString(R.string.sg_public_library_ok), (dialog, which) -> {
                            PublicLibraryManager.saveDocumentToProfile(currentAccount, document, false, success -> {
                                PublicLibraryManager.setLibraryPublic(currentAccount, false);
                                if (success && containerView != null) {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                            .createSimpleBulletin(R.raw.saved_messages, "Удалено из профиля")
                                            .show();
                                }
                            });
                        });
                        builder.show();
                    } else {
                        PublicLibraryManager.saveDocumentToProfile(currentAccount, document, false, success -> {
                            if (success && containerView != null) {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .createSimpleBulletin(R.raw.saved_messages, "Удалено из профиля")
                                        .show();
                            }
                        });
                    }
                }
            });
        } else {
            o.add(R.drawable.left_status_profile, "Добавить в профиль", () -> {
                o.dismiss();
                if (document != null) {
                    if (PublicLibraryManager.isLibraryPublic(currentAccount)) {
                        Context alertCtx = getContext() != null ? getContext() : ApplicationLoader.applicationContext;
                        AlertDialog.Builder builder = new AlertDialog.Builder(alertCtx);
                        builder.setTitle(LocaleController.getString(R.string.sg_public_library_title));
                        builder.setMessage(LocaleController.getString(R.string.sg_public_library_attach_warning));
                        builder.setPositiveButton(LocaleController.getString(R.string.sg_public_library_keep_public), (dialog, which) -> {
                            Bulletin.ProgressTwoLineAnimatedTitleLottieLayout layout = new Bulletin.ProgressTwoLineAnimatedTitleLottieLayout(alertCtx, resourcesProvider);
                            layout.titleTextView.setText("Публикация библиотеки");
                            layout.subtitleTextView.setText("Обновление трека... 0%");
                            layout.setAnimation(R.raw.ic_save_to_music, "Box", "Arrow");
                            layout.setProgress(0.01f);

                            Bulletin progressBulletin = containerView != null ? Bulletin.make((FrameLayout) containerView, layout, 60000) : null;
                            if (progressBulletin != null) {
                                progressBulletin.setCanHide(false);
                                progressBulletin.show();
                            }

                            PublicLibraryManager.publishLibraryWithTrack(currentAccount, alertCtx, document, new PublicLibraryManager.PublishListener() {
                                @Override
                                public void onProgress(float progress) {
                                    AndroidUtilities.runOnUIThread(() -> {
                                        layout.setProgress(progress);
                                        layout.subtitleTextView.setText("Обновление трека... " + (int) (progress * 100) + "%");
                                    });
                                }

                                @Override
                                public void onDone(boolean success) {
                                    AndroidUtilities.runOnUIThread(() -> {
                                        if (progressBulletin != null) {
                                            progressBulletin.setCanHide(true);
                                            progressBulletin.hide();
                                        }
                                        if (containerView != null) {
                                            if (success) {
                                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                                        .createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.AudioSaveToMyProfileSaved))
                                                        .show();
                                            } else {
                                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                                        .createErrorBulletin("Не удалось обновить публичную библиотеку")
                                                        .show();
                                            }
                                        }
                                    });
                                }
                            });
                        });
                        builder.setNegativeButton(LocaleController.getString(R.string.sg_public_library_ok), (dialog, which) -> {
                            PublicLibraryManager.saveDocumentToProfile(currentAccount, document, true, success -> {
                                PublicLibraryManager.setLibraryPublic(currentAccount, false);
                                if (success && containerView != null) {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                            .createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.AudioSaveToMyProfileSaved))
                                            .show();
                                }
                            });
                        });
                        builder.show();
                    } else {
                        PublicLibraryManager.saveDocumentToProfile(currentAccount, document, true, success -> {
                            if (success && containerView != null) {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.AudioSaveToMyProfileSaved))
                                        .show();
                            }
                        });
                    }
                }
            });
        }

        // 2. Источник трека: если TG сообщение -> показать имя чата/канала и перейти (openSourceChat); если стриминг -> показать "Стриминг" с no-op
        boolean isSGStream = mo.messageOwner != null
                && mo.messageOwner.message != null
                && mo.messageOwner.message.startsWith("SG_STREAM|||");

        if (isSGStream) {
            o.add(R.drawable.live_stream, "Стриминг", o::dismiss);
        } else if (mo.getId() > 0) {
            long did = mo.getDialogId();
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
            boolean isChannel = chat != null && ChatObject.isChannel(chat) && !chat.megagroup;
            int iconRes = isChannel ? R.drawable.msg_channel : (chat != null ? R.drawable.msg_groups : R.drawable.msg_openprofile);
            String titleText;
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                titleText = (isChannel ? "Канал: " : "Чат: ") + chat.title;
            } else if (user != null) {
                titleText = "Чат: " + ContactsController.formatName(user.first_name, user.last_name);
            } else {
                titleText = isChannel ? "Канал" : "Чат";
            }
            o.add(iconRes, titleText, () -> {
                o.dismiss();
                openSourceChat(mo);
            });
        }

        // 3. Поделиться -> сразу Telegram ShareAlert
        o.add(R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
            o.dismiss();
            onSubItemClick(1);
        });

        o.show();
    }

    private LinearLayout buildPopupRow(String title, int iconRes, boolean isLast, Runnable onClick) {
        int textColor = getThemedColor(Theme.key_dialogTextBlack);
        int iconColor = getThemedColor(Theme.key_dialogIcon);
        int selectorColor = getThemedColor(Theme.key_listSelector);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AndroidUtilities.dp(52));
        row.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(24), 0);
        if (Build.VERSION.SDK_INT >= 21)
            row.setBackground(Theme.createSelectorDrawable(selectorColor, 0));
        row.setOnClickListener(v -> onClick.run());

        ImageView iconView = new ImageView(getContext());
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iconView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tv.setTextColor(textColor);
        tv.setSingleLine(true);
        row.addView(tv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        return row;
    }

    private View buildPopupDivider() {
        boolean isDark = Theme.isCurrentThemeDark();
        int dividerColor = isDark ? 0x20FFFFFF : 0x14000000;
        View v = new View(getContext());
        v.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(0.5f));
        lp.setMargins(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void showPlayerOptionsMenu() {
        if (getContext() == null)
            return;

        final BottomSheet[] menuDialogRef = { null };

        // Внешний контейнер с тёмным стеклянным фоном (как на скрине)
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE8252528); // очень тёмный, почти чёрный
            float r = AndroidUtilities.dp(22);
            bg.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
            bg.setStroke(AndroidUtilities.dp(1), 0x22FFFFFF);
            container.setBackground(bg);
        }
        container.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16),
                AndroidUtilities.dp(32));

        // Drag-handle
        View handle = new View(getContext());
        {
            GradientDrawable hBg = new GradientDrawable();
            hBg.setColor(0x55FFFFFF);
            hBg.setCornerRadius(AndroidUtilities.dp(3));
            handle.setBackground(hBg);
        }
        FrameLayout hw = new FrameLayout(getContext());
        hw.addView(handle, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(36), AndroidUtilities.dp(4), Gravity.CENTER));
        container.addView(hw,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20, 0, 4, 0, 12));

        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();

        // Карточка с пунктами меню — единый блок с закруглениями (как на скрине)
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(getThemedColor(Theme.key_dialogBackground));
            cardBg.setCornerRadius(AndroidUtilities.dp(14));
            cardBg.setStroke(AndroidUtilities.dp(0.75f), getThemedColor(Theme.key_divider));
            card.setBackground(cardBg);
        }
        card.setClipToOutline(true);

        // Определяем наличие трека в библиотеке (нужно для пункта "В плейлист")
        boolean inLib = findInLibrary(mo) != null;

        // 1. Поделиться
        card.addView(
                buildMenuRow2(getContext(), getString(R.string.sg_menu_share), R.drawable.msg_shareout, false, () -> {
                    if (menuDialogRef[0] != null)
                        menuDialogRef[0].dismiss();
                    onSubItemClick(1);
                }));
        card.addView(buildMenuDivider2(getContext()));

        // 2. Профиль: добавить или удалить в зависимости от состояния
        {
            boolean moInProfile = false;
            if (mo != null) {
                final TLRPC.Document _doc = mo.getDocument();
                if (_doc != null) {
                    moInProfile = MessagesController.getInstance(currentAccount)
                            .getSavedMusicIds().ids.contains(_doc.id);
                }
            }
            if (moInProfile) {
                card.addView(buildMenuRow2(getContext(),
                        getString(R.string.AudioSaveToMyProfileUnsaved),
                        R.drawable.msg_delete, false, () -> {
                            if (menuDialogRef[0] != null)
                                menuDialogRef[0].dismiss();
                            onSubItemClick(206);
                        }));
            } else {
                card.addView(buildMenuRow2(getContext(),
                        getString(R.string.sg_menu_add_to_profile),
                        R.drawable.msg_openprofile, false, () -> {
                            if (menuDialogRef[0] != null)
                                menuDialogRef[0].dismiss();
                            onSubItemClick(205);
                        }));
            }
        }

        // 3. Добавить в плейлист — только если трек есть в библиотеке
        if (inLib) {
            card.addView(buildMenuDivider2(getContext()));
            card.addView(buildMenuRow2(getContext(), getString(R.string.sg_menu_add_to_playlist), R.drawable.msg_add,
                    true, () -> {
                        if (menuDialogRef[0] != null)
                            menuDialogRef[0].dismiss();
                        onSubItemClick(100);
                    }));
        }

        container.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        builder.setCustomView(container);
        BottomSheet menuDialog = builder.create();
        menuDialogRef[0] = menuDialog;
        menuDialog.show();
    }

    /** Строка меню в новом стиле (без иконки слева, только текст, крупный) */
    private View buildMenuRow2(Context ctx, String title, int iconRes, boolean isLast, Runnable onClick) {
        FrameLayout row = new FrameLayout(ctx);
        row.setMinimumHeight(AndroidUtilities.dp(58));
        if (Build.VERSION.SDK_INT >= 21) {
            row.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 0));
        }
        row.setOnClickListener(v -> onClick.run());

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

        return row;
    }

    private View buildMenuDivider2(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(0.5f));
        lp.setMargins(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(getThemedColor(Theme.key_divider));
        return v;
    }

    /** Является ли данный режим активным прямо сейчас */
    private boolean isModeActive(int id) {
        switch (id) {
            case 5:
                return SharedConfig.repeatMode == PLAY_ONCE;
            case 3:
                return SharedConfig.repeatMode == 2;
            case 4:
                return SharedConfig.repeatMode == 1;
            case 2:
                return SharedConfig.shuffleMusic;
            case 1:
                return SharedConfig.playOrderReversed;
        }
        return false;
    }

    /**
     * Кастомный попап режимов воспроизведения — появляется ВВЕРХ над кнопкой
     * modeMenuButton.
     * Строки крупные (52dp), иконка + текст, активный режим подсвечен акцентным
     * цветом.
     */
    private void showModeMenu() {
        if (getContext() == null || modeMenuButton == null)
            return;

        int popupBgColor = getThemedColor(Theme.key_dialogBackground);
        int strokeColor = getThemedColor(Theme.key_divider);
        int selectorColor = getThemedColor(Theme.key_listSelector);

        // Собираем контейнер попапа
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(popupBgColor);
            bg.setCornerRadius(AndroidUtilities.dp(16));
            bg.setStroke(AndroidUtilities.dp(1), strokeColor);
            container.setBackground(bg);
        }
        container.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));

        int[] modeIds = { 5, 3, 4, 2, 1 };
        int[] modeIcons = {
                R.drawable.photo_timer,
                R.drawable.player_new_repeatone,
                R.drawable.player_new_repeatall,
                R.drawable.player_new_shuffle,
                R.drawable.player_new_order
        };
        int[] modeLabels = {
                R.string.PlayOnce,
                R.string.RepeatSong,
                R.string.RepeatList,
                R.string.ShuffleList,
                R.string.ReverseOrder
        };

        android.widget.PopupWindow[] popupRef = { null };

        for (int i = 0; i < modeIds.length; i++) {
            final int id = modeIds[i];
            boolean active = isModeActive(id);
            int color = active ? getThemedColor(Theme.key_player_buttonActive) : getThemedColor(Theme.key_dialogTextBlack);

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(AndroidUtilities.dp(52));
            row.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(24), 0);
            if (Build.VERSION.SDK_INT >= 21)
                row.setBackground(Theme.createSelectorDrawable(selectorColor, 0));

            ImageView iconView = new ImageView(getContext());
            iconView.setImageResource(modeIcons[i]);
            iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(iconView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

            TextView tv = new TextView(getContext());
            tv.setText(LocaleController.getString(modeLabels[i]));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            tv.setTextColor(color);
            tv.setSingleLine(true);
            row.addView(tv, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            row.setOnClickListener(v -> {
                if (popupRef[0] != null)
                    popupRef[0].dismiss();
                // Применяем режим
                if (id == 1 || id == 2) {
                    boolean oldReversed = SharedConfig.playOrderReversed;
                    if (SharedConfig.playOrderReversed && id == 1 || SharedConfig.shuffleMusic && id == 2)
                        MediaController.getInstance().setPlaybackOrderType(0);
                    else
                        MediaController.getInstance().setPlaybackOrderType(id);
                    if (listAdapter != null)
                        listAdapter.notifyDataSetChanged();
                    if (oldReversed != SharedConfig.playOrderReversed) {
                        if (listView != null)
                            listView.stopScroll();
                        scrollToCurrentSong(false);
                    }
                } else {
                    if (id == 4) {
                        if (SharedConfig.repeatMode == 1)
                            SharedConfig.setRepeatMode(0);
                        else
                            SharedConfig.setRepeatMode(1);
                    } else if (id == 5) {
                        if (SharedConfig.repeatMode == PLAY_ONCE)
                            SharedConfig.setRepeatMode(0);
                        else
                            SharedConfig.setRepeatMode(PLAY_ONCE);
                    } else {
                        if (SharedConfig.repeatMode == 2)
                            SharedConfig.setRepeatMode(0);
                        else
                            SharedConfig.setRepeatMode(2);
                    }
                }
                updateRepeatButtonBoth();
            });

            container.addView(row, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (i < modeIds.length - 1) {
                View div = new View(getContext());
                div.setBackgroundColor(0x20FFFFFF);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                dlp.setMargins(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
                container.addView(div, dlp);
            }
        }

        // Минимальная ширина попапа — 220dp
        int popupWidth = Math.max(AndroidUtilities.dp(220),
                AndroidUtilities.displaySize.x / 2);
        container.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupH = container.getMeasuredHeight();

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                container, popupWidth, popupH, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(AndroidUtilities.dp(8));
        popupRef[0] = popup;

        // Координаты кнопки на экране → показываем попап ВЫШЕ неё
        int[] loc = new int[2];
        modeMenuButton.getLocationInWindow(loc);
        int xOff = loc[0] + modeMenuButton.getWidth() - popupWidth;
        int yOff = loc[1] - popupH - AndroidUtilities.dp(6);

        popup.showAtLocation(modeMenuButton, Gravity.NO_GRAVITY, xOff, yOff);

        // Плавное появление попапа: анимация alpha + scale (как у кнопки ···)
        container.setAlpha(0f);
        container.setScaleY(0.85f);
        container.setPivotX(popupWidth);
        container.setPivotY(popupH);
        container.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
    }

    public AudioPlayerAlert(final Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        instance = this;
        occupyNavigationBar = true;
        fixNavigationBar(0x00000000);
        navBarColorKey = -1;
        if (Build.VERSION.SDK_INT >= 21) {
            android.view.Window window = getWindow();
            if (window != null) {
                window.setNavigationBarColor(0x00000000);
            }
        }

        try {
            shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
            shadowDrawable.setColorFilter(
                    new PorterDuffColorFilter(getThemedColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));
        } catch (Exception e) {
            shadowDrawable = new android.graphics.drawable.ColorDrawable(getThemedColor(Theme.key_dialogBackground));
        }

        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject != null) {
            currentAccount = messageObject.currentAccount;
        } else {
            currentAccount = UserConfig.selectedAccount;
        }

        android.app.Activity act = AndroidUtilities.findActivity(context);
        if (act instanceof LaunchActivity) {
            parentActivity = (LaunchActivity) act;
        } else if (context instanceof LaunchActivity) {
            parentActivity = (LaunchActivity) context;
        } else {
            parentActivity = LaunchActivity.instance;
        }

        TAG = DownloadController.getInstance(currentAccount).generateObserverTag();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this,
                NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this,
                NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);

        containerView = new FrameLayout(context) {
            private RectF rect = new RectF();
            private boolean ignoreLayout = false;
            private int lastMeasturedHeight;
            private int lastMeasturedWidth;

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                return !isDismissed() && super.onTouchEvent(e);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int totalHeight = MeasureSpec.getSize(heightMeasureSpec);
                int w = MeasureSpec.getSize(widthMeasureSpec);

                if (totalHeight != lastMeasturedHeight || w != lastMeasturedWidth) {
                    if (blurredView != null && blurredView.getTag() != null) {
                        showAlbumCover(false, false);
                    }
                    lastMeasturedWidth = w;
                    lastMeasturedHeight = totalHeight;
                }

                ignoreLayout = true;

                if (playerLayout != null) {
                    playerLayout.setVisibility(searchWas && keyboardVisible ? INVISIBLE : VISIBLE);
                    if (playerShadow != null)
                        playerShadow.setVisibility(GONE); // Полностью отключаем тень

                    // Измеряем плеер как WRAP_CONTENT, чтобы он занял ровно свой объем
                    playerLayout.measure(widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.AT_MOST));

                    // listView скрыт — padding не нужен
                }

                if (listView != null && actionBarShadow != null) {
                    LayoutParams layoutParams = (LayoutParams) listView.getLayoutParams();
                    layoutParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
                    layoutParams = (LayoutParams) actionBarShadow.getLayoutParams();
                    layoutParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
                }

                if (blurredView != null) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) blurredView.getLayoutParams();
                    lp.topMargin = -getPaddingTop();
                }

                ignoreLayout = false;

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY));
                inFullSize = true;
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
                updateEmptyViewPosition();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    if (scrollOffsetY != 0 && actionBar != null && actionBar.getAlpha() == 0.0f) {
                        boolean tapOnPlayer = false;
                        if (playerLayout != null && playerLayout.getVisibility() == VISIBLE) {
                            float py = playerLayout.getTranslationY();
                            if (ev.getY() >= py && ev.getY() <= py + playerLayout.getMeasuredHeight()) {
                                tapOnPlayer = true;
                            }
                        }
                        if (!tapOnPlayer && ev.getY() < scrollOffsetY + dp(12)) {
                            dismiss();
                            return true;
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout)
                    return;
                super.requestLayout();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (listView == null || listView.getVisibility() != View.VISIBLE)
                    return;
                int offset = dp(13);
                int top = scrollOffsetY - backgroundPaddingTop - offset;
                top += listView.getTranslationY();
                int y = top + dp(20);
                int height = getMeasuredHeight() + dp(15) + backgroundPaddingTop;
                float rad = 1.0f;

                if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
                    float toMove = offset + dp(11 - 7);
                    float moveProgress = Math.min(1.0f,
                            (ActionBar.getCurrentActionBarHeight() - top - backgroundPaddingTop) / toMove);
                    float availableToMove = ActionBar.getCurrentActionBarHeight() - toMove;
                    top -= (int) (availableToMove * moveProgress);
                    y -= (int) (availableToMove * moveProgress);
                    height += (int) (availableToMove * moveProgress);
                    rad = 1.0f - moveProgress;
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    top += AndroidUtilities.statusBarHeight;
                    y += AndroidUtilities.statusBarHeight;
                }
                if (shadowDrawable != null) {
                    shadowDrawable.setBounds(0, top, getMeasuredWidth(), height);
                    shadowDrawable.draw(canvas);
                }
                if (rad != 1.0f) {
                    Theme.dialogs_onlineCirclePaint.setColor(getThemedColor(Theme.key_dialogBackground));
                    rect.set(backgroundPaddingLeft, backgroundPaddingTop + top,
                            getMeasuredWidth() - backgroundPaddingLeft, backgroundPaddingTop + top + dp(24));
                    canvas.drawRoundRect(rect, dp(12) * rad, dp(12) * rad, Theme.dialogs_onlineCirclePaint);
                }
                if (rad != 0) {
                    Theme.dialogs_onlineCirclePaint.setColor(getThemedColor(Theme.key_sheet_scrollUp));
                    Theme.dialogs_onlineCirclePaint
                            .setAlpha((int) (Color.alpha(getThemedColor(Theme.key_sheet_scrollUp)) * rad));
                    rect.set((getMeasuredWidth() - dp(36)) / 2, y, (getMeasuredWidth() + dp(36)) / 2, y + dp(4));
                    canvas.drawRoundRect(rect, dp(2), dp(2), Theme.dialogs_onlineCirclePaint);
                }
            }
        };
        containerView.setWillNotDraw(false);
        containerView.setPadding(0, 0, 0, 0);
        containerView.setBackgroundColor(0x00000000);
        containerView.setClipChildren(false);

        actionBar = new ActionBar(context, resourcesProvider) {
            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                containerView.invalidate();
            }
        };
        actionBar.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setItemsColor(getThemedColor(Theme.key_player_actionBarTitle), false);
        actionBar.setTitleColor(getThemedColor(Theme.key_player_actionBarTitle));
        actionBar.setOccupyStatusBar(true);
        actionBar.setAlpha(0.0f);

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchCollapse() {
                        if (searching) {
                            searchWas = false;
                            searching = false;
                            setAllowNestedScroll(true);
                            if (listAdapter != null)
                                listAdapter.search(null);
                        }
                    }

                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        setAllowNestedScroll(false);
                        if (listAdapter != null)
                            listAdapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        if (listAdapter != null) {
                            if (editText.length() > 0)
                                listAdapter.search(editText.getText().toString());
                            else {
                                searchWas = false;
                                listAdapter.search(null);
                            }
                        }
                    }
                });
        searchItem.setContentDescription("Search");
        EditTextBoldCursor editText = searchItem.getSearchField();
        editText.setHint("Search");
        editText.setTextColor(getThemedColor(Theme.key_player_actionBarTitle));
        editText.setCursorColor(getThemedColor(Theme.key_player_actionBarTitle));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1)
                    dismiss();
                else
                    onSubItemClick(id);
            }
        });

        actionBarShadow = new View(context);
        actionBarShadow.setAlpha(0.0f);
        actionBarShadow.setBackgroundResource(R.drawable.header_shadow);

        playerShadow = new View(context);
        playerShadow.setBackgroundColor(getThemedColor(Theme.key_dialogShadowLine));

        playerLayout = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                // ... твой текущий код setTranslationY ...
                if (containerView != null && getMeasuredHeight() > 0) {
                    float targetY = scrollOffsetY - getMeasuredHeight();
                    if (targetY > 0)
                        targetY = 0;
                    super.setTranslationY(targetY);
                } else {
                    super.setTranslationY(translationY);
                }
            }

            // ДОБАВИТЬ ЭТО:
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                // Сначала даем шанс кнопкам внутри плеера обработать нажатие
                boolean handled = super.dispatchTouchEvent(ev);

                // Если мы свайпаем по пустому месту плеера, передаем событие в список
                if (!handled && listView != null) {
                    MotionEvent e = MotionEvent.obtain(ev);
                    // Корректируем Y-координату, чтобы список правильно понял жест
                    e.offsetLocation(0, getTranslationY() - listView.getTranslationY());
                    handled = listView.onTouchEvent(e);
                    e.recycle();
                }
                return handled;
            }
        };
        int playerAccent = Theme.getColor(Theme.key_featuredStickers_addButton);
        android.graphics.drawable.GradientDrawable playerGlass = SoundGramStyleManager.buildGlassDrawable(
                SoundGramStyleSettingsFragment.KEY_GLASS_PLAYER,
                SoundGramStyleSettingsFragment.KEY_ALPHA_PLAYER,
                playerAccent, 28);
        playerLayout.setBackground(playerGlass);
        if (Build.VERSION.SDK_INT >= 21)
            playerLayout.setElevation(AndroidUtilities.dp(12));

        // playerUiContainer — FrameLayout, чтобы якорить элементы снизу вверх
        // и вычислять оставшееся место для обложки динамически
        FrameLayout playerUiContainer = new FrameLayout(context);
        int navBarPad = AndroidUtilities.navigationBarHeight > 0 ? AndroidUtilities.navigationBarHeight : 0;
        int statusBarPad = AndroidUtilities.statusBarHeight > 0 ? AndroidUtilities.statusBarHeight
                : AndroidUtilities.dp(24);
        playerUiContainer.setPadding(0, 0, 0, navBarPad);
        playerLayout.addView(playerUiContainer,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

        coverContainer = new CoverContainer(context) {
            @Override
            protected void onImageUpdated(ImageReceiver imageReceiver) {
                android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
                base.setCornerRadii(new float[] {
                        AndroidUtilities.dp(28), AndroidUtilities.dp(28),
                        AndroidUtilities.dp(28), AndroidUtilities.dp(28),
                        0, 0, 0, 0
                });
                base.setStroke(AndroidUtilities.dp(1), 0x33FFFFFF);

                android.content.SharedPreferences stylePrefs = ApplicationLoader.applicationContext
                        .getSharedPreferences(SoundGramStyleSettingsFragment.PREFS_NAME,
                                android.content.Context.MODE_PRIVATE);
                int playerAlpha = stylePrefs.getInt(SoundGramStyleSettingsFragment.KEY_ALPHA_PLAYER, 220);

                if (imageReceiver != null) {
                    android.graphics.Bitmap b = imageReceiver.getBitmap();
                    if (b != null && !b.isRecycled()) {
                        try {
                            int w = b.getWidth(), h = b.getHeight();
                            // Усредняем 4 угла → доминирующий цвет обложки
                            int avg = androidx.core.graphics.ColorUtils.blendARGB(
                                    androidx.core.graphics.ColorUtils.blendARGB(b.getPixel(w / 4, h / 4),
                                            b.getPixel(w * 3 / 4, h / 4), 0.5f),
                                    androidx.core.graphics.ColorUtils.blendARGB(b.getPixel(w / 4, h * 3 / 4),
                                            b.getPixel(w * 3 / 4, h * 3 / 4), 0.5f),
                                    0.5f);
                            // Непрозрачный градиент для "Выразительного" стиля
                            int baseColor = Theme.isCurrentThemeDark() ? 0xFF181818 : 0xFFFFFFFF;
                            int bottomColor = Theme.isCurrentThemeDark() ? 0xFF121212 : 0xFFF0F0F0;
                            int dark = androidx.core.graphics.ColorUtils.blendARGB(baseColor, avg, 0.30f);
                            int darker = bottomColor; // Почти черный/белый низ
                            base.setColors(new int[] {
                                    0xFF000000 | dark,
                                    0xFF000000 | darker
                            });
                            base.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT);
                        } catch (Exception e) {
                            base.setColor(Theme.getColor(Theme.key_dialogBackground));
                        }
                    } else {
                        base.setColor(Theme.getColor(Theme.key_dialogBackground));
                    }
                } else {
                    base.setColor(Theme.getColor(Theme.key_dialogBackground));
                }
                lastCoverDrawable = base;
                playerLayout.setBackground(base);
            }
        };

        // Слушаем изменения стиля — перерисовываем фон плеера при смене прозрачности
        SoundGramStyleManager.addStyleListener(playerLayout, () -> {
            if (coverContainer != null)
                coverContainer.invalidate();
        });

        borderView = new View(context);
        borderView.setVisibility(View.GONE);

        // ── TopBar: [↓ dismiss]  [опианариум · 1 из 9]  [в чат] ─────────
        FrameLayout playerTopBar = new FrameLayout(context);

        // Кнопка сворачивания (СЛЕВА)
        topOptionsBtn = new ImageView(context);
        topOptionsBtn.setImageResource(R.drawable.ic_ab_back);
        topOptionsBtn.setRotation(270f); // Стрелочка вниз
        topOptionsBtn.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        topOptionsBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable topBackBg = new GradientDrawable();
        topBackBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        topBackBg.setCornerRadius(AndroidUtilities.dp(14));
        topBackBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        topOptionsBtn.setBackground(topBackBg);
        topOptionsBtn.setOnClickListener(v -> dismiss());
        playerTopBar.addView(topOptionsBtn,
                LayoutHelper.createFrame(44, 44, Gravity.CENTER_VERTICAL | Gravity.LEFT, 0, 0, 0, 0));

        // Текст источника скрыт по запросу пользователя
        sourceChipTextView = new TextView(context);
        sourceChipTextView.setVisibility(View.GONE);
        playerTopBar.addView(sourceChipTextView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER, 48, 0, 96, 0));

        // Кнопка опций ··· (СПРАВА)
        moreBtn = new ImageView(context);
        moreBtn.setImageResource(R.drawable.ic_ab_other);
        moreBtn.setScaleType(ImageView.ScaleType.CENTER);
        moreBtn.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        moreBtn.setContentDescription("Опции");
        GradientDrawable moreBtnBg = new GradientDrawable();
        moreBtnBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        moreBtnBg.setCornerRadius(AndroidUtilities.dp(14));
        moreBtnBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        moreBtn.setBackground(moreBtnBg);
        moreBtn.setOnClickListener(v -> showPlayerMoreOptions(v));
        playerTopBar.addView(moreBtn,
                LayoutHelper.createFrame(44, 44, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 0, 0));

        // topBar — в самом верху плеера, минимальный отступ под статус-бар
        int topBarTopMargin = statusBarPad > 0 ? (int) (statusBarPad / AndroidUtilities.density) + 4 : 8;
        playerUiContainer.addView(playerTopBar,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                        18, topBarTopMargin, 18, 0));

        // ── Обложка ───────────────────────────────────────────────────────────
        int screenWidthDp = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density);
        int screenHeightDp = (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density);

        // Отступы снизу (строго по дизайн-системе SoundGram)
        int baseBottom = (navBarPad > 0 ? (int) (navBarPad / AndroidUtilities.density) : 0) + 10;
        int nextTrackBarMargin = baseBottom;
        int mediaButtonsBottomMargin = nextTrackBarMargin + 42 + 10;
        int seekBottomMargin = mediaButtonsBottomMargin + 76 + 10;
        int textBottomMargin = seekBottomMargin + 48 + 10;

        int totalControlsTop = textBottomMargin + 64;
        int topBarHeightArea = topBarTopMargin + 44 + 8;
        int availableForCover = Math.max(160, screenHeightDp - topBarHeightArea - totalControlsTop);
        int coverWidthDp = Math.min(screenWidthDp - 36, (int) (availableForCover / 0.92f));
        coverWidthDp = Math.min(coverWidthDp, 340);
        int coverHeightDp = (int) (coverWidthDp * 0.92f);
        int coverTopMargin = topBarHeightArea + Math.max(4, (availableForCover - coverHeightDp) / 2);

        FrameLayout coverFrame = new FrameLayout(context);
        coverFrame.addView(coverContainer, LayoutHelper.createFrame(coverWidthDp, coverHeightDp, Gravity.CENTER));

        GradientDrawable borderBg = new GradientDrawable();
        borderBg.setColor(Color.TRANSPARENT);
        borderBg.setCornerRadii(new float[]{
                AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(56), AndroidUtilities.dp(56)
        });
        borderBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        borderView.setBackground(borderBg);
        borderView.setVisibility(View.VISIBLE);
        coverFrame.addView(borderView, LayoutHelper.createFrame(coverWidthDp, coverHeightDp, Gravity.CENTER));

        // --- СОЗДАНИЕ КОНТЕЙНЕРА С ТЕКСТАМИ (ровно на месте обложки) ---
        lyricsContainer = new FrameLayout(context);
        lyricsContainer.setVisibility(View.GONE);
        lyricsContainer.setAlpha(0f);

        lyricsView = new LyricsView(context);
        lyricsContainer.addView(lyricsView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        coverFrame.addView(lyricsContainer, LayoutHelper.createFrame(coverWidthDp, coverHeightDp, Gravity.CENTER));

        // "текст" / "обложка" чип — BOTTOM RIGHT обложки, акцентный
        lyricsChip = new TextView(context);
        lyricsChip.setText("≡  текст");
        lyricsChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        android.graphics.Typeface monoFaceChip = AndroidUtilities.getTypeface("fonts/rmono.ttf");
        if (monoFaceChip == null) monoFaceChip = android.graphics.Typeface.MONOSPACE;
        lyricsChip.setTypeface(monoFaceChip);
        lyricsChip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        lyricsChip.setGravity(Gravity.CENTER);
        lyricsChip.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        lyricsChip.setBackground(soundgram.ui.SoundGramDesignTokens.makePillDrawable(getThemedColor(Theme.key_featuredStickers_addButton), 999));
        lyricsChip.setOnClickListener(v -> toggleLyrics());
        coverFrame.addView(lyricsChip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));

        // coverFrame — под topBar с центрированием в доступном пространстве
        playerUiContainer.addView(coverFrame, LayoutHelper.createFrame(
                coverWidthDp, coverHeightDp,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 18, coverTopMargin, 18, 0));

        // ── Название + автор + кнопка лирики ─────────────────────────────────
        // Используем горизонтальный LinearLayout: [текст (weight=1)] [кнопка лирики
        // (48dp)]
        LinearLayout textAndButtonsContainer = new LinearLayout(context);
        textAndButtonsContainer.setOrientation(LinearLayout.HORIZONTAL);
        textAndButtonsContainer.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        titleTextView = new ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                final TextView textView = new MarqueeTextView(context);
                textView.setTextColor(getThemedColor(Theme.key_player_actionBarTitle));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32);
                // Unbounded/Geologica для заголовка трека
                android.graphics.Typeface titleFace = AndroidUtilities.getTypeface("fonts/roundedmplus1c_bold.ttf");
                if (titleFace == null) titleFace = AndroidUtilities.bold();
                textView.setTypeface(titleFace);
                textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                textView.setMarqueeRepeatLimit(-1);
                textView.setSingleLine(true);
                textView.setSelected(true);
                textView.setFocusable(true);
                textView.setFocusableInTouchMode(true);
                textView.setHorizontallyScrolling(true);
                textView.setGravity(Gravity.CENTER_VERTICAL);
                return textView;
            }
        };
        textLayout.addView(titleTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        titleTextView.setOnClickListener(v -> {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && !TextUtils.isEmpty(playing.getMusicTitle())) {
                soundgram.music.SoundGramTTS.speak(v, playing.getMusicTitle());
            }
        });

        authorTextView = new ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                final TextView textView = new MarqueeTextView(context);
                textView.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                textView.setMarqueeRepeatLimit(-1);
                textView.setSingleLine(true);
                textView.setSelected(true);
                textView.setFocusable(true);
                textView.setFocusableInTouchMode(true);
                textView.setHorizontallyScrolling(true);
                textView.setGravity(Gravity.CENTER_VERTICAL);
                return textView;
            }
        };
        textLayout.addView(authorTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        // Обработчик нажатия на автора — переход в профиль артиста
        authorTextView.setOnClickListener(v -> {
            MessageObject playingMsg = MediaController.getInstance().getPlayingMessageObject();
            if (playingMsg == null)
                return;
            String fullAuthor = playingMsg.getMusicAuthor();
            String[] authors = fullAuthor.split("\\s*(,|&|\\bfeat\\.|\\bft\\.)\\s*");
            soundgram.music.ChannelMusicFragment dummyFragment = new soundgram.music.ChannelMusicFragment();
            if (authors.length > 1) {
                BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
                LinearLayout container = new LinearLayout(getContext());
                container.setOrientation(LinearLayout.VERTICAL);
                container.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16),
                        Theme.getColor(Theme.key_dialogBackground)));
                container.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));
                TextView sheetTitle = new TextView(getContext());
                sheetTitle.setText(getString(R.string.sg_goto_artist));
                sheetTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                sheetTitle.setTextSize(18);
                sheetTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                sheetTitle.setGravity(Gravity.CENTER);
                sheetTitle.setPadding(0, 0, 0, AndroidUtilities.dp(16));
                container.addView(sheetTitle);
                for (String authorName : authors) {
                    String cleanName = authorName.trim();
                    if (cleanName.isEmpty())
                        continue;
                    LinearLayout row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20),
                            AndroidUtilities.dp(12));
                    row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));
                    ImageView icon = new ImageView(getContext());
                    icon.setImageResource(R.drawable.msg_allowspeak);
                    icon.setColorFilter(Theme.getColor(Theme.key_dialogIcon), PorterDuff.Mode.SRC_IN);
                    row.addView(icon, LayoutHelper.createLinear(24, 24));
                    TextView text = new TextView(getContext());
                    text.setText(cleanName);
                    text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                    text.setTextSize(16);
                    row.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                            16, 0, 0, 0));
                    row.setOnClickListener(v1 -> {
                        builder.getDismissRunnable().run();
                        parentActivity.presentFragment(dummyFragment.new ArtistProfileFragment(cleanName, "", ""));
                        dismiss();
                    });
                    container.addView(row);
                }
                builder.setCustomView(container);
                builder.show();
            } else {
                parentActivity.presentFragment(dummyFragment.new ArtistProfileFragment(fullAuthor, "", ""));
                dismiss();
            }
        });

        // Текст занимает всё пространство слева (weight=1), кнопки справа
        textAndButtonsContainer.addView(textLayout,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));

        // Download / Library Button (стиль по макету: скругленный квадрат 48x48, рамка, иконка скачать)
        favButton = new ImageView(context);
        {
            boolean favInLib = findInLibrary(messageObject) != null;
            favButton.setImageResource(favInLib ? R.drawable.msg_delete : R.drawable.msg_download);
        }
        favButton.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        favButton.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable favBg = new GradientDrawable();
        favBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        favBg.setCornerRadius(AndroidUtilities.dp(14));
        favBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        favButton.setBackground(favBg);
        favButton.setOnClickListener(v -> {
            soundgram.music.SoundGramTTS.speak(v, favButton.getContentDescription());
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            MessageObject libMatch = findInLibrary(mo);
            if (libMatch != null) {
                showLibraryTrackDeleteSheet(libMatch);
            } else {
                onSubItemClick(5);
            }
        });
        favButton.setContentDescription(LocaleController.getString(R.string.AddToFavorites));
        favButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        textAndButtonsContainer.addView(favButton,
                LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        // Options Button (hidden from main UI per design spec)
        buttons[6] = optionsButton = new ActionBarMenuItem(context, null, 0, getThemedColor(Theme.key_player_button), false, resourcesProvider);
        optionsButton.setVisibility(View.GONE);

        // Название + автор + кнопка скачать — якорим СНИЗУ, прямо над seekFrame
        playerUiContainer.addView(textAndButtonsContainer,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START,
                        18, 0, 18, textBottomMargin));

        // Перемотка — с отступом под текстом
        seekBarView = new SeekBarView(context, resourcesProvider) {
            @Override
            boolean onTouch(MotionEvent ev) {
                return rewindingState == 0 && super.onTouch(ev);
            }
        };
        seekBarView.setLineWidth(16);
        
        seekBarView.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (stop)
                    MediaController.getInstance()
                            .seekToProgress(MediaController.getInstance().getPlayingMessageObject(), progress);
                MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                if (messageObject != null && (messageObject.isMusic() || messageObject.isVoice()))
                    updateProgress(messageObject);
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
                draggingSeekBar = pressed;
            }

            @Override
            public CharSequence getContentDescription() {
                return "";
            }
        });
        seekBarView.setReportChanges(true);

        seekBarBufferSpring = new SpringAnimation(new FloatValueHolder(0))
                .setSpring(new SpringForce().setStiffness(750f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY))
                .addUpdateListener((animation, value, velocity) -> {
                    if (seekBarView != null)
                        seekBarView.setBufferedProgress(value / 1000f);
                });

        progressView = new LineProgressView(context);
        progressView.setVisibility(View.INVISIBLE);

        timeTextView = new SimpleTextView(context);
        timeTextView.setTextSize(11);
        timeTextView.setText("0:00");
        timeTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmono.ttf") != null ? AndroidUtilities.getTypeface("fonts/rmono.ttf") : android.graphics.Typeface.MONOSPACE);
        timeTextView.setTextColor(getThemedColor(Theme.key_player_time));

        durationTextView = new TextView(context);
        durationTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        durationTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmono.ttf") != null ? AndroidUtilities.getTypeface("fonts/rmono.ttf") : android.graphics.Typeface.MONOSPACE);
        durationTextView.setTextColor(getThemedColor(Theme.key_player_time));
        durationTextView.setGravity(Gravity.RIGHT);
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        int bgColor = Theme.getColor(Theme.key_dialogBackground);

        // SeekFrame with 60-bar RadioWaveProgressView
        FrameLayout seekFrame = new FrameLayout(context);
        waveProgressView = new soundgram.ui.RadioWaveProgressView(context);
        waveProgressView.setOnSeekListener((progress, isFinal) -> {
            if (isFinal) {
                MediaController.getInstance().seekToProgress(MediaController.getInstance().getPlayingMessageObject(), progress);
            }
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo != null) {
                updateProgress(mo, false);
            }
        });
        seekFrame.addView(waveProgressView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));
        seekBarView.setVisibility(View.GONE);

        timeTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        durationTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        seekFrame.addView(timeTextView,
                LayoutHelper.createFrame(100, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 0, 0, 0, 0));
        seekFrame.addView(durationTextView,
                LayoutHelper.createFrame(100, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 0, 0));

        // seekFrame — волна и время прямо над кнопками воспроизведения
        playerUiContainer.addView(seekFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                18, 0, 18, seekBottomMargin));

        buttons = new View[7];
        final int iconColor = Theme.getColor(Theme.key_player_actionBarTitle);

        FrameLayout hiddenContainer = new FrameLayout(context);
        hiddenContainer.setVisibility(View.GONE);
        playerLayout.addView(hiddenContainer, LayoutHelper.createFrame(0, 0));

        playbackSpeedButton = new ActionBarMenuItem(context, null, 0, 0, false, resourcesProvider);
        hiddenContainer.addView(playbackSpeedButton);
        speedSlider = new ActionBarMenuSlider.SpeedSlider(getContext(), resourcesProvider);
        for (int i = 0; i < 6; i++)
            speedItems[i] = playbackSpeedButton.addSubItem(i, R.drawable.msg_speed_normal, "");

        buttons[5] = forwardButton = new TextView(context);
        buttons[1] = backwardButton = new TextView(context);
        hiddenContainer.addView(forwardButton);
        hiddenContainer.addView(backwardButton);

        FrameLayout mediaButtonsContainer = new FrameLayout(context);

        buttons[3] = playButton = new ImageView(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(150)
                            .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT).start();
                } else if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    animate().scaleX(1f).scaleY(1f).setDuration(250)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
                return super.onTouchEvent(event);
            }
        };
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setImageDrawable(playPauseDrawable = new PlayPauseDrawable(36));
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN);
        playButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), getThemedColor(Theme.key_featuredStickers_addButton), Theme.blendOver(getThemedColor(Theme.key_featuredStickers_addButton), 0x25000000)));
        playButton.setOnClickListener(v -> {
            if (MediaController.getInstance().isDownloadingCurrentMessage())
                return;
            boolean willPlay = MediaController.getInstance().isMessagePaused();
            soundgram.music.SoundGramTTS.speak(v, LocaleController.getString(willPlay ? R.string.AccActionPlay : R.string.AccActionPause));
            if (willPlay)
                MediaController.getInstance().playMessage(MediaController.getInstance().getPlayingMessageObject());
            else
                MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
        });
        mediaButtonsContainer.addView(playButton, LayoutHelper.createFrame(76, 76, Gravity.CENTER, 0, 0, 0, 0));

        buttons[2] = prevButton = new RLottieImageView(context) {
            float startX;
            float startY;
            int pressedCount = 0;
            long lastTime;
            long lastUpdateTime;
            private final Runnable pressedRunnable = new Runnable() {
                @Override
                public void run() {
                    pressedCount++;
                    if (pressedCount == 1) {
                        rewindingState = -1;
                        if (MediaController.getInstance().getPlayingMessageObject() != null)
                            rewindingProgress = MediaController.getInstance().getPlayingMessageObject().audioProgress;
                        lastTime = System.currentTimeMillis();
                        AndroidUtilities.runOnUIThread(this, 2000);
                        AndroidUtilities.runOnUIThread(backSeek);
                    } else if (pressedCount == 2)
                        AndroidUtilities.runOnUIThread(this, 2000);
                }
            };
            private final Runnable backSeek = new Runnable() {
                @Override
                public void run() {
                    long duration = MediaController.getInstance().getDuration();
                    if (duration == 0 || duration == C.TIME_UNSET) {
                        lastTime = System.currentTimeMillis();
                        return;
                    }
                    float currentProgress = rewindingProgress;
                    long t = System.currentTimeMillis();
                    long dt = t - lastTime;
                    lastTime = t;
                    long updateDt = t - lastUpdateTime;
                    if (pressedCount == 1)
                        dt *= 3;
                    else if (pressedCount == 2)
                        dt *= 6;
                    else
                        dt *= 12;
                    long currentTime = (long) (duration * currentProgress - dt);
                    currentProgress = currentTime / (float) duration;
                    if (currentProgress < 0)
                        currentProgress = 0;
                    rewindingProgress = currentProgress;
                    MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                    if (messageObject != null && (messageObject.isMusic() || messageObject.isVoice()))
                        updateProgress(messageObject);
                    if (rewindingState == -1 && pressedCount > 0) {
                        if (updateDt > 200 || rewindingProgress == 0) {
                            lastUpdateTime = t;
                            if (rewindingProgress == 0) {
                                if (MediaController.getInstance().getPlayingMessageObject() != null) {
                                    MediaController.getInstance()
                                            .seekToProgress(MediaController.getInstance().getPlayingMessageObject(), 0);
                                    MediaController.getInstance().pauseByRewind();
                                }
                            } else {
                                if (MediaController.getInstance().getPlayingMessageObject() != null)
                                    MediaController.getInstance().seekToProgress(
                                            MediaController.getInstance().getPlayingMessageObject(), currentProgress);
                            }
                        }
                        if (pressedCount > 0 && rewindingProgress > 0)
                            AndroidUtilities.runOnUIThread(backSeek, 16);
                    }
                }
            };
            long startTime;

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (seekBarView != null && (seekBarView.isDragging() || rewindingState == 1))
                    return false;
                float x = event.getRawX();
                float y = event.getRawY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        animate().scaleX(0.85f).scaleY(0.85f).setDuration(150)
                                .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT).start();
                        startX = x;
                        startY = y;
                        startTime = System.currentTimeMillis();
                        rewindingState = 0;
                        AndroidUtilities.runOnUIThread(pressedRunnable, 300);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && getBackground() != null)
                            getBackground().setHotspot(startX, startY);
                        setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = x - startX;
                        float dy = y - startY;
                        if ((dx * dx + dy * dy) > 100 && rewindingState == 0) {
                            animate().scaleX(1f).scaleY(1f).setDuration(300)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                            AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                            setPressed(false);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        animate().scaleX(1f).scaleY(1f).setDuration(300)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                        AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                        AndroidUtilities.cancelRunOnUIThread(backSeek);
                        if (rewindingState == 0 && event.getAction() == MotionEvent.ACTION_UP
                                && (System.currentTimeMillis() - startTime < 300)) {
                            soundgram.music.SoundGramTTS.speak(prevButton, LocaleController.getString(R.string.AccDescrPrevious));
                            MediaController.getInstance().playPreviousMessage();
                            prevButton.setProgress(0f);
                            prevButton.playAnimation();
                        }
                        if (pressedCount > 0) {
                            lastUpdateTime = 0;
                            backSeek.run();
                            MediaController.getInstance().resumeByRewind();
                        }
                        rewindingProgress = -1;
                        setPressed(false);
                        rewindingState = 0;
                        pressedCount = 0;
                        break;
                }
                return true;
            }
        };
        prevButton.setScaleType(ImageView.ScaleType.CENTER);
        prevButton.setAnimation(R.raw.player_prev, 24, 24);
        int playerBtnColor = getThemedColor(Theme.key_player_button);
        prevButton.setLayerColor("Triangle 3.**", playerBtnColor);
        prevButton.setLayerColor("Triangle 4.**", playerBtnColor);
        prevButton.setLayerColor("Rectangle 4.**", playerBtnColor);
        prevButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(16)));
        prevButton.setContentDescription(LocaleController.getString(R.string.AccDescrPrevious));
        prevButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mediaButtonsContainer.addView(prevButton, LayoutHelper.createFrame(44, 44, Gravity.CENTER, -78, 0, 0, 0));

        buttons[1] = queueButton = new RLottieImageView(context);
        queueButton.setScaleType(ImageView.ScaleType.CENTER);
        queueButton.setImageResource(R.drawable.menu_intro);
        queueButton.setColorFilter(new PorterDuffColorFilter(playerBtnColor, PorterDuff.Mode.SRC_IN));
        queueButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(12)));
        queueButton.setContentDescription(LocaleController.getString(R.string.AccDescrOpenMenu2));
        queueButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        queueButton.setOnClickListener(v -> {
            soundgram.music.SoundGramTTS.speak(v, queueButton.getContentDescription());
            showQueueSheet();
        });

        buttons[4] = nextButton = new RLottieImageView(context) {
            float startX;
            float startY;
            boolean pressed;
            private final Runnable pressedRunnable = new Runnable() {
                @Override
                public void run() {
                    if (MediaController.getInstance().getPlayingMessageObject() == null)
                        return;
                    rewindingForwardPressedCount++;
                    if (rewindingForwardPressedCount == 1) {
                        pressed = true;
                        rewindingState = 1;
                        if (MediaController.getInstance().isMessagePaused())
                            startForwardRewindingSeek();
                        else if (rewindingState == 1) {
                            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
                            lastUpdateRewindingPlayerTime = 0;
                        }
                        MediaController.getInstance().setPlaybackSpeed(true, 4);
                        AndroidUtilities.runOnUIThread(this, 2000);
                    } else if (rewindingForwardPressedCount == 2) {
                        MediaController.getInstance().setPlaybackSpeed(true, 7);
                        AndroidUtilities.runOnUIThread(this, 2000);
                    } else
                        MediaController.getInstance().setPlaybackSpeed(true, 13);
                }
            };

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (seekBarView != null && (seekBarView.isDragging() || rewindingState == -1))
                    return false;
                float x = event.getRawX();
                float y = event.getRawY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        animate().scaleX(0.85f).scaleY(0.85f).setDuration(150)
                                .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT).start();
                        pressed = false;
                        startX = x;
                        startY = y;
                        AndroidUtilities.runOnUIThread(pressedRunnable, 300);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && getBackground() != null)
                            getBackground().setHotspot(startX, startY);
                        setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = x - startX;
                        float dy = y - startY;
                        if ((dx * dx + dy * dy) > 100 && !pressed) {
                            animate().scaleX(1f).scaleY(1f).setDuration(300)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                            AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                            setPressed(false);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        animate().scaleX(1f).scaleY(1f).setDuration(300)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                        if (!pressed && event.getAction() == MotionEvent.ACTION_UP && isPressed()) {
                            soundgram.music.SoundGramTTS.speak(nextButton, LocaleController.getString(R.string.AccDescrNext));
                            MediaController.getInstance().playNextMessage();
                            nextButton.setProgress(0f);
                            nextButton.playAnimation();
                        }
                        AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                        if (rewindingForwardPressedCount > 0) {
                            MediaController.getInstance().setPlaybackSpeed(true, 1f);
                            if (MediaController.getInstance().isMessagePaused()) {
                                lastUpdateRewindingPlayerTime = 0;
                                forwardSeek.run();
                            }
                        }
                        rewindingState = 0;
                        setPressed(false);
                        rewindingForwardPressedCount = 0;
                        rewindingProgress = -1;
                        break;
                }
                return true;
            }
        };
        nextButton.setScaleType(ImageView.ScaleType.CENTER);
        nextButton.setAnimation(R.raw.player_prev, 24, 24);
        nextButton.setLayerColor("Triangle 3.**", playerBtnColor);
        nextButton.setLayerColor("Triangle 4.**", playerBtnColor);
        nextButton.setLayerColor("Rectangle 4.**", playerBtnColor);
        nextButton.setRotation(180f);
        nextButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(16)));
        nextButton.setContentDescription(LocaleController.getString(R.string.AccDescrNext));
        nextButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mediaButtonsContainer.addView(nextButton, LayoutHelper.createFrame(44, 44, Gravity.CENTER, 78, 0, 0, 0));

        // Repeat button — крайняя левая кнопка в ряду управления (по макету)
        buttons[0] = repeatButton = new ActionBarMenuItem(context, null, 0, 0, false, resourcesProvider);
        repeatButton.setLongClickEnabled(false);
        repeatButton.setShowSubmenuByMove(false);
        repeatButton.setAdditionalYOffset(-dp(166 + 48));
        repeatButton.setIcon(R.drawable.player_new_repeatall);
        repeatButton.setIconColor(getThemedColor(Theme.key_player_button));
        GradientDrawable repeatBg = new GradientDrawable();
        repeatBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        repeatBg.setCornerRadius(AndroidUtilities.dp(14));
        repeatBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        repeatButton.setBackground(repeatBg);
        repeatButton.setOnClickListener(v -> {
            updateSubMenu();
            repeatButton.toggleSubMenu();
        });
        playOnceItem = repeatButton.addSubItem(5, R.drawable.photo_timer,
                LocaleController.getString(R.string.PlayOnce));
        repeatSongItem = repeatButton.addSubItem(3, R.drawable.player_new_repeatone,
                LocaleController.getString(R.string.RepeatSong));
        repeatListItem = repeatButton.addSubItem(4, R.drawable.player_new_repeatall,
                LocaleController.getString(R.string.RepeatList));
        shuffleListItem = repeatButton.addSubItem(2, R.drawable.player_new_shuffle,
                LocaleController.getString(R.string.ShuffleList));
        reverseOrderItem = repeatButton.addSubItem(1, R.drawable.player_new_order,
                LocaleController.getString(R.string.ReverseOrder));
        repeatButton.setShowedFromBottom(true);
        repeatButton.setDelegate(id -> {
            if (id == 1 || id == 2) {
                boolean oldReversed = SharedConfig.playOrderReversed;
                if (SharedConfig.playOrderReversed && id == 1 || SharedConfig.shuffleMusic && id == 2)
                    MediaController.getInstance().setPlaybackOrderType(0);
                else
                    MediaController.getInstance().setPlaybackOrderType(id);
                if (listAdapter != null)
                    listAdapter.notifyDataSetChanged();
                if (oldReversed != SharedConfig.playOrderReversed) {
                    if (listView != null)
                        listView.stopScroll();
                    scrollToCurrentSong(false);
                }
            } else {
                if (id == 4) {
                    if (SharedConfig.repeatMode == 1)
                        SharedConfig.setRepeatMode(0);
                    else
                        SharedConfig.setRepeatMode(1);
                } else if (id == 5) {
                    if (SharedConfig.repeatMode == PLAY_ONCE)
                        SharedConfig.setRepeatMode(0);
                    else
                        SharedConfig.setRepeatMode(PLAY_ONCE);
                } else {
                    if (SharedConfig.repeatMode == 2)
                        SharedConfig.setRepeatMode(0);
                    else
                        SharedConfig.setRepeatMode(2);
                }
            }
            updateRepeatButton();
        });

        // EQ/Sound button — крайняя правая кнопка в ряду управления (по макету)
        eqBtn = new ImageView(context);
        eqBtn.setImageResource(R.drawable.msg_customize);
        eqBtn.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        eqBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable eqBg = new GradientDrawable();
        eqBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        eqBg.setCornerRadius(AndroidUtilities.dp(14));
        eqBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        eqBtn.setBackground(eqBg);
        eqBtn.setContentDescription(LocaleController.getString(R.string.SoundGramEqualizer));
        eqBtn.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        eqBtn.setOnClickListener(v -> {
            soundgram.music.SoundGramTTS.speak(v, LocaleController.getString(R.string.SoundGramEqualizer));
            if (parentActivity != null)
                new SoundGramEQAlert(parentActivity).show();
        });

        // Добавляем все 5 кнопок в единый ряд mediaButtonsContainer:
        // [повтор: -140] [назад: -78] [Play 76x76: 0] [вперёд: +78] [звук: +140]
        mediaButtonsContainer.addView(repeatButton, LayoutHelper.createFrame(44, 44, Gravity.CENTER, -140, 0, 0, 0));
        mediaButtonsContainer.addView(eqBtn, LayoutHelper.createFrame(44, 44, Gravity.CENTER, 140, 0, 0, 0));

        // ── "дальше" bar — фон #1B1B1B, радиус 14 ──
        FrameLayout nextTrackBar = new FrameLayout(context);
        GradientDrawable nextTrackBg = new GradientDrawable();
        nextTrackBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        nextTrackBg.setCornerRadius(AndroidUtilities.dp(14));
        nextTrackBg.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        nextTrackBar.setBackground(nextTrackBg);
        nextTrackBar.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);

        LinearLayout nextTrackContent = new LinearLayout(context);
        nextTrackContent.setOrientation(LinearLayout.HORIZONTAL);
        nextTrackContent.setGravity(Gravity.CENTER_VERTICAL);

        android.graphics.Typeface monoNextBar = AndroidUtilities.getTypeface("fonts/rmono.ttf");
        if (monoNextBar == null) monoNextBar = android.graphics.Typeface.MONOSPACE;

        TextView nextLabelView = new TextView(context);
        nextLabelView.setText("дальше");
        nextLabelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        nextLabelView.setTypeface(monoNextBar);
        nextLabelView.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));
        nextTrackContent.addView(nextLabelView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        nextTrackTitleView = new TextView(context);
        nextTrackTitleView.setText("—");
        nextTrackTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        nextTrackTitleView.setTypeface(AndroidUtilities.bold());
        nextTrackTitleView.setTextColor(getThemedColor(Theme.key_player_actionBarTitle));
        nextTrackTitleView.setSingleLine(true);
        nextTrackTitleView.setEllipsize(TextUtils.TruncateAt.END);
        nextTrackContent.addView(nextTrackTitleView,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

        nextTrackDurationView = new TextView(context);
        nextTrackDurationView.setText("");
        nextTrackDurationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        nextTrackDurationView.setTypeface(monoNextBar);
        nextTrackDurationView.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));
        nextTrackContent.addView(nextTrackDurationView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        nextTrackBar.addView(nextTrackContent,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.CENTER_VERTICAL));

        // Тап переключает на следующий трек, свайп вверх открывает очередь
        nextTrackBar.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        nextTrackBar.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startY = event.getY();
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (startY - event.getY() > AndroidUtilities.dp(20)) {
                        showQueueSheet();
                        return true;
                    }
                }
                return false;
            }
        });

        // mediaButtonsContainer прямо над nextTrackBar
        playerUiContainer.addView(mediaButtonsContainer,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                        18, 0, 18, mediaButtonsBottomMargin));

        // "дальше" bar в самом низу
        playerUiContainer.addView(nextTrackBar,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                        18, 0, 18, nextTrackBarMargin));

        // Инициализируем остальные поля для безопасности
        queueButton = new RLottieImageView(context);
        lyricsBtnBottom = new ImageView(context);

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        containerView.addView(emptyView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.setOnTouchListener((v, event) -> true);

        emptyImageView = new ImageView(context);
        emptyImageView.setImageResource(R.drawable.music_empty);
        emptyImageView.setColorFilter(
                new PorterDuffColorFilter(getThemedColor(Theme.key_dialogEmptyImage), PorterDuff.Mode.MULTIPLY));
        emptyView.addView(emptyImageView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        emptyTitleTextView = new TextView(context);
        emptyTitleTextView.setTextColor(getThemedColor(Theme.key_dialogEmptyText));
        emptyTitleTextView.setGravity(Gravity.CENTER);
        emptyTitleTextView.setText(LocaleController.getString(R.string.NoAudioFound));
        emptyTitleTextView.setTypeface(AndroidUtilities.bold());
        emptyTitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        emptyTitleTextView.setPadding(dp(40), 0, dp(40), 0);
        emptyView.addView(emptyTitleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 11, 0, 0));

        emptySubtitleTextView = new TextView(context);
        emptySubtitleTextView.setTextColor(getThemedColor(Theme.key_dialogEmptyText));
        emptySubtitleTextView.setGravity(Gravity.CENTER);
        emptySubtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptySubtitleTextView.setPadding(dp(40), 0, dp(40), 0);
        emptyView.addView(emptySubtitleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 6, 0, 0));

        listView = new RecyclerListView(context) {
            boolean ignoreLayout;

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (searchOpenPosition != -1 && actionBar != null && !actionBar.isSearchFieldVisible()) {
                    ignoreLayout = true;
                    if (layoutManager != null)
                        layoutManager.scrollToPositionWithOffset(searchOpenPosition,
                                searchOpenOffset - listView.getPaddingTop());
                    super.onLayout(false, l, t, r, b);
                    ignoreLayout = false;
                    searchOpenPosition = -1;
                } else if (scrollToSong) {
                    scrollToSong = false;
                    ignoreLayout = true;
                    if (scrollToCurrentSong(true))
                        super.onLayout(false, l, t, r, b);
                    ignoreLayout = false;
                }
            }

            @Override
            protected boolean allowSelectChildAtPosition(float x, float y) {
                return playerLayout != null && y > playerLayout.getTranslationY() + playerLayout.getMeasuredHeight();
            }

            @Override
            public void requestLayout() {
                if (!ignoreLayout)
                    super.requestLayout();
            }
        };
        listView.setClipToPadding(false);
        listView.setLayoutManager(
                layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        listView.setHorizontalScrollBarEnabled(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setFastScrollVisible(false);
        containerView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP | Gravity.LEFT));
        listView.setVisibility(View.GONE); // Список треков скрыт — плеер на весь экран
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof AudioPlayerCell)
                ((AudioPlayerCell) view).didPressedButton();
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (view instanceof AudioPlayerCell && !isMyList()) {
                showOptions((AudioPlayerCell) view, ((AudioPlayerCell) view).getMessageObject());
                return true;
            }
            return false;
        });

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
                    AndroidUtilities.hideKeyboard(getCurrentFocus());
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateLayout(); // Родная логика Telegram идеально позиционирует WRAP_CONTENT плеер
                updateEmptyViewPosition();

                if (!searchWas) {
                    int firstVisibleItem = layoutManager != null ? layoutManager.findFirstVisibleItemPosition() : 0;
                    if (padWithItem)
                        firstVisibleItem = Math.max(0, firstVisibleItem - 1);
                    int visibleItemCount = firstVisibleItem == RecyclerView.NO_POSITION ? 0
                            : Math.abs((layoutManager != null ? layoutManager.findLastVisibleItemPosition() : 0)
                                    - firstVisibleItem) + 1;
                    int totalItemCount = recyclerView.getAdapter() != null ? recyclerView.getAdapter().getItemCount()
                            : 0;
                    if (SharedConfig.playOrderReversed) {
                        if (firstVisibleItem < 10)
                            MediaController.getInstance().loadMoreMusic();
                    } else {
                        if (firstVisibleItem + visibleItemCount > totalItemCount - 10)
                            MediaController.getInstance().loadMoreMusic();
                    }
                }
            }
        });

        unsaveFromProfileTextView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        unsaveFromProfileTextView.setVisibility(View.GONE);
        playerLayout.addView(unsaveFromProfileTextView, LayoutHelper.createFrame(0, 0));

        saveToProfileButton = new ButtonWithCounterView(context, resourcesProvider);
        saveToProfileButton.setVisibility(View.GONE);
        playerLayout.addView(saveToProfileButton, LayoutHelper.createFrame(0, 0));

        savedMusicList = MediaController.getInstance().currentSavedMusicList;

        padWithItem = false;

        playlist = MediaController.getInstance().getPlaylist();
        if (listAdapter != null) {
            listAdapter.setup();
            listAdapter.notifyDataSetChanged();
        }

        actionBar.setTitle(LocaleController.getString(R.string.AttachMusic));
        if (savedMusicList != null) {
            if (savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId())
                actionBar.setTitle(getString(R.string.ProfilePlaylistTitleMine));
            else
                actionBar.setTitle(formatString(R.string.ProfilePlaylistTitle,
                        DialogObject.getShortName(savedMusicList.dialogId)));
        } else if (messageObject != null && !MediaController.getInstance().currentPlaylistIsGlobalSearch()) {
            long did = messageObject.getDialogId();
            if (DialogObject.isEncryptedDialog(did)) {
                TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount)
                        .getEncryptedChat(DialogObject.getEncryptedChatId(did));
                if (encryptedChat != null) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(encryptedChat.user_id);
                    if (user != null)
                        actionBar.setTitle(ContactsController.formatName(user.first_name, user.last_name));
                }
            } else if (did == UserConfig.getInstance(currentAccount).getClientUserId()) {
                if (messageObject.getSavedDialogId() == UserObject.ANONYMOUS)
                    actionBar.setTitle(LocaleController.getString(R.string.AnonymousForward));
                else
                    actionBar.setTitle(LocaleController.getString(R.string.SavedMessages));
            } else if (DialogObject.isUserDialog(did)) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
                if (user != null)
                    actionBar.setTitle(ContactsController.formatName(user.first_name, user.last_name));
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                if (chat != null)
                    actionBar.setTitle(chat.title);
            }
        }

        if (isMyList()) {
            itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
                @Override
                public int getMovementFlags(@NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder) {
                    if (viewHolder.getItemViewType() != 0)
                        return 0;
                    return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
                }

                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                        @NonNull RecyclerView.ViewHolder target) {
                    int fromPosition = viewHolder.getAdapterPosition();
                    int toPosition = target.getAdapterPosition();
                    if (padWithItem) {
                        if (fromPosition <= 0 || toPosition <= 0)
                            return false;
                        savedMusicList.move(fromPosition - 1, toPosition - 1);
                    } else
                        savedMusicList.move(fromPosition, toPosition);
                    if (playlist != null) {
                        playlist.clear();
                        playlist.addAll(savedMusicList.list);
                    }
                    if (listAdapter != null)
                        listAdapter.notifyItemMoved(fromPosition, toPosition);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                    if (viewHolder != null && listView != null)
                        listView.hideSelector(false);
                    if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                        if (listView != null)
                            listView.cancelClickRunnables(false);
                        if (viewHolder != null)
                            viewHolder.itemView.setPressed(true);
                    }
                    super.onSelectedChanged(viewHolder, actionState);
                }

                @Override
                public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    viewHolder.itemView.setPressed(false);
                }
            });
            if (listView != null)
                itemTouchHelper.attachToRecyclerView(listView);
        }

        View navBarFiller = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                // Берём цвет фона самого playerLayout
                if (playerLayout != null
                        && playerLayout.getBackground() instanceof android.graphics.drawable.ColorDrawable) {
                    canvas.drawColor(
                            ((android.graphics.drawable.ColorDrawable) playerLayout.getBackground()).getColor());
                } else {
                    canvas.drawColor(getThemedColor(Theme.key_dialogBackground));
                }
            }
        };
        int bottomMargin = 0;
        containerView.addView(playerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 0, 0, bottomMargin));
        actionBarShadow.setVisibility(View.GONE); // скрываем — список треков убран
        containerView.addView(actionBarShadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3));
        actionBar.setVisibility(View.GONE); // скрываем — список треков убран
        containerView.addView(actionBar);
        blurredView = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (blurredView != null && blurredView.getTag() != null)
                    showAlbumCover(false, true);
                return true;
            }
        };
        blurredView.setAlpha(0.0f);
        blurredView.setVisibility(View.INVISIBLE);
        getContainer().addView(blurredView);

        updateTitle(false);
        try {
            updateRepeatButtonBoth();
        } catch (Exception ignored) {
        }
        try {
            updateEmptyView();
        } catch (Exception ignored) {
        }
    }

    @Override
    public int getContainerViewHeight() {
        if (containerView == null)
            return 0;
        return containerView.getMeasuredHeight();
    }

    private void startForwardRewindingSeek() {
        if (rewindingState == 1) {
            lastRewindingTime = System.currentTimeMillis();
            rewindingProgress = MediaController.getInstance().getPlayingMessageObject().audioProgress;
            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
            AndroidUtilities.runOnUIThread(forwardSeek);
        }
    }

    private void updateEmptyViewPosition() {
        if (emptyView.getVisibility() != View.VISIBLE) {
            return;
        }
        int h = playerLayout.getVisibility() == View.VISIBLE ? dp(150) : -dp(30);
        emptyView.setTranslationY((emptyView.getMeasuredHeight() - containerView.getMeasuredHeight() - h) / 2);
    }

    private void updateEmptyView() {
        emptyView.setVisibility(searching && listAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        updateEmptyViewPosition();
    }

    private boolean scrollToCurrentSong(boolean search) {
        if (layoutManager == null)
            return false;
        // ИСПРАВЛЕНИЕ: Принудительный скролл на 0 (самый верх, чтобы был виден плеер на
        // весь экран)
        layoutManager.scrollToPositionWithOffset(0, 0);
        return true;
    }

    @Override
    public boolean onCustomMeasure(View view, int width, int height) {
        boolean isPortrait = width < height;
        if (view == blurredView) {
            blurredView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCustomLayout(View view, int left, int top, int right, int bottom) {
        int width = (right - left);
        int height = (bottom - top);
        boolean isPortrait = width < height;
        if (view == blurredView) {
            blurredView.layout(left, 0, left + width, height);
            return true;
        }
        return false;
    }

    private void setMenuItemChecked(ActionBarMenuSubItem item, boolean checked) {
        if (checked) {
            item.setTextColor(getThemedColor(Theme.key_player_buttonActive));
            item.setIconColor(getThemedColor(Theme.key_player_buttonActive));
        } else {
            item.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
            item.setIconColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
        }
    }

    private HintView speedHintView;
    private long lastPlaybackClick;

    private void checkSpeedHint() {
        final long now = System.currentTimeMillis();
        if (now - lastPlaybackClick > 300) {
            int hintValue = MessagesController.getGlobalNotificationsSettings().getInt("speedhint", 0);
            hintValue++;
            if (hintValue > 2) {
                hintValue = -10;
            }
            MessagesController.getGlobalNotificationsSettings().edit().putInt("speedhint", hintValue).apply();
            if (hintValue >= 0) {
                showSpeedHint();
            }
        }
        lastPlaybackClick = now;
    }

    private void showSpeedHint() {
        if (containerView != null) {
            speedHintView = new HintView(getContext(), 5, false) {
                @Override
                public void setVisibility(int visibility) {
                    super.setVisibility(visibility);
                    if (visibility != View.VISIBLE) {
                        try {
                            ((ViewGroup) getParent()).removeView(this);
                        } catch (Exception e) {
                        }
                    }
                }
            };
            speedHintView.setExtraTranslationY(dp(6));
            speedHintView.setText(LocaleController.getString("SpeedHint"));
            playerLayout.addView(speedHintView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 6, 0));
            speedHintView.showForView(playbackSpeedButton, true);
        }
    }

    private void updateSubMenu() {
        setMenuItemChecked(shuffleListItem, SharedConfig.shuffleMusic);
        setMenuItemChecked(reverseOrderItem, SharedConfig.playOrderReversed);
        setMenuItemChecked(repeatListItem, SharedConfig.repeatMode == 1);
        setMenuItemChecked(repeatSongItem, SharedConfig.repeatMode == 2);
        setMenuItemChecked(playOnceItem, SharedConfig.repeatMode == PLAY_ONCE);
        // Синхронизируем иконку кнопки режимов в нижнем правом углу
        if (modeMenuButton != null) {
            int iconRes;
            int mode = SharedConfig.repeatMode;
            if (mode == 2)
                iconRes = R.drawable.player_new_repeatone;
            else if (mode == PLAY_ONCE)
                iconRes = R.drawable.photo_timer;
            else if (SharedConfig.shuffleMusic)
                iconRes = mode == 1 ? R.drawable.player_new_repeat_shuffle : R.drawable.player_new_shuffle;
            else if (SharedConfig.playOrderReversed)
                iconRes = mode == 1 ? R.drawable.player_new_repeat_reverse : R.drawable.player_new_order;
            else
                iconRes = R.drawable.player_new_repeatall;
            modeMenuButton.setIcon(iconRes);
            boolean isActive = mode != 0 || SharedConfig.shuffleMusic || SharedConfig.playOrderReversed;
            modeMenuButton.setIconColor(isActive ? getThemedColor(Theme.key_player_buttonActive) : getThemedColor(Theme.key_player_button));
        }
    }

    private boolean equals(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    private void updatePlaybackButton(boolean animated) {
        if (playbackSpeedButton == null) {
            return;
        }
        float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(true);
        if (speedIcon != null) {
            speedIcon.setValue(currentPlaybackSpeed, animated);
        }
        if (speedSlider != null) {
            speedSlider.setSpeed(currentPlaybackSpeed, animated);
        }
        updateColors();

        boolean isFinal = !slidingSpeed;
        slidingSpeed = false;

        for (int a = 0; a < speedItems.length; a++) {
            if (isFinal && equals(currentPlaybackSpeed, speeds[a])) {
                speedItems[a].setColors(getThemedColor(Theme.key_featuredStickers_addButtonPressed),
                        getThemedColor(Theme.key_featuredStickers_addButtonPressed));
            } else {
                speedItems[a].setColors(getThemedColor(Theme.key_actionBarDefaultSubmenuItem),
                        getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
            }
        }
    }

    public void updateColors() {
        if (playbackSpeedButton != null) {
            float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(true);
            final int color = getThemedColor(
                    !equals(currentPlaybackSpeed, 1.0f) ? Theme.key_featuredStickers_addButtonPressed
                            : Theme.key_inappPlayerClose);
            if (speedIcon != null) {
                speedIcon.setColor(color);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                playbackSpeedButton.setBackground(Theme.createSelectorDrawable(color & 0x19ffffff, 1, dp(14)));
            }
        }
        if (castItem != null) {
            castItem.setEnabledByColor(castItemButton != null && castItemButton.isConnected(),
                    getThemedColor(Theme.key_actionBarDefaultSubmenuItem),
                    getThemedColor(Theme.key_actionBarDefaultSubmenuItemIcon),
                    getThemedColor(Theme.key_featuredStickers_addButton));
            castItem.setSelectorColor(castItemButton != null && castItemButton.isConnected()
                    ? Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .10f)
                    : getThemedColor(Theme.key_listSelector));
        }
        if (forwardButton != null) {
            forwardButton.setTextColor(getThemedColor(Theme.key_player_button));
        }
        if (backwardButton != null) {
            backwardButton.setTextColor(getThemedColor(Theme.key_player_button));
        }
        final int titleColor = getThemedColor(Theme.key_player_actionBarTitle);
        final int subtitleColor = getThemedColor(Theme.key_player_actionBarSubtitle);
        final int timeColor = getThemedColor(Theme.key_player_time);
        final int accentColor = getThemedColor(Theme.key_featuredStickers_addButton);
        final int playerBg = getThemedColor(Theme.key_dialogBackground);
        final int onAccentColor = getThemedColor(Theme.key_featuredStickers_buttonText);

        int surfaceColor = getThemedColor(Theme.key_windowBackgroundGray);
        if (surfaceColor == 0 || soundgram.ui.SoundGramDesignTokens.isTooClose(surfaceColor, playerBg)) {
            surfaceColor = getThemedColor(Theme.key_chat_inBubble);
        }
        if (surfaceColor == 0 || soundgram.ui.SoundGramDesignTokens.isTooClose(surfaceColor, playerBg)) {
            surfaceColor = getThemedColor(Theme.key_actionBarDefaultSubmenuBackground);
        }
        surfaceColor = soundgram.ui.SoundGramDesignTokens.ensureContrast(surfaceColor, playerBg, 0.08f);

        int dividerColor = getThemedColor(Theme.key_divider);
        if (dividerColor == 0 || soundgram.ui.SoundGramDesignTokens.isTooClose(dividerColor, playerBg)) {
            dividerColor = soundgram.ui.SoundGramDesignTokens.ensureContrast(playerBg, playerBg, 0.12f);
        }

        int progressBg = soundgram.ui.SoundGramDesignTokens.getPlaylistCardColor();

        int progressColor = getThemedColor(Theme.key_player_progress);
        if (progressColor == 0 || soundgram.ui.SoundGramDesignTokens.isTooClose(progressColor, playerBg)) {
            progressColor = accentColor != 0 ? accentColor : soundgram.ui.SoundGramDesignTokens.getAccentColor();
        }

        final int iconColor = getThemedColor(Theme.key_player_button);

        if (seekBarView != null) {
            seekBarView.setWavy(true);
            seekBarView.setColors(progressBg, progressColor);
        }
        if (waveProgressView != null) {
            waveProgressView.updateColors();
        }
        if (timeTextView != null) {
            timeTextView.setTextColor(timeColor);
        }
        if (durationTextView != null) {
            durationTextView.setTextColor(timeColor);
        }
        if (titleTextView != null) {
            titleTextView.setTextColor(titleColor);
        }
        if (authorTextView != null) {
            authorTextView.setTextColor(timeColor);
        }
        if (borderView != null) {
            borderView.setVisibility(View.VISIBLE);
            GradientDrawable borderBg = new GradientDrawable();
            borderBg.setColor(Color.TRANSPARENT);
            borderBg.setCornerRadii(new float[]{
                    AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                    AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                    AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                    AndroidUtilities.dp(56), AndroidUtilities.dp(56)
            });
            borderBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            borderView.setBackground(borderBg);
        }
        if (playButton != null) {
            playButton.setColorFilter(onAccentColor, PorterDuff.Mode.SRC_IN);
            playButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), accentColor, Theme.blendOver(accentColor, 0x25000000)));
        }
        if (prevButton != null) {
            prevButton.setLayerColor("Triangle 3.**", iconColor);
            prevButton.setLayerColor("Triangle 4.**", iconColor);
            prevButton.setLayerColor("Rectangle 4.**", iconColor);
            prevButton.setBackground(Theme.createSelectorDrawable(androidx.core.graphics.ColorUtils.setAlphaComponent(iconColor, 34), 1, dp(16)));
        }
        if (nextButton != null) {
            nextButton.setLayerColor("Triangle 3.**", iconColor);
            nextButton.setLayerColor("Triangle 4.**", iconColor);
            nextButton.setLayerColor("Rectangle 4.**", iconColor);
            nextButton.setBackground(Theme.createSelectorDrawable(androidx.core.graphics.ColorUtils.setAlphaComponent(iconColor, 34), 1, dp(16)));
        }
        if (repeatButton != null) {
            repeatButton.setIconColor(iconColor);
            GradientDrawable repeatBg = new GradientDrawable();
            repeatBg.setColor(surfaceColor);
            repeatBg.setCornerRadius(AndroidUtilities.dp(14));
            repeatBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            repeatButton.setBackground(repeatBg);
        }
        if (eqBtn != null) {
            eqBtn.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            GradientDrawable eqBg = new GradientDrawable();
            eqBg.setColor(surfaceColor);
            eqBg.setCornerRadius(AndroidUtilities.dp(14));
            eqBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            eqBtn.setBackground(eqBg);
        }
        if (favButton != null) {
            favButton.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            GradientDrawable favBg = new GradientDrawable();
            favBg.setColor(surfaceColor);
            favBg.setCornerRadius(AndroidUtilities.dp(14));
            favBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            favButton.setBackground(favBg);
        }
        if (lyricsBtnBottom != null) {
            int btnBg = androidx.core.graphics.ColorUtils.setAlphaComponent(titleColor, 35);
            int btnPressedBg = androidx.core.graphics.ColorUtils.setAlphaComponent(titleColor, 55);
            lyricsBtnBottom.setColorFilter(titleColor, PorterDuff.Mode.SRC_IN);
            lyricsBtnBottom.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), btnBg, btnPressedBg));
        }
        if (topOptionsBtn != null) {
            topOptionsBtn.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            GradientDrawable topBackBg = new GradientDrawable();
            topBackBg.setColor(surfaceColor);
            topBackBg.setCornerRadius(AndroidUtilities.dp(14));
            topBackBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            topOptionsBtn.setBackground(topBackBg);
        }
        if (moreBtn != null) {
            moreBtn.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            GradientDrawable moreBackBg = new GradientDrawable();
            moreBackBg.setColor(surfaceColor);
            moreBackBg.setCornerRadius(AndroidUtilities.dp(14));
            moreBackBg.setStroke(AndroidUtilities.dp(1), dividerColor);
            moreBtn.setBackground(moreBackBg);
        }
        if (sourceChipTextView != null) {
            sourceChipTextView.setVisibility(View.GONE);
        }
        updatePlayerBackground();
    }

    public void updatePlayerBackground() {
        if (playerLayout == null) return;
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setCornerRadii(new float[] {
                AndroidUtilities.dp(28), AndroidUtilities.dp(28),
                AndroidUtilities.dp(28), AndroidUtilities.dp(28),
                0, 0, 0, 0
        });
        base.setColor(getThemedColor(Theme.key_dialogBackground));
        playerLayout.setBackground(base);
    }

    private boolean blockAudiusFileAction(MessageObject message) {
        if (message == null || message.messageOwner == null
                || !soundgram.music.AudiusClient.isStreamUrl(message.messageOwner.attachPath)) return false;
        // Audius M1 is stream-only. Avoid downloads and same-name Hitmos substitutions.
        Toast.makeText(getContext(), getString(R.string.sg_download_error), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void onSubItemClick(int id) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null || parentActivity == null)
            return;

        if (blockAudiusFileAction(messageObject)) return;

        if (id == 1) { // Поделиться -> Сразу выбор чатов Telegram
            ArrayList<MessageObject> arrayList = new ArrayList<>();
            arrayList.add(messageObject);
            ShareAlert shareAlert = new ShareAlert(getContext(), null, arrayList, null, null, false, null, null, false, true, false, null, resourcesProvider);
            final int account = currentAccount;
            final MessageObject mo = messageObject;
            shareAlert.setOnShareDoneListener((dids, topics, comment) -> {
                if (dids == null || dids.size() == 0) {
                    return false;
                }
                final ArrayList<Long> targetDids = new ArrayList<>();
                final HashMap<Long, TLRPC.TL_forumTopic> topicMap = new HashMap<>();
                for (int i = 0; i < dids.size(); i++) {
                    long did = dids.keyAt(i);
                    targetDids.add(did);
                    if (topics != null) {
                        TLRPC.TL_forumTopic top = topics.get(dids.get(did));
                        if (top != null) {
                            topicMap.put(did, top);
                        }
                    }
                }
                final String commentStr = comment != null ? comment.toString().trim() : null;

                boolean isSGStream = mo.messageOwner != null
                        && mo.messageOwner.message != null
                        && mo.messageOwner.message.startsWith("SG_STREAM|||");

                if (isSGStream) {
                    String[] sgParts = mo.messageOwner.message.split("\\|\\|\\|");
                    String sgTitle = sgParts.length > 1 ? sgParts[1] : (mo.getMusicTitle() != null ? mo.getMusicTitle() : "Unknown");
                    String sgArtist = sgParts.length > 2 ? sgParts[2] : (mo.getMusicAuthor() != null ? mo.getMusicAuthor() : "Unknown");
                    String sgUrl = sgParts.length > 3 ? sgParts[3] : "";
                    String sgCover = sgParts.length >= 7 ? sgParts[6] : "";

                    File existingFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
                    if (!existingFile.exists() || existingFile.length() < 1024) {
                        if (mo.messageOwner.attachPath != null && !mo.messageOwner.attachPath.startsWith("http")) {
                            File ap = new File(mo.messageOwner.attachPath);
                            if (ap.exists() && ap.length() > 1024) {
                                existingFile = ap;
                            }
                        }
                    }

                    if (existingFile.exists() && existingFile.length() > 1024) {
                        for (Long did : targetDids) {
                            TLRPC.TL_forumTopic topic = topicMap.get(did);
                            boolean isMonoForum = MessagesController.getInstance(account).isMonoForum(did);
                            MessageObject replyTopMsg = topic != null && !isMonoForum ? new MessageObject(account, topic.topicStartMessage, false, false) : null;
                            if (replyTopMsg != null) {
                                replyTopMsg.isTopicMainMessage = true;
                            }
                            SendMessagesHelper.prepareSendingDocument(
                                    AccountInstance.getInstance(account),
                                    existingFile.getAbsolutePath(),
                                    existingFile.getAbsolutePath(),
                                    null,
                                    commentStr,
                                    "audio/mpeg",
                                    did,
                                    replyTopMsg, replyTopMsg, null, null, null,
                                    true, 0, null, null, false
                            );
                        }
                        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.MessageSent), Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_downloading_in_background", R.string.sg_downloading_in_background), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        File destFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
                        boolean ok = destFile.exists() && destFile.length() > 1024;
                        String fCover = sgCover;
                        if (!ok && !TextUtils.isEmpty(sgUrl)) {
                            ok = soundgram.music.MusicWebSearcher.downloadTrackToExactFileSync(sgUrl, destFile);
                        }
                        if (!ok) {
                            soundgram.music.MusicWebSearcher.WebTrack freshTrack = soundgram.music.MusicWebSearcher.resolveHitmosSync(sgTitle, sgArtist);
                            if (freshTrack != null && !TextUtils.isEmpty(freshTrack.downloadUrl)) {
                                ok = soundgram.music.MusicWebSearcher.downloadTrackToExactFileSync(freshTrack.downloadUrl, destFile);
                                if (TextUtils.isEmpty(fCover) && !TextUtils.isEmpty(freshTrack.coverUrl)) {
                                    fCover = freshTrack.coverUrl;
                                }
                            }
                        }
                        final boolean finalOk = ok && destFile.exists() && destFile.length() > 1024;
                        File coverFile = null;
                        if (finalOk && !TextUtils.isEmpty(fCover)) {
                            try {
                                File cacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "audio_covers");
                                if (!cacheDir.exists()) cacheDir.mkdirs();
                                File cFile = new File(cacheDir, Math.abs(destFile.getAbsolutePath().hashCode()) + ".jpg");
                                if (!cFile.exists() || cFile.length() == 0) {
                                    soundgram.music.MusicWebSearcher.downloadTrackToExactFileSync(fCover, cFile);
                                }
                                if (cFile.exists() && cFile.length() > 0) {
                                    coverFile = cFile;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (finalOk) {
                            try {
                                soundgram.music.MusicWebSearcher.embedId3v2Cover(destFile, coverFile, sgTitle, sgArtist);
                            } catch (Exception ignored) {}
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            if (finalOk) {
                                mo.messageOwner.attachPath = destFile.getAbsolutePath();
                                for (Long did : targetDids) {
                                    TLRPC.TL_forumTopic topic = topicMap.get(did);
                                    boolean isMonoForum = MessagesController.getInstance(account).isMonoForum(did);
                                    MessageObject replyTopMsg = topic != null && !isMonoForum ? new MessageObject(account, topic.topicStartMessage, false, false) : null;
                                    if (replyTopMsg != null) {
                                        replyTopMsg.isTopicMainMessage = true;
                                    }
                                    SendMessagesHelper.prepareSendingDocument(
                                            AccountInstance.getInstance(account),
                                            destFile.getAbsolutePath(),
                                            destFile.getAbsolutePath(),
                                            null,
                                            commentStr,
                                            "audio/mpeg",
                                            did,
                                            replyTopMsg, replyTopMsg, null, null, null,
                                            true, 0, null, null, false
                                    );
                                }
                                Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.MessageSent), Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_download_error", R.string.sg_download_error), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                    return true;
                }

                File localFile = null;
                if (mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.attachPath)) {
                    File ap = new File(mo.messageOwner.attachPath);
                    if (ap.exists() && ap.length() > 1024) {
                        localFile = ap;
                    }
                }
                if (localFile == null && mo.messageOwner != null) {
                    File f = FileLoader.getInstance(account).getPathToMessage(mo.messageOwner);
                    if (f != null && f.exists() && f.length() > 1024) {
                        localFile = f;
                    }
                }

                boolean canForwardOnServer = mo.getId() > 0
                        && mo.getDialogId() != 0
                        && (mo.messageOwner == null || !mo.messageOwner.noforwards);

                if (canForwardOnServer) {
                    return false;
                }

                if (localFile != null) {
                    final File fToSend = localFile;
                    for (Long did : targetDids) {
                        TLRPC.TL_forumTopic topic = topicMap.get(did);
                        boolean isMonoForum = MessagesController.getInstance(account).isMonoForum(did);
                        MessageObject replyTopMsg = topic != null && !isMonoForum ? new MessageObject(account, topic.topicStartMessage, false, false) : null;
                        if (replyTopMsg != null) {
                            replyTopMsg.isTopicMainMessage = true;
                        }
                        SendMessagesHelper.prepareSendingDocument(
                                AccountInstance.getInstance(account),
                                fToSend.getAbsolutePath(),
                                fToSend.getAbsolutePath(),
                                null,
                                commentStr,
                                mo.getDocument() != null && !TextUtils.isEmpty(mo.getDocument().mime_type) ? mo.getDocument().mime_type : "audio/mpeg",
                                did,
                                replyTopMsg, replyTopMsg, null, null, null,
                                true, 0, null, null, false
                        );
                    }
                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.MessageSent), Toast.LENGTH_SHORT).show();
                    return true;
                }

                if (mo.getDocument() != null) {
                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_downloading_in_background", R.string.sg_downloading_in_background), Toast.LENGTH_SHORT).show();
                    final String attachFileName = FileLoader.getAttachFileName(mo.getDocument());
                    NotificationCenter.NotificationCenterDelegate observer = new NotificationCenter.NotificationCenterDelegate() {
                        @Override
                        public void didReceivedNotification(int id, int accountNum, Object... args) {
                            if (id == NotificationCenter.fileLoaded) {
                                String name = (String) args[0];
                                if (attachFileName != null && attachFileName.equals(name)) {
                                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
                                    File file = (File) args[1];
                                    if (file == null || !file.exists()) {
                                        file = FileLoader.getInstance(account).getPathToMessage(mo.messageOwner);
                                    }
                                    if (file != null && file.exists()) {
                                        final File fFound = file;
                                        AndroidUtilities.runOnUIThread(() -> {
                                            for (Long did : targetDids) {
                                                TLRPC.TL_forumTopic topic = topicMap.get(did);
                                                boolean isMonoForum = MessagesController.getInstance(account).isMonoForum(did);
                                                MessageObject replyTopMsg = topic != null && !isMonoForum ? new MessageObject(account, topic.topicStartMessage, false, false) : null;
                                                if (replyTopMsg != null) {
                                                    replyTopMsg.isTopicMainMessage = true;
                                                }
                                                SendMessagesHelper.prepareSendingDocument(
                                                        AccountInstance.getInstance(account),
                                                        fFound.getAbsolutePath(),
                                                        fFound.getAbsolutePath(),
                                                        null,
                                                        commentStr,
                                                        mo.getDocument() != null && !TextUtils.isEmpty(mo.getDocument().mime_type) ? mo.getDocument().mime_type : "audio/mpeg",
                                                        did,
                                                        replyTopMsg, replyTopMsg, null, null, null,
                                                        true, 0, null, null, false
                                                );
                                            }
                                            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.MessageSent), Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                }
                            } else if (id == NotificationCenter.fileLoadFailed) {
                                String name = (String) args[0];
                                if (attachFileName != null && attachFileName.equals(name)) {
                                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
                                    AndroidUtilities.runOnUIThread(() -> {
                                        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_download_error", R.string.sg_download_error), Toast.LENGTH_SHORT).show();
                                    });
                                }
                            }
                        }
                    };
                    NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.fileLoaded);
                    NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.fileLoadFailed);
                    FileLoader.getInstance(account).loadFile(mo.getDocument(), mo, 1, 0);
                    return true;
                }

                return false;
            });
            shareAlert.show();
            dismiss();
            return;
        } else if (id == 2) {
            share(messageObject);
            return;
        } else if (id == 5) { // В библиотеку (БЕЗ ДИАЛОГА)
        long targetId = getMusicChannelIdOrZero();
        if (targetId == 0) {
            Toast.makeText(getContext(), getString(R.string.sg_open_music_first), Toast.LENGTH_LONG).show();
            return;
        }
        boolean isSGStream = messageObject.messageOwner != null
                && messageObject.messageOwner.message != null
                && messageObject.messageOwner.message.startsWith("SG_STREAM|||");
        if (isSGStream) {
            String[] sgParts = messageObject.messageOwner.message.split("\\|\\|\\|");
            String sgTitle = sgParts.length > 1 ? sgParts[1] : "Unknown";
            String sgArtist = sgParts.length > 2 ? sgParts[2] : "Unknown";
            String sgUrl = sgParts.length > 3 ? sgParts[3] : "";
            String sgCover = sgParts.length >= 7 ? sgParts[6] : "";

            File existingFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
            if (!existingFile.exists() || existingFile.length() < 1024) {
                if (messageObject.messageOwner.attachPath != null && !messageObject.messageOwner.attachPath.startsWith("http")) {
                    File ap = new File(messageObject.messageOwner.attachPath);
                    if (ap.exists() && ap.length() > 1024) existingFile = ap;
                }
            }

            final long fTargetId2 = targetId;
            if (existingFile.exists() && existingFile.length() > 1024) {
                soundgram.music.ChannelMusicFragment.sendLocalAudioFileToChannel(existingFile, sgTitle, sgArtist, fTargetId2, currentAccount);
                // Оптимистично добавляем в audioMessages для немедленного findInLibrary
                if (!soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).contains(messageObject)) {
                    soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).add(0, messageObject);
                }
                Toast.makeText(getContext(), getString(R.string.sg_saved_to_library), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), LocaleController.getString("sg_downloading_in_background", R.string.sg_downloading_in_background), Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    String fCover = sgCover;
                    File destFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
                    boolean ok = MusicWebSearcher.downloadTrackToExactFileSync(sgUrl, destFile);
                    if (!ok) {
                        MusicWebSearcher.WebTrack freshTrack = MusicWebSearcher.resolveHitmosSync(sgTitle, sgArtist);
                        if (freshTrack != null && !TextUtils.isEmpty(freshTrack.downloadUrl)) {
                            ok = MusicWebSearcher.downloadTrackToExactFileSync(freshTrack.downloadUrl, destFile);
                            if (TextUtils.isEmpty(fCover) && !TextUtils.isEmpty(freshTrack.coverUrl)) {
                                fCover = freshTrack.coverUrl;
                            }
                        }
                    }
                    final boolean finalOk = ok;
                    File coverFile = null;
                    if (finalOk && destFile.exists() && !TextUtils.isEmpty(fCover)) {
                        try {
                            File cacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "audio_covers");
                            if (!cacheDir.exists()) cacheDir.mkdirs();
                            File cFile = new File(cacheDir, Math.abs(destFile.getAbsolutePath().hashCode()) + ".jpg");
                            if (!cFile.exists() || cFile.length() == 0) {
                                MusicWebSearcher.downloadTrackToExactFileSync(fCover, cFile);
                            }
                            if (cFile.exists() && cFile.length() > 0) {
                                coverFile = cFile;
                            }
                        } catch (Exception ignored) {}
                    }
                    final String thumbPath = coverFile != null ? coverFile.getAbsolutePath() : null;

                    AndroidUtilities.runOnUIThread(() -> {
                        if (finalOk && destFile.exists() && destFile.length() > 1024) {
                            soundgram.music.ChannelMusicFragment.sendLocalAudioFileToChannel(destFile, sgTitle, sgArtist, fTargetId2, currentAccount, thumbPath);
                            // Оптимистично добавляем в audioMessages
                            if (!soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).contains(messageObject)) {
                                soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).add(0, messageObject);
                            }
                            if (SmartRadioEngine.isWaveActive == true) {
                                SmartRadioEngine.reportLibraryAdd(sgArtist, sgTitle);
                            }
                            if (favButton != null) {
                                favButton.setImageResource(R.drawable.msg_delete);
                                favButton.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
                            }
                            Toast.makeText(getContext(), getString(R.string.sg_saved_to_library), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), getString(R.string.sg_download_error), Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            }
        } else {
            ArrayList<MessageObject> fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, -targetId, false, false, true, 0, 0);
            // Оптимистично добавляем в audioMessages, чтобы findInLibrary
            // сразу видел трек (реальный ID придёт позже через NotificationCenter)
            if (!soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).contains(messageObject)) {
                soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).add(0, messageObject);
            }
            Toast.makeText(getContext(), getString(R.string.sg_saved_to_library), Toast.LENGTH_SHORT).show();
        }
        // НЕ закрываем плеер; обновляем иконку закладки и видимость пунктов меню
        if (favButton != null) {
            favButton.setImageResource(R.drawable.msg_delete);
            favButton.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        }
        if (topOptionsButton != null) {
            topOptionsButton.setSubItemShown(5, false);
            topOptionsButton.setSubItemShown(203, true);
        }

        // ... код для других id ...

    }else if (id == 100) { // В плейлист
            showPlaylistSelectorFromDialog(messageObject);
        } else if (id == 7) { // В профиль
            boolean isSGStream = messageObject.messageOwner != null
                    && messageObject.messageOwner.message != null
                    && messageObject.messageOwner.message.startsWith("SG_STREAM|||");
            boolean isInLibrary = false;
            for (MessageObject libMsg : soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount)) {
                if (libMsg.getId() == messageObject.getId()
                        || (libMsg.messageOwner != null && messageObject.messageOwner != null
                        && libMsg.messageOwner.message != null
                        && messageObject.messageOwner.message != null
                        && libMsg.messageOwner.message.equals(messageObject.messageOwner.message))) {
                    isInLibrary = true;
                    break;
                }
            }
            if (isSGStream) {
                long targetId = getMusicChannelIdOrZero();
                if (targetId == 0) {
                    Toast.makeText(getContext(), getString(R.string.sg_open_music_first), Toast.LENGTH_LONG).show();
                    dismiss();
                    return;
                }
                String[] sgParts = messageObject.messageOwner.message.split("\\|\\|\\|");
                String sgTitle = sgParts.length > 1 ? sgParts[1] : "Unknown";
                String sgArtist = sgParts.length > 2 ? sgParts[2] : "Unknown";
                String sgUrl = sgParts.length > 3 ? sgParts[3] : "";
                AlertDialog prog = new AlertDialog(getContext(), 3);
                prog.setMessage(getString(R.string.sg_downloading_track));
                prog.show();
                final long fTargetId = targetId;
                new Thread(() -> {
                    File destFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
                    boolean ok = destFile.exists() && destFile.length() > 1024;
                    if (!ok) {
                        ok = MusicWebSearcher.downloadTrackToExactFileSync(sgUrl, destFile);
                    }
                    if (!ok) {
                        MusicWebSearcher.WebTrack freshTrack = MusicWebSearcher.resolveHitmosSync(sgTitle, sgArtist);
                        if (freshTrack != null && !TextUtils.isEmpty(freshTrack.downloadUrl)) {
                            ok = MusicWebSearcher.downloadTrackToExactFileSync(freshTrack.downloadUrl, destFile);
                        }
                    }
                    final boolean finalOk = ok;
                    AndroidUtilities.runOnUIThread(() -> {
                        prog.dismiss();
                        if (finalOk && destFile.exists() && destFile.length() > 1024) {
                            soundgram.music.ChannelMusicFragment.sendLocalAudioFileToChannel(
                                    destFile, sgTitle, sgArtist, fTargetId, currentAccount);
                            Toast.makeText(getContext(), getString(R.string.sg_saved_to_library), Toast.LENGTH_SHORT)
                                    .show();
                            saveLatestLibraryTrackToProfile(fTargetId, sgTitle, sgArtist);
                        } else {
                            Toast.makeText(getContext(), getString(R.string.sg_download_error), Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
                }).start();
                dismiss();
                return;
            }
            if (!isInLibrary) {
                long targetId = getMusicChannelIdOrZero();
                if (targetId == 0) {
                    Toast.makeText(getContext(), getString(R.string.sg_open_music_first), Toast.LENGTH_LONG).show();
                    dismiss();
                    return;
                }
                ArrayList<MessageObject> fmessages = new ArrayList<>();
                fmessages.add(messageObject);
                SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, -targetId, false, false, true, 0,
                        0);
                saveLatestLibraryTrackToProfile(targetId, messageObject.getMusicTitle(),
                        messageObject.getMusicAuthor());
                Toast.makeText(getContext(), getString(R.string.sg_saved_to_library), Toast.LENGTH_SHORT).show();
                dismiss();
                return;
            }
            saveToProfile(messageObject, true, () -> Toast
                            .makeText(getContext(), getString(R.string.sg_track_added_to_profile), Toast.LENGTH_SHORT).show(),
                    false);
            dismiss();
        }else if (id == 205) { // Добавить в профиль (Умная загрузка)
                boolean isSGStream = messageObject.messageOwner != null && messageObject.messageOwner.message != null && messageObject.messageOwner.message.startsWith("SG_STREAM|||");
                if (isSGStream) {
                    long targetId = getMusicChannelIdOrZero();
                    if (targetId == 0) {
                        Toast.makeText(getContext(), getString(R.string.sg_open_music_first), Toast.LENGTH_LONG).show();
                        dismiss();
                        return;
                    }
                    String[] sgParts = messageObject.messageOwner.message.split("\\|\\|\\|");
                    String sgTitle = sgParts.length > 1 ? sgParts[1] : "Unknown";
                    String sgArtist = sgParts.length > 2 ? sgParts[2] : "Unknown";
                    String sgUrl = sgParts.length > 3 ? sgParts[3] : "";
                    final long fTargetId = targetId;

                    Toast.makeText(getContext(), LocaleController.getString("sg_saving_to_profile", R.string.sg_saving_to_profile), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        File destFile = soundgram.music.ChannelMusicFragment.getLocalTrackFile(sgArtist, sgTitle);
                        boolean ok = destFile.exists() && destFile.length() > 1024;
                        if (!ok) ok = MusicWebSearcher.downloadTrackToExactFileSync(sgUrl, destFile);
                        if (!ok) {
                            MusicWebSearcher.WebTrack freshTrack = MusicWebSearcher.resolveHitmosSync(sgTitle, sgArtist);
                            if (freshTrack != null && !TextUtils.isEmpty(freshTrack.downloadUrl)) {
                                ok = MusicWebSearcher.downloadTrackToExactFileSync(freshTrack.downloadUrl, destFile);
                            }
                        }

                        final boolean finalOk = ok;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (finalOk && destFile.exists() && destFile.length() > 1024) {
                                soundgram.music.ChannelMusicFragment.sendLocalAudioFileToChannel(destFile, sgTitle, sgArtist, fTargetId, currentAccount);
                                saveLatestLibraryTrackToProfile(fTargetId, sgTitle, sgArtist);
                            } else {
                                Toast.makeText(getContext(), getString(R.string.sg_download_error), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                } else {
                    saveToProfile(messageObject, true, () -> Toast.makeText(getContext(), getString(R.string.sg_track_added_to_profile), Toast.LENGTH_SHORT).show(), false);
                }
                dismiss();
            } else if (id == 206) { // Удалить из профиля (из меню трёх точек / bottom sheet)
            saveToProfile(messageObject, false, () ->
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                            .createSimpleBulletin(R.raw.ic_delete,
                                    getString(R.string.AudioSaveToMyProfileUnsaved))
                            .show(),
                    false);
            dismiss();
        }
    }

    /** Удаляет трек из библиотеки (канала) — точная копия логики ChannelMusicFragment.deleteTrackFromChannel */
    private void deleteTrackFromLibrary(MessageObject msg) {
        if (msg == null) return;
        long rawId = getMusicChannelIdOrZero();
        if (rawId == 0) {
            Toast.makeText(getContext(), getString(R.string.sg_open_music_first), Toast.LENGTH_LONG).show();
            return;
        }

        // Добавляем трек в blacklist ДО удаления — чтобы при следующем loadMusicFromChannel
        // он не вернулся обратно в audioMessages пока сервер ещё не применил удаление.
        if (msg.getId() > 0) {
            deletedTrackIds.add(msg.getId());
        }
        if (msg.messageOwner != null && msg.messageOwner.message != null
                && !msg.messageOwner.message.isEmpty()) {
            deletedTrackMessages.add(msg.messageOwner.message);
        }
        // Blacklist по ключу "artist|||title" — защищает от повторного появления
        // нативных файловых треков через mergeLocalOnlyTracks() после обновления.
        if (!TextUtils.isEmpty(msg.getMusicAuthor()) && !TextUtils.isEmpty(msg.getMusicTitle())) {
            deletedTrackKeys.add(
                msg.getMusicAuthor().trim().toLowerCase() + "|||" +
                msg.getMusicTitle().trim().toLowerCase());
        }

        MessagesController.getInstance(currentAccount).deleteMessages(
                new ArrayList<>(Collections.singletonList(msg.getId())),
                null, null, -rawId, 0, true, 0);

        // Удаляем все возможные варианты локального файла
        if (msg.messageOwner != null) {
            // По attachPath
            if (msg.messageOwner.attachPath != null) {
                File f = new File(msg.messageOwner.attachPath);
                if (f.exists() && f.getAbsolutePath().startsWith(
                        soundgram.music.ChannelMusicFragment.getMusicStorageDir().getAbsolutePath())) {
                    f.delete();
                }
            }

            // По новому имени "Исполнитель - Название.mp3"
            if (msg.messageOwner.message != null && msg.messageOwner.message.startsWith("SG_STREAM|||")) {
                String[] parts = msg.messageOwner.message.split("\\|\\|\\|");
                if (parts.length >= 3) {
                    File byName = soundgram.music.ChannelMusicFragment.getLocalTrackFile(parts[2], parts[1]);
                    if (byName.exists()) byName.delete();
                }
                // По hashId (старый формат)
                if (parts.length >= 4) {
                    long hashId = Math.abs((long) parts[3].hashCode());
                    new File(soundgram.music.ChannelMusicFragment.getMusicStorageDir(),
                            hashId + ".mp3").delete();
                }
            } else {
                // Нативный файловый трек — тоже пробуем удалить "Artist - Title.mp3" из musicDir.
                // Это главная причина, по которой трек появлялся повторно: mergeLocalOnlyTracks()
                // находил файл на диске и добавлял его обратно как "локальный".
                if (!TextUtils.isEmpty(msg.getMusicAuthor()) && !TextUtils.isEmpty(msg.getMusicTitle())) {
                    File byName = soundgram.music.ChannelMusicFragment.getLocalTrackFile(
                            msg.getMusicAuthor(), msg.getMusicTitle());
                    if (byName.exists()) byName.delete();
                }
            }

            // По messageId.mp3 (старый формат)
            new File(soundgram.music.ChannelMusicFragment.getMusicStorageDir(),
                    msg.getId() + ".mp3").delete();
        }

        // Удаляем по условию, а не по ссылке — иначе несовпадение инстансов не даст удалить
        final int msgId = msg.getId();
        final String msgText = msg.messageOwner != null ? msg.messageOwner.message : null;
        final String msgTitle = msg.getMusicTitle();
        final String msgAuthor = msg.getMusicAuthor();
        soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).removeIf(mo -> {
            if (msgId > 0 && mo.getId() == msgId) return true;
            if (msgText != null && !msgText.isEmpty()
                    && mo.messageOwner != null && msgText.equals(mo.messageOwner.message)) return true;
            if (!TextUtils.isEmpty(msgTitle) && !TextUtils.isEmpty(msgAuthor)
                    && msgTitle.equalsIgnoreCase(mo.getMusicTitle())
                    && msgAuthor.equalsIgnoreCase(mo.getMusicAuthor())) return true;
            return false;
        });

        // Если удалённый трек сейчас играет — переключаемся на следующий,
        // но только если воспроизведение идёт из библиотеки или плейлиста
        MessageObject nowPlaying = MediaController.getInstance().getPlayingMessageObject();
        if (nowPlaying != null) {
            boolean isCurrent = (msg.getId() > 0 && msg.getId() == nowPlaying.getId())
                    || (msg.messageOwner != null && nowPlaying.messageOwner != null
                        && msg.messageOwner.message != null && nowPlaying.messageOwner.message != null
                        && msg.messageOwner.message.equals(nowPlaying.messageOwner.message))
                    || (!TextUtils.isEmpty(msg.getMusicTitle())
                        && msg.getMusicTitle().equalsIgnoreCase(nowPlaying.getMusicTitle())
                        && !TextUtils.isEmpty(msg.getMusicAuthor())
                        && msg.getMusicAuthor().equalsIgnoreCase(nowPlaying.getMusicAuthor()));
            boolean isLibraryOrPlaylist = SOURCE_LIBRARY.equals(currentSource)
                    || SOURCE_PLAYLIST.equals(currentSource);
            if (isCurrent && isLibraryOrPlaylist) {
                AndroidUtilities.runOnUIThread(() ->
                        MediaController.getInstance().playNextMessage(), 150);
            }
        }

        // Обновляем иконку закладки
        if (favButton != null) {
            favButton.setImageResource(R.drawable.msg_download);
            favButton.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
        }

        Toast.makeText(getContext(), getString(R.string.dl), Toast.LENGTH_SHORT).show();
    }

    /** Поиск трека в audioMessages по id, document-id, message-строке или title+artist */
    private MessageObject findInLibrary(MessageObject mo) {
        if (mo == null) return null;
        // Получаем document.id текущего трека для точного сопоставления при пересылке
        TLRPC.Document moDoc = mo.getDocument();
        long moDocId = moDoc != null ? moDoc.id : 0;

        for (MessageObject libMsg : soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount)) {
            if (libMsg == null) continue;
            // 1. По Telegram messageId (для треков, которые находятся прямо в библиотеке)
            if (mo.getId() > 0 && libMsg.getId() > 0 && libMsg.getId() == mo.getId()) return libMsg;

            // 2. По SG_STREAM message-строке (одинаковая строка = тот же трек)
            // ИСПРАВЛЕНИЕ: Добавлена проверка !isEmpty(), чтобы пустые caption нативных аудио не совпадали друг с другом
            if (libMsg.messageOwner != null && mo.messageOwner != null
                    && libMsg.messageOwner.message != null && mo.messageOwner.message != null
                    && !libMsg.messageOwner.message.isEmpty()
                    && libMsg.messageOwner.message.equals(mo.messageOwner.message)) return libMsg;

            // 3. По document.id — пересланные треки имеют другой messageId, но тот же документ
            if (moDocId != 0) {
                TLRPC.Document libDoc = libMsg.getDocument();
                if (libDoc != null && libDoc.id == moDocId) return libMsg;
            }

            // 4. По title+artist — запасной вариант для SG_STREAM с разными URL
            String libTitle  = libMsg.getMusicTitle();
            String moTitle   = mo.getMusicTitle();
            String libAuthor = libMsg.getMusicAuthor();
            String moAuthor  = mo.getMusicAuthor();
            if (!TextUtils.isEmpty(libTitle) && !TextUtils.isEmpty(moTitle)
                    && !TextUtils.isEmpty(libAuthor) && !TextUtils.isEmpty(moAuthor)
                    && libTitle.equalsIgnoreCase(moTitle)
                    && libAuthor.equalsIgnoreCase(moAuthor)) {
                return libMsg;
            }
        }
        return null;
    }

    /** Шторка подтверждения удаления трека из библиотеки — аналог ChannelMusicFragment.showTrackOptionsBottomSheet */
    private void showLibraryTrackDeleteSheet(MessageObject msg) {
        if (msg == null || getContext() == null) return;

        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_dialogBackground));
        float r = AndroidUtilities.dp(20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(AndroidUtilities.dp(1), 0x22FFFFFF);
        container.setBackground(bg);
        container.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));

        // Ручка
        View handle = new View(getContext());
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hBg.setCornerRadius(AndroidUtilities.dp(2));
        handle.setBackground(hBg);
        FrameLayout hw = new FrameLayout(getContext());
        hw.addView(handle, new FrameLayout.LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(4), Gravity.CENTER_HORIZONTAL));
        container.addView(hw, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20, 0, 4, 0, 8));

        // Заголовок трека
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        BackupImageView cover = new BackupImageView(getContext());
        cover.setRoundRadius(AndroidUtilities.dp(10));
        ChannelMusicFragment.setAudioCover(cover, msg, "50_50", 10);
        LinearLayout textContainer = new LinearLayout(getContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        TextView titleTv = new TextView(getContext());
        titleTv.setText(msg.getMusicTitle());
        titleTv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleTv.setSingleLine(true);
        titleTv.setTypeface(AndroidUtilities.bold());
        TextView artistTv = new TextView(getContext());
        artistTv.setText(msg.getMusicAuthor());
        artistTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        artistTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        artistTv.setSingleLine(true);
        textContainer.addView(titleTv);
        textContainer.addView(artistTv);
        header.addView(cover, LayoutHelper.createLinear(52, 52));
        header.addView(textContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(header);

        View divider = new View(getContext());
        divider.setBackgroundColor(0x30FFFFFF);
        container.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 4));

        // Строка удаления
        LinearLayout deleteRow = new LinearLayout(getContext());
        deleteRow.setOrientation(LinearLayout.HORIZONTAL);
        deleteRow.setGravity(Gravity.CENTER_VERTICAL);
        deleteRow.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
        deleteRow.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));
        ImageView deleteIcon = new ImageView(getContext());
        deleteIcon.setImageResource(R.drawable.msg_delete);
        deleteIcon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN);
        TextView deleteText = new TextView(getContext());
        deleteText.setText(getString(R.string.dll));
        deleteText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        deleteText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        deleteRow.addView(deleteIcon, LayoutHelper.createLinear(22, 22));
        deleteRow.addView(deleteText, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 18, 0, 0, 0));

        BottomSheet[] sheetRef = new BottomSheet[1];
        deleteRow.setOnClickListener(v -> {
            if (sheetRef[0] != null) sheetRef[0].dismiss();
            deleteTrackFromLibrary(msg);
        });
        container.addView(deleteRow);

        builder.setCustomView(container);
        sheetRef[0] = builder.create();
        sheetRef[0].show();
    }

    private long getMusicChannelIdOrZero() {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        long targetId = MessagesController.getNotificationsSettings(currentAccount).getLong("music_channel_id_" + userId, 0);
        if (targetId != 0)
            return targetId;
        try {
            File baseDir = ApplicationLoader.applicationContext.getExternalFilesDir(null);
            if (baseDir == null)
                return 0;
            File userDir = userId != 0 ? new File(baseDir, "user_" + userId) : baseDir;
            File f = new File(userDir, "music_channel_id_" + userId + ".txt");
            if (!f.exists())
                return 0;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            if (line == null || line.trim().isEmpty())
                return 0;
            targetId = Long.parseLong(line.trim());
            MessagesController.getNotificationsSettings(currentAccount).edit()
                    .putLong("music_channel_id_" + userId, targetId).apply();
            return targetId;
        } catch (Exception ignore) {
            return 0;
        }
    }

    private void saveLatestLibraryTrackToProfile(long targetId, String title, String artist) {
        AndroidUtilities.runOnUIThread(() -> {
            final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-targetId);
            req.limit = 20;
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterMusic();
            req.offset_id = 0;
            req.add_offset = 0;
            req.max_id = 0;
            req.min_id = 0;
            req.hash = 0;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (!(res instanceof TLRPC.messages_Messages)) {
                    return;
                }
                TLRPC.messages_Messages mm = (TLRPC.messages_Messages) res;
                MessageObject candidate = null;
                for (int i = 0; i < mm.messages.size(); i++) {
                    MessageObject mo = new MessageObject(currentAccount, mm.messages.get(i), false, true);
                    if (!mo.isMusic())
                        continue;
                    String t = mo.getMusicTitle() != null ? mo.getMusicTitle() : "";
                    String a = mo.getMusicAuthor() != null ? mo.getMusicAuthor() : "";
                    if (t.equalsIgnoreCase(title) || a.equalsIgnoreCase(artist)
                            || (t.toLowerCase().contains(title.toLowerCase())
                                    && a.toLowerCase().contains(artist.toLowerCase()))) {
                        candidate = mo;
                        break;
                    }
                    if (candidate == null)
                        candidate = mo;
                }
                if (candidate != null) {
                    MessageObject finalCandidate = candidate;
                    AndroidUtilities
                            .runOnUIThread(() -> saveToProfile(
                                    finalCandidate, true, () -> Toast.makeText(getContext(),
                                            getString(R.string.sg_track_added_to_profile), Toast.LENGTH_SHORT).show(),
                                    false));
                }
            });
        }, 1500);
    }

    private void showPlaylistSelectorFromDialog(MessageObject msg) {
        File dir = soundgram.music.ChannelMusicFragment.getPlaylistsDir();
        File[] files = dir != null ? dir.listFiles((d, name) -> name.endsWith(".txt")) : null;

        if (files == null || files.length == 0) {
            Toast.makeText(getContext(), getString(R.string.sg_playlists_not_found), Toast.LENGTH_LONG).show();
            return;
        }

        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Theme.getColor(Theme.key_dialogBackground));
        gd.setCornerRadii(new float[] { AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                AndroidUtilities.dp(16), 0, 0, 0, 0 });
        container.setBackground(gd);
        container.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));

        TextView titleView = new TextView(getContext());
        titleView.setText(getString(R.string.sg_menu_add_to_playlist));
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));
        container.addView(titleView);

        View div = new View(getContext());
        div.setBackgroundColor(Theme.getColor(Theme.key_divider));
        container.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 8));

        for (File pFile : files) {
            String pName = pFile.getName().replace(".txt", "");

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20),
                    AndroidUtilities.dp(14));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));

            ImageView icon = new ImageView(getContext());
            icon.setImageResource(R.drawable.msg_add);
            icon.setColorFilter(Theme.getColor(Theme.key_dialogIcon), android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));

            TextView nameView = new TextView(getContext());
            nameView.setText(pName);
            nameView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setPadding(AndroidUtilities.dp(16), 0, 0, 0);
            row.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));

            row.setOnClickListener(v -> {
                soundgram.music.PlaylistSyncManager.PlaylistData pData = soundgram.music.PlaylistSyncManager.readPlaylistFile(pFile);
                if (msg != null) {
                    String trackLine = null;
                    if (msg.messageOwner != null && msg.messageOwner.message != null && msg.messageOwner.message.startsWith("SG_STREAM|||")) {
                        trackLine = msg.messageOwner.message;
                    } else if (msg.getId() > 0) {
                        trackLine = String.valueOf(msg.getId());
                    }
                    if (!TextUtils.isEmpty(trackLine) && !pData.trackLines.contains(trackLine)) {
                        pData.trackLines.add(0, trackLine);
                        soundgram.music.PlaylistSyncManager.writePlaylistFile(pFile, pData);
                        soundgram.music.PlaylistSyncManager.syncPlaylistToChannel(currentAccount, getMusicChannelIdOrZero(), pFile);
                    }
                }
                Toast.makeText(getContext(), String.format(getString(R.string.sg_added_to_playlist), pName),
                        Toast.LENGTH_SHORT).show();
                builder.getDismissRunnable().run();
            });
            container.addView(row);
        }

        builder.setCustomView(container);
        builder.create().show();
    }

    private void showQueueSheet() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        if (playlist == null || playlist.isEmpty()) {
            Toast.makeText(ctx, getString(R.string.NoAudioFound), Toast.LENGTH_SHORT).show();
            return;
        }
        final BottomSheet[] queueSheetRef = new BottomSheet[1];
        BottomSheet.Builder builder = new BottomSheet.Builder(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(12));

        // Заголовок с индикатором режима воспроизведения
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView title = new TextView(ctx);
        title.setText(LocaleController.getString(R.string.sg_queue_title));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Бейдж режима: «↕ Обратный», «⇌ Shuffle» или ничего
        if (SharedConfig.playOrderReversed || SharedConfig.shuffleMusic) {
            TextView modeBadge = new TextView(ctx);
            modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            modeBadge.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            modeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
            if (SharedConfig.shuffleMusic) {
                modeBadge.setText("⇌ " + getString(R.string.ShuffleList));
            } else {
                modeBadge.setText("↕ " + getString(R.string.ReverseOrder));
            }
            int badgeColor = Theme.getColor(Theme.key_featuredStickers_addButton);
            modeBadge.setTextColor(badgeColor);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(badgeColor, 30));
            badgeBg.setCornerRadius(dp(10));
            badgeBg.setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(badgeColor, 80));
            modeBadge.setBackground(badgeBg);
            titleRow.addView(modeBadge,
                    LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        container.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // displayList строим в том же порядке что видит пользователь.
        // При клике используем playMessageAtIndex с индексом из внутреннего playlist MediaController.
        boolean queueDirectOrder = savedMusicList != null
                ? !SharedConfig.playOrderReversed
                : SharedConfig.playOrderReversed;
        ArrayList<MessageObject> displayList = new ArrayList<>();
        if (SharedConfig.shuffleMusic) {
            displayList.addAll(playlist);
        } else if (queueDirectOrder) {
            displayList.addAll(playlist);
        } else {
            for (int i = playlist.size() - 1; i >= 0; i--) {
                displayList.add(playlist.get(i));
            }
        }

        // RecyclerView с QueueAdapter — высота адаптивная (макс 70% экрана)
        RecyclerListView queueListView = new RecyclerListView(ctx);
        queueListView.setVerticalScrollBarEnabled(false);
        queueListView.setHorizontalScrollBarEnabled(false);
        queueListView.setFastScrollVisible(false);
        LinearLayoutManager queueLlm = new LinearLayoutManager(ctx);
        queueListView.setLayoutManager(queueLlm);
        queueListView.setNestedScrollingEnabled(true);
        queueListView.setHasFixedSize(false);
        QueueAdapter queueAdapter = new QueueAdapter(ctx, displayList, queueSheetRef);
        queueListView.setAdapter(queueAdapter);
        // Фикс: при скролле вверх BottomSheet не должна перехватывать жест и
        // закрываться.
        queueListView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    if (rv.getParent() != null) {
                        rv.getParent().requestDisallowInterceptTouchEvent(rv.canScrollVertically(-1));
                    }
                }
                return false;
            }
        });
        // Скроллить к текущему треку
        int queueCurrentPos = 0;
        MessageObject queuePlayingNow = MediaController.getInstance().getPlayingMessageObject();
        if (queuePlayingNow != null) {
            for (int qi = 0; qi < displayList.size(); qi++) {
                if (displayList.get(qi).getId() == queuePlayingNow.getId()) {
                    queueCurrentPos = qi;
                    break;
                }
            }
        }
        queueLlm.scrollToPositionWithOffset(Math.max(0, queueCurrentPos - 2), 0);
        // Высота: min(70% экрана, кол-во треков * 72dp), но не меньше 200dp
        int queueTrackH = AndroidUtilities.dp(72);
        int queueMaxH = (int) (AndroidUtilities.displaySize.y * 0.70f);
        int queueListH = Math.min(queueMaxH, Math.max(AndroidUtilities.dp(200), displayList.size() * queueTrackH));
        container.addView(queueListView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (queueListH / AndroidUtilities.density)));

        builder.setCustomView(container);
        queueSheetRef[0] = builder.create();
        currentQueueSheet = queueSheetRef[0];
        currentQueueAdapter = queueAdapter;
        currentQueueDisplayList = displayList;
        currentQueueListView = queueListView;
        queueSheetRef[0].setOnDismissListener(d -> {
            if (currentQueueSheet == queueSheetRef[0]) {
                currentQueueSheet = null;
                currentQueueAdapter = null;
                currentQueueDisplayList = null;
                currentQueueListView = null;
            }
        });
        queueSheetRef[0].show();
    }

    // Адаптер очереди воспроизведения
    private class QueueAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        private final ArrayList<MessageObject> queueTracks;
        private final BottomSheet[] bottomSheetRef;

        public QueueAdapter(Context context, ArrayList<MessageObject> tracks, BottomSheet[] sheetRef) {
            this.context = context;
            this.queueTracks = tracks;
            this.bottomSheetRef = sheetRef;
        }

        @Override
        public int getItemCount() {
            return queueTracks.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            QueueTrackCell cell = new QueueTrackCell(context);
            RecyclerListView.Holder holder = new RecyclerListView.Holder(cell);

            cell.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos < 0 || pos >= queueTracks.size()) return;
                MessageObject targetMsg = queueTracks.get(pos);

                // Ищем индекс трека во внутреннем playlist MediaController и вызываем
                // playMessageAtIndex — только он правильно обновляет currentPlaylistNum,
                // благодаря чему следующий/предыдущий трек после окончания будет верным.
                // playMessage() этого НЕ делает — он просто воспроизводит объект без
                // обновления позиции в плейлисте.
                List<MessageObject> mcPlaylist = MediaController.getInstance().getPlaylist();
                int foundIndex = -1;
                if (mcPlaylist != null) {
                    for (int i = 0; i < mcPlaylist.size(); i++) {
                        MessageObject mo = mcPlaylist.get(i);
                        if (mo == targetMsg) { foundIndex = i; break; }
                        if (mo.getId() > 0 && mo.getId() == targetMsg.getId()) { foundIndex = i; break; }
                        if (mo.messageOwner != null && targetMsg.messageOwner != null
                                && mo.messageOwner.message != null && !mo.messageOwner.message.isEmpty()
                                && mo.messageOwner.message.equals(targetMsg.messageOwner.message)) {
                            foundIndex = i; break;
                        }
                    }
                }

                if (foundIndex >= 0) {
                    MessageObject current = MediaController.getInstance().getPlayingMessageObject();
                    if (current != null && mcPlaylist != null && foundIndex < mcPlaylist.size()
                            && mcPlaylist.get(foundIndex) == current) {
                        // Это уже играющий трек — toggle pause
                        if (MediaController.getInstance().isMessagePaused()) {
                            MediaController.getInstance().playMessage(current);
                        } else {
                            MediaController.getInstance().pauseMessage(current);
                        }
                    } else {
                        // Переключаем через индекс — currentPlaylistNum обновится правильно
                        MediaController.getInstance().playMessageAtIndex(foundIndex);
                    }
                } else {
                    // Запасной вариант если не нашли в плейлисте
                    MediaController.getInstance().playMessage(targetMsg);
                }

                if (bottomSheetRef[0] != null) {
                    bottomSheetRef[0].dismiss();
                }
            });

            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            QueueTrackCell cell = (QueueTrackCell) holder.itemView;
            MessageObject trackMsg = queueTracks.get(position);
            cell.setMessageObject(trackMsg);
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }
    }


    // Ячейка очереди — вся строка кликабельна
    private class QueueTrackCell extends FrameLayout {
        public BackupImageView coverImage;
        public TextView titleTextView;
        public TextView artistTextView;
        public ImageView playingIndicator;
        private MessageObject currentMessageObject;

        public QueueTrackCell(Context context) {
            super(context);

            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(72)));
            // Клики перехватывает сам FrameLayout, дочерние — нет
            setClickable(true);
            setFocusable(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));

            // Обложка
            FrameLayout coverFrame = new FrameLayout(context);
            coverImage = new BackupImageView(context);
            coverImage.setRoundRadius(AndroidUtilities.dp(8));
            // Не перехватывать клики
            coverImage.setClickable(false);
            coverImage.setFocusable(false);
            coverFrame.addView(coverImage, LayoutHelper.createFrame(52, 52));

            // Индикатор воспроизведения (поверх обложки)
            playingIndicator = new ImageView(context);
            playingIndicator.setScaleType(ImageView.ScaleType.CENTER);
            playingIndicator.setColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.SRC_IN);
            GradientDrawable indicatorBg = new GradientDrawable();
            indicatorBg.setColor(0xCC000000);
            indicatorBg.setCornerRadius(AndroidUtilities.dp(8));
            playingIndicator.setBackground(indicatorBg);
            playingIndicator.setVisibility(View.GONE);
            playingIndicator.setClickable(false);
            coverFrame.addView(playingIndicator, LayoutHelper.createFrame(52, 52));

            addView(coverFrame, LayoutHelper.createFrame(52, 52, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            // Текст — занимает всё оставшееся пространство
            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            textLayout.setGravity(Gravity.CENTER_VERTICAL);
            textLayout.setClickable(false);
            textLayout.setFocusable(false);

            titleTextView = new TextView(context);
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            titleTextView.setClickable(false);

            artistTextView = new TextView(context);
            artistTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            artistTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            artistTextView.setSingleLine(true);
            artistTextView.setEllipsize(TextUtils.TruncateAt.END);
            artistTextView.setClickable(false);

            textLayout.addView(titleTextView,
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            textLayout.addView(artistTextView,
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            // Правый отступ 16dp — кнопки опций нет, текст занимает всё место
            addView(textLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 82, 0, 16, 0));

            // Разделитель снизу
            View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            divider.setClickable(false);
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density,
                    Gravity.BOTTOM | Gravity.LEFT, 82, 0, 0, 0));
        }

        public void setMessageObject(MessageObject msg) {
            this.currentMessageObject = msg;
            if (msg == null)
                return;

            titleTextView.setText(msg.getMusicTitle());
            artistTextView.setText(msg.getMusicAuthor());

            MessageObject playingMsg = MediaController.getInstance().getPlayingMessageObject();
            boolean isPlaying = playingMsg != null && playingMsg.getId() == msg.getId() && msg.getId() != 0;
            int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);

            if (isPlaying) {
                titleTextView.setTextColor(accentColor);
                playingIndicator.setVisibility(View.VISIBLE);
                ValueAnimator pulseAnim = ValueAnimator.ofFloat(1f, 1.04f, 1f);
                pulseAnim.setDuration(1200);
                pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnim.setRepeatMode(ValueAnimator.RESTART);
                pulseAnim.addUpdateListener(anim -> {
                    float val = (float) anim.getAnimatedValue();
                    coverImage.setScaleX(val);
                    coverImage.setScaleY(val);
                });
                Object oldAnim = coverImage.getTag();
                if (oldAnim instanceof ValueAnimator)
                    ((ValueAnimator) oldAnim).cancel();
                coverImage.setTag(pulseAnim);
                pulseAnim.start();
            } else {
                titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                playingIndicator.setVisibility(View.GONE);
                Object tag = coverImage.getTag();
                if (tag instanceof ValueAnimator)
                    ((ValueAnimator) tag).cancel();
                coverImage.setScaleX(1f);
                coverImage.setScaleY(1f);
            }

            setAudioCover(coverImage, msg, "50_50", 8);

            String title = msg.getMusicTitle();
            String author = msg.getMusicAuthor();
            int duration = (int) msg.getDuration();
            String durStr = duration > 0 ? String.format("%d:%02d", duration / 60, duration % 60) : "";
            String accText;
            if (isPlaying) {
                accText = LocaleController.formatString("AccDescrMusicTrackPlaying", R.string.AccDescrMusicTrackPlaying, title != null ? title : "", author != null ? author : "");
            } else {
                accText = LocaleController.formatString("AccDescrMusicTrack", R.string.AccDescrMusicTrack, title != null ? title : "", author != null ? author : "", durStr);
            }
            setContentDescription(accText);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    private void showAlbumCover(boolean show, boolean animated) {
        if (show) {
            if (blurredView.getVisibility() == View.VISIBLE || blurredAnimationInProgress) {
                return;
            }
            blurredView.setTag(1);
            bigAlbumConver.setImageBitmap(coverContainer.getImageReceiver().getBitmap());
            blurredAnimationInProgress = true;
            BaseFragment fragment = parentActivity.getActionBarLayout().getFragmentStack()
                    .get(parentActivity.getActionBarLayout().getFragmentStack().size() - 1);
            View fragmentView = fragment.getFragmentView();
            if (fragmentView != null) {
                int w = (int) (fragmentView.getMeasuredWidth() / 6.0f);
                int h = (int) (fragmentView.getMeasuredHeight() / 6.0f);
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(1.0f / 6.0f, 1.0f / 6.0f);
                fragmentView.draw(canvas);
                canvas.translate(containerView.getLeft() - getLeftInset(), 0);
                containerView.draw(canvas);
                Utilities.stackBlurBitmap(bitmap, Math.max(7, Math.max(w, h) / 180));
                blurredView.setBackground(new BitmapDrawable(bitmap));
            }
            blurredView.setVisibility(View.VISIBLE);
            blurredView.animate().alpha(1.0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    blurredAnimationInProgress = false;
                }
            }).start();
            bigAlbumConver.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        } else {
            if (blurredView.getVisibility() != View.VISIBLE) {
                return;
            }
            blurredView.setTag(null);
            if (animated) {
                blurredAnimationInProgress = true;
                blurredView.animate().alpha(0.0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        blurredView.setVisibility(View.INVISIBLE);
                        bigAlbumConver.setImageBitmap(null);
                        blurredAnimationInProgress = false;
                    }
                }).start();
                bigAlbumConver.animate().scaleX(0.9f).scaleY(0.9f).setDuration(180).start();
            } else {
                blurredView.setAlpha(0.0f);
                blurredView.setVisibility(View.INVISIBLE);
                bigAlbumConver.setImageBitmap(null);
                bigAlbumConver.setScaleX(0.9f);
                bigAlbumConver.setScaleY(0.9f);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged
                || id == NotificationCenter.messagePlayingDidReset) {
            updateTitle(id == NotificationCenter.messagePlayingDidReset && (Boolean) args[1]);
            if (id == NotificationCenter.messagePlayingDidReset
                    || id == NotificationCenter.messagePlayingPlayStateChanged) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = listView.getChildAt(a);
                    if (view instanceof AudioPlayerCell) {
                        AudioPlayerCell cell = (AudioPlayerCell) view;
                        MessageObject messageObject = cell.getMessageObject();
                        if (messageObject != null && (messageObject.isVoice() || messageObject.isMusic())) {
                            cell.updateButtonState(false, true);
                        }
                    }
                }
                if (id == NotificationCenter.messagePlayingPlayStateChanged) {
                    if (MediaController.getInstance().getPlayingMessageObject() != null) {
                        if (MediaController.getInstance().isMessagePaused()) {
                            startForwardRewindingSeek();
                        } else if (rewindingState == 1 && rewindingProgress != -1f) {
                            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
                            lastUpdateRewindingPlayerTime = 0;
                            forwardSeek.run();
                            rewindingProgress = -1f;
                        }
                    }
                }
                if (currentQueueAdapter != null) {
                    currentQueueAdapter.notifyDataSetChanged();
                }
            } else {
                MessageObject messageObject = (MessageObject) args[0];
                if (messageObject.eventId != 0) {
                    return;
                }
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = listView.getChildAt(a);
                    if (view instanceof AudioPlayerCell) {
                        AudioPlayerCell cell = (AudioPlayerCell) view;
                        MessageObject messageObject1 = cell.getMessageObject();
                        if (messageObject1 != null && (messageObject1.isVoice() || messageObject1.isMusic())) {
                            cell.updateButtonState(false, true);
                        }
                    }
                }
            }
            if (optionsIcon != null) {
                optionsIcon.setCasting(CastSync.isActive(), true);
            }
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null && (messageObject.isMusic() || messageObject.isVoice())) {
                updateProgress(messageObject);
            }
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updatePlaybackButton(true);
        } else if (id == NotificationCenter.musicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            playlist = MediaController.getInstance().getPlaylist();
            // Убираем треки из blacklist — они были удалены, но loadMusicFromChannel
            // мог вернуть их обратно до того как сервер применил удаление.
            if (!deletedTrackIds.isEmpty() || !deletedTrackMessages.isEmpty()) {
                soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).removeIf(mo -> {
                    if (mo.getId() > 0 && deletedTrackIds.contains(mo.getId())) return true;
                    if (mo.messageOwner != null && mo.messageOwner.message != null
                            && deletedTrackMessages.contains(mo.messageOwner.message)) return true;
                    return false;
                });
            }
            listAdapter.notifyDataSetChanged();
        } else if (id == NotificationCenter.moreMusicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            playlist = MediaController.getInstance().getPlaylist();
            if (!deletedTrackIds.isEmpty() || !deletedTrackMessages.isEmpty()) {
                soundgram.music.ChannelMusicFragment.getAudioMessages(currentAccount).removeIf(mo -> {
                    if (mo.getId() > 0 && deletedTrackIds.contains(mo.getId())) return true;
                    if (mo.messageOwner != null && mo.messageOwner.message != null
                            && deletedTrackMessages.contains(mo.messageOwner.message)) return true;
                    return false;
                });
            }
            listAdapter.notifyDataSetChanged();
            if (currentQueueAdapter != null && currentQueueDisplayList != null && playlist != null) {
                boolean queueDirectOrder = savedMusicList != null
                        ? !SharedConfig.playOrderReversed
                        : SharedConfig.playOrderReversed;
                currentQueueDisplayList.clear();
                if (SharedConfig.shuffleMusic) {
                    currentQueueDisplayList.addAll(playlist);
                } else if (queueDirectOrder) {
                    currentQueueDisplayList.addAll(playlist);
                } else {
                    for (int i = playlist.size() - 1; i >= 0; i--) {
                        currentQueueDisplayList.add(playlist.get(i));
                    }
                }
                currentQueueAdapter.notifyDataSetChanged();
                if (currentQueueListView != null) {
                    int queueTrackH = AndroidUtilities.dp(72);
                    int queueMaxH = (int) (AndroidUtilities.displaySize.y * 0.70f);
                    int queueListH = Math.min(queueMaxH, Math.max(AndroidUtilities.dp(200), currentQueueDisplayList.size() * queueTrackH));
                    android.view.ViewGroup.LayoutParams lp = currentQueueListView.getLayoutParams();
                    if (lp != null && lp.height != (int) (queueListH / AndroidUtilities.density)) {
                        lp.height = (int) (queueListH / AndroidUtilities.density);
                        currentQueueListView.setLayoutParams(lp);
                    }
                }
            }
            if (SharedConfig.playOrderReversed) {
                listView.stopScroll();
                int addedCount = (Integer) args[0];
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                int position = layoutManager.findLastVisibleItemPosition();
                if (position != RecyclerView.NO_POSITION) {
                    View firstVisView = layoutManager.findViewByPosition(position);
                    int offset = firstVisView == null ? 0 : firstVisView.getTop();
                    layoutManager.scrollToPositionWithOffset(position + addedCount, offset);
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String name = (String) args[0];
            if (name.equals(currentFile)) {
                updateTitle(false);
                currentAudioFinishedLoading = true;
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            String name = (String) args[0];
            if (name.equals(currentFile)) {
                MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                if (messageObject == null) {
                    return;
                }
                Long loadedSize = (Long) args[1];
                Long totalSize = (Long) args[2];
                float bufferedProgress;
                if (currentAudioFinishedLoading) {
                    bufferedProgress = 1.0f;
                } else {
                    long newTime = SystemClock.elapsedRealtime();
                    if (Math.abs(newTime - lastBufferedPositionCheck) >= 500) {
                        bufferedProgress = MediaController.getInstance().isStreamingCurrentAudio()
                                ? FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(
                                        messageObject.audioProgress, currentFile)
                                : 1.0f;
                        lastBufferedPositionCheck = newTime;
                    } else {
                        bufferedProgress = -1;
                    }
                }
                if (bufferedProgress != -1) {
                    seekBarBufferSpring.getSpring().setFinalPosition(bufferedProgress * 1000);
                    seekBarBufferSpring.start();
                }
            }
        } else if (id == NotificationCenter.musicIdsLoaded) {
            updateTitle(false);
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    private void updateLayout() {
        if (listView == null || playerLayout == null)
            return;

        if (listView.getChildCount() <= 0) {
            listView.setTopGlowOffset(scrollOffsetY = listView.getPaddingTop());
            containerView.invalidate();
            return;
        }

        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = child instanceof AudioPlayerCell ? child.getTop() : child.getBottom();
        int newOffset = dp(7);
        if (top >= dp(7) && holder != null && holder.getAdapterPosition() == 0) {
            newOffset = top;
        }

        boolean show = newOffset <= dp(12);
        if (show && actionBar.getTag() == null || !show && actionBar.getTag() != null) {
            actionBar.setTag(show ? 1 : null);
            if (actionBarAnimation != null) {
                actionBarAnimation.cancel();
                actionBarAnimation = null;
            }
            actionBarAnimation = new AnimatorSet();
            actionBarAnimation.setDuration(180);
            actionBarAnimation.playTogether(
                    ObjectAnimator.ofFloat(actionBar, View.ALPHA, show ? 1.0f : 0.0f),
                    ObjectAnimator.ofFloat(actionBarShadow, View.ALPHA, show ? 1.0f : 0.0f));
            actionBarAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    actionBarAnimation = null;
                }
            });
            actionBarAnimation.start();
        }

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
        newOffset += layoutParams.topMargin - AndroidUtilities.statusBarHeight - dp(11);
        if (scrollOffsetY != newOffset) {
            listView.setTopGlowOffset(
                    (scrollOffsetY = newOffset) - layoutParams.topMargin - AndroidUtilities.statusBarHeight);
            containerView.invalidate();
        }

        // ИСПРАВЛЕНИЕ: Сшиваем плеер со списком
        // Плеер выталкивается вверх при скролле списка
        float yOffset = scrollOffsetY - playerLayout.getMeasuredHeight();
        if (yOffset > 0)
            yOffset = 0; // Не даем плееру оторваться вниз при оттяжке
        playerLayout.setTranslationY(yOffset);
        if (playerShadow != null) {
            playerShadow.setTranslationY(yOffset + playerLayout.getMeasuredHeight());
        }

        int offset = dp(13);
        top = scrollOffsetY - backgroundPaddingTop - offset;
        float rad = 1.0f;
        if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
            float toMove = offset + dp(11 - 7);
            float moveProgress = Math.min(1.0f,
                    (ActionBar.getCurrentActionBarHeight() - top - backgroundPaddingTop) / toMove);
            rad = 1.0f - moveProgress;
        }

        boolean light = rad <= 0.5f && ColorUtils.calculateLuminance(getThemedColor(Theme.key_dialogBackground)) > 0.7f;
        if (light != wasLight) {
            AndroidUtilities.setLightStatusBar(this, wasLight = light);
        }
    }

    @Override
    public void dismiss() {
        instance = null;
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this,
                NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this,
                NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
    }

    @Override
    public void onBackPressed() {
        if (actionBar != null && actionBar.isSearchFieldVisible()) {
            actionBar.closeSearchField();
            return;
        }
        if (blurredView.getTag() != null) {
            showAlbumCover(false, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onFailedDownload(String fileName, boolean canceled) {

    }

    @Override
    public void onSuccessDownload(String fileName) {

    }

    @Override
    public void onProgressDownload(String fileName, long downloadedSize, long totalSize) {
        progressView.setProgress(Math.min(1f, downloadedSize / (float) totalSize), true);
    }

    @Override
    public void onProgressUpload(String fileName, long uploadedSize, long totalSize, boolean isEncrypted) {

    }

    @Override
    public int getObserverTag() {
        return TAG;
    }

    private void updateRepeatButtonBoth() {
        updateRepeatButton();
        // Синхронизируем иконку modeMenuButton с текущим режимом
        if (modeMenuButton == null)
            return;
        int mode = SharedConfig.repeatMode;
        int iconRes;
        if (mode == 2) {
            iconRes = R.drawable.player_new_repeatone;
        } else if (mode == AudioPlayerAlert.PLAY_ONCE) {
            iconRes = R.drawable.photo_timer;
        } else if (SharedConfig.shuffleMusic) {
            iconRes = mode == 1 ? R.drawable.player_new_repeat_shuffle : R.drawable.player_new_shuffle;
        } else if (SharedConfig.playOrderReversed) {
            iconRes = mode == 1 ? R.drawable.player_new_repeat_reverse : R.drawable.player_new_order;
        } else {
            iconRes = R.drawable.player_new_repeatall;
        }
        modeMenuButton.setIcon(iconRes);
        boolean isActive = mode != 0 || SharedConfig.shuffleMusic || SharedConfig.playOrderReversed;
        modeMenuButton.setIconColor(isActive ? getThemedColor(Theme.key_player_buttonActive) : getThemedColor(Theme.key_player_button));
    }

    public void updateRepeatButton() {
        int mode = SharedConfig.repeatMode;
        if (mode == 0 || mode == 1) {
            if (SharedConfig.shuffleMusic) {
                if (mode == 0) {
                    repeatButton.setIcon(R.drawable.player_new_shuffle);
                } else {
                    repeatButton.setIcon(R.drawable.player_new_repeat_shuffle);
                }
            } else if (SharedConfig.playOrderReversed) {
                if (mode == 0) {
                    repeatButton.setIcon(R.drawable.player_new_order);
                } else {
                    repeatButton.setIcon(R.drawable.player_new_repeat_reverse);
                }
            } else {
                repeatButton.setIcon(R.drawable.player_new_repeatall);
            }
            if (mode == 0 && !SharedConfig.shuffleMusic && !SharedConfig.playOrderReversed) {
                repeatButton.setTag(Theme.key_player_button);
                repeatButton.setIconColor(getThemedColor(Theme.key_player_button));
                Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_listSelector),
                        true);
                repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatOff));
            } else {
                repeatButton.setTag(Theme.key_player_buttonActive);
                repeatButton.setIconColor(getThemedColor(Theme.key_player_buttonActive));
                Theme.setSelectorDrawableColor(repeatButton.getBackground(),
                        getThemedColor(Theme.key_player_buttonActive) & 0x19ffffff, true);
                if (mode == 0) {
                    if (SharedConfig.shuffleMusic) {
                        repeatButton.setContentDescription(LocaleController.getString(R.string.ShuffleList));
                    } else {
                        repeatButton.setContentDescription(LocaleController.getString(R.string.ReverseOrder));
                    }
                } else {
                    repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatList));
                }
            }
        } else if (mode == 2) {
            repeatButton.setIcon(R.drawable.player_new_repeatone);
            repeatButton.setTag(Theme.key_player_buttonActive);
            repeatButton.setIconColor(getThemedColor(Theme.key_player_buttonActive));
            Theme.setSelectorDrawableColor(repeatButton.getBackground(),
                    getThemedColor(Theme.key_player_buttonActive) & 0x19ffffff, true);
            repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatOne));
        } else if (mode == PLAY_ONCE) {
            repeatButton.setIcon(R.drawable.photo_timer);
            repeatButton.setTag(Theme.key_player_buttonActive);
            repeatButton.setIconColor(getThemedColor(Theme.key_player_buttonActive));
            Theme.setSelectorDrawableColor(repeatButton.getBackground(),
                    getThemedColor(Theme.key_player_buttonActive) & 0x19ffffff, true);
            repeatButton.setContentDescription(LocaleController.getString(R.string.PlayOnce));
        }
    }

    private void updateProgress(MessageObject messageObject) {
        updateProgress(messageObject, false);
    }

    private void updateProgress(MessageObject messageObject, boolean animated) {
        if (seekBarView != null) {
            int newTime;
            if (seekBarView.isDragging()) {
                newTime = (int) (messageObject.getDuration() * seekBarView.getProgress());
            } else {
                boolean updateRewinding = rewindingProgress >= 0 && (rewindingState == -1
                        || (rewindingState == 1 && MediaController.getInstance().isMessagePaused()));
                if (updateRewinding) {
                    seekBarView.setProgress(rewindingProgress, animated);
                } else {
                    seekBarView.setProgress(messageObject.audioProgress, animated);
                }

                float bufferedProgress;
                if (currentAudioFinishedLoading) {
                    bufferedProgress = 1.0f;
                } else {
                    long time = SystemClock.elapsedRealtime();
                    if (Math.abs(time - lastBufferedPositionCheck) >= 500) {
                        bufferedProgress = MediaController.getInstance().isStreamingCurrentAudio()
                                ? FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(
                                        messageObject.audioProgress, currentFile)
                                : 1.0f;
                        lastBufferedPositionCheck = time;
                    } else {
                        bufferedProgress = -1;
                    }
                }
                if (bufferedProgress != -1) {
                    seekBarBufferSpring.getSpring().setFinalPosition(bufferedProgress * 1000);
                    seekBarBufferSpring.start();
                }
                if (updateRewinding) {
                    newTime = (int) (messageObject.getDuration() * seekBarView.getProgress());
                    messageObject.audioProgressSec = newTime;
                } else {
                    newTime = messageObject.audioProgressSec;
                }
            }
            if (waveProgressView != null) {
                waveProgressView.setProgress(messageObject.audioProgress);
            }
            if (lastTime != newTime) {
                lastTime = newTime;
                timeTextView.setText(AndroidUtilities.formatShortDuration(newTime));
                int remaining = (int) Math.max(0, messageObject.getDuration() - newTime);
                durationTextView.setText("−" + AndroidUtilities.formatShortDuration(remaining));
            }
            seekBarView.updateTimestamps(messageObject, null);
        }

        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (isLyricsVisible && !lyricsLines.isEmpty() && playing != null) {
            // ИСПРАВЛЕНИЕ: Берем точное время в миллисекундах из плеера для идеальной
            // синхронизации
            long currentMs = MediaController.getInstance().getProgressMs(playing);
            if (currentMs < 0) {
                if (playing.audioProgressSec > 0)
                    currentMs = playing.audioProgressSec * 1000L;
                else
                    currentMs = (long) (playing.audioProgress * playing.getDuration() * 1000L);
            }
            updateHighlight(currentMs);
        }
    }

    private void smoothScrollToCenter(int position) {
        if (lyricsView == null)
            return;
        lyricsView.setActiveIndex(position, true);
    }

    private void jumpToCurrentLyric() {
        if (lyricsLines.isEmpty() || lyricsView == null)
            return;
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null)
            return;
        long currentMs = MediaController.getInstance().getProgressMs(playing);
        if (currentMs < 0) {
            if (playing.audioProgressSec > 0)
                currentMs = playing.audioProgressSec * 1000L;
            else
                currentMs = (long) (playing.audioProgress * playing.getDuration() * 1000L);
        }
        float speed = soundgram.music.SoundGramAudioFX.getCurrentSpeed();
        if (speed <= 0f) speed = 1.0f;
        long leadMs = Math.round(30 * speed);
        long adjustedMs = currentMs + leadMs;
        int idx = -1;
        for (int i = lyricsLines.size() - 1; i >= 0; i--) {
            if (adjustedMs >= lyricsLines.get(i).timeMs) {
                idx = i;
                break;
            }
        }
        currentLyricIndex = idx;
        lyricsView.jumpToIndex(Math.max(0, idx));
    }

    private void updateHighlight(long currentMs) {
        if (isFetchingLyrics || lyricsLines.isEmpty())
            return;
        float speed = soundgram.music.SoundGramAudioFX.getCurrentSpeed();
        if (speed <= 0f) speed = 1.0f;
        long leadMs = Math.round(30 * speed);
        long adjustedMs = currentMs + leadMs;

        int newActiveIndex = -1;
        for (int i = lyricsLines.size() - 1; i >= 0; i--) {
            if (adjustedMs >= lyricsLines.get(i).timeMs) {
                newActiveIndex = i;
                break;
            }
        }
        if (newActiveIndex == currentLyricIndex)
            return;
        currentLyricIndex = newActiveIndex;

        if (lyricsView != null) {
            lyricsView.setActiveIndex(currentLyricIndex, true);
        }
    }

    private void checkIfMusicDownloaded(MessageObject messageObject) {
        File cacheFile = null;
        if (messageObject.messageOwner.attachPath != null && messageObject.messageOwner.attachPath.length() > 0) {
            cacheFile = new File(messageObject.messageOwner.attachPath);
            if (!cacheFile.exists()) {
                cacheFile = null;
            }
        }
        if (cacheFile == null) {
            cacheFile = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
        }

        // ИСПРАВЛЕНИЕ: Разрешаем проигрывать и контроллировать стримы напрямую
        boolean isDirectStream = messageObject.messageOwner.attachPath != null &&
                (messageObject.messageOwner.attachPath.startsWith("http://") ||
                        messageObject.messageOwner.attachPath.startsWith("https://"));

        boolean canStream = SharedConfig.streamMedia && ((int) messageObject.getDialogId() != 0 || isDirectStream)
                && messageObject.isMusic();

        if (!cacheFile.exists() && !canStream && !isDirectStream) {
            String fileName = messageObject.getFileName();
            DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
            Float progress = ImageLoader.getInstance().getFileProgress(fileName);
            progressView.setProgress(progress != null ? progress : 0, false);
            progressView.setVisibility(View.VISIBLE);
            seekBarView.setVisibility(View.INVISIBLE);
            playButton.setEnabled(false);
        } else {
            DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            progressView.setVisibility(View.INVISIBLE);
            seekBarView.setVisibility(View.VISIBLE);
            playButton.setEnabled(true);
        }
    }

    private void updateTitle(boolean shutdown) {
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null && shutdown
                || messageObject != null && !(messageObject.isMusic() || messageObject.isVoice())) {
            dismiss();
        } else {
            if (messageObject == null) {
                lastMessageObject = null;
                return;
            }
            final boolean sameMessageObject = messageObject == lastMessageObject;
            lastMessageObject = messageObject;
            if (messageObject.eventId != 0 || messageObject.getId() <= -2000000000) {
                optionsButton.setVisibility(View.INVISIBLE);
            } else {
                optionsButton.setVisibility(View.VISIBLE);
            }
            final long dialogId = messageObject.getDialogId();
            final long docId = messageObject.getDocument() != null ? messageObject.getDocument().id : 0L;
            final boolean noforwards = (dialogId < 0
                    && MessagesController.getInstance(currentAccount).isPeerNoForwards(dialogId) ||
                    MessagesController.getInstance(currentAccount).isPeerNoForwards(messageObject.getDialogId()) ||
                    messageObject.messageOwner.noforwards);

            // ИСПРАВЛЕНИЕ: Удален код, который плющил высоту плеера обратно до стандартных
            // Telegram размеров
            if (noforwards != this.noforwards) {
                this.noforwards = noforwards;
            }

            if (noforwards) {
                optionsButton.hideSubItem(1);
                optionsButton.hideSubItem(100);
                optionsButton.setAdditionalYOffset(-dp(16 + 48 * 3));
            } else {
                optionsButton.showSubItem(1);
                optionsButton.showSubItem(100);
                optionsButton.showSubItem(2);
                optionsButton.showSubItem(5);
                optionsButton.setAdditionalYOffset(-dp(157 + 40 + 48));
            }
            optionsButton.setSubItemShown(4, messageObject.getId() > 0);
            optionsButton.setSubItemShown(7, messageObject != null
                    && (messageObject.isMusic() || messageObject.isVoice() || messageObject.isRoundVideo()));

            checkIfMusicDownloaded(messageObject);
            updateProgress(messageObject, !sameMessageObject);
            updateCover(messageObject, !sameMessageObject);
            // Показываем кнопки в зависимости от нахождения в библиотеке
            if (topOptionsButton != null) {
                boolean isInLib = messageObject != null && findInLibrary(messageObject) != null;
                topOptionsButton.setSubItemShown(203, isInLib);
                topOptionsButton.setSubItemShown(5, !isInLib); // <-- Показываем 'В библиотеку' если трека там нет
            }
            if (MediaController.getInstance().isMessagePaused()) {
                playPauseDrawable.setPause(false);
                playButton.setContentDescription(LocaleController.getString(R.string.AccActionPlay));
            } else {
                playPauseDrawable.setPause(true);
                playButton.setContentDescription(LocaleController.getString(R.string.AccActionPause));
            }
            String title = messageObject.getMusicTitle();
            String author = messageObject.getMusicAuthor();
            titleTextView.setText(title);
            titleTextView.setContentDescription(title);
            authorTextView.setText(author);
            authorTextView.setContentDescription(author);
            // ФИКС ТЕКСТА: Запрашиваем новый текст только если трек сменился
            // или лирика ещё не загружена (isFetchingLyrics или список пуст/placeholder)
            if (isLyricsVisible && messageObject != null) {
                boolean lyricsReady = !isFetchingLyrics && !lyricsLines.isEmpty()
                        && !(lyricsLines.size() == 1
                             && (lyricsLines.get(0).text.equals(getString(R.string.sg_lyrics_searching))
                                 || lyricsLines.get(0).text.equals(getString(R.string.sg_lyrics_not_found))));
                if (!sameMessageObject || !lyricsReady) {
                    fetchLyrics(messageObject);
                }
            }
            // Источник скрыт
            if (sourceChipTextView != null) {
                sourceChipTextView.setVisibility(View.GONE);
            }

            final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount)
                    .getSavedMusicIds();
            saveToProfileButton.setLoading(musicIds.loading);
            setVisibleInProfile(musicIds.ids.contains(docId));

            // Обновляем иконку кнопки-закладки (библиотека)
            if (favButton != null) {
                boolean nowInLib = findInLibrary(messageObject) != null;
                favButton.setImageResource(nowInLib ? R.drawable.msg_delete : R.drawable.msg_download);
            }

            int duration = lastDuration = (int) messageObject.getDuration();

            if (durationTextView != null) {
                durationTextView.setText(duration != 0 ? AndroidUtilities.formatShortDuration(duration) : "-:--");
            }

            if (duration > 60 * 10) {
                playbackSpeedButton.setVisibility(View.VISIBLE);
            } else {
                playbackSpeedButton.setVisibility(View.GONE);
            }

            if (nextTrackTitleView != null) {
                MessageObject nextTrack = MediaController.getInstance().getNextMessage();
                if (nextTrack != null) {
                    String nTitle = nextTrack.getMusicTitle();
                    String nAuthor = nextTrack.getMusicAuthor();
                    if (!TextUtils.isEmpty(nAuthor)) {
                        nextTrackTitleView.setText(nTitle + " — " + nAuthor);
                    } else {
                        nextTrackTitleView.setText(!TextUtils.isEmpty(nTitle) ? nTitle : "—");
                    }
                    if (nextTrackDurationView != null) {
                        int dur = (int) nextTrack.getDuration();
                        nextTrackDurationView.setText(dur > 0 ? AndroidUtilities.formatShortDuration(dur) : "");
                    }
                } else {
                    nextTrackTitleView.setText("Конец очереди");
                    if (nextTrackDurationView != null) {
                        nextTrackDurationView.setText("");
                    }
                }
            }

            if (!sameMessageObject) {
                preloadNeighboringThumbs();
            }
        }
    }

    private void updateCover(MessageObject messageObject, boolean animated) {
        final BackupImageView imageView = animated ? coverContainer.getNextImageView() : coverContainer.getImageView();
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (animated) {
            coverContainer.switchImageViews();
        }
        if (audioInfo != null && audioInfo.getCover() != null) {
            imageView.setImageBitmap(audioInfo.getCover());
            currentFile = null;
            currentAudioFinishedLoading = true;
        } else {
            // SG_STREAM треки хранят URL обложки в parts[6] — проверяем первым делом
            if (messageObject.messageOwner != null
                    && messageObject.messageOwner.message != null
                    && messageObject.messageOwner.message.startsWith("SG_STREAM|||")) {
                String[] sgParts = messageObject.messageOwner.message.split("\\|\\|\\|");
                if (sgParts.length >= 7 && !TextUtils.isEmpty(sgParts[6])) {
                    imageView.setImage(ImageLocation.getForPath(sgParts[6]), null, Theme.createRoundRectDrawable(0, Theme.getColor(Theme.key_graySection)), messageObject);
                    currentFile = null;
                    currentAudioFinishedLoading = true;
                    imageView.invalidate();
                    return;
                }
            }
            TLRPC.Document document = messageObject.getDocument();
            currentFile = FileLoader.getAttachFileName(document);
            currentAudioFinishedLoading = false;
            String artworkUrl = messageObject.getArtworkUrl(false);
            final ImageLocation thumbImageLocation = getArtworkThumbImageLocation(messageObject);
            if (!TextUtils.isEmpty(artworkUrl)) {
                imageView.setImage(ImageLocation.getForPath(artworkUrl), null, thumbImageLocation, null, null, 0, 1,
                        messageObject);
            } else if (thumbImageLocation != null) {
                imageView.setImage(null, null, thumbImageLocation, null, null, 0, 1, messageObject);
            } else {
                imageView.setImageDrawable(null);
            }
            imageView.invalidate();
        }
    }

    private ImageLocation getArtworkThumbImageLocation(MessageObject messageObject) {
        // SG_STREAM: обложка хранится в parts[6]
        if (messageObject.messageOwner != null
                && messageObject.messageOwner.message != null
                && messageObject.messageOwner.message.startsWith("SG_STREAM|||")) {
            String[] sgParts = messageObject.messageOwner.message.split("\\|\\|\\|");
            if (sgParts.length >= 7 && !TextUtils.isEmpty(sgParts[6])) {
                return ImageLocation.getForPath(sgParts[6]);
            }
        }
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String smallArtworkUrl = messageObject.getArtworkUrl(true);
        if (smallArtworkUrl != null) {
            return ImageLocation.getForPath(smallArtworkUrl);
        }
        return null;
    }

    private void preloadNeighboringThumbs() {
        final MediaController mediaController = MediaController.getInstance();
        final List<MessageObject> playlist = mediaController.getPlaylist();
        if (playlist.size() <= 1) {
            return;
        }

        final List<MessageObject> neighboringItems = new ArrayList<>();
        final int playingIndex = mediaController.getPlayingMessageObjectNum();

        int nextIndex = playingIndex + 1;
        int prevIndex = playingIndex - 1;
        if (nextIndex >= playlist.size()) {
            nextIndex = 0;
        }
        if (nextIndex <= -1) {
            nextIndex = playlist.size() - 1;
        }
        if (prevIndex <= -1) {
            prevIndex = playlist.size() - 1;
        }
        if (prevIndex >= playlist.size()) {
            prevIndex = 0;
        }

        neighboringItems.add(playlist.get(nextIndex));
        if (nextIndex != prevIndex) {
            neighboringItems.add(playlist.get(prevIndex));
        }

        for (int i = 0, N = neighboringItems.size(); i < N; i++) {
            final MessageObject messageObject = neighboringItems.get(i);
            final ImageLocation thumbImageLocation = getArtworkThumbImageLocation(messageObject);
            if (thumbImageLocation != null) {
                if (thumbImageLocation.path != null) {
                    ImageLoader.getInstance().preloadArtwork(thumbImageLocation.path);
                } else {
                    FileLoader.getInstance(currentAccount).loadFile(thumbImageLocation, messageObject, null,
                            FileLoader.PRIORITY_LOW, 1);
                }
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context context;
        private ArrayList<MessageObject> searchResult = new ArrayList<>();
        private Runnable searchRunnable;

        public ListAdapter(Context context) {
            this.context = context;
        }

        private boolean listViewIsVisible;

        public void setup() {
            // Список треков всегда скрыт — плеер на весь экран
            listViewIsVisible = false;
            listView.setVisibility(View.GONE);
            listView.setTranslationY(AndroidUtilities.displaySize.y);
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            // Список треков всегда скрыт
            listViewIsVisible = false;
            listView.setVisibility(View.GONE);
            updatePlayerBackground();
            playerShadow.setVisibility(View.VISIBLE);
            if (playlist.size() > 1) {
                listView.setPadding(0, listView.getPaddingTop(), 0, dp(179 + 52));
            } else {
                listView.setPadding(0, listView.getPaddingTop(), 0, 0);
            }
            updateEmptyView();
        }

        @Override
        public int getItemCount() {
            if (searchWas) {
                return searchResult.size();
            }
            return playlist.size() > 1 ? playlist.size() : 0;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 1) {
                View paddingView = new View(context) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(
                                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.EXACTLY));
                    }
                };
                return new RecyclerListView.Holder(paddingView);
            } else {
                View view = new AudioPlayerCell(context,
                        MediaController.getInstance().currentPlaylistIsGlobalSearch()
                                ? AudioPlayerCell.VIEW_TYPE_GLOBAL_SEARCH
                                : AudioPlayerCell.VIEW_TYPE_DEFAULT,
                        resourcesProvider);
                return new RecyclerListView.Holder(view);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (padWithItem) {
                if (position == 0) {
                    final View paddingView = holder.itemView;
                    return;
                }
                position--;
            }
            final AudioPlayerCell cell = (AudioPlayerCell) holder.itemView;
            final MessageObject messageObject;
            final boolean needDivider;
            final View.OnTouchListener onReorderTouch;
            if (searchWas) {
                messageObject = searchResult.get(position);
                needDivider = position + 1 < searchResult.size();
            } else if (savedMusicList != null ? !SharedConfig.playOrderReversed : SharedConfig.playOrderReversed) {
                messageObject = playlist.get(position);
                needDivider = position + 1 < playlist.size();
            } else {
                messageObject = playlist.get(playlist.size() - position - 1);
                needDivider = (playlist.size() - position - 2) >= 0;
            }
            if (isMyList()) {
                onReorderTouch = (v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(listView.getChildViewHolder(cell));
                    }
                    return false;
                };
            } else {
                onReorderTouch = null;
            }
            cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
            cell.setMessageObject(messageObject, isMyList(),
                    isMyList() || noforwards || messageObject.getId() <= 0 ? null
                            : btn -> showOptions(cell, messageObject),
                    needDivider, onReorderTouch);
        }

        @Override
        public int getItemViewType(int i) {
            if (padWithItem && i == 0) {
                return 1;
            }
            return 0;
        }

        public void search(final String query) {
            if (searchRunnable != null) {
                Utilities.searchQueue.cancelRunnable(searchRunnable);
                searchRunnable = null;
            }
            if (query == null) {
                searchResult.clear();
                notifyDataSetChanged();
            } else {
                Utilities.searchQueue.postRunnable(searchRunnable = () -> {
                    searchRunnable = null;
                    processSearch(query);
                }, 300);
            }
        }

        private void processSearch(final String query) {
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<MessageObject> copy = new ArrayList<>(playlist);
                Utilities.searchQueue.postRunnable(() -> {
                    String search1 = query.trim().toLowerCase();
                    if (search1.length() == 0) {
                        updateSearchResults(new ArrayList<>(), query);
                        return;
                    }
                    String search2 = LocaleController.getInstance().getTranslitString(search1);
                    if (search1.equals(search2) || search2.length() == 0) {
                        search2 = null;
                    }
                    String[] search = new String[1 + (search2 != null ? 1 : 0)];
                    search[0] = search1;
                    if (search2 != null) {
                        search[1] = search2;
                    }

                    ArrayList<MessageObject> resultArray = new ArrayList<>();

                    for (int a = 0; a < copy.size(); a++) {
                        MessageObject messageObject = copy.get(a);
                        for (int b = 0; b < search.length; b++) {
                            String q = search[b];
                            String name = messageObject.getDocumentName();
                            if (name == null || name.length() == 0) {
                                continue;
                            }
                            name = name.toLowerCase();
                            if (name.contains(q)) {
                                resultArray.add(messageObject);
                                break;
                            }
                            TLRPC.Document document;
                            if (messageObject.type == MessageObject.TYPE_TEXT) {
                                document = messageObject.messageOwner.media.webpage.document;
                            } else {
                                document = messageObject.messageOwner.media.document;
                            }
                            boolean ok = false;
                            for (int c = 0; c < document.attributes.size(); c++) {
                                TLRPC.DocumentAttribute attribute = document.attributes.get(c);
                                if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                                    if (attribute.performer != null) {
                                        ok = attribute.performer.toLowerCase().contains(q);
                                    }
                                    if (!ok && attribute.title != null) {
                                        ok = attribute.title.toLowerCase().contains(q);
                                    }
                                    break;
                                }
                            }
                            if (ok) {
                                resultArray.add(messageObject);
                                break;
                            }
                        }
                    }

                    updateSearchResults(resultArray, query);
                });
            });
        }

        private void updateSearchResults(final ArrayList<MessageObject> documents, String query) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!searching) {
                    return;
                }
                searchWas = true;
                searchResult = documents;
                notifyDataSetChanged();
                layoutManager.scrollToPosition(0);
                emptySubtitleTextView.setText(AndroidUtilities.replaceTags(LocaleController
                        .formatString("NoAudioFoundPlayerInfo", R.string.NoAudioFoundPlayerInfo, query)));
            });
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            EditTextBoldCursor editText = searchItem.getSearchField();
            editText.setCursorColor(getThemedColor(Theme.key_player_actionBarTitle));

            repeatButton.setIconColor(getThemedColor((Integer) repeatButton.getTag()));
            Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_listSelector), true);

            optionsButton.setIconColor(getThemedColor(Theme.key_player_button));
            Theme.setSelectorDrawableColor(optionsButton.getBackground(), getThemedColor(Theme.key_listSelector), true);

            if (forwardButton != null) {
                forwardButton.setTextColor(getThemedColor(Theme.key_player_button));
                if (Build.VERSION.SDK_INT >= 21) {
                    forwardButton.setBackgroundDrawable(Theme.createSelectorDrawable(
                            getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(24)));
                }
            }
            if (backwardButton != null) {
                backwardButton.setTextColor(getThemedColor(Theme.key_player_button));
                if (Build.VERSION.SDK_INT >= 21) {
                    backwardButton.setBackgroundDrawable(Theme.createSelectorDrawable(
                            getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(24)));
                }
            }

            progressView.setBackgroundColor(soundgram.ui.SoundGramDesignTokens.getPlaylistCardColor());
            progressView.setProgressColor(getThemedColor(Theme.key_player_progress));

            updateSubMenu();
            repeatButton.redrawPopup(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));

            optionsButton.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), false);
            optionsButton.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), true);
            optionsButton.redrawPopup(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
        };

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null,
                Theme.key_dialogBackground));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null,
                delegate, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null,
                null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBTITLECOLOR, null, null, null,
                null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null,
                null, Theme.key_player_actionBarSelector));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null,
                Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null,
                null, null, Theme.key_player_time));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inLoader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_outLoader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inLoaderSelected));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inMediaIcon));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inMediaIconSelected));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inAudioSelectedProgress));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { AudioPlayerCell.class }, null, null, null,
                Theme.key_chat_inAudioProgress));

        themeDescriptions.add(new ThemeDescription(containerView, 0, null, null, new Drawable[] { shadowDrawable },
                null, Theme.key_dialogBackground));

        themeDescriptions.add(
                new ThemeDescription(progressView, 0, null, null, null, null, Theme.key_player_progressBackground));
        themeDescriptions.add(new ThemeDescription(progressView, 0, null, null, null, null, Theme.key_player_progress));
        themeDescriptions
                .add(new ThemeDescription(seekBarView, 0, null, null, null, null, Theme.key_player_progressBackground));
        themeDescriptions.add(new ThemeDescription(seekBarView, 0, null, null, null, null,
                Theme.key_player_progressCachedBackground));
        themeDescriptions.add(new ThemeDescription(seekBarView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null,
                null, Theme.key_player_progress));

        themeDescriptions.add(new ThemeDescription(playbackSpeedButton,
                ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null,
                Theme.key_inappPlayerPlayPause));
        themeDescriptions.add(new ThemeDescription(playbackSpeedButton,
                ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null,
                Theme.key_inappPlayerClose));

        themeDescriptions
                .add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_player_button));
        themeDescriptions
                .add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_player_buttonActive));
        themeDescriptions
                .add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate,
                Theme.key_actionBarDefaultSubmenuItem));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate,
                Theme.key_actionBarDefaultSubmenuBackground));
        themeDescriptions
                .add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_player_button));
        themeDescriptions
                .add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate,
                Theme.key_actionBarDefaultSubmenuItem));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate,
                Theme.key_actionBarDefaultSubmenuBackground));

        themeDescriptions.add(new ThemeDescription(prevButton, 0, null,
                new RLottieDrawable[] { prevButton.getAnimatedDrawable() }, "Triangle 3", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton, 0, null,
                new RLottieDrawable[] { prevButton.getAnimatedDrawable() }, "Triangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton, 0, null,
                new RLottieDrawable[] { prevButton.getAnimatedDrawable() }, "Rectangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton,
                ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null,
                Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(playButton, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null,
                Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(playButton,
                ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null,
                Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(nextButton, 0, null,
                new RLottieDrawable[] { nextButton.getAnimatedDrawable() }, "Triangle 3", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton, 0, null,
                new RLottieDrawable[] { nextButton.getAnimatedDrawable() }, "Triangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton, 0, null,
                new RLottieDrawable[] { nextButton.getAnimatedDrawable() }, "Rectangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton,
                ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null,
                Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(forwardButton, ThemeDescription.FLAG_TEXTCOLOR, null, null, null,
                delegate, Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(forwardButton,
                ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null,
                delegate, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(backwardButton, ThemeDescription.FLAG_TEXTCOLOR, null, null, null,
                delegate, Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(backwardButton,
                ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null,
                delegate, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(playerLayout, ThemeDescription.FLAG_BACKGROUND, null, null, null,
                null, Theme.key_player_background));

        themeDescriptions.add(new ThemeDescription(playerShadow, ThemeDescription.FLAG_BACKGROUND, null, null, null,
                null, Theme.key_dialogShadowLine));

        themeDescriptions.add(new ThemeDescription(emptyImageView, ThemeDescription.FLAG_IMAGECOLOR, null, null, null,
                null, Theme.key_dialogEmptyImage));
        themeDescriptions.add(new ThemeDescription(emptyTitleTextView, ThemeDescription.FLAG_IMAGECOLOR, null, null,
                null, null, Theme.key_dialogEmptyText));
        themeDescriptions.add(new ThemeDescription(emptySubtitleTextView, ThemeDescription.FLAG_IMAGECOLOR, null, null,
                null, null, Theme.key_dialogEmptyText));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null,
                null, Theme.key_dialogScrollGlow));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null,
                Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[] { View.class }, Theme.dividerPaint, null,
                null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(progressView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null,
                null, Theme.key_emptyListPlaceholder));
        themeDescriptions.add(new ThemeDescription(progressView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null,
                null, Theme.key_progressCircle));

        themeDescriptions.add(new ThemeDescription(durationTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null,
                null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(timeTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null,
                null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(titleTextView.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null,
                null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(titleTextView.getNextTextView(), ThemeDescription.FLAG_TEXTCOLOR,
                null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(authorTextView.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null,
                null, null, null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(authorTextView.getNextTextView(), ThemeDescription.FLAG_TEXTCOLOR,
                null, null, null, null, Theme.key_player_time));

        themeDescriptions.add(new ThemeDescription(containerView, 0, null, null, null, null, Theme.key_sheet_scrollUp));

        return themeDescriptions;
    }

    private void saveToProfile(MessageObject messageObject, boolean save, Runnable done, boolean triedFileRef) {
        if (blockAudiusFileAction(messageObject)) return;
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return;
        }
        final long documentId = document.id;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = documentId;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference;
        if (req.id.file_reference == null) {
            req.id.file_reference = new byte[0];
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text)) {
                if (triedFileRef || messageObject.getId() < 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                .showForError(err);
                    });
                    return;
                }
                if (messageObject.getDialogId() >= 0) {
                    final int msg_id = messageObject.getId();
                    final TLRPC.TL_messages_getMessages fileRefReq = new TLRPC.TL_messages_getMessages();
                    fileRefReq.id.add(msg_id);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                        if (res1 instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res1;
                            TLRPC.Message message = null;
                            for (int i = 0; i < r.messages.size(); ++i) {
                                if (r.messages.get(i).id == msg_id) {
                                    message = r.messages.get(i);
                                    break;
                                }
                            }
                            if (message != null) {
                                saveToProfile(new MessageObject(currentAccount, message, false, true), save, done,
                                        true);
                            } else {
                                AndroidUtilities.runOnUIThread(() -> {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                            .createErrorBulletin(LocaleController.formatString(
                                                    R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND"))
                                            .show();
                                });
                            }
                        } else if (err1 != null) {
                            AndroidUtilities.runOnUIThread(() -> {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .showForError(err1);
                            });
                        }
                    });
                } else {
                    final int msg_id = messageObject.getId();
                    final TLRPC.TL_channels_getMessages fileRefReq = new TLRPC.TL_channels_getMessages();
                    fileRefReq.channel = MessagesController.getInstance(currentAccount)
                            .getInputChannel(-messageObject.getDialogId());
                    fileRefReq.id.add(msg_id);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                        if (res1 instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res1;
                            TLRPC.Message message = null;
                            for (int i = 0; i < r.messages.size(); ++i) {
                                if (r.messages.get(i).id == msg_id) {
                                    message = r.messages.get(i);
                                    break;
                                }
                            }
                            if (message != null) {
                                saveToProfile(new MessageObject(currentAccount, message, false, true), save, done,
                                        true);
                            } else {
                                AndroidUtilities.runOnUIThread(() -> {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                            .createErrorBulletin(LocaleController.formatString(
                                                    R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND"))
                                            .show();
                                });
                            }
                        } else if (err1 != null) {
                            AndroidUtilities.runOnUIThread(() -> {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .showForError(err1);
                            });
                        }
                    });
                }
                return;
            } else if (err != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                            .showForError(err);
                });
            }

            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(currentAccount)
                        .getSavedMusicIds()
                        .update(documentId, save);
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                final TLRPC.UserFull userInfo = MessagesController.getInstance(currentAccount).getUserFull(selfId);
                if (userInfo != null) {
                    if (save) {
                        userInfo.flags2 |= TLObject.FLAG_21;
                        userInfo.saved_music = document;
                    } else if (userInfo.saved_music != null && userInfo.saved_music.id == documentId) {
                        userInfo.flags2 &= ~TLObject.FLAG_21;
                        userInfo.saved_music = null;
                    }
                    MessagesStorage.getInstance(currentAccount).updateUserInfo(userInfo, true);
                    NotificationCenter.getInstance(currentAccount)
                            .postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
                }
                if (done != null) {
                    done.run();
                }
            });
        });
    }

    private void showMenuOptions(View v) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null) {
            return;
        }

        final ItemOptions o = ItemOptions.makeOptions(container, resourcesProvider, v, true);
        ItemOptions o2 = buildSaveOptions(o, messageObject);
        if (!isMyList()) {
            o.addIf(!noforwards, R.drawable.msg_stories_save, getString(R.string.AudioSaveTo), () -> o.openSwipeback(o2));
            if (!noforwards && o.getLast() != null)
                o.getLast().setRightIcon(R.drawable.msg_arrowright);
            o.addGap();
        }

        o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
            o.dismiss();
            onSubItemClick(1);
        });
        o.addIf(!noforwards, R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
            o.dismiss();
            onSubItemClick(2);
        });
        o.addIf(messageObject.getId() > 0, R.drawable.msg_message, getString(R.string.ShowInChat), () -> {
            o.dismiss();
            onSubItemClick(4);
        });
        if (castAvailable) {
            castItem = o.add();
            castItem.setTextAndIcon(getString(R.string.VideoPlayerChromecast), R.drawable.menu_video_chromecast);
            castItem.setOnClickListener(v2 -> {
                o.dismiss();
                onSubItemClick(6);
            });
            AndroidUtilities.removeFromParent(castItemButton);
            castItem.addView(castItemButton, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            updateColors();
        }
        o.addIf(isMyList(), R.drawable.msg_delete, getString(R.string.ProfilePlaylistRemoveFromProfile), true, () -> {
            o.dismiss();
            onSubItemClick(7);
        });
        o.setTranslationY(dp(64));
        o.show();
    }

    private ItemOptions buildSaveOptions(ItemOptions o, MessageObject messageObject) {
        final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount).getSavedMusicIds();
        final TLRPC.Document document = messageObject.getDocument();
        final long documentId = document != null ? document.id : 0;

        final ItemOptions o2 = o.makeSwipeback();
        o2.add(R.drawable.ic_ab_back, getString(R.string.Back), o::closeSwipeback);
        o2.addGap();
        o2.addIf(!musicIds.ids.contains(documentId), R.drawable.left_status_profile, getString(R.string.AudioSaveToMyProfile), () -> {
            saveToProfile(messageObject, true, () -> {
                setVisibleInProfile(true);
                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToMyProfileSaved))
                        .show();
                o.dismiss();
            }, false);
        });
        o2.add(R.drawable.msg_saved, getString(R.string.AudioSaveToSavedMessages), () -> {
            forward(messageObject, UserConfig.getInstance(currentAccount).getClientUserId());
            o.dismiss();

            BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                    .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToSavedMessagesSaved))
                    .show();
        });
        o2.add(R.drawable.menu_download_round, getString(R.string.AudioSaveToMusicFolder), () -> {
            saveToMusic(messageObject);
            o.dismiss();
        });
        o2.addGap();
        o2.addText(getString(R.string.AudioSaveToInfo), 12, dp(200));
        return o2;
    }
    private void showOptions(AudioPlayerCell cell, MessageObject messageObject) {
        final ItemOptions o = ItemOptions.makeOptions(container, resourcesProvider, cell, true);

        if (isMyList()) {
            o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
                o.dismiss();
                forward(messageObject);
            });
            o.addIf(!noforwards, R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
                o.dismiss();
                share(messageObject);
            });
            o.add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> {
                saveToProfile(messageObject, false, () -> {
                    savedMusicList.remove(messageObject);
                    playlist.remove(messageObject);
                    listAdapter.notifyDataSetChanged();
                    o.dismiss();

                    setVisibleInProfile(false);
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                            .createSimpleBulletin(R.raw.ic_delete, getString(R.string.AudioSaveToMyProfileUnsaved))
                            .show();
                }, false);
            });
        } else {
            final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount)
                    .getSavedMusicIds();
            final TLRPC.Document document = messageObject.getDocument();
            final long documentId = document != null ? document.id : 0;

            final ItemOptions o2 = o.makeSwipeback();
            o2.add(R.drawable.ic_ab_back, getString(R.string.Back), o::closeSwipeback);
            o2.addGap();
            o2.addIf(!musicIds.ids.contains(documentId), R.drawable.left_status_profile,
                    getString(R.string.AudioSaveToMyProfile), () -> {
                        saveToProfile(messageObject, true, () -> {
                            setVisibleInProfile(true);
                            BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                    .createSimpleBulletin(R.raw.saved_messages,
                                            getString(R.string.AudioSaveToMyProfileSaved))
                                    .show();
                            o.dismiss();
                        }, false);
                    });
            o2.add(R.drawable.msg_saved, getString(R.string.AudioSaveToSavedMessages), () -> {
                forward(messageObject, UserConfig.getInstance(currentAccount).getClientUserId());
                o.dismiss();

                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToSavedMessagesSaved))
                        .show();
            });
            o2.add(R.drawable.menu_download_round, getString(R.string.AudioSaveToMusicFolder), () -> {
                saveToMusic(messageObject);
                o.dismiss();
            });
            o2.addGap();
            o2.addText(getString(R.string.AudioSaveToInfo), 12, dp(200));

            o.addIf(!noforwards, R.drawable.msg_stories_save, getString(R.string.AudioSaveTo),
                    () -> o.openSwipeback(o2));
            if (!noforwards && o.getLast() != null)
                o.getLast().setRightIcon(R.drawable.msg_arrowright);

            o.addGap();
            o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
                o.dismiss();
                forward(messageObject);
            });
            o.addIf(!noforwards, R.drawable.msg_share, getString(R.string.ShareFile), () -> {
                o.dismiss();
                share(messageObject);
            });
            o.addIf(messageObject.getId() > 0, R.drawable.msg_view_file, getString(R.string.ShowInChat), () -> {
                if (UserConfig.selectedAccount != currentAccount) {
                    parentActivity.switchToAccount(currentAccount, true);
                }

                Bundle args = new Bundle();
                long did = messageObject.getDialogId();
                if (DialogObject.isEncryptedDialog(did)) {
                    args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    args.putLong("user_id", did);
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                    if (chat != null && chat.migrated_to != null) {
                        args.putLong("migrated_to", did);
                        did = -chat.migrated_to.channel_id;
                    }
                    args.putLong("chat_id", -did);
                }
                args.putInt("message_id", messageObject.getId());
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
                parentActivity.presentFragment(new ChatActivity(args), false, false);
                dismiss();
            });
        }

        o.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        o.show();
    }

    private void setVisibleInProfile(boolean visible) {
        if (isMyList() || noforwards) {
            saveToProfileButton.setVisibility(View.GONE);
            unsaveFromProfileTextView.setVisibility(View.GONE);
            return;
        }
        saveToProfileButton.setVisibility(View.VISIBLE);
        unsaveFromProfileTextView.setVisibility(View.VISIBLE);
        saveToProfileButton.animate()
                .alpha(visible ? 0.0f : 1.0f)
                .scaleX(visible ? 0.8f : 1.0f)
                .scaleY(visible ? 0.8f : 1.0f)
                .setDuration(420)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> {
                    saveToProfileButton.setVisibility(visible ? View.GONE : View.VISIBLE);
                })
                .start();
        unsaveFromProfileTextView.animate()
                .alpha(!visible ? 0.0f : 1.0f)
                .scaleX(!visible ? 0.8f : 1.0f)
                .scaleY(!visible ? 0.8f : 1.0f)
                .setDuration(420)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> {
                    unsaveFromProfileTextView.setVisibility(visible ? View.VISIBLE : View.GONE);
                })
                .start();
    }

    private void saveToMusic(MessageObject messageObject) {
        if (blockAudiusFileAction(messageObject)) return;
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE)
                && parentActivity.checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            parentActivity.requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, 4);
            return;
        }
        if (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.message != null && messageObject.messageOwner.message.startsWith("SG_STREAM|||")) {
            String[] parts = messageObject.messageOwner.message.split("\\|\\|\\|");
            String title = parts.length > 1 ? parts[1] : "Unknown";
            String artist = parts.length > 2 ? parts[2] : "Unknown";
            String streamUrl = parts.length > 3 ? parts[3] : "";
            File local = soundgram.music.ChannelMusicFragment.getLocalTrackFile(artist, title);
            if (local.exists() && local.length() > 1024) {
                MediaController.saveFile(local.getAbsolutePath(), parentActivity, 3, artist + " - " + title + ".mp3", "audio/mpeg",
                        uri -> BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                .createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
            } else {
                Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_downloading_in_background", R.string.sg_downloading_in_background), Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean ok = soundgram.music.MusicWebSearcher.downloadTrackToExactFileSync(streamUrl, local);
                    if (!ok) {
                        soundgram.music.MusicWebSearcher.WebTrack freshTrack = soundgram.music.MusicWebSearcher.resolveHitmosSync(title, artist);
                        if (freshTrack != null && !TextUtils.isEmpty(freshTrack.downloadUrl)) {
                            ok = soundgram.music.MusicWebSearcher.downloadTrackToExactFileSync(freshTrack.downloadUrl, local);
                        }
                    }
                    boolean finalOk = ok && local.exists() && local.length() > 1024;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (finalOk) {
                            MediaController.saveFile(local.getAbsolutePath(), parentActivity, 3, artist + " - " + title + ".mp3", "audio/mpeg",
                                    uri -> BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                            .createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
                        } else {
                            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString("sg_download_error", R.string.sg_download_error), Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            }
            return;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        if (TextUtils.isEmpty(fileName)) {
            fileName = messageObject.getFileName();
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && path.length() > 0) {
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (path == null || path.length() == 0) {
            path = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
        }
        MediaController.saveFile(path, parentActivity, 3, fileName,
                messageObject.getDocument() != null ? messageObject.getDocument().mime_type : "",
                uri -> BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
    }

    private void share(MessageObject messageObject) {
        if (blockAudiusFileAction(messageObject)) return;
        try {
            File f = null;
            boolean isVideo = false;

            if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
                f = new File(messageObject.messageOwner.attachPath);
                if (!f.exists()) {
                    f = null;
                }
            }
            if (f == null) {
                f = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            }

            if (f.exists()) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(messageObject.getMimeType());
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        intent.putExtra(Intent.EXTRA_STREAM,
                                FileProvider.getUriForFile(ApplicationLoader.applicationContext,
                                        ApplicationLoader.getApplicationId() + ".provider", f));
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignore) {
                        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                    }
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                }

                parentActivity.startActivityForResult(
                        Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.AppName));
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.setMessage(LocaleController.getString(R.string.PleaseDownload));
                builder.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void forward(MessageObject messageObject, long dialogId) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }
        if (fmessages != null) {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, dialogId, false, false,
                    true, 0, 0);
        } else {
            SendMessagesHelper.getInstance(currentAccount)
                    .sendMessage(SendMessagesHelper.SendMessageParams.of(document, null,
                            messageObject.messageOwner.attachPath, dialogId, null, null, null, null, null, null,
                            true, 0, 0, 0, savedMusicList, null, false,
                            false));
        }
        final BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment != null) {
            BulletinFactory.of(lastFragment)
                    .createSimpleBulletin(
                            R.raw.forward,
                            dialogId == UserConfig.getInstance(currentAccount).getClientUserId()
                                    ? LocaleController.getString(R.string.FwdMessageToSavedMessages)
                                    : dialogId > 0
                                            ? LocaleController.formatString(R.string.FwdMessageToUser,
                                                    DialogObject.getShortName(dialogId))
                                            : LocaleController.formatString(R.string.FwdMessageToGroup,
                                                    DialogObject.getShortName(dialogId)))
                    .show();
        }
    }

    private void forward(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        DialogsActivity fragment = new DialogsActivity(args);
        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }
        fragment.setDelegate(
                (fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                    if (dids.size() > 1
                            || dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId()
                            || message != null || fmessages == null) {
                        for (int a = 0; a < dids.size(); a++) {
                            long did = dids.get(a).dialogId;
                            if (message != null) {
                                SendMessagesHelper.getInstance(currentAccount)
                                        .sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did,
                                                null, null, null, true, null, null, null,
                                                true, 0, 0, null,
                                                false));
                            }
                            if (fmessages != null) {
                                SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, did, false, false,
                                        true, 0, 0);
                            } else {
                                SendMessagesHelper.getInstance(currentAccount)
                                        .sendMessage(SendMessagesHelper.SendMessageParams.of(document, null,
                                                messageObject.messageOwner.attachPath, did, null, null, null, null,
                                                null, null,
                                                notify,
                                                scheduleDate, 0, 0, savedMusicList, null, false, false));
                            }
                        }
                        fragment1.finishFragment();
                        final BaseFragment lastFragment = LaunchActivity.getLastFragment();
                        if (lastFragment != null) {
                            BulletinFactory.of(lastFragment)
                                    .createSimpleBulletin(
                                            R.raw.forward,
                                            dids.size() == 1 && dids.get(0).dialogId == UserConfig
                                                    .getInstance(currentAccount).getClientUserId()
                                                            ? LocaleController
                                                                    .getString(R.string.FwdMessageToSavedMessages)
                                                            : dids.size() == 1 && dids.get(0).dialogId > 0
                                                                    ? LocaleController.formatString(
                                                                            R.string.FwdMessageToUser,
                                                                            DialogObject
                                                                                    .getShortName(dids.get(0).dialogId))
                                                                    : dids.size() == 1 && dids.get(0).dialogId < 0
                                                                            ? LocaleController.formatString(
                                                                                    R.string.FwdMessageToGroup,
                                                                                    DialogObject.getShortName(
                                                                                            dids.get(0).dialogId))
                                                                            : LocaleController.formatPluralStringComma(
                                                                                    "FwdMessageToManyChats",
                                                                                    dids.size()))
                                    .show();
                        }
                    } else {
                        MessagesStorage.TopicKey topicKey = dids.get(0);
                        long did = topicKey.dialogId;
                        Bundle args1 = new Bundle();
                        args1.putBoolean("scrollToTopOnResume", true);
                        if (DialogObject.isEncryptedDialog(did)) {
                            args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                        } else if (DialogObject.isUserDialog(did)) {
                            args1.putLong("user_id", did);
                        } else {
                            args1.putLong("chat_id", -did);
                        }
                        ChatActivity chatActivity = new ChatActivity(args1);
                        if (topicKey.topicId != 0) {
                            ForumUtilities.applyTopic(chatActivity, topicKey);
                        }
                        if (parentActivity.presentFragment(chatActivity, true, false)) {
                            chatActivity.showFieldPanelForForward(true, fmessages);
                            if (topicKey.topicId != 0) {
                                fragment1.removeSelfFromStack();
                            }
                        } else {
                            fragment1.finishFragment();
                        }
                    }
                    return true;
                });
        parentActivity.presentFragment(fragment);
        dismiss();
    }

    private static abstract class CoverContainer extends FrameLayout {

        private final BackupImageView[] imageViews = new BackupImageView[2];

        private int activeIndex;
        private AnimatorSet animatorSet;

        public CoverContainer(@NonNull Context context) {
            super(context);
            for (int i = 0; i < 2; i++) {
                imageViews[i] = new BackupImageView(context);
                final int index = i;
                imageViews[i].getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
                    if (index == activeIndex) {
                        onImageUpdated(imageReceiver);
                    }
                });
                imageViews[i].setRoundRadius(dp(4), dp(4), dp(56), dp(4)); // асимметричные грани обложки (4 4 4 56 -> BL=56)
                if (i == 1) {
                    imageViews[i].setVisibility(GONE);
                }
                addView(imageViews[i], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
        }

        public final void switchImageViews() {
            if (animatorSet != null) {
                animatorSet.cancel();
            }
            animatorSet = new AnimatorSet();
            activeIndex = activeIndex == 0 ? 1 : 0;

            final BackupImageView prevImageView = imageViews[activeIndex == 0 ? 1 : 0];
            final BackupImageView currImageView = imageViews[activeIndex];

            final boolean hasBitmapImage = prevImageView.getImageReceiver().hasBitmapImage();

            currImageView.setAlpha(hasBitmapImage ? 1f : 0f);
            currImageView.setScaleX(0.8f);
            currImageView.setScaleY(0.8f);
            currImageView.setVisibility(VISIBLE);

            if (hasBitmapImage) {
                prevImageView.bringToFront();
            } else {
                prevImageView.setVisibility(GONE);
                prevImageView.setImageDrawable(null);
            }

            final ValueAnimator expandAnimator = ValueAnimator.ofFloat(0.8f, 1f);
            expandAnimator.setDuration(125);
            expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            expandAnimator.addUpdateListener(a -> {
                float animatedValue = (float) a.getAnimatedValue();
                currImageView.setScaleX(animatedValue);
                currImageView.setScaleY(animatedValue);
                if (!hasBitmapImage) {
                    currImageView.setAlpha(a.getAnimatedFraction());
                }
            });

            if (hasBitmapImage) {
                final ValueAnimator collapseAnimator = ValueAnimator.ofFloat(prevImageView.getScaleX(), 0.8f);
                collapseAnimator.setDuration(125);
                collapseAnimator.setInterpolator(CubicBezierInterpolator.EASE_IN);
                collapseAnimator.addUpdateListener(a -> {
                    float animatedValue = (float) a.getAnimatedValue();
                    prevImageView.setScaleX(animatedValue);
                    prevImageView.setScaleY(animatedValue);
                    final float fraction = a.getAnimatedFraction();
                    if (fraction > 0.25f && !currImageView.getImageReceiver().hasBitmapImage()) {
                        prevImageView.setAlpha(1f - (fraction - 0.25f) * (1f / 0.75f));
                    }
                });
                collapseAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        prevImageView.setVisibility(GONE);
                        prevImageView.setImageDrawable(null);
                        prevImageView.setAlpha(1f);
                    }
                });

                animatorSet.playSequentially(collapseAnimator, expandAnimator);
            } else {
                animatorSet.play(expandAnimator);
            }

            animatorSet.start();
        }

        public final BackupImageView getImageView() {
            return imageViews[activeIndex];
        }

        public final BackupImageView getNextImageView() {
            return imageViews[activeIndex == 0 ? 1 : 0];
        }

        public final ImageReceiver getImageReceiver() {
            return getImageView().getImageReceiver();
        }

        private final Path clipPath = new Path();
        private final RectF clipRect = new RectF();
        private final float[] radii = new float[8];

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            clipPath.reset();
            clipRect.set(0, 0, w, h);
            float rTL = dp(4);
            float rTR = dp(4);
            float rBR = dp(4);
            float rBL = dp(56);
            radii[0] = rTL; radii[1] = rTL;
            radii[2] = rTR; radii[3] = rTR;
            radii[4] = rBR; radii[5] = rBR;
            radii[6] = rBL; radii[7] = rBL;
            clipPath.addRoundRect(clipRect, radii, Path.Direction.CW);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(clipPath);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        protected abstract void onImageUpdated(ImageReceiver imageReceiver);
    }

    public abstract static class ClippingTextViewSwitcher extends FrameLayout {

        private final TextView[] textViews = new TextView[2];
        private final float[] clipProgress = new float[] { 0f, 0.75f };
        private final int gradientSize = dp(24);

        private final Matrix gradientMatrix;
        private final Paint gradientPaint;
        private final Paint erasePaint;

        private int activeIndex;
        private AnimatorSet animatorSet;
        private LinearGradient gradientShader;
        private int stableOffest = -1;
        private final RectF rectF = new RectF();
        private int rightPadding;

        public ClippingTextViewSwitcher(@NonNull Context context) {
            super(context);
            for (int i = 0; i < 2; i++) {
                textViews[i] = createTextView();
                if (i == 1) {
                    textViews[i].setAlpha(0f);
                    textViews[i].setVisibility(GONE);
                }
                addView(textViews[i], LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
            }
            gradientMatrix = new Matrix();
            gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        public void setTextColor(int color) {
            for (int i = 0; i < textViews.length; i++) {
                if (textViews[i] != null) {
                    textViews[i].setTextColor(color);
                }
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            gradientShader = new LinearGradient(gradientSize, 0, 0, 0, 0, 0xFF000000, Shader.TileMode.CLAMP);
            gradientPaint.setShader(gradientShader);
        }

        private boolean isCenter;

        public void setIsCenter() {
            isCenter = true;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (isCenter) {
                for (int a = 0; a < textViews.length; a++) {
                    View v = textViews[a];
                    if (v == null)
                        continue;
                    if (v.getMeasuredWidth() < getMeasuredWidth()) {
                        int l = (getMeasuredWidth() - v.getMeasuredWidth()) / 2;
                        v.layout(l, 0, l + v.getMeasuredWidth(), v.getMeasuredHeight());
                    }
                }
            }
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            final int index = child == textViews[0] ? 0 : 1;
            final boolean result;
            boolean hasStableRect = false;

            if (isCenter) {
                stableOffest = -1;
            }

            if (stableOffest > 0) {
                for (TextView tv : textViews) {
                    if (tv instanceof MarqueeTextView && ((MarqueeTextView) tv).isNeedMarquee()) {
                        stableOffest = -1;
                        break;
                    }
                }
            }

            if (stableOffest > 0 && textViews[activeIndex].getAlpha() != 1f
                    && textViews[activeIndex].getLayout() != null) {
                float x1 = textViews[activeIndex].getLayout().getPrimaryHorizontal(0);
                float x2 = textViews[activeIndex].getLayout().getPrimaryHorizontal(stableOffest);
                hasStableRect = true;
                if (x1 == x2) {
                    hasStableRect = false;
                } else if (x2 > x1) {
                    rectF.set(x1, 0, x2, getMeasuredHeight());
                } else {
                    rectF.set(x2, 0, x1, getMeasuredHeight());
                }

                if (hasStableRect && index == activeIndex) {
                    canvas.save();
                    canvas.clipRect(rectF);
                    textViews[0].draw(canvas);
                    canvas.restore();
                }
            }
            if (clipProgress[index] > 0f || hasStableRect) {
                final int width = Math.min(child.getWidth(), getWidth()); // - rightPadding;
                final int height = Math.min(child.getHeight(), getHeight());
                final int saveCount = canvas.saveLayer(0, 0, width, height, null, Canvas.ALL_SAVE_FLAG);
                result = super.drawChild(canvas, child, drawingTime);
                final float gradientStart = width * (1f - clipProgress[index]);
                final float gradientEnd = gradientStart + gradientSize;
                gradientMatrix.setTranslate(gradientStart, 0);
                gradientShader.setLocalMatrix(gradientMatrix);
                canvas.drawRect(gradientStart, 0, gradientEnd, height, gradientPaint);
                if (width > gradientEnd) {
                    canvas.drawRect(gradientEnd, 0, width, height, erasePaint);
                }
                if (hasStableRect) {
                    canvas.drawRect(rectF, erasePaint);
                }
                canvas.restoreToCount(saveCount);
            } else {
                result = super.drawChild(canvas, child, drawingTime);
            }
            return result;
        }

        public void setText(CharSequence text) {
            setText(text, true);
        }

        public void setText(CharSequence text, boolean animated) {
            final CharSequence currentText = textViews[activeIndex].getText();

            if (TextUtils.isEmpty(currentText) || !animated) {
                textViews[activeIndex].setText(text);
                return;
            } else if (TextUtils.equals(text, currentText)) {
                return;
            }

            stableOffest = 0;
            int n = Math.min(text.length(), currentText.length());
            for (int i = 0; i < n; i++) {
                if (text.charAt(i) != currentText.charAt(i)) {
                    break;
                }
                stableOffest++;
            }
            if (stableOffest <= 3) {
                stableOffest = -1;
            }

            final int index = activeIndex == 0 ? 1 : 0;
            final int prevIndex = activeIndex;
            activeIndex = index;

            if (animatorSet != null) {
                animatorSet.cancel();
            }
            animatorSet = new AnimatorSet();
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    textViews[prevIndex].setVisibility(GONE);
                }
            });

            textViews[index].setText(text);
            textViews[index].bringToFront();
            textViews[index].setVisibility(VISIBLE);

            final int duration = 300;

            final ValueAnimator collapseAnimator = ValueAnimator.ofFloat(clipProgress[prevIndex], 0.75f);
            collapseAnimator.setDuration(duration / 3 * 2); // 0.66
            collapseAnimator.addUpdateListener(a -> {
                clipProgress[prevIndex] = (float) a.getAnimatedValue();
                invalidate();
            });

            final ValueAnimator expandAnimator = ValueAnimator.ofFloat(clipProgress[index], 0f);
            expandAnimator.setStartDelay(duration / 3); // 0.33
            expandAnimator.setDuration(duration / 3 * 2); // 0.66
            expandAnimator.addUpdateListener(a -> {
                clipProgress[index] = (float) a.getAnimatedValue();
                invalidate();
            });

            final ObjectAnimator fadeOutAnimator = ObjectAnimator.ofFloat(textViews[prevIndex], View.ALPHA, 0f);
            fadeOutAnimator.setStartDelay(duration / 4); // 0.25
            fadeOutAnimator.setDuration(duration / 2); // 0.5

            final ObjectAnimator fadeInAnimator = ObjectAnimator.ofFloat(textViews[index], View.ALPHA, 1f);
            fadeInAnimator.setStartDelay(duration / 4); // 0.25
            fadeInAnimator.setDuration(duration / 2); // 0.5

            animatorSet.playTogether(collapseAnimator, expandAnimator, fadeOutAnimator, fadeInAnimator);
            animatorSet.start();
        }

        public TextView getTextView() {
            return textViews[activeIndex];
        }

        public TextView getNextTextView() {
            return textViews[activeIndex == 0 ? 1 : 0];
        }

        public int getCustomPaddingRight() {
            return rightPadding;
        }

        public void setCustomPaddingRight(int padding) {
            rightPadding = padding;
            for (TextView tv : textViews) {
                if (tv instanceof MarqueeTextView) {
                    ((MarqueeTextView) tv).setCustomPaddingRight(padding);
                }
            }
            invalidate();
        }

        protected abstract TextView createTextView();
    }

    private ValueAnimator rightPaddingAnimator;

    private void setCustomPaddingRight(int padding, boolean animated) {
        if (rightPaddingAnimator != null) {
            rightPaddingAnimator.cancel();
            rightPaddingAnimator = null;
        }
        if (titleTextView.getCustomPaddingRight() == padding) {
            return;
        }

        if (!animated) {
            titleTextView.setCustomPaddingRight(padding);
            authorTextView.setCustomPaddingRight(padding);
            return;
        }

        rightPaddingAnimator = ValueAnimator.ofInt(titleTextView.getCustomPaddingRight(), padding);
        if (padding == 0) {
            rightPaddingAnimator.setStartDelay(200L);
            rightPaddingAnimator.setDuration(100L);
        } else {
            rightPaddingAnimator.setDuration(200L);
        }
        rightPaddingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        rightPaddingAnimator.addUpdateListener(animation -> {
            titleTextView.setCustomPaddingRight((int) animation.getAnimatedValue());
            authorTextView.setCustomPaddingRight((int) animation.getAnimatedValue());
        });
        rightPaddingAnimator.start();
    }

    private void toggleLyrics() {
        if (lyricsAnimator != null)
            lyricsAnimator.cancel();
        isLyricsVisible = !isLyricsVisible;

        if (lyricsChip != null) {
            lyricsChip.setText(isLyricsVisible ? "■  обложка" : "≡  текст");
            lyricsChip.bringToFront();
        }

        if (isLyricsVisible) {
            lyricsContainer.setVisibility(View.VISIBLE);
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null)
                fetchLyrics(playing);
        }

        lyricsAnimator = ValueAnimator.ofFloat(isLyricsVisible ? 0f : 1f, isLyricsVisible ? 1f : 0f);
        lyricsAnimator.setDuration(250);
        lyricsAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            lyricsContainer.setAlpha(alpha);

            // Плавно скрываем обложку и рамку
            if (coverContainer != null)
                coverContainer.setAlpha(1f - alpha);
            if (borderView != null)
                borderView.setAlpha(1f - alpha);
        });
        lyricsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isLyricsVisible) {
                    lyricsContainer.setVisibility(View.GONE);
                    if (coverContainer != null) {
                        coverContainer.setVisibility(View.VISIBLE);
                        coverContainer.setAlpha(1f);
                    }
                    if (borderView != null) {
                        borderView.setVisibility(View.VISIBLE);
                        borderView.setAlpha(1f);
                    }
                } else {
                    if (coverContainer != null)
                        coverContainer.setVisibility(View.INVISIBLE);
                    if (borderView != null)
                        borderView.setVisibility(View.INVISIBLE);
                }
                if (lyricsChip != null) {
                    lyricsChip.bringToFront();
                }
            }
        });
        lyricsAnimator.start();
    }

    private static final java.util.HashMap<String, ArrayList<LrcLine>> lyricsInMemoryCache = new java.util.HashMap<>();

    private static class LyricsSearchResult {
        String synced = "";
        String plain = "";
        int score = -1;
    }

    private void fetchLyrics(MessageObject msg) {
        final int reqId = ++currentLyricsReqId; // Фиксируем номер текущего запроса

        String title = msg.getMusicTitle() != null ? msg.getMusicTitle() : "";
        String artist = msg.getMusicAuthor() != null ? msg.getMusicAuthor() : "";

        // Убираем расширения файлов (.mp3, .flac, .ogg, .m4a, .wav) — когда нет ID3 тегов,
        // getMusicTitle() возвращает имя файла
        title = title.replaceAll("(?i)\\.(mp3|flac|ogg|m4a|wav|aac|opus|wma)$", "").trim();

        // Пытаемся вытащить артиста и название из формата "Artist - Title" в имени файла
        if ((TextUtils.isEmpty(artist) || artist.equals(getString(R.string.AudioUnknownArtist)))
                && title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            artist = parts[0].trim();
            title = parts[1].trim();
        }

        FileLog.d("fetchLyrics: artist=\"" + artist + "\", title=\"" + title + "\"");

        String cleanArtist = artist.replaceAll("(?i)(feat\\.|ft\\.|&|,).*$", "").trim();
        String cleanTitle = title.replaceAll("(?i)(\\(.*?\\)|\\[.*?\\]|-.*?(remix|edit|mix)).*$", "").trim();
        if (TextUtils.isEmpty(cleanArtist)) cleanArtist = artist;
        if (TextUtils.isEmpty(cleanTitle)) cleanTitle = title;

        final String finalArtist = artist;
        final String finalTitle = title;
        final String finalCleanArtist = cleanArtist;
        final String finalCleanTitle = cleanTitle;
        final String cacheKey = (finalCleanArtist + "|" + finalCleanTitle).toLowerCase();

        FileLog.d("fetchLyrics: searching for cleanArtist=\"" + finalCleanArtist + "\", cleanTitle=\"" + finalCleanTitle + "\"");

        // ФИКС МЕРЦАНИЯ: если в памяти есть кеш — применяем НЕМЕДЛЕННО, без reset и
        // без placeholder "поиск текста". Никакого мерцания.
        if (lyricsInMemoryCache.containsKey(cacheKey)) {
            ArrayList<LrcLine> cached = lyricsInMemoryCache.get(cacheKey);
            isFetchingLyrics = false;
            lyricsLines.clear();
            lyricsLines.addAll(cached);
            if (lyricsView != null) lyricsView.reset();
            jumpToCurrentLyric();
            return;
        }

        // Не в кеше — показываем "поиск текста" и запускаем фоновый поток
        isFetchingLyrics = true;
        lyricsLines.clear();
        lyricsLines.add(new LrcLine(0, getString(R.string.sg_lyrics_searching)));
        currentLyricIndex = 0;

        if (lyricsView != null) {
            lyricsView.reset();
        }

        new Thread(() -> {
            try {
                File textDir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "Texts");
                if (!textDir.exists()) textDir.mkdirs();
                File lyricsFile = new File(textDir, finalCleanArtist + " - " + finalCleanTitle + ".lrc");

                if (lyricsFile.exists()) {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(lyricsFile))) {
                        String fileLine;
                        while ((fileLine = br.readLine()) != null) sb.append(fileLine).append("\n");
                    }
                    String content = sb.toString();
                    if (content.contains("NOT_FOUND") || content.trim().isEmpty()) {
                        // Удаляем устаревший файл "не найдено" и ищем заново в сети
                        //noinspection ResultOfMethodCallIgnored
                        lyricsFile.delete();
                        // Продолжаем выполнение — переходим к сетевому поиску ниже
                    } else {
                        ArrayList<LrcLine> parsed = new ArrayList<>();
                        parseLrcIntoList(content, parsed);
                        if (!parsed.isEmpty()) {
                            lyricsInMemoryCache.put(cacheKey, parsed);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (reqId != currentLyricsReqId) return; // Защита
                                isFetchingLyrics = false;
                                lyricsLines.clear();
                                lyricsLines.addAll(parsed);
                                jumpToCurrentLyric();
                            });
                            return;
                        }
                    }
                }

                // Сетевой запрос
                double trackDuration = msg.getDuration();
                LyricsSearchResult resultObj = searchLyricsLrcLib(finalCleanArtist, finalCleanTitle, trackDuration);
                if (resultObj == null && (!finalCleanTitle.equals(finalTitle) || !finalCleanArtist.equals(finalArtist))) {
                    resultObj = searchLyricsLrcLib(finalArtist, finalTitle, trackDuration);
                }

                if (reqId != currentLyricsReqId) return; // Защита перед применением UI

                String foundSynced = resultObj != null ? resultObj.synced : "";
                String foundPlain = resultObj != null ? resultObj.plain : "";

                if ("null".equals(foundSynced)) foundSynced = "";
                if ("null".equals(foundPlain)) foundPlain = "";

                String toUse = !TextUtils.isEmpty(foundSynced) ? foundSynced : foundPlain;

                if (TextUtils.isEmpty(toUse) || toUse.equals("null")) {
                    showLyricsError(reqId, cacheKey);
                    return;
                }

                try (java.io.FileWriter fw = new java.io.FileWriter(lyricsFile)) {
                    fw.write(toUse);
                } catch (Exception ignored) {}

                ArrayList<LrcLine> result = new ArrayList<>();
                if (!TextUtils.isEmpty(foundSynced)) parseLrcIntoList(foundSynced, result);
                if (result.isEmpty() && !TextUtils.isEmpty(foundPlain)) {
                    for (String l : foundPlain.split("\n")) {
                        if (!l.trim().isEmpty()) result.add(new LrcLine(-1, l.trim()));
                    }
                }

                if (!result.isEmpty()) {
                    lyricsInMemoryCache.put(cacheKey, result);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (reqId != currentLyricsReqId) return;
                        isFetchingLyrics = false;
                        lyricsLines.clear();
                        lyricsLines.addAll(result);
                        jumpToCurrentLyric();
                    });
                } else {
                    showLyricsError(reqId, cacheKey);
                }

            } catch (Exception e) {
                FileLog.e(e);
                showLyricsError(reqId, cacheKey);
            }
        }).start();
    }

    private String makeHttpGet(String urlString) {
        // Стратегия 1: напрямую без прокси (Proxy.NO_PROXY) — обходим WS/SOCKS прокси Telegram
        try {
            String result = makeHttpGetInternal(urlString, false);
            if (result != null) return result;
        } catch (Exception e) {
            FileLog.d("makeHttpGet: direct attempt failed: " + e.getMessage());
        }

        // Стратегия 2: напрямую с trust-all SSL (VPN ломает TLS)
        try {
            String result = makeHttpGetInternal(urlString, true);
            if (result != null) return result;
        } catch (Exception e) {
            FileLog.d("makeHttpGet: trust-all attempt failed: " + e.getMessage());
        }

        // Стратегия 3: через системный прокси (помогает при VPN)
        try {
            java.net.URL url = new java.net.URL(urlString);
            java.net.ProxySelector selector = java.net.ProxySelector.getDefault();
            java.util.List<java.net.Proxy> proxies = selector != null
                    ? selector.select(url.toURI()) : null;
            java.net.Proxy proxy = (proxies != null && !proxies.isEmpty())
                    ? proxies.get(0) : java.net.Proxy.NO_PROXY;
            if (proxy != java.net.Proxy.NO_PROXY) {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection(proxy);
                conn.setRequestProperty("User-Agent", "SoundGram/1.0 (https://github.com/nicegram)");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                    is.close();
                    return bos.toString("UTF-8");
                }
                FileLog.d("makeHttpGet: system proxy returned code " + code + " for " + urlString);
            }
        } catch (Exception e) {
            FileLog.d("makeHttpGet: system proxy attempt failed: " + e.getMessage());
        }

        FileLog.d("makeHttpGet: all attempts failed for " + urlString);
        return null;
    }

    private String makeHttpGetInternal(String urlString, boolean trustAll) throws Exception {
        java.net.URL url = new java.net.URL(urlString);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);

        if (trustAll && conn instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }
            }, new java.security.SecureRandom());
            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier((h, s) -> true);
        }

        conn.setRequestProperty("User-Agent", "SoundGram/1.0 (https://github.com/nicegram)");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            FileLog.d("makeHttpGet: code " + code + " for " + urlString);
            conn.disconnect();
            return null;
        }
        java.io.InputStream is = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        is.close();
        conn.disconnect();
        return bos.toString("UTF-8");
    }

    private LyricsSearchResult parseSearchResponse(String response, double trackDuration) {
        if (TextUtils.isEmpty(response) || !response.startsWith("[")) {
            return null;
        }
        try {
            org.json.JSONArray json = new org.json.JSONArray(response);
            if (json.length() == 0) {
                return null;
            }

            org.json.JSONObject bestMatch = null;
            for (int i = 0; i < json.length(); i++) {
                org.json.JSONObject item = json.getJSONObject(i);
                if (item.optBoolean("instrumental", false)) {
                    continue;
                }

                if (bestMatch == null) {
                    bestMatch = item;
                }

                double duration = item.optDouble("duration", 0);
                if (trackDuration > 0 && Math.round(duration) == Math.round(trackDuration)) {
                    bestMatch = item;
                    break;
                }
            }

            if (bestMatch != null) {
                LyricsSearchResult result = new LyricsSearchResult();
                result.synced = bestMatch.optString("syncedLyrics", "");
                result.plain = bestMatch.optString("plainLyrics", "");
                if ("null".equals(result.synced)) result.synced = "";
                if ("null".equals(result.plain)) result.plain = "";
                if (!TextUtils.isEmpty(result.synced) || !TextUtils.isEmpty(result.plain)) {
                    return result;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private LyricsSearchResult searchLyricsLrcLib(String artistName, String trackName, double trackDuration) {
        try {
            // 1. Precise GET endpoint
            String encodedParams = "artist_name=" + java.net.URLEncoder.encode(artistName, "UTF-8")
                    + "&track_name=" + java.net.URLEncoder.encode(trackName, "UTF-8");
            if (trackDuration > 0) {
                encodedParams += "&duration=" + Math.round(trackDuration);
            }

            String response = makeHttpGet("https://lrclib.net/api/get?" + encodedParams);
            if (!TextUtils.isEmpty(response)) {
                org.json.JSONObject obj = new org.json.JSONObject(response);
                if (!obj.optBoolean("instrumental", false)) {
                    LyricsSearchResult result = new LyricsSearchResult();
                    result.synced = obj.optString("syncedLyrics", "");
                    result.plain = obj.optString("plainLyrics", "");
                    if ("null".equals(result.synced)) result.synced = "";
                    if ("null".equals(result.plain)) result.plain = "";
                    if (!TextUtils.isEmpty(result.synced) || !TextUtils.isEmpty(result.plain)) {
                        return result;
                    }
                }
            }

            // 2. Structured search fallback
            String searchParams = "artist_name=" + java.net.URLEncoder.encode(artistName, "UTF-8")
                    + "&track_name=" + java.net.URLEncoder.encode(trackName, "UTF-8");
            response = makeHttpGet("https://lrclib.net/api/search?" + searchParams);
            LyricsSearchResult searchResult = parseSearchResponse(response, trackDuration);
            if (searchResult != null) {
                return searchResult;
            }

            // 3. General query fallback
            String query = artistName + " - " + trackName;
            String encodedQuery = "q=" + java.net.URLEncoder.encode(query, "UTF-8");
            response = makeHttpGet("https://lrclib.net/api/search?" + encodedQuery);
            searchResult = parseSearchResponse(response, trackDuration);
            if (searchResult != null) {
                return searchResult;
            }

        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private void showLyricsError(int reqId) {
        showLyricsError(reqId, null);
    }

    private void showLyricsError(int reqId, String cacheKey) {
        AndroidUtilities.runOnUIThread(() -> {
            if (reqId != currentLyricsReqId) return;
            isFetchingLyrics = false;
            lyricsLines.clear();
            ArrayList<LrcLine> errList = new ArrayList<>();
            errList.add(new LrcLine(0, getString(R.string.sg_lyrics_not_found)));
            lyricsLines.addAll(errList);
            // Намеренно НЕ кешируем "не найдено" — при следующем открытии будет новый поиск
            currentLyricIndex = 0;
            if (lyricsView != null) lyricsView.jumpToIndex(0);
        });
    }

    // Вынесенный метод парсинга LRC в ArrayList (заменяет parseLrc)
    private void parseLrcIntoList(String lrcContent, ArrayList<LrcLine> out) {
        if (TextUtils.isEmpty(lrcContent)) return;
        String[] lines = lrcContent.split("\n");
        long offsetMs = 0;
        java.util.regex.Pattern timePattern = java.util.regex.Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
        java.util.regex.Pattern offsetPattern = java.util.regex.Pattern.compile("(?i)\\[offset:\\s*([+-]?\\d+)\\]");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            java.util.regex.Matcher offsetMatcher = offsetPattern.matcher(line);
            if (offsetMatcher.find()) {
                try {
                    offsetMs = Long.parseLong(offsetMatcher.group(1));
                } catch (Exception ignored) {}
                continue;
            }

            java.util.regex.Matcher timeMatcher = timePattern.matcher(line);
            ArrayList<Long> timestamps = new ArrayList<>();
            int lastMatchEnd = 0;
            while (timeMatcher.find()) {
                try {
                    int min = Integer.parseInt(timeMatcher.group(1));
                    int sec = Integer.parseInt(timeMatcher.group(2));
                    String msStr = timeMatcher.group(3);
                    int ms = 0;
                    if (msStr != null) {
                        ms = Integer.parseInt(msStr);
                        if (msStr.length() == 1) ms *= 100;
                        else if (msStr.length() == 2) ms *= 10;
                    }
                    long timeMs = min * 60000L + sec * 1000L + ms;
                    timestamps.add(timeMs);
                    lastMatchEnd = timeMatcher.end();
                } catch (Exception ignored) {}
            }

            if (!timestamps.isEmpty()) {
                String text = line.substring(lastMatchEnd).trim();
                if (!TextUtils.isEmpty(text)) {
                    for (long t : timestamps) {
                        long finalTime = t + offsetMs;
                        if (finalTime < 0) finalTime = 0;
                        out.add(new LrcLine(finalTime, text));
                    }
                }
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
    }

    /**
     * Кастомный View лирики в стиле Spotify:
     * - Активная строка всегда нарисована в фиксированной точке (activeY = 350dp от
     * верха)
     * - Остальные строки рисуются выше/ниже с одинаковым шагом ROW_HEIGHT
     * - При смене activeIndex View плавно анимирует scrollOffset через
     * ValueAnimator
     * - textSize НЕ меняется (нет requestLayout) — только alpha, scale, цвет, glow
     * через Paint
     */
    private class LyricsView extends View {

        private final int ACTIVE_Y = AndroidUtilities.dp(140);
        private final int LINE_GAP = AndroidUtilities.dp(28);
        private final float BASE_TEXT_SIZE = AndroidUtilities.dp(22);

        private final android.text.TextPaint textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);

        private float scrollOffset = 0f;
        private androidx.dynamicanimation.animation.SpringAnimation springAnim;
        private final androidx.dynamicanimation.animation.FloatValueHolder scrollHolder = new androidx.dynamicanimation.animation.FloatValueHolder(0f);

        private int activeIndex = 0;
        private final java.util.HashMap<String, android.text.StaticLayout> layoutCache = new java.util.HashMap<>();
        private float[] cumulativeY = null;
        private int cachedWidth = 0;

        private float touchDownY;
        private float touchDownX;
        private boolean isClick;

        public LyricsView(Context context) {
            super(context);
            textPaint.setTextSize(BASE_TEXT_SIZE);
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setColor(getThemedColor(Theme.key_dialogTextBlack));

            springAnim = new androidx.dynamicanimation.animation.SpringAnimation(scrollHolder)
                    .setSpring(new androidx.dynamicanimation.animation.SpringForce(0f)
                            .setStiffness(300f)
                            .setDampingRatio(0.82f));
            springAnim.addUpdateListener((anim, value, velocity) -> {
                scrollOffset = value;
                invalidate();
            });
        }

        private void ensureCumulativeY(int w) {
            if (lyricsLines.isEmpty() || w <= 0) {
                cumulativeY = null;
                return;
            }
            if (cumulativeY != null && cumulativeY.length == lyricsLines.size() && cachedWidth == w) {
                return;
            }
            cachedWidth = w;
            int n = lyricsLines.size();
            cumulativeY = new float[n];
            cumulativeY[0] = 0f;
            float prevHalf = getLineHeight(0, w) / 2f;
            for (int i = 1; i < n; i++) {
                float currHalf = getLineHeight(i, w) / 2f;
                cumulativeY[i] = cumulativeY[i - 1] + prevHalf + LINE_GAP + currHalf;
                prevHalf = currHalf;
            }
        }

        private float getLineHeight(int index, int w) {
            if (index < 0 || index >= lyricsLines.size()) return AndroidUtilities.dp(44);
            android.text.StaticLayout sl = getStaticLayout(lyricsLines.get(index).text, w);
            return sl != null ? Math.max(AndroidUtilities.dp(36), sl.getHeight()) : AndroidUtilities.dp(44);
        }

        private float getCumulativePos(int index) {
            if (cumulativeY == null || index < 0 || index >= cumulativeY.length) {
                return index * (float) (AndroidUtilities.dp(44) + LINE_GAP);
            }
            return cumulativeY[index];
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (lyricsLines.isEmpty() || isFetchingLyrics) {
                return false;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    isClick = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - touchDownX) > AndroidUtilities.dp(10) ||
                        Math.abs(event.getY() - touchDownY) > AndroidUtilities.dp(10)) {
                        isClick = false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    isClick = false;
                    return true;
            }
            return super.onTouchEvent(event);
        }

        public void reset() {
            if (springAnim != null) springAnim.cancel();
            activeIndex = 0;
            scrollOffset = 0f;
            scrollHolder.setValue(0f);
            layoutCache.clear();
            cumulativeY = null;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw) {
                layoutCache.clear();
                cumulativeY = null;
            }
        }

        public void setActiveIndex(int index, boolean animate) {
            if (index < -1 || lyricsLines.isEmpty()) return;
            if (index == activeIndex && animate) return;

            ensureCumulativeY(getWidth());

            if (!animate) {
                if (springAnim != null) springAnim.cancel();
                activeIndex = index;
                scrollOffset = 0f;
                scrollHolder.setValue(0f);
                invalidate();
                return;
            }

            float currentVisualOffset = scrollOffset;
            float shiftDue = getCumulativePos(index) - getCumulativePos(activeIndex);
            activeIndex = index;

            float newFrom = currentVisualOffset + shiftDue;
            scrollOffset = newFrom;
            scrollHolder.setValue(newFrom);

            if (springAnim != null) springAnim.cancel();
            springAnim.getSpring().setFinalPosition(0f);
            springAnim.setStartValue(newFrom);
            springAnim.start();
        }

        public void jumpToIndex(int index) {
            setActiveIndex(index, false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (lyricsLines.isEmpty()) return;

            int w = getWidth();
            if (w <= 0) return;
            ensureCumulativeY(w);

            int cx = w / 2;
            int dynamicActiveY = Math.min(ACTIVE_Y, Math.max(AndroidUtilities.dp(120), getHeight() / 2));
            float activePos = getCumulativePos(activeIndex);
            float visualActivePos = activePos - scrollOffset;
            int baseColor = getThemedColor(Theme.key_dialogTextBlack);
            float standardRowHeight = AndroidUtilities.dp(64);

            for (int i = 0; i < lyricsLines.size(); i++) {
                float linePos = getCumulativePos(i);
                float rowY = dynamicActiveY + scrollOffset + (linePos - activePos);

                String text = lyricsLines.get(i).text;
                android.text.StaticLayout sl = getStaticLayout(text, w);
                if (sl == null) continue;
                float totalH = sl.getHeight();

                if (rowY + totalH < 0 || rowY - totalH > getMeasuredHeight()) continue;

                float distFromActive = Math.abs(linePos - visualActivePos);
                float fracDist = distFromActive / standardRowHeight;
                float alpha = lyricsInterpAlpha(fracDist);
                float scale = lyricsInterpScale(fracDist);

                if (alpha <= 0.01f) continue;

                canvas.save();
                canvas.translate(cx, rowY);
                canvas.scale(scale, scale);

                textPaint.setColor(baseColor);
                textPaint.setAlpha((int) (alpha * 255));

                canvas.translate(-sl.getWidth() / 2f, -totalH / 2f);
                sl.draw(canvas);

                canvas.restore();
            }

            textPaint.setAlpha(255);
        }

        private android.text.StaticLayout getStaticLayout(String text, int viewWidth) {
            if (text == null) return null;
            String key = text + "_" + viewWidth;
            android.text.StaticLayout sl = layoutCache.get(key);
            if (sl == null) {
                int sidePadding = AndroidUtilities.dp(24);
                int maxWidth = Math.max(AndroidUtilities.dp(100), viewWidth - sidePadding * 2);
                sl = android.text.StaticLayout.Builder
                        .obtain(text, 0, text.length(), textPaint, maxWidth)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .build();
                layoutCache.put(key, sl);
            }
            return sl;
        }

        private float lyricsInterpAlpha(float d) {
            if (d <= 0f) return 1.0f;
            if (d <= 1f) return lerp(1.0f, 0.45f, d);
            if (d <= 2f) return lerp(0.45f, 0.22f, d - 1f);
            if (d <= 3f) return lerp(0.22f, 0.10f, d - 2f);
            return 0f;
        }

        private float lyricsInterpScale(float d) {
            if (d <= 0f) return 1.00f;
            if (d <= 1f) return lerp(1.00f, 0.85f, d);
            if (d <= 2f) return lerp(0.85f, 0.80f, d - 1f);
            return 0.80f;
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

}
