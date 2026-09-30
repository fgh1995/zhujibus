package org.zjfgh.zhujibus;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公交查询地区模型。
 *
 * <p>设计要点（对应需求第 4 条）：以 {@code adCode}（6 位行政区划代码）作为地区的唯一且稳定标识，
 * 而不是仅用地级市名。例如绍兴市下辖诸暨市(adCode 330681)、上虞区(330604)、嵊州市(330683) 等，
 * 这些区县由不同的公交公司运营，必须按「区/县」(adCode) 来区分。若只用地级市名"绍兴市"去搜索公交，
 * 会把诸暨、上虞等不同公交公司的线路全部混在一起，定位不到准确的公交数据。</p>
 *
 * <p>展示名 {@code regionName} 优先使用「区/县」，仅在区县与地级市同名（如直辖市/地级市主城区）时回落到地级市。</p>
 */
public class BusRegion implements Parcelable {

    public final String adCode;       // 6 位行政区划代码，唯一且稳定
    public final String provinceName; // 省，如"浙江省"
    public final String cityName;     // 地级市，如"绍兴市"
    public final String districtName; // 区/县，如"诸暨市" / "上虞区"
    public final String regionName;   // 展示名：优先区县，否则地级市

    private static final Pattern PROV = Pattern.compile("^(.+?省)");
    private static final Pattern CITY = Pattern.compile("^(.+?市)");

    public BusRegion(String adCode, String provinceName, String cityName, String districtName) {
        this.adCode = adCode == null ? "" : adCode.trim();
        this.provinceName = provinceName == null ? "" : provinceName.trim();
        String cRaw = cityName == null ? "" : cityName.trim();
        String dRaw = districtName == null ? "" : districtName.trim();

        // 归一化：高德定位/输入提示常把"省/市/区"连写在同一字段，
        // 例如 city="浙江省宁波市江北区" 或 district="浙江省宁波市江北区"。
        // 这里按"省"/"市"标记拆分，并交叉剔除 city 含 district、district 含 city 的情况，
        // 最终统一成 city="宁波市"、district="江北区"，UI 显示"宁波市·江北区"。
        String prov = this.provinceName;
        if (prov.isEmpty()) {
            Matcher mp = PROV.matcher(cRaw);
            if (!mp.find()) mp = PROV.matcher(dRaw);
            if (mp.find()) prov = mp.group(1);
        }

        String c1 = stripPrefix(cRaw, prov);
        String d1 = stripPrefix(dRaw, prov);

        // 若 city 缺失，但 district 形如"宁波市江北区"，从中切出市名
        if (c1.isEmpty()) {
            Matcher mc = CITY.matcher(d1);
            if (mc.find()) {
                c1 = mc.group(1);
                d1 = d1.substring(mc.end());
            }
        }

        String d2 = stripPrefix(d1, c1);
        String c2 = c1;
        if (!d2.isEmpty() && c1.length() > d2.length() && c1.endsWith(d2)) {
            c2 = c1.substring(0, c1.length() - d2.length());
        }

        this.cityName = c2;
        this.districtName = d2.isEmpty() ? d1 : d2;

        if (!this.districtName.isEmpty() && !this.districtName.equals(this.cityName)) {
            this.regionName = this.districtName;
        } else {
            this.regionName = this.cityName;
        }
    }

    /** 若 value 以 prefix 开头则去掉该前缀，否则原样返回（prefix 为空则原样） */
    private static String stripPrefix(String value, String prefix) {
        if (value == null || prefix == null || prefix.isEmpty()) return value == null ? "" : value;
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /** 简短展示：常规「市·区/县」（如"绍兴市·上虞区"）；诸暨为特例，显示"浙江省·诸暨市"。 */
    public String toShortString() {
        // 诸暨（adCode 330681）特例：浙江省·诸暨市
        if (adCode != null && adCode.startsWith("330681")) {
            String p = provinceName;
            if (p == null || p.isEmpty()) p = provinceNameFromAdCode(adCode);
            String d = (districtName != null && !districtName.isEmpty()) ? districtName
                    : (regionName != null ? regionName : "");
            return (p != null && !p.isEmpty() ? p + "·" : "") + d;
        }
        // 其余：市·区/县
        if (!cityName.isEmpty() && !districtName.isEmpty() && !districtName.equals(cityName)) {
            return cityName + "·" + districtName;
        }
        return regionName;
    }

    /** 省/直辖市/自治区代码（adCode 前 2 位）-> 名称，用于 province 字段缺失时补全（如诸暨特例） */
    private static final java.util.Map<String, String> PROVINCE_NAMES = new java.util.HashMap<>();
    static {
        PROVINCE_NAMES.put("11", "北京市"); PROVINCE_NAMES.put("12", "天津市");
        PROVINCE_NAMES.put("13", "河北省"); PROVINCE_NAMES.put("14", "山西省");
        PROVINCE_NAMES.put("15", "内蒙古自治区"); PROVINCE_NAMES.put("21", "辽宁省");
        PROVINCE_NAMES.put("22", "吉林省"); PROVINCE_NAMES.put("23", "黑龙江省");
        PROVINCE_NAMES.put("31", "上海市"); PROVINCE_NAMES.put("32", "江苏省");
        PROVINCE_NAMES.put("33", "浙江省"); PROVINCE_NAMES.put("34", "安徽省");
        PROVINCE_NAMES.put("35", "福建省"); PROVINCE_NAMES.put("36", "江西省");
        PROVINCE_NAMES.put("37", "山东省"); PROVINCE_NAMES.put("41", "河南省");
        PROVINCE_NAMES.put("42", "湖北省"); PROVINCE_NAMES.put("43", "湖南省");
        PROVINCE_NAMES.put("44", "广东省"); PROVINCE_NAMES.put("45", "广西壮族自治区");
        PROVINCE_NAMES.put("46", "海南省"); PROVINCE_NAMES.put("50", "重庆市");
        PROVINCE_NAMES.put("51", "四川省"); PROVINCE_NAMES.put("52", "贵州省");
        PROVINCE_NAMES.put("53", "云南省"); PROVINCE_NAMES.put("54", "西藏自治区");
        PROVINCE_NAMES.put("61", "陕西省"); PROVINCE_NAMES.put("62", "甘肃省");
        PROVINCE_NAMES.put("63", "青海省"); PROVINCE_NAMES.put("64", "宁夏回族自治区");
        PROVINCE_NAMES.put("65", "新疆维吾尔自治区"); PROVINCE_NAMES.put("71", "台湾省");
        PROVINCE_NAMES.put("81", "香港特别行政区"); PROVINCE_NAMES.put("82", "澳门特别行政区");
    }

    private static String provinceNameFromAdCode(String adCode) {
        if (adCode == null || adCode.length() < 2) return "";
        String name = PROVINCE_NAMES.get(adCode.substring(0, 2));
        return name != null ? name : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusRegion)) return false;
        BusRegion that = (BusRegion) o;
        return adCode.equals(that.adCode);
    }

    @Override
    public int hashCode() {
        return adCode.hashCode();
    }

    // ===== Parcelable =====

    protected BusRegion(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(adCode);
        dest.writeString(provinceName);
        dest.writeString(cityName);
        dest.writeString(districtName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BusRegion> CREATOR = new Creator<BusRegion>() {
        @Override
        public BusRegion createFromParcel(Parcel in) {
            return new BusRegion(in);
        }

        @Override
        public BusRegion[] newArray(int size) {
            return new BusRegion[size];
        }
    };
}
