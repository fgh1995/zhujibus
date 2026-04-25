package org.zjfgh.zhujibus;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;
public class TTSUtils implements TextToSpeech.OnInitListener {
    private static final String TAG = "TTSUtils";

    private static TTSUtils instance;
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private Context context;
    private Locale defaultLocale = Locale.CHINESE;
    private float speechRate = 1.0f;
    private float pitch = 1.0f;

    private SoundPool soundPool;
    private MediaPlayer durationMediaPlayer;
    private HashMap<Integer, Integer> soundMap = new HashMap<>();
    private Handler mainHandler = new Handler();
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private List<PlaybackItem> playbackQueue = new ArrayList<>();
    private boolean isPlaying = false;
    private String currentUtteranceId;
    private List<QueuedAnnouncement> pendingAnnouncements = new ArrayList<>();

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    private final ConcurrentHashMap<String, Integer> stationResIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> md5Cache = new ConcurrentHashMap<>();

    private static final AudioAttributes UNIFIED_AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .build();

    private static class PlaybackItem {
        enum Type { WAV, TTS_CN, TTS_EN, MEDIA_PLAYER_WAV }
        Type type;
        int rawResId;
        String text;

        PlaybackItem(int rawResId) {
            this.type = Type.WAV;
            this.rawResId = rawResId;
        }

        PlaybackItem(String text, Type type) {
            this.type = type;
            this.text = text;
        }
        
        PlaybackItem(int rawResId, Type type) {
            this.type = type;
            this.rawResId = rawResId;
        }
    }

    private static class QueuedAnnouncement {
        String lineName;
        String endStation;
        String nextStationName;

        QueuedAnnouncement(String lineName, String endStation, String nextStationName) {
            this.lineName = lineName;
            this.endStation = endStation;
            this.nextStationName = nextStationName;
        }
    }

    public static synchronized TTSUtils getInstance(Context context) {
        if (instance == null) {
            instance = new TTSUtils(context);
        }
        return instance;
    }

    private TTSUtils(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        backgroundThread = new HandlerThread("TTSBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        initAudioFocusRequest();
        initTTS();
        initSoundPool();
    }

    private void initAudioFocusRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            switch (focusChange) {
                                case AudioManager.AUDIOFOCUS_LOSS:
                                    Log.d(TAG, "AudioFocus: 失去焦点, 停止播放");
                                    stopAll();
                                    hasAudioFocus = false;
                                    break;
                                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                    Log.d(TAG, "AudioFocus: 暂时失去焦点");
                                    pausePlayback();
                                    break;
                                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                    Log.d(TAG, "AudioFocus: 暂时失去焦点, 降低音量");
                                    break;
                                case AudioManager.AUDIOFOCUS_GAIN:
                                    Log.d(TAG, "AudioFocus: 获得焦点");
                                    hasAudioFocus = true;
                                    resumePlayback();
                                    break;
                            }
                        }
                    })
                    .build();
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null || audioFocusRequest == null) {
            return false;
        }
        int result = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        }
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
            hasAudioFocus = false;
        }
    }

    private void pausePlayback() {
    }

    private void resumePlayback() {
    }

    private void initSoundPool() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES)
                .build();
        loadSounds();
    }

    private void loadSounds() {
        int[] cnNumRes = {
                R.raw.cn_num_0, R.raw.cn_num_1, R.raw.cn_num_2, R.raw.cn_num_3, R.raw.cn_num_4,
                R.raw.cn_num_5, R.raw.cn_num_6, R.raw.cn_num_7, R.raw.cn_num_8, R.raw.cn_num_9,
                R.raw.cn_num_10, R.raw.cn_num_11, R.raw.cn_num_12, R.raw.cn_num_13, R.raw.cn_num_14,
                R.raw.cn_num_15, R.raw.cn_num_16, R.raw.cn_num_17, R.raw.cn_num_18, R.raw.cn_num_19,
                R.raw.cn_num_20, R.raw.cn_num_21, R.raw.cn_num_22, R.raw.cn_num_23, R.raw.cn_num_24,
                R.raw.cn_num_25, R.raw.cn_num_26, R.raw.cn_num_27, R.raw.cn_num_28, R.raw.cn_num_29,
                R.raw.cn_num_30, R.raw.cn_num_31, R.raw.cn_num_32, R.raw.cn_num_33, R.raw.cn_num_34,
                R.raw.cn_num_35, R.raw.cn_num_36, R.raw.cn_num_37, R.raw.cn_num_38, R.raw.cn_num_39,
                R.raw.cn_num_40, R.raw.cn_num_41, R.raw.cn_num_42, R.raw.cn_num_43, R.raw.cn_num_44,
                R.raw.cn_num_45, R.raw.cn_num_46, R.raw.cn_num_47, R.raw.cn_num_48, R.raw.cn_num_49,
                R.raw.cn_num_50, R.raw.cn_num_51, R.raw.cn_num_52, R.raw.cn_num_53, R.raw.cn_num_54,
                R.raw.cn_num_55, R.raw.cn_num_56, R.raw.cn_num_57, R.raw.cn_num_58, R.raw.cn_num_59,
                R.raw.cn_num_60, R.raw.cn_num_61, R.raw.cn_num_62, R.raw.cn_num_63, R.raw.cn_num_64,
                R.raw.cn_num_65, R.raw.cn_num_66, R.raw.cn_num_67, R.raw.cn_num_68, R.raw.cn_num_69,
                R.raw.cn_num_70, R.raw.cn_num_71, R.raw.cn_num_72, R.raw.cn_num_73, R.raw.cn_num_74,
                R.raw.cn_num_75, R.raw.cn_num_76, R.raw.cn_num_77, R.raw.cn_num_78, R.raw.cn_num_79,
                R.raw.cn_num_80, R.raw.cn_num_81, R.raw.cn_num_82, R.raw.cn_num_83, R.raw.cn_num_84,
                R.raw.cn_num_85, R.raw.cn_num_86, R.raw.cn_num_87, R.raw.cn_num_88, R.raw.cn_num_89,
                R.raw.cn_num_90, R.raw.cn_num_91, R.raw.cn_num_92, R.raw.cn_num_93, R.raw.cn_num_94,
                R.raw.cn_num_95, R.raw.cn_num_96, R.raw.cn_num_97, R.raw.cn_num_98, R.raw.cn_num_99,
                R.raw.cn_num_100, R.raw.cn_num_yao, R.raw.cn_route,
                R.raw.cn_01_zhuji_bus_reminder, R.raw.cn_02_heading_to,
                R.raw.cn_03_direction, R.raw.cn_04_arriving,
                R.raw.en_01_zhuji_bus_reminder, R.raw.en_02_bound_for, R.raw.en_03_is_arriving_at
        };
        int[] enNumRes = {
                R.raw.en_num_0, R.raw.en_num_1, R.raw.en_num_2, R.raw.en_num_3, R.raw.en_num_4,
                R.raw.en_num_5, R.raw.en_num_6, R.raw.en_num_7, R.raw.en_num_8, R.raw.en_num_9,
                R.raw.en_num_10, R.raw.en_num_11, R.raw.en_num_12, R.raw.en_num_13, R.raw.en_num_14,
                R.raw.en_num_15, R.raw.en_num_16, R.raw.en_num_17, R.raw.en_num_18, R.raw.en_num_19,
                R.raw.en_num_20, R.raw.en_num_21, R.raw.en_num_22, R.raw.en_num_23, R.raw.en_num_24,
                R.raw.en_num_25, R.raw.en_num_26, R.raw.en_num_27, R.raw.en_num_28, R.raw.en_num_29,
                R.raw.en_num_30, R.raw.en_num_31, R.raw.en_num_32, R.raw.en_num_33, R.raw.en_num_34,
                R.raw.en_num_35, R.raw.en_num_36, R.raw.en_num_37, R.raw.en_num_38, R.raw.en_num_39,
                R.raw.en_num_40, R.raw.en_num_41, R.raw.en_num_42, R.raw.en_num_43, R.raw.en_num_44,
                R.raw.en_num_45, R.raw.en_num_46, R.raw.en_num_47, R.raw.en_num_48, R.raw.en_num_49,
                R.raw.en_num_50, R.raw.en_num_51, R.raw.en_num_52, R.raw.en_num_53, R.raw.en_num_54,
                R.raw.en_num_55, R.raw.en_num_56, R.raw.en_num_57, R.raw.en_num_58, R.raw.en_num_59,
                R.raw.en_num_60, R.raw.en_num_61, R.raw.en_num_62, R.raw.en_num_63, R.raw.en_num_64,
                R.raw.en_num_65, R.raw.en_num_66, R.raw.en_num_67, R.raw.en_num_68, R.raw.en_num_69,
                R.raw.en_num_70, R.raw.en_num_71, R.raw.en_num_72, R.raw.en_num_73, R.raw.en_num_74,
                R.raw.en_num_75, R.raw.en_num_76, R.raw.en_num_77, R.raw.en_num_78, R.raw.en_num_79,
                R.raw.en_num_80, R.raw.en_num_81, R.raw.en_num_82, R.raw.en_num_83, R.raw.en_num_84,
                R.raw.en_num_85, R.raw.en_num_86, R.raw.en_num_87, R.raw.en_num_88, R.raw.en_num_89,
                R.raw.en_num_90, R.raw.en_num_91, R.raw.en_num_92, R.raw.en_num_93, R.raw.en_num_94,
                R.raw.en_num_95, R.raw.en_num_96, R.raw.en_num_97, R.raw.en_num_98, R.raw.en_num_99,
                R.raw.en_route
        };
        for (int resId : cnNumRes) {
            int soundId = soundPool.load(context, resId, 1);
            soundMap.put(resId, soundId);
        }
        for (int resId : enNumRes) {
            int soundId = soundPool.load(context, resId, 1);
            soundMap.put(resId, soundId);
        }
    }

    private void initTTS() {
        tts = new TextToSpeech(context, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "TTS开始播放: " + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "TTS播放完成: " + utteranceId);
                if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
                    mainHandler.post(() -> playNext());
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS播放出错: " + utteranceId);
                if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
                    mainHandler.post(() -> playNext());
                }
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(defaultLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "语言不支持或数据缺失");
            } else {
                isInitialized = true;
                tts.setSpeechRate(speechRate);
                tts.setPitch(pitch);
                Log.d(TAG, "TTS初始化成功");
            }
        } else {
            Log.e(TAG, "TTS初始化失败" + status);
        }
    }

    public void setLanguage(Locale locale) {
        if (isInitialized) {
            defaultLocale = locale;
            tts.setLanguage(locale);
        }
    }

    public void setSpeechRate(float rate) {
        if (isInitialized) {
            speechRate = rate;
            tts.setSpeechRate(rate);
        }
    }

    public void setPitch(float pitch) {
        if (isInitialized) {
            this.pitch = pitch;
            tts.setPitch(pitch);
        }
    }

    public void speak(String text) {
        speak(text, null);
    }

    public void speak(String text, String utteranceId) {
        if (isInitialized && text != null && !text.isEmpty()) {
            if (utteranceId == null) {
                utteranceId = String.valueOf(System.currentTimeMillis());
            }
            if (!requestAudioFocus()) {
                Log.w(TAG, "无法获取音频焦点");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);
            }
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    public void playArrivalAnnouncement(String lineName, String startStation, String endStation, String nextStationName) {
        if (isPlaying) {
            pendingAnnouncements.add(new QueuedAnnouncement(lineName, endStation, nextStationName));
            return;
        }
        pendingAnnouncements.clear();
        pendingAnnouncements.add(new QueuedAnnouncement(lineName, endStation, nextStationName));
        playbackQueue.clear();
        queueArrivalAnnouncementWithDirection(lineName, endStation, nextStationName);
        isPlaying = true;
        playNext();
    }

    public void queueArrivalAnnouncement(String lineName, String startStation, String endStation, String nextStationName) {
        pendingAnnouncements.add(new QueuedAnnouncement(lineName, endStation, nextStationName));
    }

    private void queueArrivalAnnouncementWithDirection(String lineName, String endStation, String nextStationName) {
        playbackQueue.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        playbackQueue.add(new PlaybackItem(R.raw.cn_02_heading_to));
        addCnStationName(endStation);
        playbackQueue.add(new PlaybackItem(R.raw.cn_03_direction));
        addCnLineNumber(lineName);
        playbackQueue.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(nextStationName);

        String lineNameEn = lineName.replace("路", "");
        playbackQueue.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        addEnLineNumber(lineNameEn);
        playbackQueue.add(new PlaybackItem(R.raw.en_02_bound_for));
        addEnStationName(endStation);
        playbackQueue.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(nextStationName);
    }

    private void queueArrivalAnnouncement(String lineName, String nextStationName) {
        playbackQueue.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        addCnLineNumber(lineName);
        playbackQueue.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(nextStationName);

        String lineNameEn = lineName.replace("路", "");
        playbackQueue.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        addEnLineNumber(lineNameEn);
        playbackQueue.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(nextStationName);
    }

    private void mergeAndPlayQueuedAnnouncements() {
        playbackQueue.clear();
        if (pendingAnnouncements.size() <= 1) {
            pendingAnnouncements.clear();
            isPlaying = false;
            abandonAudioFocus();
            return;
        }

        String firstNextStation = pendingAnnouncements.get(1).nextStationName;

        playbackQueue.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        for (int i = 1; i < pendingAnnouncements.size(); i++) {
            QueuedAnnouncement qa = pendingAnnouncements.get(i);
            addCnLineNumber(qa.lineName);
        }
        playbackQueue.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(firstNextStation);

        playbackQueue.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        for (int i = 1; i < pendingAnnouncements.size(); i++) {
            QueuedAnnouncement qa = pendingAnnouncements.get(i);
            addEnLineNumber(qa.lineName.replace("路", ""));
        }
        playbackQueue.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(firstNextStation);

        pendingAnnouncements.clear();
        playNext();
    }

    public void playLineDetailAnnouncement(String lineName, String startStation, String endStation, String nextStationName) {
        stopAll();
        playbackQueue.clear();
        playbackQueue.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        playbackQueue.add(new PlaybackItem(R.raw.cn_02_heading_to));
        addCnStationName(endStation);
        playbackQueue.add(new PlaybackItem(R.raw.cn_03_direction));
        addCnLineNumber(lineName);
        playbackQueue.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(nextStationName);

        String lineNameEn = lineName.replace("路", "");
        playbackQueue.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        addEnLineNumber(lineNameEn);
        playbackQueue.add(new PlaybackItem(R.raw.en_02_bound_for));
        addEnStationName(endStation);
        playbackQueue.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(nextStationName);

        isPlaying = true;
        playNext();
    }

    private void playNext() {
        if (playbackQueue.isEmpty()) {
            if (!pendingAnnouncements.isEmpty()) {
                mergeAndPlayQueuedAnnouncements();
                return;
            }
            isPlaying = false;
            currentUtteranceId = null;
            abandonAudioFocus();
            return;
        }

        PlaybackItem item = playbackQueue.remove(0);

        if (item.type == PlaybackItem.Type.WAV) {
            playWav(item.rawResId);
        } else if (item.type == PlaybackItem.Type.MEDIA_PLAYER_WAV) {
            playMediaPlayerWav(item.rawResId);
        } else {
            playTts(item);
        }
    }

    private void playWav(int rawResId) {
        if (soundPool == null) {
            initSoundPool();
        }
        if (!requestAudioFocus()) {
            Log.w(TAG, "无法获取音频焦点");
        }
        soundPool.stop(0);
        Integer soundId = soundMap.get(rawResId);
        if (soundId != null) {
            releaseDurationMediaPlayer();
            durationMediaPlayer = new MediaPlayer();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    durationMediaPlayer.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);
                } else {
                    @SuppressWarnings("deprecation")
                    int streamType = AudioManager.STREAM_NOTIFICATION;
                    durationMediaPlayer.setAudioStreamType(streamType);
                }
                durationMediaPlayer.setDataSource(context, android.net.Uri.parse("android.resource://" + context.getPackageName() + "/" + rawResId));
                durationMediaPlayer.prepare();
                int duration = durationMediaPlayer.getDuration();
                soundPool.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f);
                mainHandler.postDelayed(this::playNext, duration);
            } catch (Exception e) {
                Log.e(TAG, "获取音频时长失败", e);
                soundPool.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f);
                mainHandler.postDelayed(this::playNext, getSoundDuration(rawResId));
            }
        } else {
            playNext();
        }
    }
    
    private void playMediaPlayerWav(int rawResId) {
        if (!requestAudioFocus()) {
            Log.w(TAG, "无法获取音频焦点");
        }
        releaseDurationMediaPlayer();
        durationMediaPlayer = new MediaPlayer();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                durationMediaPlayer.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);
            } else {
                @SuppressWarnings("deprecation")
                int streamType = AudioManager.STREAM_NOTIFICATION;
                durationMediaPlayer.setAudioStreamType(streamType);
            }
            durationMediaPlayer.setDataSource(context, android.net.Uri.parse("android.resource://" + context.getPackageName() + "/" + rawResId));
            durationMediaPlayer.prepare();
            int duration = durationMediaPlayer.getDuration();
            durationMediaPlayer.start();
            mainHandler.postDelayed(this::playNext, duration);
        } catch (Exception e) {
            Log.e(TAG, "使用MediaPlayer播放站点音频失败", e);
            playNext();
        }
    }

    private void releaseDurationMediaPlayer() {
        if (durationMediaPlayer != null) {
            try {
                durationMediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "释放durationMediaPlayer异常", e);
            }
            durationMediaPlayer = null;
        }
    }

    private int getSoundDuration(int rawResId) {
        if (rawResId >= R.raw.cn_num_0 && rawResId <= R.raw.cn_num_9) return 350;
        if (rawResId >= R.raw.cn_num_10 && rawResId <= R.raw.cn_num_99) return 450;
        if (rawResId == R.raw.cn_num_100) return 550;
        if (rawResId == R.raw.cn_num_yao) return 350;
        if (rawResId == R.raw.cn_route) return 400;
        if (rawResId >= R.raw.en_num_0 && rawResId <= R.raw.en_num_9) return 350;
        if (rawResId >= R.raw.en_num_10 && rawResId <= R.raw.en_num_99) return 450;
        if (rawResId == R.raw.cn_01_zhuji_bus_reminder) return 1600;
        if (rawResId == R.raw.cn_02_heading_to) return 500;
        if (rawResId == R.raw.cn_03_direction) return 500;
        if (rawResId == R.raw.cn_04_arriving) return 700;
        if (rawResId == R.raw.en_01_zhuji_bus_reminder) return 1300;
        if (rawResId == R.raw.en_02_bound_for) return 600;
        if (rawResId == R.raw.en_03_is_arriving_at) return 700;
        return 600;
    }

    private void playTts(PlaybackItem item) {
        if (!isInitialized) {
            playNext();
            return;
        }

        if (!requestAudioFocus()) {
            Log.w(TAG, "无法获取音频焦点");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);
        }

        currentUtteranceId = "tts_" + System.currentTimeMillis();
        Locale locale = item.type == PlaybackItem.Type.TTS_EN ? Locale.US : Locale.CHINESE;
        tts.setLanguage(locale);
        tts.setSpeechRate(speechRate);
        tts.setPitch(pitch);
        tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
    }

    private static final int[] CN_NUM_RES = {
        R.raw.cn_num_0, R.raw.cn_num_1, R.raw.cn_num_2, R.raw.cn_num_3, R.raw.cn_num_4,
        R.raw.cn_num_5, R.raw.cn_num_6, R.raw.cn_num_7, R.raw.cn_num_8, R.raw.cn_num_9,
        R.raw.cn_num_10, R.raw.cn_num_11, R.raw.cn_num_12, R.raw.cn_num_13, R.raw.cn_num_14,
        R.raw.cn_num_15, R.raw.cn_num_16, R.raw.cn_num_17, R.raw.cn_num_18, R.raw.cn_num_19,
        R.raw.cn_num_20, R.raw.cn_num_21, R.raw.cn_num_22, R.raw.cn_num_23, R.raw.cn_num_24,
        R.raw.cn_num_25, R.raw.cn_num_26, R.raw.cn_num_27, R.raw.cn_num_28, R.raw.cn_num_29,
        R.raw.cn_num_30, R.raw.cn_num_31, R.raw.cn_num_32, R.raw.cn_num_33, R.raw.cn_num_34,
        R.raw.cn_num_35, R.raw.cn_num_36, R.raw.cn_num_37, R.raw.cn_num_38, R.raw.cn_num_39,
        R.raw.cn_num_40, R.raw.cn_num_41, R.raw.cn_num_42, R.raw.cn_num_43, R.raw.cn_num_44,
        R.raw.cn_num_45, R.raw.cn_num_46, R.raw.cn_num_47, R.raw.cn_num_48, R.raw.cn_num_49,
        R.raw.cn_num_50, R.raw.cn_num_51, R.raw.cn_num_52, R.raw.cn_num_53, R.raw.cn_num_54,
        R.raw.cn_num_55, R.raw.cn_num_56, R.raw.cn_num_57, R.raw.cn_num_58, R.raw.cn_num_59,
        R.raw.cn_num_60, R.raw.cn_num_61, R.raw.cn_num_62, R.raw.cn_num_63, R.raw.cn_num_64,
        R.raw.cn_num_65, R.raw.cn_num_66, R.raw.cn_num_67, R.raw.cn_num_68, R.raw.cn_num_69,
        R.raw.cn_num_70, R.raw.cn_num_71, R.raw.cn_num_72, R.raw.cn_num_73, R.raw.cn_num_74,
        R.raw.cn_num_75, R.raw.cn_num_76, R.raw.cn_num_77, R.raw.cn_num_78, R.raw.cn_num_79,
        R.raw.cn_num_80, R.raw.cn_num_81, R.raw.cn_num_82, R.raw.cn_num_83, R.raw.cn_num_84,
        R.raw.cn_num_85, R.raw.cn_num_86, R.raw.cn_num_87, R.raw.cn_num_88, R.raw.cn_num_89,
        R.raw.cn_num_90, R.raw.cn_num_91, R.raw.cn_num_92, R.raw.cn_num_93, R.raw.cn_num_94,
        R.raw.cn_num_95, R.raw.cn_num_96, R.raw.cn_num_97, R.raw.cn_num_98, R.raw.cn_num_99,
        R.raw.cn_num_100
    };

    private static final int[] EN_NUM_RES = {
        R.raw.en_num_0, R.raw.en_num_1, R.raw.en_num_2, R.raw.en_num_3, R.raw.en_num_4,
        R.raw.en_num_5, R.raw.en_num_6, R.raw.en_num_7, R.raw.en_num_8, R.raw.en_num_9,
        R.raw.en_num_10, R.raw.en_num_11, R.raw.en_num_12, R.raw.en_num_13, R.raw.en_num_14,
        R.raw.en_num_15, R.raw.en_num_16, R.raw.en_num_17, R.raw.en_num_18, R.raw.en_num_19,
        R.raw.en_num_20, R.raw.en_num_21, R.raw.en_num_22, R.raw.en_num_23, R.raw.en_num_24,
        R.raw.en_num_25, R.raw.en_num_26, R.raw.en_num_27, R.raw.en_num_28, R.raw.en_num_29,
        R.raw.en_num_30, R.raw.en_num_31, R.raw.en_num_32, R.raw.en_num_33, R.raw.en_num_34,
        R.raw.en_num_35, R.raw.en_num_36, R.raw.en_num_37, R.raw.en_num_38, R.raw.en_num_39,
        R.raw.en_num_40, R.raw.en_num_41, R.raw.en_num_42, R.raw.en_num_43, R.raw.en_num_44,
        R.raw.en_num_45, R.raw.en_num_46, R.raw.en_num_47, R.raw.en_num_48, R.raw.en_num_49,
        R.raw.en_num_50, R.raw.en_num_51, R.raw.en_num_52, R.raw.en_num_53, R.raw.en_num_54,
        R.raw.en_num_55, R.raw.en_num_56, R.raw.en_num_57, R.raw.en_num_58, R.raw.en_num_59,
        R.raw.en_num_60, R.raw.en_num_61, R.raw.en_num_62, R.raw.en_num_63, R.raw.en_num_64,
        R.raw.en_num_65, R.raw.en_num_66, R.raw.en_num_67, R.raw.en_num_68, R.raw.en_num_69,
        R.raw.en_num_70, R.raw.en_num_71, R.raw.en_num_72, R.raw.en_num_73, R.raw.en_num_74,
        R.raw.en_num_75, R.raw.en_num_76, R.raw.en_num_77, R.raw.en_num_78, R.raw.en_num_79,
        R.raw.en_num_80, R.raw.en_num_81, R.raw.en_num_82, R.raw.en_num_83, R.raw.en_num_84,
        R.raw.en_num_85, R.raw.en_num_86, R.raw.en_num_87, R.raw.en_num_88, R.raw.en_num_89,
        R.raw.en_num_90, R.raw.en_num_91, R.raw.en_num_92, R.raw.en_num_93, R.raw.en_num_94,
        R.raw.en_num_95, R.raw.en_num_96, R.raw.en_num_97, R.raw.en_num_98, R.raw.en_num_99
    };

    private void addCnLineNumber(String lineName) {
        String numStr = lineName.replace("路", " ").trim();
        String[] parts = numStr.split("\\s+", 2);
        String numberPart = parts[0];
        String suffix = parts.length > 1 ? parts[1].trim() : "";
        try {
            int num = Integer.parseInt(numberPart);
            if (num >= 1 && num <= 100) {
                playbackQueue.add(new PlaybackItem(CN_NUM_RES[num]));
            } else if (num > 100) {
                String digits = String.valueOf(num);
                for (char c : digits.toCharArray()) {
                    int d = c - '0';
                    if (d == 1) {
                        playbackQueue.add(new PlaybackItem(R.raw.cn_num_yao));
                    } else {
                        playbackQueue.add(new PlaybackItem(CN_NUM_RES[d]));
                    }
                }
            }
            playbackQueue.add(new PlaybackItem(R.raw.cn_route));
            if (!suffix.isEmpty()) {
                playbackQueue.add(new PlaybackItem(suffix, PlaybackItem.Type.TTS_CN));
            }
        } catch (NumberFormatException e) {
            playbackQueue.add(new PlaybackItem(lineName, PlaybackItem.Type.TTS_CN));
        }
    }

    private void addEnLineNumber(String lineNameEn) {
        addEnLineNumber(lineNameEn, true);
    }

    private void addEnLineNumber(String lineNameEn, boolean withRoute) {
        String numStr = lineNameEn.trim();
        try {
            int num = Integer.parseInt(numStr);
            if (withRoute) {
                playbackQueue.add(new PlaybackItem(R.raw.en_route));
            }
            if (num >= 0 && num <= 99) {
                playbackQueue.add(new PlaybackItem(EN_NUM_RES[num]));
            } else if (num >= 100) {
                String digits = String.valueOf(num);
                for (char c : digits.toCharArray()) {
                    int d = c - '0';
                    if (d >= 0 && d <= 9) {
                        playbackQueue.add(new PlaybackItem(EN_NUM_RES[d]));
                    }
                }
            }
        } catch (NumberFormatException e) {
            if (withRoute) {
                playbackQueue.add(new PlaybackItem(R.raw.en_route));
            }
            playbackQueue.add(new PlaybackItem(lineNameEn, PlaybackItem.Type.TTS_EN));
        }
    }

    private void addCnStationName(String stationName) {
        Integer resId = getCachedStationResId(stationName, "cn_stations_");
        if (resId != null) {
            playbackQueue.add(new PlaybackItem(resId, PlaybackItem.Type.MEDIA_PLAYER_WAV));
        } else {
            playbackQueue.add(new PlaybackItem(stationName, PlaybackItem.Type.TTS_CN));
        }
    }

    private void addEnStationName(String stationName) {
        Integer resId = getCachedStationResId(stationName, "en_stations_");
        if (resId != null) {
            playbackQueue.add(new PlaybackItem(resId, PlaybackItem.Type.MEDIA_PLAYER_WAV));
        } else {
            playbackQueue.add(new PlaybackItem(stationName, PlaybackItem.Type.TTS_CN));
        }
    }

    private Integer getCachedStationResId(String stationName, String prefix) {
        String cacheKey = prefix + stationName;
        Integer cached = stationResIdCache.get(cacheKey);
        if (cached != null) {
            return cached == -1 ? null : cached;
        }

        String normalized = normalizeStationName(stationName);
        String md5Hash = getCachedMd5(normalized);
        if (md5Hash != null) {
            Integer resId = getStationResIdByMd5(md5Hash, prefix);
            stationResIdCache.put(cacheKey, resId == null ? -1 : resId);
            return resId;
        }
        stationResIdCache.put(cacheKey, -1);
        return null;
    }

    private String getCachedMd5(String input) {
        String cached = md5Cache.get(input);
        if (cached != null) {
            return cached;
        }
        String md5 = toMd5(input);
        if (md5 != null) {
            md5Cache.put(input, md5);
        }
        return md5;
    }

    private Integer getStationResIdByMd5(String md5Hash, String prefix) {
        try {
            Field field = R.raw.class.getField(prefix + md5Hash);
            return field.getInt(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer getStationResId(String pinyinName, String prefix) {
        try {
            Field field = R.raw.class.getField(prefix + pinyinName);
            return field.getInt(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeStationName(String stationName) {
        String normalized = stationName;
        normalized = normalized.replace("（", "(").replace("）", ")");
        normalized = normalized.replace("【", "[").replace("】", "]");
        normalized = normalized.replace("《", "<").replace("》", ">");
        normalized = normalized.replace("、", ",");
        normalized = normalized.replace("。", ".");
        normalized = normalized.replace("，", ",");
        normalized = normalized.replaceAll("[()\\[\\]<>.,，。、]", "");
        return normalized;
    }

    private String toMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "MD5计算失败", e);
            return null;
        }
    }

    public void stopAll() {
        playbackQueue.clear();
        pendingAnnouncements.clear();
        isPlaying = false;
        currentUtteranceId = null;
        mainHandler.removeCallbacksAndMessages(null);
        releaseDurationMediaPlayer();
        abandonAudioFocus();
        if (isInitialized) {
            tts.stop();
        }
    }

    public void stop() {
        abandonAudioFocus();
        if (isInitialized) {
            tts.stop();
        }
    }

    public void shutdown() {
        stopAll();
        abandonAudioFocus();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            isInitialized = false;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
        instance = null;
    }

    public boolean isSpeaking() {
        return isPlaying || (isInitialized && tts.isSpeaking());
    }

    public boolean isInitialized() {
        return isInitialized;
    }
}
