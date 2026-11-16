package soundgram.music.wave;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SharedConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import soundgram.music.ChannelMusicFragment;
import soundgram.music.MusicWebSearcher;
import soundgram.music.SmartRadioEngine;

/**
 * WaveQueueManager — умный менеджер очереди «Моя Волна».
 * Работает ИСКЛЮЧИТЕЛЬНО на алгоритмах YouTube Music (InnerTube / Next / Radio RDAMVM),
 * со строгим контролем жанрово-стилистической когерентности (Adjacent Genre Filter).
 */
public final class WaveQueueManager {

    private static final String TAG = "WaveQueueManager";
    private static final int BUFFER_TARGET_AHEAD = 16;
    private static final int BUFFER_REFILL_THRESHOLD = 12;
    private static final int BATCH_SIZE = 8;
    private static final int RE_SEED_INTERVAL = 8;
    private static final Random sRandom = new Random();

    public static final int MODE_GENRE = 0;
    public static final int MODE_ARTIST = 1;
    public static final int MODE_ALBUM = 2;
    public static final int MODE_PLAYLIST = 3;

    private int waveMode = MODE_GENRE;
    private String targetName = "";
    private String detectedCluster = "default";

    // Контекст альбома
    private String albumContextArtist = "";
    private String albumContextTitle = "";
    private final List<MessageObject> albumContextTracks = Collections.synchronizedList(new ArrayList<>());

    // Контекст плейлиста
    private final List<MessageObject> playlistContextTracks = Collections.synchronizedList(new ArrayList<>());
    private final List<String> playlistContextArtists = Collections.synchronizedList(new ArrayList<>());

    private final int currentAccount;
    private final SmartRadioEngine.WaveCallback callback;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    // ── Раздельные очереди: Рекомендации YouTube (70%) и Якорные треки (30%) ──
    private final Deque<InnerTubeClient.RawTrack> discoveryPool = new ArrayDeque<>();
    private final Deque<InnerTubeClient.RawTrack> anchorPool = new ArrayDeque<>();

    private final Deque<MessageObject> playBuffer = new ArrayDeque<>();

    // Сессионная дедупликация: предотвращает повторение треков в одной сессии
    private final Set<String> sessionPlayedKeys = Collections.synchronizedSet(new HashSet<>());

    // Сиды и continuation-токены YouTube Music
    private final List<String> activeSeedIds = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> seedContinuationTokens = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seedSkipCounters = new ConcurrentHashMap<>();

    // Сессионные треки для Dynamic Re-seeding
    private final List<MessageObject> completedSessionTracks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger playedTracksCount = new AtomicInteger(0);
    private final AtomicInteger candidateDeliveryCounter = new AtomicInteger(0);
    private final AtomicBoolean initialFired = new AtomicBoolean(false);

    private final WaveHistoryDao historyDb;
    private final ExecutorService executor;
    private volatile boolean isRunning = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Adjacent Genre Cluster Matrix (Матрица допустимых смежных поджанров)
    // Гарантирует, что алгоритмы не уедут из рэпа в гиперпоп, но разрешают эмо-рэп/треп/фонк
    // ─────────────────────────────────────────────────────────────────────────
    private static final Map<String, Set<String>> CLUSTER_BLOCKED_KEYWORDS = new HashMap<>();
    private static final Map<String, Set<String>> CLUSTER_ALLOWED_KEYWORDS = new HashMap<>();

    static {
        // Рэп / Хип-хоп
        Set<String> rapBlocked = new HashSet<>(Arrays.asList(
                "hyperpop", "гиперпоп", "deathcore", "metalcore", "black metal", "heavy metal",
                "классика", "classical", "mozart", "bach", "k-pop", "к-поп", "kpop", "anime",
                "nightcore", "hardstyle", "jumpstyle", "chopin"
        ));
        Set<String> rapAllowed = new HashSet<>(Arrays.asList(
                "rap", "рэп", "hip hop", "хип хоп", "trap", "треп", "трэп", "drill", "дрил",
                "boombap", "бумбэп", "emo rap", "эмо рэп", "phonk", "фонк", "r&b", "lofi", "cloud"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("рэп", rapBlocked);
        CLUSTER_BLOCKED_KEYWORDS.put("хип-хоп", rapBlocked);
        CLUSTER_ALLOWED_KEYWORDS.put("рэп", rapAllowed);
        CLUSTER_ALLOWED_KEYWORDS.put("хип-хоп", rapAllowed);

        // Рок
        Set<String> rockBlocked = new HashSet<>(Arrays.asList(
                "phonk", "фонк", "k-pop", "к-поп", "kpop", "hyperpop", "гиперпоп",
                "drill", "nightcore", "hardstyle", "club dance", "house remix"
        ));
        Set<String> rockAllowed = new HashSet<>(Arrays.asList(
                "rock", "рок", "punk", "панк", "grunge", "гранж", "alternative", "альтернатива",
                "metal", "метал", "post-punk", "пост-панк", "indie", "инди"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("рок", rockBlocked);
        CLUSTER_ALLOWED_KEYWORDS.put("рок", rockAllowed);

        // Металл
        Set<String> metalBlocked = new HashSet<>(Arrays.asList(
                "pop", "поп", "попса", "k-pop", "к-поп", "lo-fi", "lofi", "лофай", "jazz", "джаз",
                "blues", "блюз", "phonk", "фонк", "rap", "рэп", "hip hop", "trap", "шансон",
                "дискотека", "эстрада"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("металл", metalBlocked);

        // Пост-панк
        Set<String> postPunkBlocked = new HashSet<>(Arrays.asList(
                "phonk", "фонк", "trap", "треп", "k-pop", "к-поп", "deathcore", "edm", "rave",
                "шансон", "попса", "дискотека", "эстрада", "рэп", "хип-хоп"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("пост-панк", postPunkBlocked);

        // Фонк
        Set<String> phonkBlocked = new HashSet<>(Arrays.asList(
                "classical", "классика", "jazz", "джаз", "indie folk", "к-поп", "kpop",
                "symphony", "acoustic ballad", "русский рок", "шансон", "дискотека",
                "эстрада", "медляк", "вальс", "deathcore", "grunge", "рок-н-ролл",
                "попса", "диско", "хиты 80", "хиты 90", "хиты 2000", "русские хиты",
                "караоке", "огурец", "смешарики", "тикток", "tiktok remix", "meme"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("фонк", phonkBlocked);

        // Ло-фай
        Set<String> lofiBlocked = new HashSet<>(Arrays.asList(
                "deathcore", "death metal", "heavy metal", "hard rock", "hyperpop",
                "phonk", "drill", "screamo", "дискотека", "шансон", "попса"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("ло-фай", lofiBlocked);

        // Синтвейв
        Set<String> synthwaveBlocked = new HashSet<>(Arrays.asList(
                "шансон", "попса", "дискотека", "эстрада", "рэп", "хип-хоп",
                "death metal", "deathcore", "phonk", "фонк"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("синтвейв", synthwaveBlocked);

        // Инди
        Set<String> indieBlocked = new HashSet<>(Arrays.asList(
                "шансон", "дискотека", "эстрада", "phonk", "фонк", "hardstyle", "deathcore"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("инди", indieBlocked);

        // Электроника
        Set<String> electroBlocked = new HashSet<>(Arrays.asList(
                "русский рок", "классика", "шансон", "death metal", "тяжелый рок"
        ));
        CLUSTER_BLOCKED_KEYWORDS.put("электроника", electroBlocked);
    }

    public WaveQueueManager(int account, SmartRadioEngine.WaveCallback callback) {
        this.currentAccount = account;
        this.callback = callback;
        this.historyDb = WaveHistoryDao.getInstance();
        this.executor = Executors.newFixedThreadPool(8, r -> new Thread(r, "wave-queue-worker"));
    }

    /**
     * Нормализация ключа трека для надежного сравнения и дедупликации
     */
    public static String normalizeKey(String artist, String title) {
        if (artist == null) artist = "";
        if (title == null) title = "";
        String a = artist.toLowerCase().replaceAll("(?i)(feat\\.|ft\\.|&|,|\\+).*$", "").replaceAll("[^a-zа-я0-9]", "");
        String t = title.toLowerCase().replaceAll("(?i)(\\(.*?\\)|\\[.*?\\]|-.*?(remix|edit|mix|version)).*$", "").replaceAll("[^a-zа-я0-9]", "");
        return a + "___" + t;
    }

    /**
     * Старт сессии волны с переданным списком seedVideoIds, режимом и целевым именем.
     */
    public void startSession(List<String> seedVideoIds, int mode, String target) {
        this.waveMode = mode;
        this.targetName = target != null ? target.trim() : "";
        this.detectedCluster = inferCluster(targetName, null);
        startSession(seedVideoIds);
    }

    /**
     * Старт сессии волны по альбому с сохранением контекста альбома и артиста.
     */
    public void startSession(List<String> seedVideoIds, int mode, String artist, String album, List<MessageObject> albumTracks) {
        this.waveMode = mode;
        this.albumContextArtist = artist != null ? artist.trim() : "";
        this.albumContextTitle = album != null ? album.trim() : "";
        this.targetName = (!this.albumContextArtist.isEmpty() ? this.albumContextArtist + " - " : "") + this.albumContextTitle;
        if (albumTracks != null) {
            this.albumContextTracks.addAll(albumTracks);
        }
        this.detectedCluster = inferCluster(this.albumContextArtist, albumTracks);
        startSession(seedVideoIds);
    }

    /**
     * Старт сессии волны по плейлисту с сохранением треков и артистов плейлиста.
     */
    public void startSession(List<String> seedVideoIds, int mode, List<MessageObject> playlistTracks) {
        this.waveMode = mode;
        this.targetName = "playlist";
        if (playlistTracks != null) {
            this.playlistContextTracks.addAll(playlistTracks);
            Set<String> artSet = new HashSet<>();
            for (MessageObject m : playlistTracks) {
                if (m != null && m.getMusicAuthor() != null && !m.getMusicAuthor().trim().isEmpty()) {
                    artSet.add(m.getMusicAuthor().trim());
                }
            }
            this.playlistContextArtists.addAll(artSet);
        }
        this.detectedCluster = inferCluster("playlist", playlistTracks);
        startSession(seedVideoIds);
    }

    private String inferCluster(String target, List<MessageObject> tracks) {
        if (waveMode == MODE_GENRE) {
            return SeedSelector.normalizeGenre(target);
        }
        String textToAnalyze = (target != null ? target.toLowerCase() : "") + " ";
        if (tracks != null) {
            for (int i = 0; i < Math.min(6, tracks.size()); i++) {
                MessageObject m = tracks.get(i);
                if (m != null) {
                    textToAnalyze += (m.getMusicAuthor() != null ? m.getMusicAuthor().toLowerCase() + " " : "")
                            + (m.getMusicTitle() != null ? m.getMusicTitle().toLowerCase() + " " : "");
                }
            }
        }
        if (textToAnalyze.contains("рэп") || textToAnalyze.contains("rap") || textToAnalyze.contains("trap")
                || textToAnalyze.contains("oxxxymiron") || textToAnalyze.contains("kizaru") || textToAnalyze.contains("tape")
                || textToAnalyze.contains("pharaoh") || textToAnalyze.contains("scrip") || textToAnalyze.contains("buda")
                || textToAnalyze.contains("52") || textToAnalyze.contains("drake") || textToAnalyze.contains("travis")) {
            return "рэп";
        }
        if (textToAnalyze.contains("рок") || textToAnalyze.contains("rock") || textToAnalyze.contains("кино")
                || textToAnalyze.contains("шут") || textToAnalyze.contains("би-2") || textToAnalyze.contains("сплин")
                || textToAnalyze.contains("linkin") || textToAnalyze.contains("nirvana") || textToAnalyze.contains("offspring")) {
            return "рок";
        }
        if (textToAnalyze.contains("phonk") || textToAnalyze.contains("фонк") || textToAnalyze.contains("dvrst")
                || textToAnalyze.contains("kordhell") || textToAnalyze.contains("drift")) {
            return "фонк";
        }
        if (textToAnalyze.contains("метал") || textToAnalyze.contains("metal") || textToAnalyze.contains("rammstein")
                || textToAnalyze.contains("slipknot") || textToAnalyze.contains("lorna") || textToAnalyze.contains("ария")) {
            return "металл";
        }
        if (textToAnalyze.contains("пост-панк") || textToAnalyze.contains("молчат") || textToAnalyze.contains("буерак")
                || textToAnalyze.contains("ploho") || textToAnalyze.contains("думер")) {
            return "пост-панк";
        }
        if (textToAnalyze.contains("lo-fi") || textToAnalyze.contains("lofi") || textToAnalyze.contains("лофай")
                || textToAnalyze.contains("chillhop") || textToAnalyze.contains("nujabes")) {
            return "ло-фай";
        }
        return "default";
    }

    /**
     * Проверка стилистической совместимости (Adjacent Genre Filter).
     * Защищает от вылетов из рэпа в гиперпоп/металл/к-поп, но оставляет эмо-рэп/треп/фонк.
     */
    private boolean isStylisticallyCoherent(InnerTubeClient.RawTrack track) {
        if (track == null) return false;
        String cluster = detectedCluster;
        if ("default".equals(cluster)) return true;

        String titleLower = track.title != null ? track.title.toLowerCase() : "";
        String artistLower = track.artist != null ? track.artist.toLowerCase() : "";
        String combined = titleLower + " " + artistLower;

        Set<String> blocked = CLUSTER_BLOCKED_KEYWORDS.get(cluster);
        if (blocked != null) {
            for (String kw : blocked) {
                if (combined.contains(kw)) {
                    FileLog.d(TAG + " Coherence Gatekeeper blocked distant track: " + track + " for cluster " + cluster);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Старт сессии волны:
     * Запуск параллельных радио-потоков YouTube Music по сидам (RDAMVM...).
     */
    public void startSession(List<String> seedVideoIds) {
        if (seedVideoIds == null || seedVideoIds.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) callback.onError();
            });
            return;
        }

        isRunning = true;
        cancelFlag.set(false);
        activeSeedIds.clear();
        activeSeedIds.addAll(seedVideoIds);
        for (String id : seedVideoIds) {
            seedSkipCounters.put(id, new AtomicInteger(0));
        }

        executor.execute(() -> {
            try {
                // 1. Быстрый параллельный запрос радио YouTube Music по сидам
                CompletionService<InnerTubeClient.NextResult> cs = new ExecutorCompletionService<>(executor);
                int submitted = 0;
                for (String seedId : seedVideoIds) {
                    try {
                        cs.submit(() -> InnerTubeClient.fetchRadioBatch(seedId, null, cancelFlag));
                        submitted++;
                    } catch (Exception ignored) {}
                }

                // 2. Первичное наполнение якорного пула (Артист / Альбом / Плейлист)
                populateInitialAnchors();

                // Ждем первую готовую пачку радио-треков с таймаутом (до 1.8 с) для мгновенного старта
                long deadline = SystemClock.uptimeMillis() + 1800L;
                List<List<InnerTubeClient.RawTrack>> batches = new ArrayList<>();
                for (int i = 0; i < submitted; i++) {
                    long remaining = deadline - SystemClock.uptimeMillis();
                    if (remaining <= 0 && !batches.isEmpty()) {
                        break;
                    }
                    try {
                        Future<InnerTubeClient.NextResult> f = cs.poll(Math.max(100, remaining), TimeUnit.MILLISECONDS);
                        if (f != null) {
                            InnerTubeClient.NextResult nr = f.get();
                            if (nr != null && !nr.tracks.isEmpty()) {
                                batches.add(nr.tracks);
                                if (nr.continuationToken != null && i < seedVideoIds.size()) {
                                    seedContinuationTokens.put(seedVideoIds.get(i), nr.continuationToken);
                                }
                                if (batches.size() >= 1) {
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(TAG + " error awaiting batch: " + e);
                    }
                }

                if (cancelFlag.get() || !isRunning) return;

                List<InnerTubeClient.RawTrack> initialPool = interleaveBatches(batches);
                addCandidatesFiltered(discoveryPool, initialPool);

                int totalCandidates;
                synchronized (discoveryPool) { totalCandidates = discoveryPool.size() + anchorPool.size(); }
                if (totalCandidates < 10) {
                    triggerDynamicReSeeding();
                }

                synchronized (discoveryPool) { totalCandidates = discoveryPool.size() + anchorPool.size(); }
                if (totalCandidates == 0) {
                    FileLog.w(TAG + " candidates pools are empty after start");
                    AndroidUtilities.runOnUIThread(() -> {
                        if (callback != null && !initialFired.get() && !cancelFlag.get()) {
                            callback.onError();
                        }
                    });
                    stop();
                    return;
                }

                // Фоновый сбор оставшихся радио-батчей
                final int remainingSubmits = submitted - batches.size();
                if (remainingSubmits > 0) {
                    executor.execute(() -> {
                        for (int i = 0; i < remainingSubmits; i++) {
                            if (cancelFlag.get() || !isRunning) break;
                            try {
                                Future<InnerTubeClient.NextResult> f = cs.poll(3, TimeUnit.SECONDS);
                                if (f != null) {
                                    InnerTubeClient.NextResult nr = f.get();
                                    if (nr != null && !nr.tracks.isEmpty()) {
                                        addCandidatesFiltered(discoveryPool, nr.tracks);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                }

                // 3. Запуск буферизации воспроизведения (трек #1 стартует мгновенно!)
                runBufferLoop();

            } catch (Exception e) {
                FileLog.e(TAG + " startSession error", e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null && !initialFired.get()) {
                        callback.onError();
                    }
                });
            }
        });
    }

    /**
     * Наполняет якорный пул из YouTube Music и локального контекста (30% волны).
     */
    private void populateInitialAnchors() {
        if (waveMode == MODE_GENRE && !targetName.isEmpty()) {
            int moodIdx = SeedSelector.parseMoodIndex(SmartRadioEngine.currentConfig.mood, SmartRadioEngine.currentConfig.moodProgress);
            List<SeedSelector.CanonicalSeed> canonicals = SeedSelector.getCanonicalSeedsForGenre(targetName, moodIdx);
            if (canonicals != null && !canonicals.isEmpty()) {
                List<InnerTubeClient.RawTrack> canonicalTracks = new ArrayList<>();
                for (SeedSelector.CanonicalSeed s : canonicals) {
                    if (s != null && s.title != null && s.artist != null) {
                        canonicalTracks.add(new InnerTubeClient.RawTrack(null, s.title, s.artist, 0));
                    }
                }
                List<InnerTubeClient.RawTrack> anchors = new ArrayList<>();
                List<InnerTubeClient.RawTrack> discovery = new ArrayList<>();
                for (int i = 0; i < canonicalTracks.size(); i++) {
                    if (i % 2 == 0) {
                        anchors.add(canonicalTracks.get(i));
                    } else {
                        discovery.add(canonicalTracks.get(i));
                    }
                }
                addCandidatesFiltered(anchorPool, anchors);
                addCandidatesFiltered(discoveryPool, discovery);
            }
        } else if (waveMode == MODE_ARTIST && !targetName.isEmpty()) {
            List<InnerTubeClient.RawTrack> artistSongs = InnerTubeClient.searchSongs(targetName + " популярные песни", 8, cancelFlag);
            if (artistSongs != null) {
                List<InnerTubeClient.RawTrack> artistAnchors = new ArrayList<>();
                for (InnerTubeClient.RawTrack t : artistSongs) {
                    if (MusicWebSearcher.isArtistMatch(t.artist, targetName)) {
                        artistAnchors.add(t);
                        if (artistAnchors.size() >= 5) break;
                    }
                }
                addCandidatesFiltered(anchorPool, artistAnchors);
            }
        } else if (waveMode == MODE_ALBUM && !albumContextTracks.isEmpty()) {
            List<MessageObject> copy = new ArrayList<>(albumContextTracks);
            Collections.shuffle(copy, sRandom);
            List<InnerTubeClient.RawTrack> albumAnchors = new ArrayList<>();
            for (MessageObject m : copy.subList(0, Math.min(4, copy.size()))) {
                if (m != null && m.getMusicTitle() != null && m.getMusicAuthor() != null) {
                    albumAnchors.add(createRawTrackFromMessage(m));
                }
            }
            addCandidatesFiltered(anchorPool, albumAnchors);
        } else if (waveMode == MODE_PLAYLIST && !playlistContextTracks.isEmpty()) {
            List<MessageObject> copy = new ArrayList<>(playlistContextTracks);
            Collections.shuffle(copy, sRandom);
            List<InnerTubeClient.RawTrack> pAnchors = new ArrayList<>();
            for (MessageObject m : copy.subList(0, Math.min(5, copy.size()))) {
                if (m != null && m.getMusicTitle() != null && m.getMusicAuthor() != null) {
                    pAnchors.add(createRawTrackFromMessage(m));
                }
            }
            addCandidatesFiltered(anchorPool, pAnchors);
        }
    }

    private InnerTubeClient.RawTrack createRawTrackFromMessage(MessageObject m) {
        String dlUrl = null;
        String covUrl = null;
        if (m.messageOwner != null && m.messageOwner.message != null && m.messageOwner.message.startsWith("SG_STREAM|||")) {
            String[] parts = m.messageOwner.message.split("\\|\\|\\|");
            if (parts.length >= 2) dlUrl = parts[1];
            if (parts.length >= 3) covUrl = parts[2];
        }
        return new InnerTubeClient.RawTrack(null, m.getMusicTitle(), m.getMusicAuthor(), (int) m.getDuration(), dlUrl, covUrl);
    }

    private void addCandidatesFiltered(Deque<InnerTubeClient.RawTrack> targetQueue, List<InnerTubeClient.RawTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        synchronized (discoveryPool) {
            for (InnerTubeClient.RawTrack t : tracks) {
                if (t == null) continue;
                String k = normalizeKey(t.artist, t.title);
                if (!sessionPlayedKeys.contains(k) && isStylisticallyCoherent(t)) {
                    targetQueue.addLast(t);
                }
            }
        }
    }

    /**
     * Извлечение следующего кандидата: 70% YouTube Рекомендации / 30% Якорные вехи.
     */
    private InnerTubeClient.RawTrack pollNextCandidate() {
        synchronized (discoveryPool) {
            int count = candidateDeliveryCounter.incrementAndGet();
            boolean takeAnchor = (count % 3 == 0);
            if (takeAnchor && !anchorPool.isEmpty()) {
                return anchorPool.pollFirst();
            } else if (!discoveryPool.isEmpty()) {
                return discoveryPool.pollFirst();
            } else if (!anchorPool.isEmpty()) {
                return anchorPool.pollFirst();
            }
            return null;
        }
    }

    /**
     * Round-Robin перемешивание пачек
     */
    public static List<InnerTubeClient.RawTrack> interleaveBatches(List<List<InnerTubeClient.RawTrack>> batches) {
        List<InnerTubeClient.RawTrack> result = new ArrayList<>();
        if (batches == null || batches.isEmpty()) return result;

        int maxLen = 0;
        for (List<InnerTubeClient.RawTrack> b : batches) {
            if (b.size() > maxLen) maxLen = b.size();
        }

        for (int i = 0; i < maxLen; i++) {
            for (List<InnerTubeClient.RawTrack> batch : batches) {
                if (i < batch.size()) {
                    result.add(batch.get(i));
                }
            }
        }
        return result;
    }

    /**
     * Резолвинг одного кандидата в MessageObject с полной дедупликацией.
     */
    private MessageObject resolveSingleCandidate(InnerTubeClient.RawTrack candidate) {
        if (candidate == null || cancelFlag.get() || !isRunning || !SmartRadioEngine.isWaveActive) {
            return null;
        }

        String cleanTitle = TrackSanitizer.cleanTitle(candidate.title);
        String cleanArtist = TrackSanitizer.cleanArtist(candidate.artist);
        if (cleanTitle.isEmpty() || cleanArtist.isEmpty()) {
            return null;
        }

        String candidateKey = normalizeKey(cleanArtist, cleanTitle);
        if (sessionPlayedKeys.contains(candidateKey)) {
            return null;
        }

        // В режимах Альбома и Артиста НЕ фильтруем историю
        if (waveMode != MODE_ALBUM && waveMode != MODE_ARTIST) {
            String hash = TrackSanitizer.generateHash(cleanArtist, cleanTitle);
            boolean poolPlentiful;
            synchronized (discoveryPool) {
                poolPlentiful = (discoveryPool.size() + anchorPool.size()) >= 15;
            }
            if (poolPlentiful && historyDb.isRecentlyPlayed(hash)) {
                sessionPlayedKeys.add(candidateKey);
                return null;
            }
        }

        // Если у кандидата уже есть прямой downloadUrl — создаем MessageObject мгновенно
        if (candidate.downloadUrl != null && !candidate.downloadUrl.isEmpty()) {
            int dur = candidate.durationSec > 0 ? candidate.durationSec : 180;
            MessageObject msg = ChannelMusicFragment.createWebMessageObject(
                    currentAccount, cleanTitle, cleanArtist, candidate.downloadUrl, dur,
                    -(long) candidate.downloadUrl.hashCode(), candidate.coverUrl);

            sessionPlayedKeys.add(candidateKey);
            historyDb.markAsPlayed(TrackSanitizer.generateHash(cleanArtist, cleanTitle));
            return msg;
        }

        // Поиск MP3 потока для воспроизведения
        MusicWebSearcher.WebTrack wt = MusicWebSearcher.searchTrackStrict(cleanArtist, cleanTitle, candidate.durationSec);
        if (wt == null || wt.downloadUrl == null || wt.downloadUrl.isEmpty()) {
            wt = MusicWebSearcher.resolveHitmosSync(cleanTitle, cleanArtist);
        }
        if (wt == null || wt.downloadUrl == null || wt.downloadUrl.isEmpty()) {
            // Cross-catalog matching retains version and featured-artist qualifiers.
            wt = soundgram.music.MusicSourceResolver.resolveAudius(
                    candidate.artist, candidate.title, candidate.durationSec);
        }
        if (wt == null || wt.downloadUrl == null || wt.downloadUrl.isEmpty()) {
            return null;
        }

        String resolvedKey = normalizeKey(wt.artist, wt.title);
        if (sessionPlayedKeys.contains(resolvedKey)) {
            return null;
        }

        int dur = ChannelMusicFragment.parseDuration(wt.duration);
        MessageObject msg = ChannelMusicFragment.createWebMessageObject(
                currentAccount, wt.title, wt.artist, wt.downloadUrl, dur,
                -(long) wt.downloadUrl.hashCode(), wt.coverUrl);

        sessionPlayedKeys.add(candidateKey);
        sessionPlayedKeys.add(resolvedKey);
        historyDb.markAsPlayed(TrackSanitizer.generateHash(cleanArtist, cleanTitle));
        historyDb.markAsPlayed(TrackSanitizer.generateHash(wt.artist, wt.title));

        return msg;
    }

    /**
     * Параллельный резолвинг пачки кандидатов.
     */
    private List<MessageObject> resolveBatch(int targetCount) {
        List<MessageObject> resolved = new ArrayList<>();
        if (cancelFlag.get() || !isRunning || !SmartRadioEngine.isWaveActive) {
            return resolved;
        }

        List<InnerTubeClient.RawTrack> toProcess = new ArrayList<>();
        synchronized (discoveryPool) {
            if (discoveryPool.size() < 20) {
                executor.execute(this::loadMoreCandidates);
            }
            int take = Math.min(discoveryPool.size() + anchorPool.size(), Math.max(targetCount * 3, targetCount == 1 ? 6 : 14));
            for (int i = 0; i < take; i++) {
                InnerTubeClient.RawTrack c = pollNextCandidate();
                if (c != null) {
                    toProcess.add(c);
                }
            }
        }

        if (toProcess.isEmpty()) {
            return resolved;
        }

        CompletionService<MessageObject> cs = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        for (InnerTubeClient.RawTrack track : toProcess) {
            try {
                cs.submit(() -> resolveSingleCandidate(track));
                submitted++;
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < submitted; i++) {
            if (cancelFlag.get() || !isRunning || !SmartRadioEngine.isWaveActive) break;
            try {
                Future<MessageObject> f = cs.poll(4, TimeUnit.SECONDS);
                if (f != null) {
                    MessageObject msg = f.get();
                    if (msg != null) {
                        resolved.add(msg);
                        // Мгновенный запуск первого трека сессии!
                        if (initialFired.compareAndSet(false, true)) {
                            ArrayList<MessageObject> first = new ArrayList<>();
                            first.add(msg);
                            synchronized (playBuffer) {
                                playBuffer.addLast(msg);
                            }
                            AndroidUtilities.runOnUIThread(() -> {
                                if (callback != null && !cancelFlag.get() && SmartRadioEngine.isWaveActive) {
                                    callback.onTracksFound(first);
                                }
                            });
                        }
                        if (resolved.size() >= targetCount) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(TAG + " resolve future error: " + e);
            }
        }

        return resolved;
    }

    /**
     * Фоновый цикл поддержания буфера воспроизведения пачками треков.
     */
    private void runBufferLoop() {
        // 1. Сверхбыстрый старт: находим ровно 1 трек (< 1 сек) и сразу запускаем воспроизведение
        List<MessageObject> firstTrack = resolveBatch(1);
        if (firstTrack.isEmpty() && !initialFired.get()) {
            triggerDynamicReSeeding();
            firstTrack = resolveBatch(1);
        }

        if (!initialFired.get()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null && !cancelFlag.get()) {
                    callback.onError();
                }
            });
            stop();
            return;
        }

        // 2. В фоне догружаем остаток первичной очереди (5-7 треков), пока играет первый трек
        List<MessageObject> remainingInitial = resolveBatch(BATCH_SIZE - 1);
        if (remainingInitial.isEmpty()) {
            triggerDynamicReSeeding();
            remainingInitial = resolveBatch(BATCH_SIZE - 1);
        }
        if (!remainingInitial.isEmpty()) {
            ArrayList<MessageObject> toAdd = new ArrayList<>(remainingInitial);
            synchronized (playBuffer) {
                playBuffer.addAll(toAdd);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null && !cancelFlag.get() && SmartRadioEngine.isWaveActive) {
                    callback.onMoreTracksAdded(toAdd);
                }
            });
        }

        // 3. Бесконечный цикл поддержания очереди воспроизведения
        int emptyBatchesCount = 0;
        while (isRunning && !cancelFlag.get() && SmartRadioEngine.isWaveActive) {
            try {
                if (MediaController.getInstance().getPlaylistMergeDialogId() != MediaController.WAVE_CONTEXT_ID) {
                    SystemClock.sleep(1000);
                    continue;
                }

                int ahead = getRemainingAhead();
                if (ahead < BUFFER_REFILL_THRESHOLD) {
                    int need = Math.min(BATCH_SIZE, BUFFER_TARGET_AHEAD - ahead);
                    if (need > 0) {
                        List<MessageObject> batch = resolveBatch(need);
                        if (!batch.isEmpty()) {
                            emptyBatchesCount = 0;
                            ArrayList<MessageObject> toAdd = new ArrayList<>(batch);
                            synchronized (playBuffer) {
                                playBuffer.addAll(toAdd);
                            }
                            AndroidUtilities.runOnUIThread(() -> {
                                if (callback != null && !cancelFlag.get() && SmartRadioEngine.isWaveActive) {
                                    callback.onMoreTracksAdded(toAdd);
                                }
                            });
                        } else {
                            emptyBatchesCount++;
                            triggerDynamicReSeeding();
                            List<MessageObject> retryBatch = resolveBatch(need);
                            if (!retryBatch.isEmpty()) {
                                emptyBatchesCount = 0;
                                ArrayList<MessageObject> toAdd = new ArrayList<>(retryBatch);
                                synchronized (playBuffer) {
                                    playBuffer.addAll(toAdd);
                                }
                                AndroidUtilities.runOnUIThread(() -> {
                                    if (callback != null && !cancelFlag.get() && SmartRadioEngine.isWaveActive) {
                                        callback.onMoreTracksAdded(toAdd);
                                    }
                                });
                            }
                        }
                    }
                }

                synchronized (discoveryPool) {
                    if (discoveryPool.size() < 20) {
                        executor.execute(this::loadMoreCandidates);
                    }
                }

                SystemClock.sleep(ahead < BUFFER_REFILL_THRESHOLD ? 1500 : 2500);

            } catch (Exception e) {
                FileLog.e(TAG + " runBufferLoop exception", e);
                SystemClock.sleep(2000);
            }
        }

        if (!initialFired.get() && !cancelFlag.get()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null && !cancelFlag.get()) {
                    callback.onError();
                }
            });
        }
    }

    private int getRemainingAhead() {
        if (!initialFired.get()) {
            return 0;
        }
        if (MediaController.getInstance().getPlaylistMergeDialogId() != MediaController.WAVE_CONTEXT_ID) {
            return 0;
        }
        ArrayList<MessageObject> currentPlaylist = MediaController.getInstance().getPlaylist();
        if (currentPlaylist == null || currentPlaylist.isEmpty()) {
            return 0;
        }
        int num = MediaController.getInstance().getPlayingMessageObjectNum();
        if (num < 0 || num >= currentPlaylist.size()) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            num = playing != null ? currentPlaylist.indexOf(playing) : -1;
        }
        if (num >= 0 && num < currentPlaylist.size()) {
            return SharedConfig.playOrderReversed ? Math.max(0, currentPlaylist.size() - 1 - num) : num;
        }
        return 0;
    }

    /**
     * Дозагрузка кандидатов по continuationToken лучшего сида YouTube Music.
     */
    private void loadMoreCandidates() {
        if (cancelFlag.get() || !isRunning) return;

        String bestSeed = null;
        int minSkips = Integer.MAX_VALUE;
        for (String seedId : activeSeedIds) {
            String tok = seedContinuationTokens.get(seedId);
            if (tok != null && !tok.isEmpty()) {
                AtomicInteger counter = seedSkipCounters.get(seedId);
                int skips = counter != null ? counter.get() : 0;
                if (skips < minSkips) {
                    minSkips = skips;
                    bestSeed = seedId;
                }
            }
        }

        if (bestSeed != null) {
            String continuation = seedContinuationTokens.get(bestSeed);
            InnerTubeClient.NextResult next = InnerTubeClient.fetchRadioBatch(null, continuation, cancelFlag);
            if (next != null && !next.tracks.isEmpty()) {
                if (next.continuationToken != null) {
                    seedContinuationTokens.put(bestSeed, next.continuationToken);
                } else {
                    seedContinuationTokens.remove(bestSeed);
                }
                addCandidatesFiltered(discoveryPool, next.tracks);
                if (discoveryPool.size() >= 20) {
                    return;
                }
            } else {
                seedContinuationTokens.remove(bestSeed);
            }
        }

        // Если continuation исчерпаны или кандидатов мало — запускаем динамический re-seeding через YouTube Music
        triggerDynamicReSeeding();
    }

    private List<MessageObject> getRecentSessionTracks(int count) {
        List<MessageObject> list = new ArrayList<>();
        synchronized (completedSessionTracks) {
            int sz = completedSessionTracks.size();
            for (int i = sz - 1; i >= 0 && list.size() < count; i--) {
                MessageObject m = completedSessionTracks.get(i);
                if (m != null) list.add(m);
            }
        }
        if (list.size() < count) {
            synchronized (playBuffer) {
                List<MessageObject> buf = new ArrayList<>(playBuffer);
                for (int i = buf.size() - 1; i >= 0 && list.size() < count; i--) {
                    MessageObject m = buf.get(i);
                    if (m != null && !list.contains(m)) list.add(m);
                }
            }
        }
        return list;
    }

    /**
     * Динамический дрифт (Dynamic Re-seeding) ИСКЛЮЧИТЕЛЬНО через YouTube Music:
     * Опирается на алгоритмы YouTube Music и формулу 70% Рекомендации / 30% Якоря.
     */
    private void triggerDynamicReSeeding() {
        if (cancelFlag.get() || !isRunning) return;

        // ── 1. РЕЖИМ ЖАНРА ──
        if (waveMode == MODE_GENRE) {
            String g = !targetName.isEmpty() ? targetName : "default";
            int moodIdx = SeedSelector.parseMoodIndex(SmartRadioEngine.currentConfig.mood, SmartRadioEngine.currentConfig.moodProgress);

            // А. Загрузка editorial-плейлистов Deezer по жанру (настоящие студийные хиты жанра)
            String dzQuery = SeedSelector.getDeezerGenreQuery(g);
            if (dzQuery != null && !dzQuery.isEmpty()) {
                try {
                    ArrayList<MusicWebSearcher.WebTrack> dzTracks = MusicWebSearcher.getGenreTracksSync(dzQuery);
                    if (dzTracks != null && !dzTracks.isEmpty()) {
                        Collections.shuffle(dzTracks, sRandom);
                        List<InnerTubeClient.RawTrack> dzRaw = new ArrayList<>();
                        for (MusicWebSearcher.WebTrack wt : dzTracks) {
                            if (wt != null && wt.title != null && wt.artist != null) {
                                dzRaw.add(new InnerTubeClient.RawTrack(null, wt.title, wt.artist, ChannelMusicFragment.parseDuration(wt.duration), null, wt.coverUrl));
                            }
                        }
                        addCandidatesFiltered(discoveryPool, dzRaw);
                    }
                } catch (Exception ignored) {}
            }

            // Б. Пополнение каноническими треками эпох из встроенного каталога (если кандидатов мало)
            List<SeedSelector.CanonicalSeed> canonicals = SeedSelector.getCanonicalSeedsForGenre(g, moodIdx);
            if (canonicals != null && !canonicals.isEmpty()) {
                List<InnerTubeClient.RawTrack> canonRaw = new ArrayList<>();
                for (SeedSelector.CanonicalSeed cs : canonicals) {
                    if (cs != null && cs.title != null && cs.artist != null) {
                        canonRaw.add(new InnerTubeClient.RawTrack(null, cs.title, cs.artist, 0));
                    }
                }
                Collections.shuffle(canonRaw, sRandom);
                addCandidatesFiltered(anchorPool, canonRaw);
            }

            // В. Похожие топовые артисты жанра через Deezer API
            if (canonicals != null && !canonicals.isEmpty()) {
                SeedSelector.CanonicalSeed sample = canonicals.get(sRandom.nextInt(Math.min(10, canonicals.size())));
                if (sample != null && sample.artist != null) {
                    try {
                        ArrayList<String> simArtists = MusicWebSearcher.getSimilarArtistsSync(sample.artist);
                        if (simArtists != null && !simArtists.isEmpty()) {
                            for (String simArt : simArtists.subList(0, Math.min(3, simArtists.size()))) {
                                List<InnerTubeClient.RawTrack> artSongs = InnerTubeClient.searchSongs(simArt + " популярные", 4, cancelFlag);
                                if (artSongs != null && !artSongs.isEmpty()) {
                                    addCandidatesFiltered(discoveryPool, artSongs);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Г. Радио YouTube Music по недавно проигранным трекам текущей сессии
            List<MessageObject> recentTracks = getRecentSessionTracks(2);
            for (MessageObject m : recentTracks) {
                String author = m.getMusicAuthor();
                String title = m.getMusicTitle();
                String q = (author != null ? author : "") + " " + (title != null ? title : "");
                if (!q.trim().isEmpty()) {
                    List<InnerTubeClient.RawTrack> s = InnerTubeClient.searchSongs(q.trim(), 1, cancelFlag);
                    if (s != null && !s.isEmpty() && s.get(0).videoId != null && !activeSeedIds.contains(s.get(0).videoId)) {
                        String vid = s.get(0).videoId;
                        activeSeedIds.add(vid);
                        InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(vid, null, cancelFlag);
                        if (nr != null && !nr.tracks.isEmpty()) {
                            addCandidatesFiltered(discoveryPool, nr.tracks);
                            if (nr.continuationToken != null) {
                                seedContinuationTokens.put(vid, nr.continuationToken);
                            }
                        }
                    }
                }
            }

            // Д. Дополнительная порция из корзины эпох YouTube Music если кандидатов всё ещё мало
            if (discoveryPool.size() < 15) {
                List<String> moreSeeds = SeedSelector.selectSeedsByGenre(g, SmartRadioEngine.currentConfig.mood, cancelFlag);
                for (String sid : moreSeeds) {
                    if (!activeSeedIds.contains(sid)) {
                        activeSeedIds.add(sid);
                        InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(sid, null, cancelFlag);
                        if (nr != null && !nr.tracks.isEmpty()) {
                            addCandidatesFiltered(discoveryPool, nr.tracks);
                        }
                    }
                }
            }
            return;
        }

        // ── 2. РЕЖИМ АРТИСТА (70% YouTube Music Радио похожих / 30% Исходный артист) ──
        if (waveMode == MODE_ARTIST && !targetName.isEmpty()) {
            // А. YouTube Music радио по недавним трекам сессии или главному хиту артиста
            List<MessageObject> recent = getRecentSessionTracks(2);
            for (MessageObject m : recent) {
                String q = m.getMusicAuthor() + " " + m.getMusicTitle();
                List<InnerTubeClient.RawTrack> s = InnerTubeClient.searchSongs(q.trim(), 1, cancelFlag);
                if (s != null && !s.isEmpty() && s.get(0).videoId != null && !activeSeedIds.contains(s.get(0).videoId)) {
                    String vid = s.get(0).videoId;
                    activeSeedIds.add(vid);
                    InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(vid, null, cancelFlag);
                    if (nr != null && !nr.tracks.isEmpty()) {
                        addCandidatesFiltered(discoveryPool, nr.tracks);
                        if (nr.continuationToken != null) {
                            seedContinuationTokens.put(vid, nr.continuationToken);
                        }
                        break;
                    }
                }
            }

            // Б. Поиск похожих треков через поиск YouTube Music
            String simQuery = targetName + " радио похожие";
            List<InnerTubeClient.RawTrack> ytSim = InnerTubeClient.searchSongs(simQuery, 15, cancelFlag);
            if (ytSim != null && !ytSim.isEmpty()) {
                Collections.shuffle(ytSim, sRandom);
                addCandidatesFiltered(discoveryPool, ytSim);
            }

            // В. Пополнение якорных треков самого артиста (30%) через YouTube Music
            if (anchorPool.size() < 4) {
                List<InnerTubeClient.RawTrack> artistSongs = InnerTubeClient.searchSongs(targetName + " популярные песни", 10, cancelFlag);
                if (artistSongs != null) {
                    List<InnerTubeClient.RawTrack> artistAnchors = new ArrayList<>();
                    for (InnerTubeClient.RawTrack t : artistSongs) {
                        if (MusicWebSearcher.isArtistMatch(t.artist, targetName)) {
                            artistAnchors.add(t);
                        }
                    }
                    Collections.shuffle(artistAnchors, sRandom);
                    addCandidatesFiltered(anchorPool, artistAnchors.subList(0, Math.min(4, artistAnchors.size())));
                }
            }
            return;
        }

        // ── 3. РЕЖИМ АЛЬБОМА (70% YouTube Music Радио альбома / 30% Треки альбома) ──
        if (waveMode == MODE_ALBUM) {
            String art = !albumContextArtist.isEmpty() ? albumContextArtist : targetName;
            String alb = albumContextTitle;

            // А. Радио YouTube Music по неиспользованным трекам альбома
            List<MessageObject> seedPool = new ArrayList<>(getRecentSessionTracks(2));
            if (seedPool.isEmpty() && !albumContextTracks.isEmpty()) {
                seedPool.addAll(albumContextTracks);
                Collections.shuffle(seedPool, sRandom);
            }
            for (MessageObject m : seedPool.subList(0, Math.min(2, seedPool.size()))) {
                String author = m.getMusicAuthor();
                String title = m.getMusicTitle();
                String q = (author != null ? author + " - " : "") + (title != null ? title : "");
                if (q.trim().isEmpty()) continue;
                List<InnerTubeClient.RawTrack> s = InnerTubeClient.searchSongs(q.trim(), 1, cancelFlag);
                if (s != null && !s.isEmpty() && s.get(0).videoId != null && !activeSeedIds.contains(s.get(0).videoId)) {
                    String vid = s.get(0).videoId;
                    activeSeedIds.add(vid);
                    InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(vid, null, cancelFlag);
                    if (nr != null && !nr.tracks.isEmpty()) {
                        addCandidatesFiltered(discoveryPool, nr.tracks);
                        if (nr.continuationToken != null) {
                            seedContinuationTokens.put(vid, nr.continuationToken);
                        }
                        break;
                    }
                }
            }

            // Б. Поиск в YouTube Music радио по названию альбома
            if (!alb.isEmpty() || !art.isEmpty()) {
                List<InnerTubeClient.RawTrack> albumRadio = InnerTubeClient.searchSongs(art + " " + alb + " radio", 12, cancelFlag);
                if (albumRadio != null && !albumRadio.isEmpty()) {
                    Collections.shuffle(albumRadio, sRandom);
                    addCandidatesFiltered(discoveryPool, albumRadio);
                }
            }

            // В. Пополнение якорных треков из альбома (30%)
            if (anchorPool.size() < 4 && !albumContextTracks.isEmpty()) {
                List<MessageObject> copy = new ArrayList<>(albumContextTracks);
                Collections.shuffle(copy, sRandom);
                List<InnerTubeClient.RawTrack> anchors = new ArrayList<>();
                for (MessageObject m : copy.subList(0, Math.min(4, copy.size()))) {
                    anchors.add(createRawTrackFromMessage(m));
                }
                addCandidatesFiltered(anchorPool, anchors);
            }
            return;
        }

        // ── 4. РЕЖИМ ПЛЕЙЛИСТА (70% YouTube Music Радио плейлиста / 30% Треки плейлиста) ──
        if (waveMode == MODE_PLAYLIST) {
            // А. Радио YouTube Music по трекам плейлиста
            List<MessageObject> seedPool = new ArrayList<>(getRecentSessionTracks(2));
            if (seedPool.isEmpty() && !playlistContextTracks.isEmpty()) {
                seedPool.addAll(playlistContextTracks);
                Collections.shuffle(seedPool, sRandom);
            }
            for (MessageObject m : seedPool.subList(0, Math.min(2, seedPool.size()))) {
                String q = (m.getMusicAuthor() != null ? m.getMusicAuthor() + " " : "") + (m.getMusicTitle() != null ? m.getMusicTitle() : "");
                if (q.trim().isEmpty()) continue;
                List<InnerTubeClient.RawTrack> s = InnerTubeClient.searchSongs(q.trim(), 1, cancelFlag);
                if (s != null && !s.isEmpty() && s.get(0).videoId != null && !activeSeedIds.contains(s.get(0).videoId)) {
                    String vid = s.get(0).videoId;
                    activeSeedIds.add(vid);
                    InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(vid, null, cancelFlag);
                    if (nr != null && !nr.tracks.isEmpty()) {
                        addCandidatesFiltered(discoveryPool, nr.tracks);
                        if (nr.continuationToken != null) {
                            seedContinuationTokens.put(vid, nr.continuationToken);
                        }
                        break;
                    }
                }
            }

            // Б. Поиск в YouTube Music по артистам плейлиста
            if (!playlistContextArtists.isEmpty()) {
                List<String> artistsPool = new ArrayList<>(playlistContextArtists);
                Collections.shuffle(artistsPool, sRandom);
                for (String pArtist : artistsPool.subList(0, Math.min(2, artistsPool.size()))) {
                    List<InnerTubeClient.RawTrack> ytArtistRadio = InnerTubeClient.searchSongs(pArtist + " радио", 10, cancelFlag);
                    if (ytArtistRadio != null && !ytArtistRadio.isEmpty()) {
                        Collections.shuffle(ytArtistRadio, sRandom);
                        addCandidatesFiltered(discoveryPool, ytArtistRadio.subList(0, Math.min(4, ytArtistRadio.size())));
                    }
                }
            }

            // В. Пополнение якорных треков из плейлиста (30%)
            if (anchorPool.size() < 4 && !playlistContextTracks.isEmpty()) {
                List<MessageObject> copy = new ArrayList<>(playlistContextTracks);
                Collections.shuffle(copy, sRandom);
                List<InnerTubeClient.RawTrack> anchors = new ArrayList<>();
                for (MessageObject m : copy.subList(0, Math.min(4, copy.size()))) {
                    anchors.add(createRawTrackFromMessage(m));
                }
                addCandidatesFiltered(anchorPool, anchors);
            }
            return;
        }

        // ── 5. ФОЛБЭК: Re-seeding по последним сыгранным трекам сессии или дефолтным маякам ──
        List<MessageObject> seedTracks = getRecentSessionTracks(2);
        List<String> newSeedIds = new ArrayList<>();
        for (MessageObject m : seedTracks) {
            String q = m.getMusicAuthor() + " " + m.getMusicTitle();
            List<InnerTubeClient.RawTrack> s = InnerTubeClient.searchSongs(q.trim(), 1, cancelFlag);
            if (s != null && !s.isEmpty() && s.get(0).videoId != null) {
                newSeedIds.add(s.get(0).videoId);
            }
        }

        if (newSeedIds.isEmpty()) {
            String fallbackGenre = (waveMode == MODE_GENRE && !targetName.isEmpty()) ? targetName : "default";
            newSeedIds.addAll(SeedSelector.selectSeedsByGenre(fallbackGenre, cancelFlag));
        }

        if (!newSeedIds.isEmpty()) {
            List<List<InnerTubeClient.RawTrack>> newBatches = new ArrayList<>();
            for (String sid : newSeedIds) {
                InnerTubeClient.NextResult nr = InnerTubeClient.fetchRadioBatch(sid, null, cancelFlag);
                if (nr != null && !nr.tracks.isEmpty()) {
                    newBatches.add(nr.tracks);
                    activeSeedIds.add(sid);
                    seedSkipCounters.put(sid, new AtomicInteger(0));
                    if (nr.continuationToken != null) {
                        seedContinuationTokens.put(sid, nr.continuationToken);
                    }
                }
            }
            List<InnerTubeClient.RawTrack> fresh = interleaveBatches(newBatches);
            addCandidatesFiltered(discoveryPool, fresh);
        }
    }

    /**
     * Фиксация полностью прослушанного трека.
     */
    public void reportTrackPlayed(MessageObject track) {
        if (track == null) return;
        completedSessionTracks.add(track);
        int total = playedTracksCount.incrementAndGet();
        if (total % RE_SEED_INTERVAL == 0) {
            executor.execute(this::triggerDynamicReSeeding);
        }
    }

    /**
     * Фиксация скипа трека пользователем (< 20 сек).
     */
    public void reportSkip(String artist, String title, long playedMs) {
        if (playedMs < 20_000L && !activeSeedIds.isEmpty()) {
            synchronized (activeSeedIds) {
                for (String seedId : activeSeedIds) {
                    AtomicInteger c = seedSkipCounters.get(seedId);
                    if (c != null) c.incrementAndGet();
                }
            }
        }
    }

    /**
     * Остановка сессии.
     */
    public void stop() {
        isRunning = false;
        cancelFlag.set(true);
        executor.shutdownNow();
        synchronized (discoveryPool) {
            discoveryPool.clear();
            anchorPool.clear();
        }
        synchronized (playBuffer) { playBuffer.clear(); }
        activeSeedIds.clear();
        seedContinuationTokens.clear();
        seedSkipCounters.clear();
        completedSessionTracks.clear();
        albumContextTracks.clear();
        playlistContextTracks.clear();
        playlistContextArtists.clear();
    }
}
