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
        public int stationId; // 站点ID
        public double lat;    // 纬度
        public double lng;    // 经度
        public int distanceToNext; // 到下一站距离(米)
        public String plateNumber;

        // 实时状态
        public enum StationStatus {
            NORMAL,       // 正常状态
            NEXT_STATION, // 下一站
            CURRENT,      // 当前站
            DEFAULT, PASSED        // 已过站
        }

        public StationStatus status = StationStatus.NORMAL;
        public int arrivalTime; // 预计到达时间(秒)
    }

    // 车辆位置数据包装类
    public static class BusPosition {
        public int currentStationOrder; // 当前所在站点顺序号(来自vehicleOrder)
        public boolean isArrived;       // 是否已到站(来自isArriveStation)
        public int nextStationOrder;    // 下一站顺序号
        public int distanceToNext;      // 距下一站距离(米)(来自distance)
        public String plateNumber;      // 车牌号
        public long updateTime;         // 数据更新时间
        public double lat;              // 车辆纬度
        public double lng;              // 车辆经度
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
    // ==================== 公交线路计划发车时间查询接口 ====================

    /**
     * 查询公交线路计划发车时间
     *
     * @param lineId   线路ID
     * @param callback 回调接口
     */
    public void getBusLinePlanTime(String lineId,
                                   ApiCallback<BusLinePlanTimeResponse> callback) {
        BusLinePlanTimeRequest request = new BusLinePlanTimeRequest(lineId);
        callApiAsync("/gzcx-busServer/client/busLine/getBusLinePlanSimpleTime",
                request, BusLinePlanTimeResponse.class, callback);
    }

    // ==================== 计划发车时间请求和响应模型 ====================
    public static class BusLinePlanTimeRequest {
        public String lineId;  // 线路ID

        public BusLinePlanTimeRequest(String lineId) {
            this.lineId = lineId;
        }
    }

    public static class BusLinePlanTimeResponse {
        public String returnFlag;  // 返回标志
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public List<String> data; // 发车时间列表
    }
    // ==================== 公交公告信息查询接口 ====================

    /**
     * 获取最新公交公告信息
     *
     * @param page     页码(从1开始)
     * @param size     每页数量
     * @param callback 回调接口
     */
    public void getBusAnnouncements(int page, int size,
                                    ApiCallback<BusAnnouncementResponse> callback) {
        // 构建请求URL
        String url = host + "/gzcx-spaceServer/index/information/getNewestTitleV2" +
                "?page=" + page + "&size=" + size;

        // 添加自定义请求头
        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("custom-params", "{\"appCode\":\"330681\",\"codeValue\":\"330681\"}");

        // 创建请求
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(mergeHeaders(commonHeaders, customHeaders)))
                .get()
                .build();

        // 异步执行请求
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
                notifyError(callback, new BusApiException("获取公告信息失败", e));
            }
        });
    }

    // 合并请求头方法
    private Map<String, String> mergeHeaders(Map<String, String> baseHeaders,
                                             Map<String, String> additionalHeaders) {
        Map<String, String> merged = new HashMap<>(baseHeaders);
        merged.putAll(additionalHeaders);
        return merged;
    }

// ==================== 公告信息数据模型 ====================

    /**
     * 公交公告响应模型
     */
    public static class BusAnnouncementResponse {
        public String returnFlag;  // 返回标志
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public List<BusAnnouncement> data; // 公告列表
    }

    /**
     * 单个公交公告信息
     */
    public static class BusAnnouncement {
        public long id;             // 公告ID
        public String createDate;   // 创建日期
        public String createUser;   // 创建用户
        public String updateDate;   // 更新日期
        public String updateUser;   // 更新用户
        public String removed;      // 是否删除
        public String title;        // 公告标题
        public String publishUnit;  // 发布单位
        public String abstracts;    // 摘要
        public String publishContent; // 发布内容
        public String publishDate;  // 发布日期
        public String url;          // 详情URL
        public int readingNum;      // 阅读量
        public String pictureId;    // 图片ID
        public String status;       // 状态
        public String type;         // 类型
        public String publishUser;  // 发布用户
        public String appCode;      // 应用代码
        public String dateType;     // 日期类型
        public String areaCode;     // 区域代码
        public int noticeType;      // 通知类型
        public String analysisLink; // 分析链接
        public String firstStage;   // 一级分类
        public String secondStage;  // 二级分类

        // 获取格式化后的发布时间
        public String getFormattedPublishDate() {
            // 这里可以添加日期格式化逻辑
            return publishDate;
        }
    }
    // ==================== 公交站点查询接口 ====================

    /**
     * 查询公交站点信息
     *
     * @param stationName 站点名称(如"财税大楼")
     * @param callback    回调接口
     */
    public void queryStationInfo(String stationName,
                                 ApiCallback<StationInfoResponse> callback) {
        StationQueryRequest request = new StationQueryRequest(stationName);
        callApiAsync("/gzcx-busServer/client/station/queryStation",
                request, StationInfoResponse.class, callback);
    }

// ==================== 站点查询请求和响应模型 ====================

    /**
     * 站点查询请求模型
     */
    public static class StationQueryRequest {
        public String stationName;  // 站点名称

        public StationQueryRequest(String stationName) {
            this.stationName = stationName;
        }
    }

    /**
     * 站点查询响应模型
     */
    public static class StationInfoResponse {
        public String returnFlag;  // 返回标志("200"表示成功)
        public String returnInfo;  // 返回信息("成功")
        public String code;       // 状态码("200")
        public String msg;        // 消息("成功")
        public List<StationLineInfo> data; // 站点线路数据
    }

    /**
     * 站点线路信息模型
     */
    public static class StationLineInfo {
        public String lineName;   // 线路名称(如"1路")
        public LineDirection up;   // 上行方向信息
        public LineDirection down; // 下行方向信息
        List<LineDirection> directions = new ArrayList<>();

        public List<LineDirection> getDirections() {
            directions.clear();
            // 设置上行方向的lineName
            if (up != null) {
                up.lineName = this.lineName;
                directions.add(up);
            }

            // 设置下行方向的lineName
            if (down != null) {
                down.lineName = this.lineName;
                directions.add(down);
            }
            return directions;
        }
    }

    /**
     * 线路方向信息模型
     */
    public static class LineDirection {
        public String lineName;
        public String departureTime;  // 发车时间(如"06:00")
        public String collectTime;    // 收车时间(如"18:00")
        public String endStation;     // 终点站名称
        public double price;         // 票价(如1.0)
        public int lineType;         // 线路类型(1表示城市公交)
        public String lineId;        // 线路ID(如"330681112")
        public String startStation;  // 起点站名称
        public String lineTypeName;  // 线路类型名称("城市")
        public String stationId;     // 站点ID
        public StationVehicleInfo vehicleInfo;
        public String planTime;
    }

    /**
     * 查询公交站点车辆动态信息
     *
     * @param lineIds    线路ID列表，多个用逗号分隔(如"330681112,330681111")
     * @param stationIds 站点ID列表，多个用逗号分隔(如"33068111213,33068111124")
     * @param callback   回调接口
     */
    public void queryStationVehicleDynamic(String lineIds,
                                           String stationIds,
                                           ApiCallback<StationVehicleDynamicResponse> callback) {
        StationVehicleDynamicRequest request = new StationVehicleDynamicRequest(lineIds, stationIds);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/dynamic/station/details",
                request, StationVehicleDynamicResponse.class, callback);
    }
    // ==================== 站点车辆动态请求和响应模型 ====================

    /**
     * 站点车辆动态请求模型
     */
    public static class StationVehicleDynamicRequest {
        public String lineIds;    // 线路ID列表(逗号分隔)
        public String stationIds; // 站点ID列表(逗号分隔)

        public StationVehicleDynamicRequest(String lineIds, String stationIds) {
            this.lineIds = lineIds;
            this.stationIds = stationIds;
        }
    }

    /**
     * 站点车辆动态响应模型
     */
    public static class StationVehicleDynamicResponse {
        public String returnFlag;  // 返回标志("200"表示成功)
        public String returnInfo;  // 返回信息("成功")
        public String code;       // 状态码("200")
        public String msg;        // 消息("成功")
        public List<StationVehicleInfo> data; // 车辆动态数据列表
    }

    /**
     * 站点车辆动态信息模型
     */
    public static class StationVehicleInfo {
        public int nextNumber;      // 下一站序号
        public int distance;        // 距本站距离(米)
        public String lineId;       // 线路ID
        public String gpsTime;      // GPS时间(格式: "yyyy-MM-dd HH:mm:ss")
        public int isArriveStation; // 是否到站(0:未到站,1:已到站)
        public String stationId;    // 站点ID
    }
    // ==================== 公交线路计划发车时间批量查询接口 ====================

    /**
     * 批量查询多条公交线路的计划发车时间
     *
     * @param lineIds  线路ID列表(多个用英文逗号分隔，如"330681112,3306811211")
     * @param callback 回调接口
     */
    public void queryBusVehiclePlan(String lineIds,
                                    ApiCallback<BusVehiclePlanResponse> callback) {
        BusVehiclePlanRequest request = new BusVehiclePlanRequest(lineIds);
        callApiAsync("/gzcx-busServer/client/bus/vehicle/plan",
                request, BusVehiclePlanResponse.class, callback);
    }

// ==================== 计划发车时间请求和响应模型 ====================

    /**
     * 公交车辆计划请求模型
     */
    public static class BusVehiclePlanRequest {
        public String lineIds;  // 线路ID列表(多个用英文逗号分隔)

        public BusVehiclePlanRequest(String lineIds) {
            this.lineIds = lineIds;
        }
    }

    /**
     * 公交车辆计划响应模型
     */
    public static class BusVehiclePlanResponse {
        public String returnFlag;  // 返回标志("200"表示成功)
        public String returnInfo;  // 返回信息
        public String code;       // 状态码
        public String msg;        // 消息
        public List<BusPlanTime> data; // 计划时间列表
    }

    /**
     * 单个线路计划时间信息
     */
    public static class BusPlanTime {
        public String lineId;     // 线路ID
        public String startTime; // 计划发车时间(格式"HH:mm")
    }
}

