package org.zjfgh.zhujibus;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.district.DistrictItem;
import com.amap.api.services.district.DistrictResult;
import com.amap.api.services.district.DistrictSearch;
import com.amap.api.services.district.DistrictSearchQuery;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;
import com.amap.api.services.poisearch.PoiSearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工搜索地区。基于高德「输入提示」(Inputtips) 检索城市/区县，
 * 每条结果都带有 adCode，用户选中后把对应的 {@link BusRegion} 返回给首页。
 *
 * <p>注意：Inputtips 返回的是「区/县」级行政区划（例如搜"诸暨"会得到 adCode 330681、district 诸暨市），
 * 因此可以正确区分绍兴市下辖的各个区县，而不是只按地级市名确认。</p>
 */
public class RegionSearchActivity extends AppCompatActivity implements Inputtips.InputtipsListener {

    private static final String TAG = "RegionSearchActivity";
    private static final long DEBOUNCE = 400;

    private EditText edSearch;
    private RegionSearchAdapter adapter;
    private Inputtips inputtips;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_region_search);
            SystemBarUtils.fitSystemBars(this);

            // 搜索 SDK 隐私协议：未同意时 Inputtips 会返回非 1000，导致结果空白
            try {
                ServiceSettings.updatePrivacyShow(this, true, true);
                ServiceSettings.updatePrivacyAgree(this, true);
            } catch (Throwable t) {
                Log.e(TAG, "搜索隐私协议设置失败", t);
            }

            edSearch = findViewById(R.id.ed_region_search);
            RecyclerView rv = findViewById(R.id.rv_region_result);
            rv.setLayoutManager(new LinearLayoutManager(this));
            adapter = new RegionSearchAdapter();
            rv.setAdapter(adapter);
            adapter.setOnItemClickListener(region -> {
                Intent intent = new Intent();
                intent.putExtra("region", region);
                setResult(RESULT_OK, intent);
                finish();
            });

            inputtips = new Inputtips(this, this);

            edSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    scheduleSearch(s.toString().trim());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "初始化失败", e);
        }
    }

    private void scheduleSearch(String keyword) {
        if (pending != null) handler.removeCallbacks(pending);
        if (TextUtils.isEmpty(keyword)) {
            adapter.setRegions(new ArrayList<>());
            return;
        }
        String kw = keyword;
        pending = () -> doSearch(kw);
        handler.postDelayed(pending, DEBOUNCE);
    }

    private void doSearch(String keyword) {
        try {
            InputtipsQuery query = new InputtipsQuery(keyword, "");
            query.setCityLimit(false);
            inputtips.setQuery(query);
            inputtips.requestInputtipsAsyn();
        } catch (Exception e) {
            Log.e(TAG, "搜索地区失败", e);
        }
    }

    @Override
    public void onGetInputtips(List<Tip> list, int rCode) {
        if (rCode != 1000) {
            Log.w(TAG, "输入提示返回 rCode=" + rCode);
            final int code = rCode;
            runOnUiThread(() -> Toast.makeText(RegionSearchActivity.this,
                    "搜索失败(code=" + code + ")，请检查网络或重试", Toast.LENGTH_SHORT).show());
            return;
        }
        List<BusRegion> regions = new ArrayList<>();
        if (list != null) {
            for (Tip tip : list) {
                String adCode = tip.getAdcode();
                if (TextUtils.isEmpty(adCode)) continue;
                // Tip 无 getCity()/getProvince()；优先用 district，缺失时依次回退 name / address / adCode
                String district = tip.getDistrict();
                if (TextUtils.isEmpty(district)) district = tip.getName();
                if (TextUtils.isEmpty(district)) district = tip.getAddress();
                if (TextUtils.isEmpty(district)) district = adCode; // 兜底，避免列表出现空白项
                Log.d(TAG, "Tip adCode=" + adCode
                        + " district=" + tip.getDistrict()
                        + " name=" + tip.getName()
                        + " address=" + tip.getAddress()
                        + " poiID=" + tip.getPoiID()
                        + " typeCode=" + tip.getTypeCode());
                BusRegion r = new BusRegion(adCode, "", "", district);
                regions.add(r);
                // 高德对部分 adCode 不返回名称，则按 adCode 反查行政区划名
                try {
                    ensureRegionName(r, regions);
                } catch (AMapException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        final List<BusRegion> result = regions;
        runOnUiThread(() -> adapter.setRegions(result));
    }

    /** adCode -> 反查到的 [市, 区/县] 缓存，避免重复请求 */
    private final Map<String, String[]> adCodeNameCache = new HashMap<>();

    /** 若地区名缺失，用 DistrictSearch 按 adCode 反查市/区后回填列表 */
    private void ensureRegionName(BusRegion r, List<BusRegion> list) throws AMapException {
        if (!r.regionName.isEmpty()) return; // 已有名字，无需反查
        String[] cached = adCodeNameCache.get(r.adCode);
        if (cached != null) {
            replaceRegion(list, r, cached[0], cached[1]);
            return;
        }
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Throwable t) {
            Log.e(TAG, "DistrictSearch 隐私协议设置失败", t);
        }
        DistrictSearch search = new DistrictSearch(this);
        DistrictSearchQuery q = new DistrictSearchQuery();
        q.setKeywords(r.adCode);
        q.setSubDistrict(0);
        search.setQuery(q);
        search.setOnDistrictSearchListener(new DistrictSearch.OnDistrictSearchListener() {
            @Override
            public void onDistrictSearched(DistrictResult districtResult) {
                String district = null;
                String city = null;
                int rCode = districtResult != null ? districtResult.getAMapException().getErrorCode() : -1;
                if (rCode == 1000 && districtResult.getDistrict() != null
                        && !districtResult.getDistrict().isEmpty()) {
                    DistrictItem item = districtResult.getDistrict().get(0);
                    if (item != null) {
                        district = item.getName();
                        city = "";
                    }
                }
                if (district == null || district.isEmpty()) district = r.adCode; // 兜底，避免空白
                adCodeNameCache.put(r.adCode, new String[]{city, district});
                replaceRegion(list, r, city, district);
            }
        });
        search.searchDistrictAsyn();
    }

    /** 用反查到的 [市, 区/县] 替换列表中的旧 BusRegion，并刷新适配器 */
    private void replaceRegion(List<BusRegion> list, BusRegion old, String resolvedCity, String resolvedDistrict) {
        int idx = list.indexOf(old);
        if (idx >= 0) {
            list.set(idx, new BusRegion(old.adCode, "", resolvedCity != null ? resolvedCity : "",
                    resolvedDistrict != null ? resolvedDistrict : ""));
        }
        runOnUiThread(() -> adapter.setRegions(list));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
