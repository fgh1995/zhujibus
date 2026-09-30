package org.zjfgh.zhujibus;

/**
 * 公交线路搜索结果（统一模型）。
 *
 * <p>诸暨市走 {@link BusApiClient}；其他城市走高德 {@code BusLineSearch}，两者都映射到本类，
 * 由 {@link SearchBusLineAdapter} 统一展示。详情页后续可根据 {@code fromAmap}/{@code amapLineId}/
 * {@code amapCity} 决定数据来源（高德需二次按 lineId 查询）。</p>
 */
public class SearchLineResult {
    public final String lineName;
    public final String startStation;
    public final String endStation;
    /** 地区（如「嵊州」「诸暨」），用于列表单独展示样式；不计入线路名、不传入详情页 */
    public final String region;

    /** 是否来自高德搜索（用于点击进入详情时决定数据来源） */
    public final boolean fromAmap;
    /** 高德 lineId（fromAmap=true 时有效，详情二次查询用） */
    public final String amapLineId;
    /** 高德城市参数（adCode 或 cityCode，详情二次查询用） */
    public final String amapCity;

    public SearchLineResult(String lineName, String startStation, String endStation) {
        this(lineName, startStation, endStation, false, null, null, "");
    }

    public SearchLineResult(String lineName, String startStation, String endStation,
                            boolean fromAmap, String amapLineId, String amapCity) {
        this(lineName, startStation, endStation, fromAmap, amapLineId, amapCity, "");
    }

    public SearchLineResult(String lineName, String startStation, String endStation,
                            boolean fromAmap, String amapLineId, String amapCity, String region) {
        this.lineName = lineName != null ? lineName : "";
        this.startStation = startStation != null ? startStation : "";
        this.endStation = endStation != null ? endStation : "";
        this.region = region != null ? region : "";
        this.fromAmap = fromAmap;
        this.amapLineId = amapLineId;
        this.amapCity = amapCity;
    }

    /** 提取线路数字用于徽标，如"嵊州35路"->"35" */
    public String getBadge() {
        String digits = lineName.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "?" : digits;
    }
}
