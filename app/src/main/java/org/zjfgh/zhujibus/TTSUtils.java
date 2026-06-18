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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
    private MediaPlayer mediaPlayer;
    private HashMap<Integer, Integer> soundMap = new HashMap<>();
    private Handler mainHandler = new Handler();
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private boolean isPlaying = false;
    private String currentUtteranceId;
    private List<QueuedAnnouncement> pendingAnnouncements = new ArrayList<>();

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    private final ConcurrentHashMap<String, Integer> stationResIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> md5Cache = new ConcurrentHashMap<>();

    private byte[] mergedAudioBytes;

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
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    private void resumePlayback() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    private void initSoundPool() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES)
                .build();
        backgroundHandler.post(this::loadSounds);
    }

    private void loadSounds() {
        int[] cnNumRes = {
                R.raw.dingdong,
                R.raw.network_stop_chime,
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
                R.raw.cn_00_welcom_zhuji, R.raw.cn_00_bus, R.raw.cn_01_this_bus_is_from,R.raw.cn_this_is_a_driver_only_bus,
                R.raw.cn_01_zhuji_bus_reminder, R.raw.cn_02_heading_to, R.raw.cn_03_direction,
                R.raw.cn_03_starting_stop_departing, R.raw.cn_04_arriving, R.raw.cn_04_the_bus_is_moving_tips,
                R.raw.cn_05_next_station, R.raw.cn_06_press_the_bell_to_get_off_tips,
                R.raw.cn_17_terminal_station, R.raw.cn_07_passengers_terminal_station, R.raw.cn_08_thank_you_for_riding_with_us, R.raw.cn_091_we_are_now_at,
                R.raw.en_00_welcome_aboard_the_zhuji, R.raw.en_01_this_bus_is_from, R.raw.en_01_zhuji_bus_reminder, R.raw.en_02_bound_for,
                R.raw.en_02_to, R.raw.en_03_is_arriving_at, R.raw.en_03_starting_stop_departing,
                R.raw.en_04_the_bus_is_moving_tips, R.raw.en_05_next_station,
                R.raw.en_06_press_the_bell_to_get_off_tips, R.raw.en_17_terminal_station,R.raw.en_07_passengers_terminal_station,
                R.raw.en_08_thank_you_for_riding_with_us, R.raw.en_091_we_are_now_at, R.raw.en_092_passengers_getting_off,
                R.raw.cn_031_starting_stop_departing
        };
        int[] enNumRes = {
                R.raw.dingdong,
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
                R.raw.en_route, R.raw.en_031_starting_stop_departing,R.raw.en_this_is_a_driver_only_bus
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
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS播放出错: " + utteranceId);
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
            tts.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);
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
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            buildArrivalAnnouncementWithDirection(items, lineName, endStation, nextStationName);
            buildAndPlayMergedAudio(items);
        });
    }

    public void queueArrivalAnnouncement(String lineName, String startStation, String endStation, String nextStationName) {
        pendingAnnouncements.add(new QueuedAnnouncement(lineName, endStation, nextStationName));
    }

    private void buildArrivalAnnouncementWithDirection(List<PlaybackItem> items, String lineName, String endStation, String nextStationName) {
        items.add(new PlaybackItem(R.raw.network_stop_chime));
        items.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        items.add(new PlaybackItem(R.raw.cn_02_heading_to));
        addCnStationName(items, endStation);
        items.add(new PlaybackItem(R.raw.cn_03_direction));
        addCnLineNumber(items, lineName);
        items.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(items, nextStationName);

        String lineNameEn = lineName.replace("路", "");
        items.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        addEnLineNumber(items, lineNameEn);
        items.add(new PlaybackItem(R.raw.en_02_bound_for));
        addEnStationName(items, endStation);
        items.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(items, nextStationName);
    }

    private void buildArrivalAnnouncement(List<PlaybackItem> items, String lineName, String nextStationName) {
        items.add(new PlaybackItem(R.raw.network_stop_chime));
        items.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
        addCnLineNumber(items, lineName);
        items.add(new PlaybackItem(R.raw.cn_04_arriving));
        addCnStationName(items, nextStationName);

        String lineNameEn = lineName.replace("路", "");
        items.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
        addEnLineNumber(items, lineNameEn);
        items.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
        addEnStationName(items, nextStationName);
    }

    private void mergeAndPlayQueuedAnnouncements() {
        backgroundHandler.post(() -> {
            if (pendingAnnouncements.size() <= 1) {
                pendingAnnouncements.clear();
                isPlaying = false;
                mainHandler.post(this::abandonAudioFocus);
                return;
            }

            List<PlaybackItem> items = new ArrayList<>();
            String firstNextStation = pendingAnnouncements.get(1).nextStationName;
            items.add(new PlaybackItem(R.raw.network_stop_chime));
            items.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
            for (int i = 1; i < pendingAnnouncements.size(); i++) {
                QueuedAnnouncement qa = pendingAnnouncements.get(i);
                addCnLineNumber(items, qa.lineName);
            }
            items.add(new PlaybackItem(R.raw.cn_04_arriving));
            addCnStationName(items, firstNextStation);

            items.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
            for (int i = 1; i < pendingAnnouncements.size(); i++) {
                QueuedAnnouncement qa = pendingAnnouncements.get(i);
                addEnLineNumber(items, qa.lineName.replace("路", ""));
            }
            items.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
            addEnStationName(items, firstNextStation);

            pendingAnnouncements.clear();
            buildAndPlayMergedAudio(items);
        });
    }

    public void playLineDetailAnnouncement(String lineName, String startStation, String endStation, String nextStationName) {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.network_stop_chime));
            items.add(new PlaybackItem(R.raw.cn_01_zhuji_bus_reminder));
            items.add(new PlaybackItem(R.raw.cn_02_heading_to));
            addCnStationName(items, endStation);
            items.add(new PlaybackItem(R.raw.cn_03_direction));
            addCnLineNumber(items, lineName);
            items.add(new PlaybackItem(R.raw.cn_04_arriving));
            addCnStationName(items, nextStationName);

            String lineNameEn = lineName.replace("路", "");
            items.add(new PlaybackItem(R.raw.en_01_zhuji_bus_reminder));
            addEnLineNumber(items, lineNameEn);
            items.add(new PlaybackItem(R.raw.en_02_bound_for));
            addEnStationName(items, endStation);
            items.add(new PlaybackItem(R.raw.en_03_is_arriving_at));
            addEnStationName(items, nextStationName);

            buildAndPlayMergedAudio(items);
        });
    }

    public void playGpsStartStationAnnouncement(String lineName, String startStation, String endStation, String nextStation) {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.cn_00_welcom_zhuji));
            addCnLineNumber(items, lineName);
            items.add(new PlaybackItem(R.raw.cn_this_is_a_driver_only_bus));
            items.add(new PlaybackItem(R.raw.cn_01_this_bus_is_from));
            addCnStationName(items, startStation);
            items.add(new PlaybackItem(R.raw.cn_02_heading_to));
            addCnStationName(items, endStation);
            items.add(new PlaybackItem(R.raw.cn_05_next_station));
            addCnStationName(items, nextStation);
            items.add(new PlaybackItem(R.raw.cn_03_starting_stop_departing));

            String lineNameEn = lineName.replace("路", "");
            items.add(new PlaybackItem(R.raw.en_00_welcome_aboard_the_zhuji));
            addEnLineNumber(items, lineNameEn);
            items.add(new PlaybackItem(R.raw.en_this_is_a_driver_only_bus));
            items.add(new PlaybackItem(R.raw.en_01_this_bus_is_from));
            addEnStationName(items, startStation);
            items.add(new PlaybackItem(R.raw.en_02_to));
            addEnStationName(items, endStation);
            items.add(new PlaybackItem(R.raw.en_05_next_station));
            addEnStationName(items, nextStation);
            items.add(new PlaybackItem(R.raw.en_03_starting_stop_departing));

            buildAndPlayMergedAudio(items);
        });
    }

    public void playGpsMiddleStationAnnouncement(String stationName) {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.dingdong));
            addCnStationName(items, stationName);
            items.add(new PlaybackItem(R.raw.cn_091_we_are_now_at));
            items.add(new PlaybackItem(R.raw.en_091_we_are_now_at));
            addEnStationName(items, stationName);
            items.add(new PlaybackItem(R.raw.en_092_passengers_getting_off));
            buildAndPlayMergedAudio(items);
        });
    }

    public void playGpsTerminalStationAnnouncement(String stationName) {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.dingdong));
            items.add(new PlaybackItem(R.raw.cn_07_passengers_terminal_station));
            addCnStationName(items, stationName);
            items.add(new PlaybackItem(R.raw.cn_08_thank_you_for_riding_with_us));
            items.add(new PlaybackItem(R.raw.en_07_passengers_terminal_station));
            addEnStationName(items, stationName);
            items.add(new PlaybackItem(R.raw.en_08_thank_you_for_riding_with_us));
            buildAndPlayMergedAudio(items);
        });
    }

    public void playGpsLeavingStationAnnouncement(String nextStation, boolean isTerminal) {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.dingdong));
            items.add(new PlaybackItem(R.raw.cn_04_the_bus_is_moving_tips));
            items.add(new PlaybackItem(R.raw.cn_05_next_station));
            if (isTerminal) {
                items.add(new PlaybackItem(R.raw.cn_17_terminal_station));
            }
            addCnStationName(items, nextStation);
            if (isTerminal) {
                items.add(new PlaybackItem(R.raw.cn_031_starting_stop_departing));
            } else {
                items.add(new PlaybackItem(R.raw.cn_06_press_the_bell_to_get_off_tips));
            }

            items.add(new PlaybackItem(R.raw.en_04_the_bus_is_moving_tips));
            items.add(new PlaybackItem(R.raw.en_05_next_station));
            if (isTerminal) {
                items.add(new PlaybackItem(R.raw.en_17_terminal_station));
            }
            addEnStationName(items, nextStation);
            if (isTerminal) {
                items.add(new PlaybackItem(R.raw.en_031_starting_stop_departing));
            } else {
                items.add(new PlaybackItem(R.raw.en_06_press_the_bell_to_get_off_tips));
            }
            buildAndPlayMergedAudio(items);
        });
    }

    public void playScanCodeSuccessSound() {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.di));
            items.add(new PlaybackItem(R.raw.payment_successful));
            buildAndPlayMergedAudio(items);
        });
    }

    public void playCardSwipeSuccessSound() {
        stopAll();
        backgroundHandler.post(() -> {
            List<PlaybackItem> items = new ArrayList<>();
            items.add(new PlaybackItem(R.raw.di));
            buildAndPlayMergedAudio(items);
        });
    }

    private static class WavInfo {
        byte[] pcmData;
        int sampleRate;
        int channels;
        int bitsPerSample;

        WavInfo(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
            this.pcmData = pcmData;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }
    }

    private void buildAndPlayMergedAudio(List<PlaybackItem> items) {
        try {
            List<WavInfo> wavInfos = new ArrayList<>();

            for (PlaybackItem item : items) {
                if (item.type == PlaybackItem.Type.TTS_CN || item.type == PlaybackItem.Type.TTS_EN) {
                    WavInfo ttsWav = synthesizeTtsToWav(item.text,
                            item.type == PlaybackItem.Type.TTS_EN ? Locale.US : Locale.CHINESE);
                    if (ttsWav != null && ttsWav.pcmData.length > 0) {
                        wavInfos.add(ttsWav);
                    }
                } else {
                    WavInfo rawWav = readRawWav(item.rawResId);
                    if (rawWav != null && rawWav.pcmData.length > 0) {
                        wavInfos.add(rawWav);
                    }
                }
            }

            if (wavInfos.isEmpty()) {
                isPlaying = false;
                mainHandler.post(this::abandonAudioFocus);
                return;
            }

            int targetSampleRate = 22050;
            int targetChannels = 1;
            int targetBitsPerSample = 16;

            for (int i = 1; i < wavInfos.size(); i++) {
                WavInfo info = wavInfos.get(i);
                if (info.sampleRate != targetSampleRate || info.channels != targetChannels || info.bitsPerSample != targetBitsPerSample) {
                    Log.w(TAG, "WAV格式不一致，需要重采样: " + info.sampleRate + "Hz/" + info.channels + "ch/" + info.bitsPerSample + "bit -> " + targetSampleRate + "Hz/" + targetChannels + "ch/" + targetBitsPerSample + "bit");
                    WavInfo resampledInfo = resampleAudio(info, targetSampleRate, targetChannels, targetBitsPerSample);
                    if (resampledInfo != null) {
                        wavInfos.set(i, resampledInfo);
                    }
                }
            }

            int totalPcmSize = 0;
            for (WavInfo info : wavInfos) {
                totalPcmSize += info.pcmData.length;
            }

            byte[] mergedPcm = new byte[totalPcmSize];
            int offset = 0;
            for (WavInfo info : wavInfos) {
                System.arraycopy(info.pcmData, 0, mergedPcm, offset, info.pcmData.length);
                offset += info.pcmData.length;
            }

            byte[] wavHeader = buildWavHeader(mergedPcm.length, targetSampleRate, targetChannels, targetBitsPerSample);
            mergedAudioBytes = new byte[wavHeader.length + mergedPcm.length];
            System.arraycopy(wavHeader, 0, mergedAudioBytes, 0, wavHeader.length);
            System.arraycopy(mergedPcm, 0, mergedAudioBytes, wavHeader.length, mergedPcm.length);

            mainHandler.post(this::playMergedAudioFromMemory);
        } catch (Exception e) {
            Log.e(TAG, "拼接音频失败", e);
            isPlaying = false;
            mainHandler.post(this::abandonAudioFocus);
        }
    }

    private void playMergedAudioFromMemory() {
        if (!requestAudioFocus()) {
            Log.w(TAG, "无法获取音频焦点");
        }

        releaseMediaPlayer();
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setAudioAttributes(UNIFIED_AUDIO_ATTRIBUTES);

            mediaPlayer.setDataSource(new ByteArrayMediaDataSource(mergedAudioBytes));

            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                abandonAudioFocus();
                if (!pendingAnnouncements.isEmpty()) {
                    mergeAndPlayQueuedAnnouncements();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "合并音频播放出错: " + what + ", " + extra);
                isPlaying = false;
                abandonAudioFocus();
                return true;
            });
            mediaPlayer.prepare();
            isPlaying = true;
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "播放合并音频失败", e);
            isPlaying = false;
            abandonAudioFocus();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "释放mediaPlayer异常", e);
            }
            mediaPlayer = null;
        }
    }

    private WavInfo synthesizeTtsToWav(String text, Locale locale) {
        if (!isInitialized) {
            return null;
        }

        final WavInfo[] result = new WavInfo[1];
        final CountDownLatch latch = new CountDownLatch(1);

        File tempFile = new File(context.getCacheDir(), "tts_temp_" + System.currentTimeMillis() + ".wav");

        tts.setLanguage(locale);
        tts.setSpeechRate(speechRate);
        tts.setPitch(pitch);

        int status = tts.synthesizeToFile(text, null, tempFile, "tts_synth_" + System.currentTimeMillis());
        if (status != TextToSpeech.SUCCESS) {
            return null;
        }

        mainHandler.postDelayed(() -> {
            try {
                if (tempFile.exists() && tempFile.length() > 0) {
                    result[0] = readWavFile(tempFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "读取TTS合成文件失败", e);
            } finally {
                latch.countDown();
            }
        }, 500);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        tempFile.delete();
        return result[0];
    }

    private WavInfo readRawWav(int rawResId) {
        try (InputStream is = context.getResources().openRawResource(rawResId);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return parseWav(baos.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "读取WAV资源失败: " + rawResId, e);
            return null;
        }
    }

    private WavInfo readWavFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return parseWav(baos.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "读取WAV文件失败", e);
            return null;
        }
    }

    private WavInfo parseWav(byte[] wavData) {
        if (wavData.length < 44) {
            return new WavInfo(wavData, 16000, 1, 16);
        }

        ByteBuffer buffer = ByteBuffer.wrap(wavData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.position(22);
        int channels = buffer.getShort() & 0xFFFF;

        buffer.position(24);
        int sampleRate = buffer.getInt();

        buffer.position(34);
        int bitsPerSample = buffer.getShort() & 0xFFFF;

        int[] dataChunkInfo = findDataChunkInfo(wavData);
        int dataOffset;
        int dataSize;
        if (dataChunkInfo != null) {
            dataOffset = dataChunkInfo[0];
            dataSize = dataChunkInfo[1];
        } else {
            dataOffset = 44;
            dataSize = wavData.length - 44;
        }

        if (dataSize <= 0 || dataOffset + dataSize > wavData.length) {
            dataSize = wavData.length - dataOffset;
        }

        byte[] pcm = new byte[dataSize];
        System.arraycopy(wavData, dataOffset, pcm, 0, dataSize);

        return new WavInfo(pcm, sampleRate, channels, bitsPerSample);
    }

    private int[] findDataChunkInfo(byte[] wavData) {
        for (int i = 36; i < wavData.length - 8; i++) {
            if (wavData[i] == 'd' && wavData[i + 1] == 'a' && wavData[i + 2] == 't' && wavData[i + 3] == 'a') {
                ByteBuffer buffer = ByteBuffer.wrap(wavData);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                buffer.position(i + 4);
                int dataSize = buffer.getInt();
                int dataOffset = i + 8;
                return new int[]{dataOffset, dataSize};
            }
        }
        return null;
    }

    private byte[] buildWavHeader(int pcmDataLength, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int totalDataLen = pcmDataLength + 36;

        ByteBuffer buffer = ByteBuffer.allocate(44);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(totalDataLen);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitsPerSample);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(pcmDataLength);
        return buffer.array();
    }

    private WavInfo resampleAudio(WavInfo source, int targetSampleRate, int targetChannels, int targetBitsPerSample) {
        try {
            int sourceBytesPerSample = source.bitsPerSample / 8;
            int targetBytesPerSample = targetBitsPerSample / 8;
            int sourceBlockAlign = source.channels * sourceBytesPerSample;
            int targetBlockAlign = targetChannels * targetBytesPerSample;

            int sourceSamples = source.pcmData.length / sourceBlockAlign;
            double ratio = (double) targetSampleRate / source.sampleRate;
            int targetSamples = (int) (sourceSamples * ratio);

            if (targetSamples <= 0) {
                return null;
            }

            byte[] targetPcm;
            if (source.channels == targetChannels && source.bitsPerSample == targetBitsPerSample && source.sampleRate == targetSampleRate) {
                return source;
            }

            if (source.channels == 2 && targetChannels == 1) {
                targetPcm = convertStereoToMono(source.pcmData, source.bitsPerSample, ratio);
            } else if (source.channels == 1 && targetChannels == 1) {
                if (source.bitsPerSample == targetBitsPerSample && source.sampleRate == targetSampleRate) {
                    return source;
                }
                targetPcm = resampleOnly(source.pcmData, source.bitsPerSample, source.sampleRate, targetSampleRate);
            } else {
                byte[] monoPcm = source.channels == 2 ? convertStereoToMono(source.pcmData, source.bitsPerSample, 1.0) : source.pcmData;
                targetPcm = resampleOnly(monoPcm, source.bitsPerSample, source.sampleRate, targetSampleRate);
            }

            if (source.bitsPerSample != targetBitsPerSample) {
                targetPcm = convertBitDepth(targetPcm, source.bitsPerSample, targetBitsPerSample);
            }

            return new WavInfo(targetPcm, targetSampleRate, targetChannels, targetBitsPerSample);
        } catch (Exception e) {
            Log.e(TAG, "重采样失败", e);
            return null;
        }
    }

    private byte[] convertStereoToMono(byte[] stereoData, int bitsPerSample, double ratio) {
        int bytesPerSample = bitsPerSample / 8;
        int stereoSamples = stereoData.length / (2 * bytesPerSample);
        int targetSamples = (int) (stereoSamples * ratio);
        byte[] mono = new byte[targetSamples * bytesPerSample];

        for (int i = 0; i < targetSamples; i++) {
            int srcIdx = (int) (i / ratio);
            if (srcIdx >= stereoSamples) srcIdx = stereoSamples - 1;

            for (int b = 0; b < bytesPerSample; b++) {
                int leftIdx = srcIdx * 2 * bytesPerSample + b;
                int rightIdx = leftIdx + bytesPerSample;

                if (bitsPerSample == 16) {
                    short left = (short) ((stereoData[leftIdx + 1] << 8) | (stereoData[leftIdx] & 0xFF));
                    short right = (short) ((stereoData[rightIdx + 1] << 8) | (stereoData[rightIdx] & 0xFF));
                    short mixed = (short) ((left + right) / 2);
                    mono[i * bytesPerSample + b] = (byte) (mixed & 0xFF);
                    mono[i * bytesPerSample + b + 1] = (byte) ((mixed >> 8) & 0xFF);
                } else if (bitsPerSample == 8) {
                    int left = stereoData[leftIdx] & 0xFF;
                    int right = stereoData[rightIdx] & 0xFF;
                    int mixed = (left + right) / 2;
                    mono[i * bytesPerSample + b] = (byte) mixed;
                }
            }
        }
        return mono;
    }

    private byte[] resampleOnly(byte[] pcmData, int bitsPerSample, int sourceRate, int targetRate) {
        if (sourceRate == targetRate) {
            return pcmData;
        }

        int bytesPerSample = bitsPerSample / 8;
        int sourceSamples = pcmData.length / bytesPerSample;
        double ratio = (double) targetRate / sourceRate;
        int targetSamples = (int) (sourceSamples * ratio);

        if (targetSamples <= 0) {
            return pcmData;
        }

        byte[] resampled = new byte[targetSamples * bytesPerSample];

        for (int i = 0; i < targetSamples; i++) {
            double srcIndex = i / ratio;
            int srcIdx = (int) srcIndex;
            double frac = srcIndex - srcIdx;

            if (srcIdx >= sourceSamples - 1) {
                srcIdx = sourceSamples - 1;
                frac = 0;
            }

            if (bitsPerSample == 16) {
                short sample1 = (short) ((pcmData[srcIdx * bytesPerSample + 1] << 8) | (pcmData[srcIdx * bytesPerSample] & 0xFF));
                short sample2 = (short) ((pcmData[(srcIdx + 1) * bytesPerSample + 1] << 8) | (pcmData[(srcIdx + 1) * bytesPerSample] & 0xFF));
                short interpolated = (short) (sample1 + (sample2 - sample1) * frac);
                resampled[i * bytesPerSample] = (byte) (interpolated & 0xFF);
                resampled[i * bytesPerSample + 1] = (byte) ((interpolated >> 8) & 0xFF);
            } else if (bitsPerSample == 8) {
                int sample1 = pcmData[srcIdx * bytesPerSample] & 0xFF;
                int sample2 = pcmData[(srcIdx + 1) * bytesPerSample] & 0xFF;
                int interpolated = (int) (sample1 + (sample2 - sample1) * frac);
                resampled[i * bytesPerSample] = (byte) interpolated;
            }
        }
        return resampled;
    }

    private byte[] convertBitDepth(byte[] pcmData, int sourceBits, int targetBits) {
        int sourceBytes = sourceBits / 8;
        int targetBytes = targetBits / 8;
        int sourceSamples = pcmData.length / sourceBytes;
        byte[] converted = new byte[sourceSamples * targetBytes];

        if (targetBits == 16 && sourceBits == 8) {
            for (int i = 0; i < sourceSamples; i++) {
                int sample8 = pcmData[i] & 0xFF;
                short sample16 = (short) ((sample8 - 128) << 8);
                converted[i * 2] = (byte) (sample16 & 0xFF);
                converted[i * 2 + 1] = (byte) ((sample16 >> 8) & 0xFF);
            }
        } else if (targetBits == 8 && sourceBits == 16) {
            for (int i = 0; i < sourceSamples; i++) {
                short sample16 = (short) ((pcmData[i * 2 + 1] << 8) | (pcmData[i * 2] & 0xFF));
                int sample8 = (sample16 >> 8) + 128;
                converted[i] = (byte) sample8;
            }
        } else {
            System.arraycopy(pcmData, 0, converted, 0, Math.min(pcmData.length, converted.length));
        }
        return converted;
    }

    private static class ByteArrayMediaDataSource extends android.media.MediaDataSource {
        private final byte[] data;

        ByteArrayMediaDataSource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= data.length) {
                return -1;
            }
            int length = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, length);
            return length;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
        }
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

    private void addCnLineNumber(List<PlaybackItem> items, String lineName) {
        String numStr = lineName.replace("路", " ").trim();
        String[] parts = numStr.split("\\s+", 2);
        String numberPart = parts[0];
        String suffix = parts.length > 1 ? parts[1].trim() : "";
        try {
            int num = Integer.parseInt(numberPart);
            if (num >= 1 && num <= 100) {
                items.add(new PlaybackItem(CN_NUM_RES[num]));
            } else if (num > 100) {
                String digits = String.valueOf(num);
                for (char c : digits.toCharArray()) {
                    int d = c - '0';
                    if (d == 1) {
                        items.add(new PlaybackItem(R.raw.cn_num_yao));
                    } else {
                        items.add(new PlaybackItem(CN_NUM_RES[d]));
                    }
                }
            }
            items.add(new PlaybackItem(R.raw.cn_route));
            if (!suffix.isEmpty()) {
                items.add(new PlaybackItem(suffix, PlaybackItem.Type.TTS_CN));
            }
        } catch (NumberFormatException e) {
            items.add(new PlaybackItem(lineName, PlaybackItem.Type.TTS_CN));
        }
    }

    private void addEnLineNumber(List<PlaybackItem> items, String lineNameEn) {
        addEnLineNumber(items, lineNameEn, true);
    }

    private void addEnLineNumber(List<PlaybackItem> items, String lineNameEn, boolean withRoute) {
        String numStr = lineNameEn.trim();
        try {
            int num = Integer.parseInt(numStr);
            if (withRoute) {
                items.add(new PlaybackItem(R.raw.en_route));
            }
            if (num >= 0 && num <= 99) {
                items.add(new PlaybackItem(EN_NUM_RES[num]));
            } else if (num >= 100) {
                String digits = String.valueOf(num);
                for (char c : digits.toCharArray()) {
                    int d = c - '0';
                    if (d >= 0 && d <= 9) {
                        items.add(new PlaybackItem(EN_NUM_RES[d]));
                    }
                }
            }
        } catch (NumberFormatException e) {
            if (withRoute) {
                items.add(new PlaybackItem(R.raw.en_route));
            }
            items.add(new PlaybackItem(lineNameEn, PlaybackItem.Type.TTS_EN));
        }
    }

    private void addCnStationName(List<PlaybackItem> items, String stationName) {
        Integer resId = getCachedStationResId(stationName, "cn_stations_");
        if (resId != null) {
            items.add(new PlaybackItem(resId, PlaybackItem.Type.MEDIA_PLAYER_WAV));
        } else {
            items.add(new PlaybackItem(stationName, PlaybackItem.Type.TTS_CN));
        }
    }

    private void addEnStationName(List<PlaybackItem> items, String stationName) {
        Integer resId = getCachedStationResId(stationName, "en_stations_");
        if (resId != null) {
            items.add(new PlaybackItem(resId, PlaybackItem.Type.MEDIA_PLAYER_WAV));
        } else {
            items.add(new PlaybackItem(stationName, PlaybackItem.Type.TTS_CN));
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
        pendingAnnouncements.clear();
        isPlaying = false;
        currentUtteranceId = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止mediaPlayer异常", e);
            }
        }
        releaseMediaPlayer();
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
        mergedAudioBytes = null;
        instance = null;
    }

    public boolean isSpeaking() {
        return isPlaying || (isInitialized && tts.isSpeaking());
    }

    public boolean isInitialized() {
        return isInitialized;
    }
}
