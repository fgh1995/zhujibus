package org.zjfgh.zhujibus;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BusApiClient {
    private static final String BASE_URL = "https://zjcx.zhuji.gov.cn:9443/gzcx-busServer/client/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> commonHeaders;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public BusApiClient() {
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.commonHeaders = new HashMap<>();
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
        initCommonHeaders();
    }

    private void initCommonHeaders() {
        commonHeaders.put("deviceId", "DSSFSFSFSFSFSFS");
        commonHeaders.put("userToken", "userToken");
        commonHeaders.put("codeValue", "330681");
        commonHeaders.put("appCode", "330681");
        commonHeaders.put("sourceCodeValue", "330681");
        commonHeaders.put("appVersion", "1.0.0");
        commonHeaders.put("deviceTypeName", "weixin");
    }

    // ==================== 异步API调用核心方法 ====================
    public <T> void callApiAsync(String endpoint,
                                 Object requestBody,
                                 Class<T> responseType,
                                 ApiCallback<T> callback) {
        executorService.execute(() -> {
            try {

                ApiRequestWrapper wrapper = new ApiRequestWrapper();
                wrapper.h = commonHeaders;
                wrapper.b = requestBody;

                String jsonBody = objectMapper.writeValueAsString(wrapper);
                Request request = new Request.Builder()
                        .url(BASE_URL + endpoint)
                        .post(RequestBody.create(jsonBody, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {

                    if (!response.isSuccessful()) {
                        throw new BusApiException("HTTP错误: " + response.code());
                    }

                    String responseBody = response.body().string();
                    T result = objectMapper.readValue(responseBody, responseType);
                    notifySuccess(callback, result);
                }
            } catch (Exception e) {
                Log.e("busbusbus", "API调用失败" + e);
                notifyError(callback, new BusApiException("API调用失败", e));
            }
        });
    }

    private <T> void notifySuccess(ApiCallback<T> callback, T result) {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void notifyError(ApiCallback<T> callback, BusApiException e) {
        mainHandler.post(() -> callback.onError(e));
    }

    // ==================== 具体业务方法 ====================
    public void getNearbyStations(double longitude,
                                  double latitude,
                                  String showStationNum,
                                  int showLineNum,
                                  int pageSize,
                                  ApiCallback<StationAroundResponse> callback) {
        StationAroundRequest request = new StationAroundRequest(
                longitude, latitude, showStationNum, showLineNum, pageSize);
        callApiAsync("query/station/point/around", request, StationAroundResponse.class, callback);
    }
    // ==================== 回调接口 ====================
    public interface ApiCallback<T> {
        void onSuccess(T response);

        void onError(BusApiException e);
    }

    // ==================== 数据模型 ====================
    private static class ApiRequestWrapper {
        public Map<String, String> h;
        public Object b;
    }

    public static class BusApiException extends Exception {
        public BusApiException(String message) {
            super(message);
        }

        public BusApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class StationAroundRequest {
        public String x;
        public String y;
        public String showStationNum;
        public int showLineNum;
        public int pageSize;

        public StationAroundRequest(double longitude, double latitude,
                                    String showStationNum, int showLineNum,
                                    int pageSize) {
            this.x = String.valueOf(longitude);
            this.y = String.valueOf(latitude);
            this.showStationNum = showStationNum;
            this.showLineNum = showLineNum;
            this.pageSize = pageSize;
        }
    }

    public static class StationAroundResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<StationInfo> data;
    }

    public static class StationInfo {
        public double distance;
        public String stationName;
        public String stationId;
        public List<DistanceData> distanceData;
    }

    public static class DistanceData {
        public String endStation;
        public int distance;
        public int isCollect;
        public String lineName;
        public String lineId;
        public int nextNumber;
        public int streamType;
        public String nextStartTimePoint;
        public int arrivalTime;
        public String startStation;
        public String stationId;
        public int inOutState;
    }
    // ==================== 公交搜索接口数据模型 ====================
    public static class BusLineSearchRequest {
        public String lineName;
        public int needGeometry;

        public BusLineSearchRequest(String lineName, int needGeometry) {
            this.lineName = lineName;
            this.needGeometry = needGeometry;
        }
    }

    public static class BusLineSearchResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public PageData<BusLineInfo> data;
    }

    public static class PageData<T> {
        public int pageNum;
        public int pageSize;
        public int currentPageSize;
        public int total;
        public int pages;
        public List<T> list;
        public boolean firstPage;
        public boolean lastPage;
    }

    public static class BusLineInfo {
        public String endStation;
        public String lineName;
        public String startStation;
        // 可以根据实际返回数据添加更多字段
    }

// ==================== 公交搜索API方法 ====================
    /**
     * 搜索公交线路
     * @param lineName 要搜索的线路名称
     * @param needGeometry 是否需要几何数据
     * @param callback 回调接口
     */
    public void searchBusLines(String lineName,
                               int needGeometry,
                               ApiCallback<BusLineSearchResponse> callback) {
        BusLineSearchRequest request = new BusLineSearchRequest(lineName, needGeometry);
        callApiAsync("busLine/searchBusLines", request, BusLineSearchResponse.class, callback);
    }
}