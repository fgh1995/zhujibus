package org.zjfgh.zhujibus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.services.busline.BusLineItem;
import com.amap.api.services.busline.BusLineQuery;
import com.amap.api.services.busline.BusLineResult;
import com.amap.api.services.busline.BusLineSearch;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.ServiceSettings;

public class LineFragment extends Fragment {
    private SearchBusLineAdapter searchBusLineAdapter;
    private RecyclerView recyclerView;
    BusApiClient client;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. 先inflate布局
        View view = inflater.inflate(R.layout.line_fragment, container, false);
        // 2. 创建客户端实例
        client = new BusApiClient();
        // 3. 初始化视图（传入inflate得到的view）
        initViews(view);
        return view;
    }

    // 在onCreateView或onCreate中初始化
    private void initViews(View view) {
        TextView title = view.findViewById(R.id.title);
        title.setText("线路");

        recyclerView = view.findViewById(R.id.rv_bus_line_results);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        searchBusLineAdapter = new SearchBusLineAdapter();
        recyclerView.setAdapter(searchBusLineAdapter);
        // 设置点击监听
        searchBusLineAdapter.setOnItemClickListener(line -> {
            // 处理线路点击事件，例如跳转到详情页
            showBusLineDetails(line);
        });
    }

    public void searchLines(String keyword) {
        try {
            if (keyword.isEmpty()) {
                searchBusLineAdapter.setData(Collections.emptyList());
                return;
            }

            BusRegion region = new RegionManager(requireContext()).getSelectedRegion();
            boolean isZhuji = region != null && region.adCode.startsWith("330681");
            // 搜索结果 UI 地区标签：只显示地级市（如"绍兴市"），不显示区/县；搜索逻辑不变。
            // 特例在逐条结果处处理：线路名包含"诸暨"时显示"浙江省·诸暨市"。
            // regionShort 仍用于剥离线路名里的前缀（如"上虞227路"->"227路"）。
            String cityName = (region != null && region.cityName != null) ? region.cityName : "";
            if (cityName.isEmpty() && region != null) cityName = region.toShortString(); // 兜底（手动选地区未带 cityName）
            String regionTag = cityName;
            String regionShort = stripCitySuffix(region != null ? region.regionName : "");

            if (isZhuji) {
                Log.i("LineFragment", "诸暨市：走 BusApiClient 搜索：" + keyword);
                // 诸暨通道整体显示"浙江省·诸暨市"
                searchLinesByZhuji(keyword, "浙江省·诸暨市");
            } else {
                String city = region != null ? region.adCode : "绍兴市";
                Log.i("LineFragment", "非诸暨市：走高德 BusLineSearch 搜索：" + keyword + " city=" + city);
                searchLinesByAmap(keyword, city, regionShort, regionTag);
            }
        } catch (Exception e) {
            Log.e("LineFragment", "搜索线路异常", e);
            showErrorView("搜索失败");
        }
    }

    /** 诸暨市：使用原 BusApiClient 通道 */
    private void searchLinesByZhuji(String keyword, String regionTag) {
        client.searchBusLines(keyword, 123, new BusApiClient.ApiCallback<BusApiClient.BusLineSearchResponse>() {
            @Override
            public void onSuccess(BusApiClient.BusLineSearchResponse response) {
                try {
                    if (response == null || response.data == null) {
                        Log.e("BusLine", "搜索结果为空");
                        return;
                    }
                    if ("200".equals(response.code)) {
                        requireActivity().runOnUiThread(() -> {
                            try {
                                List<SearchLineResult> list = new ArrayList<>();
                                if (response.data.list != null) {
                                    for (BusApiClient.BusLineInfo info : response.data.list) {
                                        list.add(new SearchLineResult(
                                                info.lineName, info.startStation, info.endStation,
                                                false, null, null, regionTag));
                                    }
                                }
                                searchBusLineAdapter.setData(list);
                                recyclerView.setVisibility(View.VISIBLE);
                                if (list.isEmpty()) {
                                    showEmptyView();
                                }
                            } catch (Exception e) {
                                Log.e("BusLine", "更新搜索结果失败", e);
                            }
                        });
                    } else {
                        Log.e("BusLine", "搜索失败: " + response.msg);
                        showErrorView(response.msg);
                    }
                } catch (Exception e) {
                    Log.e("BusLine", "处理搜索结果失败", e);
                }
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("BusLine", "API调用错误: " + e.getMessage(), e);
                showErrorView(e.getMessage());
            }
        });
    }

    /** 非诸暨市：使用高德 BusLineSearch（按线路名搜索，城市用 adCode） */
    private void searchLinesByAmap(String keyword, String city, String regionShort, String regionTag) throws AMapException {
        try {
            ServiceSettings.updatePrivacyShow(requireContext(), true, true);
            ServiceSettings.updatePrivacyAgree(requireContext(), true);
        } catch (Throwable t) {
            Log.e("LineFragment", "高德隐私协议设置失败", t);
        }
        BusLineQuery query = new BusLineQuery(keyword, BusLineQuery.SearchType.BY_LINE_NAME, city);
        query.setPageSize(20);
        // 高德搜索 SDK 自 5.2.1 起页码从 1 开始（当前 9.5.0），传 0 会返回空结果
        query.setPageNumber(1);
        BusLineSearch search = new BusLineSearch(requireActivity(), query);
        search.setOnBusLineSearchListener((result, rCode) -> {
            List<SearchLineResult> list = new ArrayList<>();
            if (rCode == 1000 && result != null && result.getBusLines() != null) {
                for (BusLineItem item : result.getBusLines()) {
                    if (item == null) continue;
                    // 高德「按线路名」搜索的概要项 getBusLineName() 形如 "嵊州38路(芷湘站--客运中心)"。
                    // 先去掉冗余的 "(起点--终点)" 后缀；再以「所选地区简称」作为地区，若线路名以其开头则剥掉，
                    // 避免对非空数字线路（如 诸暨快线/旅游专线）误拆。地区仅用于列表样式，不传入详情页。
                    String raw = cleanAmapLineName(item.getBusLineName());
                    String name = stripPrefix(raw, regionShort);
                    // 特例：线路名包含"诸暨"时，该条标签显示"浙江省·诸暨市"，其余显示地级市
                    String tag = raw.contains("诸暨") ? "浙江省·诸暨市" : regionTag;
                    list.add(new SearchLineResult(
                            name,
                            item.getOriginatingStation(),
                            item.getTerminalStation(),
                            true, item.getBusLineId(), item.getCityCode(), tag));
                }
            } else {
                Log.w("LineFragment", "高德线路搜索失败或为空 rCode=" + rCode + " city=" + city);
            }
            final List<SearchLineResult> data = list;
            requireActivity().runOnUiThread(() -> {
                searchBusLineAdapter.setData(data);
                recyclerView.setVisibility(View.VISIBLE);
                if (data.isEmpty()) {
                    showEmptyView();
                }
            });
        });
        search.searchBusLineAsyn();
    }

    private void showBusLineDetails(SearchLineResult line) {
        // 跳转到线路详情页（高德来源的数据详情页暂未接入，按需求"配不到的数据先空着"处理）
        Intent intent = new Intent(getActivity(), BusLineDetailActivity.class);
        intent.putExtra("line_name", line.lineName);
        intent.putExtra("start_station", line.startStation);
        intent.putExtra("end_station", line.endStation);
        intent.putExtra("from_amap", line.fromAmap);
        if (line.fromAmap) {
            intent.putExtra("amap_line_id", line.amapLineId);
            intent.putExtra("amap_city", line.amapCity);
        }
        startActivity(intent);
    }

    private void showEmptyView() {
        if (getView() == null) return;
        Log.d("BusLine", "显示空视图");
    }

    private void showErrorView(String message) {
        // 显示错误视图
    }

    /**
     * 清理高德「按线路名」搜索概要项的线路名：去掉自带的 "(起点--终点)" 后缀。
     * 高德概要 BusLineItem.getBusLineName() 形如 "嵊州38路(芷湘站--客运中心)"，
     * 起点/终点已分别通过 getOriginatingStation()/getTerminalStation() 取得，括号属冗余。
     * 只匹配含连接符（--/—/－）的括号片段，不影响名称中正常的括号（如 "38路(夜班)"）。
     */
    private static String cleanAmapLineName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 末尾的 "(起点--终点)" 片段内部可能嵌套括号（如 莲花路地铁站(北广场)），
        // 正则难以配平，这里从末尾右括号反向扫描找到配对的左括号，内容含连接符才剥离。
        if (s.isEmpty()) return s;
        char lastChar = s.charAt(s.length() - 1);
        if (lastChar != ')' && lastChar != '）') return s;
        int depth = 0, open = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == '）') depth++;
            else if (c == '(' || c == '（') {
                depth--;
                if (depth == 0) { open = i; break; }
            }
        }
        if (open <= 0) return s;
        String inner = s.substring(open + 1, s.length() - 1);
        boolean hasDash = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '-' || c == '—' || c == '－' || c == '~' || c == '～') { hasDash = true; break; }
        }
        if (hasDash) s = s.substring(0, open).trim();
        return s;
    }

    /**
     * 去掉末尾行政区划后缀（市/区/县/盟/旗），保留「州」（如「嵊州市」->「嵊州」、「绍兴市」->「绍兴」）。
     * 用于剥离线路名前缀（如「上虞227路」->「227路」）。
     */
    private static String stripCitySuffix(String name) {
        if (name == null) return "";
        String s = name.trim();
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last == '市' || last == '区' || last == '县' || last == '盟' || last == '旗') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    /** 若 value 以 prefix 开头则去掉该前缀，否则原样返回 */
    private static String stripPrefix(String value, String prefix) {
        if (value == null) return "";
        if (prefix == null || prefix.isEmpty()) return value;
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
