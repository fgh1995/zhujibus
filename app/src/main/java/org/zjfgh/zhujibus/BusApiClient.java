package org.zjfgh.zhujibus;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import okhttp3.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BusApiClient {
    private static final String TAG = "BusApiClient";
    private static final String host = "https://zjcx.zhuji.gov.cn:9443";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT = 15;
    private static final int READ_TIMEOUT = 20;
    private static final int WRITE_TIMEOUT = 15;
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> commonHeaders;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    // 请求取消管理
    private final Map<String, Call> pendingCalls = new HashMap<>();
    private final Object callLock = new Object();

    public BusApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
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

    // ==================== 请求取消管理方法 ====================

    /**
     * 取消指定标签的所有请求
     */
    public void cancelRequests(String tag) {
        synchronized (callLock) {
            Call call = pendingCalls.remove(tag);
            if (call != null && !call.isCanceled()) {
                call.cancel();
                Log.d(TAG, "Cancelled request with tag: " + tag);
            }
        }
    }

    /**
     * 取消所有请求
     */
    public void cancelAllRequests() {
        synchronized (callLock) {
            for (Call call : pendingCalls.values()) {
                if (!call.isCanceled()) {
                    call.cancel();
                }
            }
            pendingCalls.clear();
            Log.d(TAG, "Cancelled all pending requests");
        }
    }

    /**
     * 注册请求
     */
    private void registerCall(String tag, Call call) {
        synchronized (callLock) {
            Call oldCall = pendingCalls.remove(tag);
            if (oldCall != null && !oldCall.isCanceled()) {
                oldCall.cancel();
            }
            pendingCalls.put(tag, call);
        }
    }

    /**
     * 移除已完成的请求
     */
    private void removeCall(String tag) {
        synchronized (callLock) {
            pendingCalls.remove(tag);
        }
    }

    /**
     * 检查请求是否已被取消
     */
    private boolean isRequestCancelled(String tag) {
        if (tag == null) return false;
        synchronized (callLock) {
            return !pendingCalls.containsKey(tag);
        }
    }

    // ==================== 异步API调用核心方法 ====================

    public <T> void callApiAsync(String endpoint,
                                 Object requestBody,
                                 Class<T> responseType,
                                 ApiCallback<T> callback) {
        callApiAsyncWithRetry(endpoint, requestBody, responseType, callback, 0, null);
    }

    public <T> void callApiAsync(String endpoint,
                                 Object requestBody,
                                 Class<T> responseType,
                                 ApiCallback<T> callback,
                                 String requestTag) {
        callApiAsyncWithRetry(endpoint, requestBody, responseType, callback, 0, requestTag);
    }

    private <T> void callApiAsyncWithRetry(String endpoint,
                                           Object requestBody,
                                           Class<T> responseType,
                                           ApiCallback<T> callback,
                                           int retryCount,
                                           String requestTag) {
        executorService.execute(() -> {
            // 检查请求是否已被取消
            if (requestTag != null && isRequestCancelled(requestTag)) {
                Log.d(TAG, "Request cancelled before execution: " + requestTag);
                return;
            }

            try {
                ApiRequestWrapper wrapper = new ApiRequestWrapper();
                wrapper.h = commonHeaders;
                wrapper.b = requestBody;
                String jsonBody = objectMapper.writeValueAsString(wrapper);
                Request request = new Request.Builder()
                        .url(host + endpoint)
                        .post(RequestBody.create(jsonBody, JSON))
                        .build();

                Call call = httpClient.newCall(request);

                if (requestTag != null) {
                    registerCall(requestTag, call);
                }

                if (requestTag != null && isRequestCancelled(requestTag)) {
                    removeCall(requestTag);
                    Log.d(TAG, "Request cancelled before execute: " + requestTag);
                    return;
                }

                try (Response response = call.execute()) {
                    if (requestTag != null) {
                        removeCall(requestTag);
                    }

                    if (requestTag != null && isRequestCancelled(requestTag)) {
                        Log.d(TAG, "Request cancelled after execution: " + requestTag);
                        return;
                    }

                    if (!response.isSuccessful()) {
                        throw new BusApiException("HTTP错误: " + response.code());
                    }

                    String responseBody = response.body().string();
                    T result = objectMapper.readValue(responseBody, responseType);
                    notifySuccess(callback, result);
                }
            } catch (Exception e) {
                if (requestTag != null) {
                    removeCall(requestTag);
                }

                if (retryCount < MAX_RETRY_COUNT && shouldRetry(e) &&
                        (requestTag == null || !isRequestCancelled(requestTag))) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                        callApiAsyncWithRetry(endpoint, requestBody, responseType, callback, retryCount + 1, requestTag);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        notifyError(callback, new BusApiException("API调用被中断", ie));
                    }
                } else {
                    notifyError(callback, new BusApiException("API调用失败", e));
                }
            }
        });
    }

    private boolean shouldRetry(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return true;
        }
        if (e instanceof java.net.UnknownHostException) {
            return true;
        }
        if (e instanceof java.io.IOException) {
            return true;
        }
        return false;
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
                                  ApiCallback<StationLineAroundResponse> callback) {
        StationAroundRequest request = new StationAroundRequest(
                longitude, latitude, showStationNum, showLineNum, pageSize);
        callApiAsync("/gzcx-busServer/client/query/station/point/around", request, StationLineAroundResponse.class, callback);
    }

    public void searchBusLines(String lineName,
                               int needGeometry,
                               ApiCallback<BusLineSearchResponse> callback) {
        BusLineSearchRequest request = new BusLineSearchRequest(lineName, needGeometry);
        callApiAsync("/gzcx-busServer/client/busLine/searchBusLines", request, BusLineSearchResponse.class, callback);
    }

    public void queryBusLineDetail(String lineName,
                                   int needGeometry,
                                   ApiCallback<BusLineDetailResponse> callback) {
        BusLineQueryRequest request = new BusLineQueryRequest(lineName, needGeometry);
        callApiAsync("/gzcx-busServer/client/busLine/queryLine", request, BusLineDetailResponse.class, callback);
    }

    public void queryLineNotification(String lineName,
                                      ApiCallback<LineNotificationResponse> callback) {
        LineNotificationRequest request = new LineNotificationRequest(lineName);
        callApiAsync("/gzcx-spaceServer/busLine/notification/content", request, LineNotificationResponse.class, callback);
    }

    public void queryBusVehicleDynamic(String lineId,
                                       ApiCallback<BusVehicleDynamicResponse> callback) {
        BusVehicleDynamicRequest request = new BusVehicleDynamicRequest(lineId);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/dynamic/line/details",
                request, BusVehicleDynamicResponse.class, callback);
    }

    public void getBusLinePlanTime(String lineId,
                                   ApiCallback<BusLinePlanTimeResponse> callback) {
        getBusLinePlanTime(lineId, callback, null);
    }

    public void getBusLinePlanTime(String lineId,
                                   ApiCallback<BusLinePlanTimeResponse> callback,
                                   String requestTag) {
        BusLinePlanTimeRequest request = new BusLinePlanTimeRequest(lineId);
        callApiAsync("/gzcx-busServer/client/busLine/getBusLinePlanSimpleTime",
                request, BusLinePlanTimeResponse.class, callback, requestTag);
    }

    public void getBusAnnouncements(int page, int size,
                                    ApiCallback<BusAnnouncementResponse> callback) {
        getBusAnnouncementsWithRetry(page, size, callback, 0);
    }

    private void getBusAnnouncementsWithRetry(int page, int size,
                                              ApiCallback<BusAnnouncementResponse> callback,
                                              int retryCount) {
        String url = host + "/gzcx-spaceServer/index/information/getNewestTitleV2" +
                "?page=" + page + "&size=" + size;

        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("custom-params", "{\"appCode\":\"330681\",\"codeValue\":\"330681\"}");

        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(mergeHeaders(commonHeaders, customHeaders)))
                .get()
                .build();

        executorService.execute(() -> {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new BusApiException("HTTP错误: " + response.code());
                }

                String responseBody = response.body().string();
                BusAnnouncementResponse result = objectMapper.readValue(
                        responseBody, BusAnnouncementResponse.class);
                notifySuccess(callback, result);
            } catch (Exception e) {
                if (retryCount < MAX_RETRY_COUNT && shouldRetry(e)) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                        getBusAnnouncementsWithRetry(page, size, callback, retryCount + 1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        notifyError(callback, new BusApiException("获取公告被中断", ie));
                    }
                } else {
                    notifyError(callback, new BusApiException("获取公告信息失败", e));
                }
            }
        });
    }

    public void queryStationInfo(String stationName,
                                 ApiCallback<StationInfoResponse> callback) {
        StationQueryRequest request = new StationQueryRequest(stationName);
        callApiAsync("/gzcx-busServer/client/station/queryStation",
                request, StationInfoResponse.class, callback);
    }

    public void queryStationVehicleDynamic(String lineIds,
                                           String stationIds,
                                           ApiCallback<StationVehicleDynamicResponse> callback) {
        StationVehicleDynamicRequest request = new StationVehicleDynamicRequest(lineIds, stationIds);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/dynamic/station/details",
                request, StationVehicleDynamicResponse.class, callback);
    }

    public void queryBusVehiclePlan(String lineIds,
                                    ApiCallback<BusVehiclePlanResponse> callback) {
        BusVehiclePlanRequest request = new BusVehiclePlanRequest(lineIds);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/plan",
                request, BusVehiclePlanResponse.class, callback);
    }

    public void searchStations(String stationName,
                               int page,
                               int offset,
                               ApiCallback<StationSearchResponse> callback) {
        StationSearchRequest request = new StationSearchRequest(stationName, page, offset);
        callApiAsync("/gzcx-busServer/client/station/searchStation",
                request, StationSearchResponse.class, callback);
    }

    private Map<String, String> mergeHeaders(Map<String, String> baseHeaders,
                                             Map<String, String> additionalHeaders) {
        Map<String, String> merged = new HashMap<>(baseHeaders);
        merged.putAll(additionalHeaders);
        return merged;
    }

    // ==================== 回调接口 ====================
    public interface ApiCallback<T> {
        void onSuccess(T response);
        void onError(BusApiException e);
    }

    // ==================== 内部类（数据模型）保持不变 ====================
    // 以下所有内部类保持不变...

    private static class ApiRequestWrapper {
        public Map<String, String> h;
        public Object b;
    }

    public static class BusApiException extends Exception {
        public BusApiException(String message) {
            super(message);
        }
        public BusApiException(String message, Throwable cause) {
            super(message + " (caused by: " + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null") + ")", cause);
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
    }

    public static class BusLineQueryRequest {
        public String lineName;
        public int needGeometry;
        public BusLineQueryRequest(String lineName, int needGeometry) {
            this.lineName = lineName;
            this.needGeometry = needGeometry;
        }
    }

    public static class BusLineDetailResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public BusLineDetailData data;
    }

    public static class BusLineDetailData {
        public String areaCode;
        public String lineName;
        public BusLineDirection up;
        public BusLineDirection down;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BusLineDirection {
        public String endStation;
        public double totalPrice;
        public int hasCj;
        public String startLast;
        public int lineLength;
        public String noticeId;
        public List<BusLineStation> stationList;
        public String startFirst;
        public String startStation;
        public String geometry;
        public String id;
        public int hasMm;
        public String notice;
        public int lineType;
        public String lineTypeName;
    }

    public static class BusLineStation {
        public int haveBikeStation;
        public double poiOriginLon;
        public String stationName;
        public String id;
        public int stationOrder;
        public int lastDistance;
        public double poiOriginLat;
        public double lat;
        public double lng;
        public int distanceToNext;
        public String plateNumber;
        public int arrivalTime;
        public enum StationStatus {
            NORMAL, NEXT_STATION, CURRENT, PASSED, DEFAULT
        }
        public StationStatus status = StationStatus.NORMAL;
    }

    public static class BusPosition {
        public int currentStationOrder;
        public boolean isArrived;
        public int nextStationOrder;
        public int distanceToNext;
        public String plateNumber;
        public long updateTime;
        public double lat;
        public double lng;
    }

    public static class StationLineAroundResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<NearbyStationInfo> data;
    }

    public static class NearbyStationInfo {
        public double distance;
        public String stationName;
        public String stationId;
        public List<DistanceData> distanceData;
    }

    public static class LineNotificationRequest {
        public String lineName;
        public LineNotificationRequest(String lineName) {
            this.lineName = lineName;
        }
    }

    public static class LineNotificationResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public NotificationData data;
    }

    public static class NotificationData {
        public boolean hasNotification;
        public String text;
    }

    public static class BusVehicleDynamicRequest {
        public String lineId;
        public BusVehicleDynamicRequest(String lineId) {
            this.lineId = lineId;
        }
    }

    public static class BusVehicleDynamicResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public BusVehicleDynamicData data;
    }

    public static class BusVehicleDynamicData {
        public int clientShowVehicleNumber;
        public int busAverageSpeed;
        public String startTime;
        public List<VehicleDynamicInfo> list;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleDynamicInfo {
        public int vehicleOrder;
        public double lng;
        public double lat;
        public int distance;
        public String gpsTime;
        public String plateNumber;
        public int isArriveStation;
    }

    public static class BusLinePlanTimeRequest {
        public String lineId;
        public BusLinePlanTimeRequest(String lineId) {
            this.lineId = lineId;
        }
    }

    public static class BusLinePlanTimeResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<String> data;
    }

    public static class BusAnnouncementResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<BusAnnouncement> data;
    }

    public static class BusAnnouncement {
        public long id;
        public String createDate;
        public String createUser;
        public String updateDate;
        public String updateUser;
        public String removed;
        public String title;
        public String publishUnit;
        public String abstracts;
        public String publishContent;
        public String publishDate;
        public String url;
        public int readingNum;
        public String pictureId;
        public String status;
        public String type;
        public String publishUser;
        public String appCode;
        public String dateType;
        public String areaCode;
        public int noticeType;
        public String analysisLink;
        public String firstStage;
        public String secondStage;
        public String getFormattedPublishDate() {
            return publishDate;
        }
    }

    public static class StationQueryRequest {
        public String stationName;
        public StationQueryRequest(String stationName) {
            this.stationName = stationName;
        }
    }

    public static class StationInfoResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<StationLineInfo> data;
    }

    public static class StationLineInfo {
        public String lineName;
        public LineDirection up;
        public LineDirection down;
        List<LineDirection> directions = new ArrayList<>();
        public List<LineDirection> getDirections() {
            directions.clear();
            if (up != null) {
                up.lineName = this.lineName;
                directions.add(up);
            }
            if (down != null) {
                down.lineName = this.lineName;
                directions.add(down);
            }
            return directions;
        }
    }

    public static class LineDirection {
        public String lineName;
        public String departureTime;
        public String collectTime;
        public String endStation;
        public double price;
        public int lineType;
        public String lineId;
        public String startStation;
        public String lineTypeName;
        public String stationId;
        public StationVehicleInfo vehicleInfo;
        public String planTime;
        public boolean isPassed = false;
    }

    public static class StationVehicleDynamicRequest {
        public String lineIds;
        public String stationIds;
        public StationVehicleDynamicRequest(String lineIds, String stationIds) {
            this.lineIds = lineIds;
            this.stationIds = stationIds;
        }
    }

    public static class StationVehicleDynamicResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<StationVehicleInfo> data;
    }

    public static class StationVehicleInfo {
        public int nextNumber;
        public int distance;
        public String lineId;
        public String gpsTime;
        public int isArriveStation;
        public String stationId;
    }

    public static class BusVehiclePlanRequest {
        public String lineIds;
        public BusVehiclePlanRequest(String lineIds) {
            this.lineIds = lineIds;
        }
    }

    public static class BusVehiclePlanResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public List<BusPlanTime> data;
    }

    public static class BusPlanTime {
        public String lineId;
        public String startTime;
    }

    public static class StationSearchRequest {
        public String stationName;
        public int page;
        public int offset;
        public StationSearchRequest(String stationName, int page, int offset) {
            this.stationName = stationName;
            this.page = page;
            this.offset = offset;
        }
    }

    public static class StationSearchResponse {
        public String returnFlag;
        public String returnInfo;
        public String code;
        public String msg;
        public StationSearchResult data;
    }

    public static class StationSearchResult {
        public int pageNum;
        public int pageSize;
        public int currentPageSize;
        public int total;
        public int pages;
        public List<StationSimpleInfo> list;
        public boolean lastPage;
        public boolean firstPage;
    }

    public static class StationSimpleInfo {
        public String stationName;
    }
}