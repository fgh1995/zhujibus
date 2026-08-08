package org.zjfgh.zhujibus;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 语音包在线化管理器。
 *
 * 职责：
 * - 拉取并解析 cn_to_en.json，建立 stationName → entry 索引
 * - 站名 wav 文件本地缓存（context.filesDir/voicepack）
 * - 降级链下载：原始 raw → 配置源 githubAddSpeed → 硬编码 github.360967.xyz
 * - cnMd5/enMd5 内容指纹比对，决定重下
 * - 批量预下载（线路包用）
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

    /** 最近一次自动清理的过时文件数（含 wav 与 md5 sidecar）；0 表示未发生过清理 */
    private volatile int lastCleanupCount = 0;
    /** 最近一次清理的时间戳（System.currentTimeMillis），0 表示未发生过清理 */
    private volatile long lastCleanupTime = 0L;

    /** 从 RemoteConfig 解析得到的 githubAddSpeed（已去掉末尾 /）；空串表示未配置 */
    private volatile String configAccelUrl = "";

    private VoicePackManager(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = new File(this.context.getFilesDir(), "voicepack");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        this.http = new OkHttpClient();
        this.executor = Executors.newSingleThreadExecutor();
        initAsync();
    }

    public static synchronized VoicePackManager getInstance(Context context) {
        if (instance == null) {
            instance = new VoicePackManager(context);
        }
        return instance;
    }

    /** 后台拉取并解析 cn_to_en.json，建立 stationName → entry 索引 */
    public void initAsync() {
        if (configLoaded.get()) return;
        if (!configFetching.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                fetchConfig();
            } catch (Exception e) {
                Log.w(TAG, "initAsync 失败: " + e.getMessage());
            } finally {
                configFetching.set(false);
            }
        });
    }

    /**
     * 从 RemoteConfig 推入 githubAddSpeed（可空）。
     * 配置变化后重新触发配置拉取，以便尽快切源。
     */
    public void setConfigAccelUrl(String url) {
        if (url == null) url = "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.equals(configAccelUrl)) {
            configAccelUrl = url;
            // 配置源变更，清空 miss 缓存让后续查询重新评估
            fileMissCache.clear();
            initAsync();
        }
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
            initAsync();
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
     */
    public void downloadBatchAsync(List<String> stationNames, ProgressCallback cb) {
        if (stationNames == null || stationNames.isEmpty()) {
            if (cb != null) cb.onComplete(true);
            return;
        }
        executor.execute(() -> {
            List<String> filenames = new ArrayList<>();
            for (String name : stationNames) {
                Entry e = stationIndex.get(name);
                if (e == null) continue;
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
            if (e == null) {
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

    private boolean downloadFile(String filename) {
        try (Response resp = executeWithFallback(filename)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                Log.w(TAG, "下载失败: " + filename);
                return false;
            }
            byte[] data = resp.body().bytes();
            if (data.length == 0) return false;
            File tmp = new File(cacheDir, filename + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            File dest = new File(cacheDir, filename);
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return false;
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
            return true;
        } catch (IOException e) {
            Log.w(TAG, "下载异常 " + filename + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 降级链下载：原始 raw → 配置源 githubAddSpeed → 硬编码 github.360967.xyz
     * 任一源成功即返回。返回的 Response 由调用方关闭。
     */
    private Response executeWithFallback(String filename) throws IOException {
        String originalUrl = BASE_RAW_URL + filename;

        // 1. 原始地址
        try {
            Request req = new Request.Builder().url(originalUrl).build();
            Response resp = http.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) return resp;
            resp.close();
        } catch (IOException e) {
            Log.d(TAG, "原始源失败 " + filename + ": " + e.getMessage());
        }

        // 2. 配置源 githubAddSpeed（拼接格式：<githubAddSpeed>/<原始URL>）
        if (configAccelUrl != null && !configAccelUrl.isEmpty()) {
            try {
                Request req = new Request.Builder().url(configAccelUrl + "/" + originalUrl).build();
                Response resp = http.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) return resp;
                resp.close();
            } catch (IOException e) {
                Log.d(TAG, "配置源失败 " + filename + ": " + e.getMessage());
            }
        }

        // 3. 硬编码兜底
        try {
            Request req = new Request.Builder().url(HARDCODED_FALLBACK + originalUrl).build();
            Response resp = http.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) return resp;
            resp.close();
        } catch (IOException e) {
            Log.d(TAG, "硬编码源失败 " + filename + ": " + e.getMessage());
        }

        return null;
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
}
