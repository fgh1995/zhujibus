package org.zjfgh.zhujibus;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 语音包在线化管理器。
 *
 * 职责：
 * - 拉取并解析 cn_to_en.json，建立 stationName → entry 索引
 * - 站名 wav 文件本地缓存（context.filesDir/voicepack）
 * - 降级链下载：原始 raw → 配置源 githubAddSpeed → 硬编码 github.360967.xyz → Gitee 镜像
 * - cnMd5/enMd5 内容指纹比对，决定重下
 * - 批量预下载（线路包用）
 * - 【统一配置状态机】拉取触发、重试、回调统一收敛在本类，UI 只需注册状态监听
 *   ConfigStateListener，按状态（LOADING/READY/FAILED）刷新界面，不再各自管理重试计数与轮询。
 *
 * 文件缺失或配置未就绪时，调用方应回退 TTS。
 */
public class VoicePackManager {
    private static final String TAG = "VoicePackManager";

    /** 语音包在 GitHub 上的原始根 URL（末尾含 /） */
    private static final String BASE_RAW_URL =
            "https://raw.githubusercontent.com/fgh1995/zhujibus/refs/heads/master/app/voicepack/";
    /** 翻译配置文件名 */
    private static final String CONFIG_FILENAME = "cn_to_en.json";
    /** 硬编码兜底加速源（末尾含 /） */
    private static final String HARDCODED_FALLBACK = "https://github.360967.xyz/";
    /** Gitee 镜像兜底根 URL（末尾含 /），作为最终备用源 */
    private static final String GITEE_BASE_URL =
            "https://gitee.com/fangguihua1995/zhujibus/raw/master/app/voicepack/";

    /** 下载结果码：成功 / 网络或其他失败 / 404 文件不存在 */
    private static final int DL_OK = 0;
    private static final int DL_FAIL = 1;
    private static final int DL_NOT_FOUND = 2;

    private static VoicePackManager instance;

    private final Context context;
    private final File cacheDir;
    private final OkHttpClient http;
    private final ExecutorService executor;

    /** stationName → entry 索引（配置解析后建立） */
    private final ConcurrentHashMap<String, Entry> stationIndex = new ConcurrentHashMap<>();
    /** 站名缓存命中：cacheKey(cn|en + stationName) → 本地 File */
    private final ConcurrentHashMap<String, File> fileCache = new ConcurrentHashMap<>();
    /** 站名缓存未命中标记，避免同一站名反复查索引/磁盘 */
    private final ConcurrentHashMap<String, Boolean> fileMissCache = new ConcurrentHashMap<>();
    /** 正在下载的文件名集合，防重入 */
    private final ConcurrentHashMap<String, Boolean> downloading = new ConcurrentHashMap<>();

    private final AtomicBoolean configLoaded = new AtomicBoolean(false);
    private final AtomicBoolean configFetching = new AtomicBoolean(false);
    /** 配置拉取期间有新的拉取请求被吞掉时置 true，拉取结束后据此补拉一次 */
    private volatile boolean needRefetch = false;

    // ===== 统一配置状态机 =====
    /** 配置最大重试次数（每次失败间隔 CONFIG_RETRY_DELAY_MS 后自动重拉，耗尽才判失败） */
    private static final int CONFIG_MAX_RETRY = 8;
    /** 配置重试间隔（毫秒） */
    private static final long CONFIG_RETRY_DELAY_MS = 2000L;
    /** 当前配置加载状态，UI 依据它展示加载中/就绪/失败 */
    private volatile ConfigState configState = ConfigState.LOADING;
    /** 当前重试计数，达到 CONFIG_MAX_RETRY 后进入 FAILED */
    private volatile int configRetryCount = 0;
    /** 用于定时重试配置拉取的调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 配置状态监听器集合。任何一次状态变更（LOADING/READY/FAILED）都会通知，供 UI 统一刷新 */
    private final List<ConfigStateListener> stateListeners = new ArrayList<>();

    /** 最近一次自动清理的过时文件数（含 wav 与 md5 sidecar）；0 表示未发生过清理 */
    private volatile int lastCleanupCount = 0;
    /** 最近一次清理的时间戳（System.currentTimeMillis），0 表示未发生过清理 */
    private volatile long lastCleanupTime = 0L;

    /** 从 RemoteConfig 解析得到的 githubAddSpeed（已去掉末尾 /）；空串表示未配置 */
    private volatile String configAccelUrl = "";

    /** 最近一次成功命中的源序号（对应降级链下标），用于后续请求优先尝试该源，跳过已确认不可用的前置源 */
    private volatile int preferredSource = 0;

    /** 已确认「远程不存在（404）」的站点名集合。下载时服务端明确返回 404 即加入，
     *  用于 classifyMissing 将其归入 notInRemote（远程不存在），从而在 UI 上直接展示，
     *  而不是靠 Toast 临时提示。仅内存态，进程重启后重新探测。 */
    private final Set<String> confirmedNotInRemote = ConcurrentHashMap.newKeySet();

    private VoicePackManager(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = new File(this.context.getFilesDir(), "voicepack");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        // 配置短超时：任一源连接/响应超时后快速失败，立即切到下一源。
        // 若用默认超时（connect/read 均 10s），原始源挂起时 4 源串行可能卡很久，表现为"一直不切源"。
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        // 立即拉取语音包配置，确保首页/详情页不依赖 loadRemoteConfig 的时序。
        // 加速源（githubAddSpeed）在 loadRemoteConfig 完成后由 setConfigAccelUrl 推送，
        // 届时会触发一次"用新加速源的重拉"，从而切换降级链第 2 级。
        // 初始状态为 LOADING，构造后立即启动首轮拉取。
        startFetchCycle();
    }

    public static synchronized VoicePackManager getInstance(Context context) {
        if (instance == null) {
            instance = new VoicePackManager(context);
        }
        return instance;
    }

    /**
     * 配置加载状态。UI 依据它统一展示"加载中/就绪/失败"，
     * 不需要自己轮询或管理重试计数。
     */
    public enum ConfigState {
        /** 配置拉取中（含源遍历中、重试等待中）：UI 应显示加载中，绝不判失败 */
        LOADING,
        /** 配置已成功就绪：UI 可进行统计/下载 */
        READY,
        /** 所有源遍历完且重试已耗尽，拉取失败：UI 显示失败 */
        FAILED
    }

    /** 配置状态监听器。注册后立即收到当前状态一次，之后每次状态变更都会收到。回调在后台线程，UI 需自行 runOnUiThread。 */
    public interface ConfigStateListener {
        void onConfigStateChanged(ConfigState state);
    }

    /** 查询当前配置加载状态 */
    public ConfigState getConfigState() {
        return configState;
    }

    /**
     * 注册配置状态监听器（统一回调入口）。
     * 注册后立即用当前状态回调一次（去重，若与上次相同则不重复回调），
     * 之后每次状态变更（LOADING/READY/FAILED）都会回调。UI 无需再自己轮询/重试。
     */
    public void addConfigStateListener(ConfigStateListener l) {
        if (l == null) return;
        boolean added;
        synchronized (stateListeners) {
            added = stateListeners.add(l);
        }
        if (added) {
            // 立即通知一次当前状态，避免 UI 注册前已完成的拉取造成漏通知
            notifyStateListener(l, configState);
        }
    }

    /** 移除配置状态监听器 */
    public void removeConfigStateListener(ConfigStateListener l) {
        if (l == null) return;
        synchronized (stateListeners) {
            stateListeners.remove(l);
        }
    }

    /** 状态变更并通知所有监听器（在状态实际变化时调用） */
    private void setConfigState(ConfigState state) {
        if (configState == state) return;
        configState = state;
        notifyConfigState(state);
    }

    /** 向单个监听器通知状态（去重：同一状态不重复回调同一监听器） */
    private void notifyStateListener(ConfigStateListener l, ConfigState state) {
        try {
            l.onConfigStateChanged(state);
        } catch (Exception e) {
            Log.w(TAG, "配置状态回调异常: " + e.getMessage());
        }
    }

    /** 向所有监听器广播状态 */
    private void notifyConfigState(ConfigState state) {
        final List<ConfigStateListener> snapshot;
        synchronized (stateListeners) {
            snapshot = new ArrayList<>(stateListeners);
        }
        for (ConfigStateListener l : snapshot) {
            notifyStateListener(l, state);
        }
    }

    /**
     * 后台拉取并解析 cn_to_en.json，建立 stationName → entry 索引。
     * 即使本地已有缓存也会拉取远程，以保证拿到最新配置。
     *
     * 【统一入口】这是唯一触发拉取的入口。内部管理重试：失败时自动按 CONFIG_RETRY_DELAY_MS
     * 间隔重拉，直到 CONFIG_MAX_RETRY 次耗尽才置 FAILED。UI 不再负责重试。
     */
    public void startFetchCycle() {
        // 若配置已就绪，无需再拉（切源重拉由 setConfigAccelUrl 负责）
        if (configLoaded.get()) return;
        // 防重入：正在拉取中则标记补拉；否则立即执行一轮拉取
        if (!configFetching.compareAndSet(false, true)) {
            needRefetch = true;
            return;
        }
        executor.execute(this::runFetchAndResolveState);
    }

    /** 执行一轮拉取并在结束后解析最终状态（成功→READY；失败→重试或 FAILED） */
    private void runFetchAndResolveState() {
        try {
            fetchConfig();
        } catch (Exception e) {
            Log.w(TAG, "拉取配置异常: " + e.getMessage());
        } finally {
            configFetching.set(false);
        }
        // 结束后根据结果解析状态
        resolveConfigState();
        // 拉取期间有更新请求被吞掉（如加速源刚推送）→ 补拉一次
        if (needRefetch) {
            needRefetch = false;
            startFetchCycle();
        }
    }

    /** 解析当前配置状态：就绪→READY；失败且重试未耗尽→调度重拉（保持 LOADING）；耗尽→FAILED */
    private void resolveConfigState() {
        if (configLoaded.get()) {
            configRetryCount = 0;
            setConfigState(ConfigState.READY);
            return;
        }
        if (configRetryCount < CONFIG_MAX_RETRY) {
            configRetryCount++;
            setConfigState(ConfigState.LOADING);
            // 调度延迟重拉，保持"加载中"直到重试耗尽
            scheduler.schedule(this::startFetchCycle, CONFIG_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        } else {
            setConfigState(ConfigState.FAILED);
        }
    }

    /**
     * 确保语音包配置正在拉取（首页/详情页状态刷新时调用）。
     * 配置尚未就绪（LOADING/FAILED）时触发一轮拉取；已就绪则跳过。
     * 若处于 FAILED，此方法会重置重试计数并重新发起，给用户手动重试的机会。
     */
    public void ensureConfigLoading() {
        if (configLoaded.get()) return;
        // FAILED 状态下用户主动触发：重置重试计数重新来过
        if (configState == ConfigState.FAILED) {
            configRetryCount = 0;
            setConfigState(ConfigState.LOADING);
        }
        startFetchCycle();
    }

    /**
     * 从 RemoteConfig 推入 githubAddSpeed（可空）。
     * loadRemoteConfig 完成后 MainActivity 调用它。当加速源变更时，
     * 触发一次"用新加速源的重拉"，使降级链第 2 级（githubAddSpeed）尽快生效。
     */
    public void setConfigAccelUrl(String url) {
        if (url == null) url = "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        boolean changed = !url.equals(configAccelUrl);
        configAccelUrl = url;
        if (changed) {
            // 配置源变更，清空 miss 缓存让后续查询重新评估，并强制用新加速源重拉配置
            fileMissCache.clear();
            // 加速源变了，重置首选源，让降级链从新的配置源开始重新尝试
            preferredSource = 0;
            // 重置重试计数并回到 LOADING，强制用新加速源重拉，让切源尽快生效
            configRetryCount = 0;
            setConfigState(ConfigState.LOADING);
            startFetchCycle();
        } else {
            // 加速源未变：仅确保配置在拉取（首页主动触发场景）
            startFetchCycle();
        }
    }

    /**
     * 兼容旧调用：loadRemoteConfig 失败时调用，确保配置拉取不被阻塞。
     * 现在配置拉取无条件进行，此方法仅兜底触发一次拉取，无副作用。
     */
    public void unlockConfigFetch() {
        startFetchCycle();
    }

    /**
     * 是否正在拉取配置（fetchConfig 尚未结束，降级链源可能还在遍历中）。
     * 保留此查询供 UI 在极端时序下辅助判断；常规场景直接用 getConfigState()。
     */
    public boolean isConfigFetching() {
        return configFetching.get();
    }

    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    /**
     * 主接口：返回站名本地文件，不存在返回 null 并触发后台下载。
     *
     * @param stationName 原始站名（无需 normalize）
     * @param isCn        true=中文语音 cn_stations_*.wav，false=英文 en_stations_*.wav
     * @return 本地 File（存在且 md5 匹配），或 null（缺失/配置未就绪，调用方回退 TTS）
     */
    public File getStationFile(String stationName, boolean isCn) {
        if (stationName == null || stationName.isEmpty()) return null;
        String cacheKey = (isCn ? "cn|" : "en|") + stationName;

        File cached = fileCache.get(cacheKey);
        if (cached != null) return cached;
        Boolean miss = fileMissCache.get(cacheKey);
        if (miss != null && miss) {
            // 之前判过 miss，仍尝试后台下载补齐
            enqueueDownloadForStation(stationName, isCn);
            return null;
        }

        Entry entry = stationIndex.get(stationName);
        if (entry == null) {
            // 配置未就绪，标记 miss 并触发配置加载
            fileMissCache.put(cacheKey, true);
            startFetchCycle();
            return null;
        }

        String filename = isCn ? entry.cnFile : entry.enFile;
        if (filename == null || filename.isEmpty()) {
            fileMissCache.put(cacheKey, true);
            return null;
        }

        File local = new File(cacheDir, filename);
        String expectedMd5 = isCn ? entry.cnMd5 : entry.enMd5;
        if (local.exists() && local.length() > 0 && md5SidecarMatches(filename, expectedMd5)) {
            fileCache.put(cacheKey, local);
            fileMissCache.remove(cacheKey);
            return local;
        }

        // 文件缺失或 md5 不匹配 → 后台下载，本次回退 TTS
        enqueueDownload(filename);
        fileMissCache.put(cacheKey, true);
        return null;
    }

    /**
     * 批量预下载（线路预下载用）。仅下载缺失/md5 不匹配文件，已就绪跳过。
     * 支持 {@link BatchProgressCallback}：每个文件失败时回调（含 404 不存在标记），
     * 整体成功 = 没有发生任何失败；便于 UI 明确提示「某语音包不存在」。
     */
    public void downloadBatchAsync(List<String> stationNames, ProgressCallback cb) {
        boolean batch = cb instanceof BatchProgressCallback;
        if (stationNames == null || stationNames.isEmpty()) {
            if (cb != null) cb.onComplete(true);
            return;
        }
        executor.execute(() -> {
            // 文件名 → 站点名（一个站点可能对应 cn/en 两个文件；找不到则回退用文件名提示）
            Map<String, String> fnToStation = new HashMap<>();
            List<String> filenames = new ArrayList<>();
            for (String name : stationNames) {
                Entry e = stationIndex.get(name);
                if (e == null) continue;
                if (e.cnFile != null && !e.cnFile.isEmpty()) {
                    filenames.add(e.cnFile);
                    fnToStation.put(e.cnFile, name);
                }
                if (e.enFile != null && !e.enFile.isEmpty()) {
                    filenames.add(e.enFile);
                    fnToStation.put(e.enFile, name);
                }
            }
            int total = filenames.size();
            int done = 0;
            boolean allOk = true;
            for (String fn : filenames) {
                // 用 md5 判断：已存在且 md5 匹配才跳过，否则重下（修复更新不重下 bug）
                Entry e = findEntryByFilename(fn);
                String expectedMd5 = e == null ? "" : (fn.startsWith("cn_") ? e.cnMd5 : e.enMd5);
                if (!isFilePresent(fn, expectedMd5)) {
                    int r = downloadFile(fn);
                    if (r != DL_OK) {
                        allOk = false;
                        if (batch) {
                            String station = fnToStation.get(fn);
                            String label = (station != null ? station : fn)
                                    + (fn.startsWith("en_") ? "(英文)" : "(中文)");
                            if (r == DL_NOT_FOUND && station != null) {
                                // 记录到「远程不存在」集合，使后续分类统计在 UI 上展示
                                confirmedNotInRemote.add(station);
                            }
                            ((BatchProgressCallback) cb).onFileFailed(label, r == DL_NOT_FOUND);
                        }
                    }
                }
                done++;
                if (cb != null) cb.onProgress(done, total);
            }
            if (cb != null) cb.onComplete(allOk);
        });
    }

    /**
     * 统计给定站名列表中缺失语音包的站点数。
     * 一个站点若 cn 或 en 任一文件缺失/md5 不匹配，计为缺失。
     *
     * @return 缺失站点数；配置未就绪返回 -1（调用方应提示"配置加载中"并稍后重试）
     */
    public int countMissing(List<String> stationNames) {
        MissingStat stat = classifyMissing(stationNames);
        if (stat == null) return -1;
        return stat.notInRemote.size() + stat.notDownloaded.size() + stat.needUpdate.size();
    }

    /**
     * 分类统计给定站名列表中缺失语音包的站点，区分三种情况：
     * - notInRemote：远程配置中无此站（远程不存在）
     * - notDownloaded：远程有但本地文件不存在（未下载）
     * - needUpdate：本地文件存在但 md5 不匹配（待更新）
     *
     * 一个站点会归入其首次命中的分类（优先级：notInRemote > notDownloaded > needUpdate）。
     *
     * @return 分类统计；配置未就绪返回 null（调用方应稍后重试）
     */
    public MissingStat classifyMissing(List<String> stationNames) {
        if (!configLoaded.get() || stationIndex.isEmpty()) return null;
        MissingStat stat = new MissingStat();
        if (stationNames == null || stationNames.isEmpty()) return stat;
        for (String name : stationNames) {
            if (name == null || name.isEmpty()) continue;
            Entry e = stationIndex.get(name);
            if (e == null || confirmedNotInRemote.contains(name)) {
                // 配置无此站，或下载时已被服务端确认 404（远程不存在）→ 归入「远程不存在」
                stat.notInRemote.add(name);
                continue;
            }
            boolean cnMissing = !isFilePresent(e.cnFile, e.cnMd5);
            boolean enMissing = !isFilePresent(e.enFile, e.enMd5);
            if (cnMissing || enMissing) {
                // 区分：完全没下过（两文件都不存在）算 notDownloaded；已下过但 md5 不匹配算 needUpdate
                boolean cnExists = fileExistsOnDisk(e.cnFile);
                boolean enExists = fileExistsOnDisk(e.enFile);
                if (!cnExists && !enExists) {
                    stat.notDownloaded.add(name);
                } else {
                    stat.needUpdate.add(name);
                }
            }
        }
        return stat;
    }

    /** 仅判断本地文件是否存在（不查 md5），用于区分"未下载"与"待更新" */
    private boolean fileExistsOnDisk(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        return new File(cacheDir, filename).exists();
    }

    /**
     * 下载全部站点语音包（cn+en），跳过已就绪文件。回调在 executor 子线程。
     */
    public void downloadAllStations(ProgressCallback cb) {
        executor.execute(() -> {
            List<String> filenames = new ArrayList<>();
            for (Entry e : stationIndex.values()) {
                if (e.cnFile != null && !e.cnFile.isEmpty()) filenames.add(e.cnFile);
                if (e.enFile != null && !e.enFile.isEmpty()) filenames.add(e.enFile);
            }
            int total = filenames.size();
            int done = 0;
            for (String fn : filenames) {
                // 用 md5 判断：已存在且 md5 匹配才跳过，否则重下（修复更新不重下 bug）
                Entry e = findEntryByFilename(fn);
                String expectedMd5 = e == null ? "" : (fn.startsWith("cn_") ? e.cnMd5 : e.enMd5);
                if (!isFilePresent(fn, expectedMd5)) {
                    downloadFile(fn);
                }
                done++;
                if (cb != null) cb.onProgress(done, total);
            }
            if (cb != null) cb.onComplete(true);
        });
    }

    /**
     * 返回所有已索引的站名（配置未就绪时返回空列表）。
     */
    public List<String> getAllStationNames() {
        return new ArrayList<>(stationIndex.keySet());
    }

    // ==================== 内部实现 ====================

    /** 判断本地文件是否齐备且 md5 匹配 */
    private boolean isFilePresent(String filename, String expectedMd5) {
        if (filename == null || filename.isEmpty()) return false;
        File local = new File(cacheDir, filename);
        return local.exists() && local.length() > 0 && md5SidecarMatches(filename, expectedMd5);
    }

    private void enqueueDownloadForStation(String stationName, boolean isCn) {
        Entry entry = stationIndex.get(stationName);
        if (entry == null) return;
        String filename = isCn ? entry.cnFile : entry.enFile;
        if (filename != null && !filename.isEmpty()) {
            enqueueDownload(filename);
        }
    }

    private void enqueueDownload(String filename) {
        if (downloading.putIfAbsent(filename, true) != null) return;
        executor.execute(() -> {
            try {
                downloadFile(filename);
            } finally {
                downloading.remove(filename);
            }
        });
    }

    private int downloadFile(String filename) {
        try (Response resp = executeWithFallback(filename)) {
            if (resp == null) {
                // 所有源均不可用（超时/连接失败/5xx），非文件不存在
                Log.w(TAG, "下载失败(所有源不可用): " + filename);
                return DL_FAIL;
            }
            int code = resp.code();
            if (code == 404) {
                // 语音包确实不存在：明确告知，不降级、不卡住
                Log.w(TAG, "下载失败(404 语音包不存在): " + filename);
                return DL_NOT_FOUND;
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.w(TAG, "下载失败(HTTP " + code + "): " + filename);
                return DL_FAIL;
            }
            byte[] data = resp.body().bytes();
            if (data.length == 0) return DL_FAIL;
            File tmp = new File(cacheDir, filename + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            File dest = new File(cacheDir, filename);
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return DL_FAIL;
            }
            // 写 md5 sidecar（取 entry 中对应字段）
            Entry entry = findEntryByFilename(filename);
            if (entry != null) {
                String expectedMd5 = filename.startsWith("cn_") ? entry.cnMd5 : entry.enMd5;
                if (expectedMd5 != null && !expectedMd5.isEmpty()) {
                    writeSidecarMd5(filename, expectedMd5);
                }
            }
            // 下载成功后清空 miss 缓存，让下次查询重新评估
            fileMissCache.clear();
            return DL_OK;
        } catch (IOException e) {
            Log.w(TAG, "下载异常 " + filename + ": " + e.getMessage());
            return DL_FAIL;
        }
    }

    /**
     * 降级链下载：原始 raw → 配置源 githubAddSpeed → 硬编码 github.360967.xyz → Gitee 镜像
     * 任一源成功即返回。返回的 Response 由调用方关闭。
     *
     * 【通道选择优化】配置已就绪时，preferredSource 即"当前正常拉取配置文件的通道"
     * （最近一次成功拉取 cn_to_en.json 的源）。下载语音包时直接只认这个通道做一次尝试，
     * 不再逐个源都试一遍（拉配置刚成功，该源必然可用，逐个试反而拖慢且浪费流量）。
     * 仅当该通道此刻也失败（临时抖动）时，才回退走完整降级链兜底。
     * 配置尚未就绪时无可用通道记忆，才从 preferredSource 起始环绕遍历全部源兜底。
     *
     * 【404 不降级】服务器明确返回 404（文件不存在）时视为终结性失败，直接返回该
     * Response 停止降级。因各降级源都是同一份内容的镜像，文件在一个源 404，其他源
     * 也必然 404，继续降级只会白白等待。只有网络级失败/超时/5xx（源本身问题）才降级。
     */
    private Response executeWithFallback(String filename) throws IOException {
        String originalUrl = BASE_RAW_URL + filename;
        if (configLoaded.get()) {
            // 配置已就绪：认准当前正常通道，单次尝试即可
            String url = buildSourceUrl(preferredSource, originalUrl, filename);
            if (url != null) {
                Response resp = trySource(preferredSource, url, filename);
                // 成功或 404 终结都直接返回；null 表示该源不可用，回退完整降级链
                if (resp != null) return resp;
            }
        }
        // 配置未就绪，或首选通道已失效 → 走完整降级链（记忆成功源，环绕遍历兜底）
        int start = preferredSource;
        int size = 4;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % size;
            String url = buildSourceUrl(idx, originalUrl, filename);
            if (url == null) continue;
            Response resp = trySource(idx, url, filename);
            // 成功或 404 终结都直接返回；null 表示该源不可用，降级下一源
            if (resp != null) return resp;
        }
        return null;
    }

    /**
     * 尝试从指定源拉取。
     *
     * @return
     *   - 成功的 Response（2xx，调用方关闭）
     *   - 404 的 Response（文件不存在，终结性失败，不降级；调用方关闭）
     *   - null（该源不可用：超时 / 5xx / 连接失败，应降级尝试下一源）
     */
    private Response trySource(int idx, String url, String filename) {
        try {
            Request req = new Request.Builder().url(url).build();
            Response resp = http.newCall(req).execute();
            int code = resp.code();
            if (resp.isSuccessful() && resp.body() != null) {
                // 记录成功源，后续请求优先从这里开始
                preferredSource = idx;
                return resp;
            }
            if (code == 404) {
                // 文件确实不存在：降级源都是同一份内容的镜像，必然也 404，
                // 继续降级只会白白等待，直接返回终结性 404 交由调用方判定失败。
                Log.d(TAG, "源[" + idx + "]返回 404 " + filename + ": 文件不存在，不降级");
                return resp;
            }
            resp.close();
            Log.d(TAG, "源[" + idx + "]返回非成功 " + filename + ": HTTP " + code + ", 降级下一源");
            return null;
        } catch (IOException e) {
            Log.d(TAG, "源[" + idx + "]失败 " + filename + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 根据源序号拼接 URL。
     * @param idx 0=原始 raw，1=配置源 githubAddSpeed，2=硬编码 github.360967.xyz，3=Gitee 镜像
     */
    private String buildSourceUrl(int idx, String originalUrl, String filename) {
        switch (idx) {
            case 0:
                return originalUrl;
            case 1:
                return (configAccelUrl != null && !configAccelUrl.isEmpty())
                        ? configAccelUrl + "/" + originalUrl : null;
            case 2:
                return HARDCODED_FALLBACK + originalUrl;
            case 3:
                // Gitee 的文件名需直接拼到镜像根 URL 后
                return GITEE_BASE_URL + filename;
            default:
                return null;
        }
    }

    private void fetchConfig() throws IOException {
        File localConfig = new File(cacheDir, CONFIG_FILENAME);
        boolean parsedLocal = false;
        // 优先读本地缓存建索引，保证离线启动可用
        if (localConfig.exists() && localConfig.length() > 0) {
            parsedLocal = parseConfig(localConfig);
        }
        // 后台拉取最新配置（走降级链）
        try (Response resp = executeWithFallback(CONFIG_FILENAME)) {
            if (resp != null && resp.isSuccessful() && resp.body() != null) {
                byte[] data = resp.body().bytes();
                try (FileOutputStream fos = new FileOutputStream(localConfig)) {
                    fos.write(data);
                }
                parseConfig(localConfig);
                // 远程配置更新后，清理本地已被配置移除的过时语音包（含 .md5 sidecar）
                int deleted = cleanupOrphanedVoicePacks();
                // 总是更新清理结果（包括 0），让 UI 据此判断是否需要提示
                lastCleanupCount = deleted;
                lastCleanupTime = System.currentTimeMillis();
                if (deleted > 0) {
                    Log.d(TAG, "本次清理过时文件数: " + deleted);
                }
            }
        }
        configLoaded.set(parsedLocal || !stationIndex.isEmpty());
        // 拉取结束（成功或失败）时的状态统一由 runFetchAndResolveState 的 resolveConfigState 处理，
        // 在此不再单独通知监听器，避免与状态机重复广播。
    }

    private boolean parseConfig(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(fis);
            if (!root.isArray()) return false;
            // 清空旧索引，避免配置更新后残留过时条目（如站名 A 改为 B 后 A 仍残留）
            stationIndex.clear();
            for (JsonNode node : root) {
                Entry e = new Entry();
                e.stationName = node.path("stationName").asText("");
                e.en = node.path("en").asText("");
                e.voice = node.path("voice").asText("");
                e.cnFile = node.path("cnFile").asText("");
                e.enFile = node.path("enFile").asText("");
                e.cnMd5 = node.path("cnMd5").asText("");
                e.enMd5 = node.path("enMd5").asText("");
                if (!e.stationName.isEmpty()) {
                    stationIndex.put(e.stationName, e);
                }
            }
            Log.d(TAG, "配置解析完成，站名数: " + stationIndex.size());
            return !stationIndex.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "解析配置失败", e);
            return false;
        }
    }

    /**
     * 清理本地缓存中已被配置移除的过时语音包（含 .md5 sidecar）。
     * 仅在远程配置解析成功后调用，基于最新 stationIndex 判定。
     * 场景：站名 A 改为 B 后，A 的 wav/md5 本地仍存在，需删除。
     *
     * @return 本次清理删除的文件总数（wav + md5 sidecar）
     */
    private int cleanupOrphanedVoicePacks() {
        // 收集配置中所有合法的文件名（wav 主文件 + 配置文件本身）
        Set<String> validFiles = new HashSet<>();
        validFiles.add(CONFIG_FILENAME);
        for (Entry e : stationIndex.values()) {
            if (e.cnFile != null && !e.cnFile.isEmpty()) validFiles.add(e.cnFile);
            if (e.enFile != null && !e.enFile.isEmpty()) validFiles.add(e.enFile);
        }
        File[] files = cacheDir.listFiles();
        if (files == null) return 0;
        // 先清空文件缓存，避免并发查询拿到即将被删除的 File 引用
        fileCache.clear();
        fileMissCache.clear();
        int deleted = 0;
        for (File f : files) {
            String name = f.getName();
            // 跳过下载中间态文件
            if (name.endsWith(".tmp")) continue;
            // md5 sidecar：对应主文件不在配置中则删除
            if (name.endsWith(".md5")) {
                String mainFile = name.substring(0, name.length() - 4);
                if (!validFiles.contains(mainFile)) {
                    if (f.delete()) {
                        deleted++;
                        Log.d(TAG, "清理过时 md5 sidecar: " + name);
                    }
                }
                continue;
            }
            // wav 文件不在配置中 → 删除主文件及其 sidecar
            if (name.endsWith(".wav") && !validFiles.contains(name)) {
                if (f.delete()) {
                    deleted++;
                    Log.d(TAG, "清理过时语音包: " + name);
                    File sidecar = new File(cacheDir, name + ".md5");
                    if (sidecar.exists() && sidecar.delete()) deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * 查询最近一次自动清理的结果。
     * 每次远程配置解析后都会更新（包括 deleted=0），用于 UI 判断是否需要提示。
     *
     * @return 数组 [count, time]：count=清理文件数，time=时间戳；
     *         count=0 表示本次配置检查未发生清理（无需提示）
     */
    public long[] getLastCleanupResult() {
        return new long[]{lastCleanupCount, lastCleanupTime};
    }

    private Entry findEntryByFilename(String filename) {
        if (filename == null) return null;
        for (Entry e : stationIndex.values()) {
            if (filename.equals(e.cnFile) || filename.equals(e.enFile)) return e;
        }
        return null;
    }

    /** 比对 sidecar 中存储的 md5 与配置中期望的 md5，决定是否需要重下 */
    private boolean md5SidecarMatches(String filename, String expectedMd5) {
        if (expectedMd5 == null || expectedMd5.isEmpty()) return true;
        File sidecar = new File(cacheDir, filename + ".md5");
        if (!sidecar.exists()) return false;
        try (FileInputStream fis = new FileInputStream(sidecar)) {
            byte[] b = new byte[(int) sidecar.length()];
            int read = fis.read(b);
            if (read <= 0) return false;
            String stored = new String(b, 0, read, StandardCharsets.UTF_8).trim();
            return expectedMd5.equals(stored);
        } catch (IOException e) {
            return false;
        }
    }

    private void writeSidecarMd5(String filename, String md5) {
        File sidecar = new File(cacheDir, filename + ".md5");
        try (FileOutputStream fos = new FileOutputStream(sidecar)) {
            fos.write(md5.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "写 md5 sidecar 失败: " + e.getMessage());
        }
    }

    /**
     * 获取站点的英文展示文本（详情页列表在站名下方显示，供后续扩展调用）。
     *
     * 规则：
     *  - 配置已加载且 stationName 命中索引 → 优先返回其 en 字段（如 "Dingyanwang Residential Area"）。
     *  - en 字段为空/缺失 → 回退为拼音，且每个音节首字母大写（如 "丁严王小区" → "Ding Yan Wang Xiao Qu"）。
     *
     * @param stationName 中文站名
     * @return 英文或拼音文本；stationName 为空时返回 null。配置未加载且拼音为空等极端情况返回空串。
     */
    public String getStationEnglish(String stationName) {
        if (stationName == null || stationName.isEmpty()) return null;
        Entry e = stationIndex.get(stationName);
        if (e != null && e.en != null && !e.en.isEmpty()) {
            return e.en;
        }
        // 回退：拼音（每个音节首字母大写）
        return toPinyinTitleCase(stationName);
    }

    /**
     * 将文本转为拼音，每个音节首字母大写，音节之间以空格分隔。
     * 汉字 → 拼音；非汉字（字母/数字/标点）原样保留并与相邻非汉字合并为同一段。
     * 例："丁严王小区" → "Ding Yan Wang Xiao Qu"；"9路B" → "9 Lu B"。
     */
    static String toPinyinTitleCase(String text) {
        if (text == null || text.isEmpty()) return "";
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        // ü 用 v 表示（WITH_V），避免默认 WITH_U_AND_COLON 产生的冒号（如"旅"→"lu:"）
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        List<String> tokens = new ArrayList<>();
        StringBuilder nonHan = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FA5) { // 汉字
                if (nonHan.length() > 0) {
                    tokens.add(nonHan.toString());
                    nonHan.setLength(0);
                }
                try {
                    String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c, format);
                    String py = (pinyins != null && pinyins.length > 0) ? pinyins[0] : String.valueOf(c);
                    if (!py.isEmpty()) {
                        py = Character.toUpperCase(py.charAt(0)) + py.substring(1);
                    }
                    tokens.add(py);
                } catch (BadHanyuPinyinOutputFormatCombination ex) {
                    tokens.add(String.valueOf(c));
                }
            } else {
                nonHan.append(c); // 非汉字累积，作为同一段
            }
        }
        if (nonHan.length() > 0) tokens.add(nonHan.toString());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    static class Entry {
        String stationName;
        String en;
        String voice;
        String cnFile;
        String enFile;
        String cnMd5;
        String enMd5;
    }

    /** 缺失分类统计结果，供详情页展示具体站名 */
    static class MissingStat {
        /** 远程配置中无此站（远程不存在） */
        final List<String> notInRemote = new ArrayList<>();
        /** 远程有但本地文件不存在（未下载） */
        final List<String> notDownloaded = new ArrayList<>();
        /** 本地文件存在但 md5 不匹配（待更新） */
        final List<String> needUpdate = new ArrayList<>();
    }

    public interface ProgressCallback {
        void onProgress(int done, int total);
        void onComplete(boolean success);
    }

    /**
     * 批量下载回调（扩展 ProgressCallback），额外回报每个文件的失败情况，
     * 便于 UI 针对「语音包不存在（404）」给出明确提示，而不是卡在 0% 无反应。
     */
    public interface BatchProgressCallback extends ProgressCallback {
        /**
         * 单个文件下载失败。
         * @param stationName 该文件对应的站点名（便于 UI 提示是哪个语音包）
         * @param notFound    true=服务端明确返回 404（语音包不存在，终结性失败）；
         *                    false=网络或其他原因失败（可重试）
         */
        void onFileFailed(String stationName, boolean notFound);
    }
}
