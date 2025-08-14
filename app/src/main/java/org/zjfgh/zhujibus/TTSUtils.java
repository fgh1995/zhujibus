package org.zjfgh.zhujibus;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

public class TTSUtils implements TextToSpeech.OnInitListener {
    private static final String TAG = "TTSUtils";

    private static TTSUtils instance;
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private Context context;
    private Locale defaultLocale = Locale.CHINESE;
    private float speechRate = 1.0f; // 默认语速
    private float pitch = 1.0f; // 默认音调

    // 单例模式
    public static synchronized TTSUtils getInstance(Context context) {
        if (instance == null) {
            instance = new TTSUtils(context);
        }
        return instance;
    }

    private TTSUtils(Context context) {
        this.context = context.getApplicationContext();
        initTTS();
    }

    // 初始化TTS
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

    // 设置语言
    public void setLanguage(Locale locale) {
        if (isInitialized) {
            defaultLocale = locale;
            tts.setLanguage(locale);
        }
    }

    // 设置语速 (0.5f-2.0f)
    public void setSpeechRate(float rate) {
        if (isInitialized) {
            speechRate = rate;
            tts.setSpeechRate(rate);
        }
    }

    // 设置音调 (0.5f-2.0f)
    public void setPitch(float pitch) {
        if (isInitialized) {
            this.pitch = pitch;
            tts.setPitch(pitch);
        }
    }

    // 语音合成
    public void speak(String text) {
        speak(text, null);
    }

    public void speak(String text, String utteranceId) {
        if (isInitialized && text != null && !text.isEmpty()) {
            if (utteranceId == null) {
                utteranceId = String.valueOf(System.currentTimeMillis());
            }

            // 清除当前队列
            tts.stop();

            // 开始语音合成
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    // 停止语音
    public void stop() {
        if (isInitialized) {
            tts.stop();
        }
    }

    // 释放资源
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            isInitialized = false;
        }
        instance = null;
    }

    // 是否正在说话
    public boolean isSpeaking() {
        return isInitialized && tts.isSpeaking();
    }

    // 是否初始化完成
    public boolean isInitialized() {
        return isInitialized;
    }
}
