package org.zjfgh.zhujibus;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BusApiClient {
    private static final String host = "https://zjcx.zhuji.gov.cn:9443";
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
                Log.d("BusInfo", host + endpoint);
                String jsonBody = objectMapper.writeValueAsString(wrapper);
                Request request = new Request.Builder()
                        .url(host + endpoint)
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
                                  ApiCallback<StationLineAroundResponse> callback) {
        StationAroundRequest request = new StationAroundRequest(
                longitude, latitude, showStationNum, showLineNum, pageSize);
        callApiAsync("/gzcx-busServer/client/query/station/point/around", request, StationLineAroundResponse.class, callback);
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
     *
     * @param lineName     要搜索的线路名称
     * @param needGeometry 是否需要几何数据
     * @param callback     回调接口
     */
    public void searchBusLines(String lineName,
                               int needGeometry,
                               ApiCallback<BusLineSearchResponse> callback) {
        BusLineSearchRequest request = new BusLineSearchRequest(lineName, needGeometry);
        callApiAsync("/gzcx-busServer/client/busLine/searchBusLines", request, BusLineSearchResponse.class, callback);
    }
    // ==================== 公交线路详情查询接口 ====================

    /**
     * 查询公交线路详细信息
     *
     * @param lineName     线路名称(如"18路")
     * @param needGeometry 是否需要返回线路几何数据(1需要,0不需要)
     * @param callback     回调接口
     */
    public void queryBusLineDetail(String lineName,
                                   int needGeometry,
                                   ApiCallback<BusLineDetailResponse> callback) {
        BusLineQueryRequest request = new BusLineQueryRequest(lineName, needGeometry);
        callApiAsync("/gzcx-busServer/client/busLine/queryLine", request, BusLineDetailResponse.class, callback);
    }

    // ==================== 请求和响应模型 ====================
    public static class BusLineQueryRequest {
        public String lineName;    // 线路名称
        public int needGeometry;   // 是否需要几何数据(1需要,0不需要)

        public BusLineQueryRequest(String lineName, int needGeometry) {
            this.lineName = lineName;
            this.needGeometry = needGeometry;
        }
    }

    public static class BusLineDetailResponse {
        public String returnFlag;  // 返回标志
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public BusLineDetailData data; // 线路详情数据(重命名避免冲突)
    }

    // ==================== 线路详情数据结构 ====================
    public static class BusLineDetailData {
        public String areaCode;    // 区域代码
        public String lineName;   // 线路名称
        public BusLineDirection up;   // 上行方向信息(重命名)
        public BusLineDirection down; // 下行方向信息(重命名)
    }

    // ==================== 线路方向信息 ====================
    public static class BusLineDirection {
        public String endStation;      // 终点站名称
        public double totalPrice;      // 全程票价
        public int hasCj;              // 是否有场站(0无,1有)
        public String startLast;       // 末班车时间
        public int lineLength;         // 线路长度(公里)
        public String noticeId;        // 通知ID
        public List<BusLineStation> stationList; // 站点列表(重命名)
        public String startFirst;      // 首班车时间
        public String startStation;    // 起点站名称
        public String geometry;        // 线路几何数据(WKT格式)
        public String id;             // 线路ID
        public int hasMm;             // 是否有末班车(0无,1有)
        public String notice;         // 通知内容
    }

    // ==================== 线路站点信息 ====================
    public static class BusLineStation {
        public int haveBikeStation;    // 是否有自行车站点(0无,1有)
        public double poiOriginLon;    // 站点经度
        public String stationName;     // 站点名称
        public String id;             // 站点ID
        public int stationOrder;      // 站点顺序
        public int lastDistance;      // 与上一站距离(米)
        public double poiOriginLat;    // 站点纬度
    }

    // ==================== 原附近站点模型保持不变 ====================
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
    // ==================== 公交线路通知查询接口 ====================

    /**
     * 查询公交线路通知内容
     *
     * @param lineName 线路名称(如"38路")
     * @param callback 回调接口
     */
    public void queryLineNotification(String lineName,
                                      ApiCallback<LineNotificationResponse> callback) {
        LineNotificationRequest request = new LineNotificationRequest(lineName);
        callApiAsync("/gzcx-spaceServer/busLine/notification/content", request, LineNotificationResponse.class, callback);
    }

    // ==================== 线路通知请求和响应模型 ====================
    public static class LineNotificationRequest {
        public String lineName;  // 线路名称

        public LineNotificationRequest(String lineName) {
            this.lineName = lineName;
        }
    }

    public static class LineNotificationResponse {
        public String returnFlag;  // 返回标志
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public NotificationData data; // 通知数据
    }

    public static class NotificationData {
        public boolean hasNotification; // 是否有通知
        public String text;            // 通知内容(HTML格式)
    }
    // ==================== 公交车辆动态信息查询接口 ====================

    /**
     * 查询公交线路实时车辆动态信息
     *
     * @param lineId   线路ID
     * @param callback 回调接口
     */
    public void queryBusVehicleDynamic(String lineId,
                                       ApiCallback<BusVehicleDynamicResponse> callback) {
        BusVehicleDynamicRequest request = new BusVehicleDynamicRequest(lineId);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/dynamic/line/details",
                request, BusVehicleDynamicResponse.class, callback);
    }

    // ==================== 车辆动态请求和响应模型 ====================
    public static class BusVehicleDynamicRequest {
        public String lineId;  // 线路ID

        public BusVehicleDynamicRequest(String lineId) {
            this.lineId = lineId;
        }
    }

    public static class BusVehicleDynamicResponse {
        public String returnFlag;  // 返回标志
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public BusVehicleDynamicData data; // 车辆动态数据
    }

    public static class BusVehicleDynamicData {
        public int clientShowVehicleNumber; // 客户端显示车辆数
        public int busAverageSpeed;        // 公交平均速度(米/分钟)
        public String startTime;           // 起始时间
        public List<VehicleDynamicInfo> list; // 车辆动态列表
    }

    public static class VehicleDynamicInfo {
        public int vehicleOrder;      // 车辆顺序
        public double lng;           // 经度
        public double lat;           // 纬度
        public int distance;         // 距离(米)
        public String gpsTime;       // GPS时间
        public String plateNumber;   // 车牌号
        public int isArriveStation;  // 是否到站(0:未到站,1:已到站)
    }
}
