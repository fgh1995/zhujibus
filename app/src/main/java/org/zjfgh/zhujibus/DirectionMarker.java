package org.zjfgh.zhujibus;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class DirectionMarker {
    public long id;
    public String markerName;
    public String stationName;
    public List<String> lineIds = new ArrayList<>();
    public List<String> stationIds = new ArrayList<>();
    public List<String> lineNames = new ArrayList<>();
    public List<String> lineTypes = new ArrayList<>();
    public List<String> directions = new ArrayList<>();
    public List<String> startStations = new ArrayList<>();
    public List<String> endStations = new ArrayList<>();
    public List<String> departureTimes = new ArrayList<>();
    public List<String> collectTimes = new ArrayList<>();
    public long createTime;

    public static class LineInfo {
        public String lineId;
        public String stationId;
        public String lineName;
        public String lineType;
        public String startStation;
        public String endStation;
        public String departureTime;
        public String collectTime;

        public LineInfo(String lineId, String stationId, String lineName, String lineType,
                       String startStation, String endStation, String departureTime, String collectTime) {
            this.lineId = lineId;
            this.stationId = stationId;
            this.lineName = lineName;
            this.lineType = lineType;
            this.startStation = startStation;
            this.endStation = endStation;
            this.departureTime = departureTime;
            this.collectTime = collectTime;
        }
    }

    public DirectionMarker() {
    }

    public DirectionMarker(String markerName, String stationName) {
        this.markerName = markerName;
        this.stationName = stationName;
        this.createTime = System.currentTimeMillis();
    }

    public void addLine(String lineId, String stationId, String lineName, String lineType,String startStation, String endStation, String departureTime, String collectTime) {
        if (!lineIds.contains(lineId)) {
            lineIds.add(lineId);
            stationIds.add(stationId);
            lineNames.add(lineName != null ? lineName : "");
            lineTypes.add(lineType != null ? lineType : "");
            startStations.add(startStation != null ? startStation : "");
            endStations.add(endStation != null ? endStation : "");
            departureTimes.add(departureTime != null ? departureTime : "");
            collectTimes.add(collectTime != null ? collectTime : "");
        }
    }

    public void removeLine(String lineId) {
        int index = lineIds.indexOf(lineId);
        if (index >= 0) {
            lineIds.remove(index);
            stationIds.remove(index);
            if (index < lineNames.size()) lineNames.remove(index);
            if (index < lineTypes.size()) lineTypes.remove(index);
            if (index < directions.size()) directions.remove(index);
            if (index < startStations.size()) startStations.remove(index);
            if (index < endStations.size()) endStations.remove(index);
            if (index < departureTimes.size()) departureTimes.remove(index);
            if (index < collectTimes.size()) collectTimes.remove(index);
        }
    }

    public String getLineIdsJson() {
        return new JSONArray(lineIds).toString();
    }

    public String getStationIdsJson() {
        return new JSONArray(stationIds).toString();
    }

    public String getLineNamesJson() {
        return new JSONArray(lineNames).toString();
    }

    public String getLineTypesJson() {
        return new JSONArray(lineTypes).toString();
    }

    public String getDirectionsJson() {
        return new JSONArray(directions).toString();
    }

    public String getStartStationsJson() {
        return new JSONArray(startStations).toString();
    }

    public String getEndStationsJson() {
        return new JSONArray(endStations).toString();
    }

    public String getDepartureTimesJson() {
        return new JSONArray(departureTimes).toString();
    }

    public String getCollectTimesJson() {
        return new JSONArray(collectTimes).toString();
    }

    public void setLineIdsFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            lineIds.clear();
            for (int i = 0; i < array.length(); i++) {
                lineIds.add(array.getString(i));
            }
        } catch (JSONException e) {
            lineIds.clear();
        }
    }

    public void setStationIdsFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            stationIds.clear();
            for (int i = 0; i < array.length(); i++) {
                stationIds.add(array.getString(i));
            }
        } catch (JSONException e) {
            stationIds.clear();
        }
    }

    public void setLineNamesFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            lineNames.clear();
            for (int i = 0; i < array.length(); i++) {
                lineNames.add(array.getString(i));
            }
        } catch (JSONException e) {
            lineNames.clear();
        }
    }

    public void setLineTypesFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            lineTypes.clear();
            for (int i = 0; i < array.length(); i++) {
                lineTypes.add(array.getString(i));
            }
        } catch (JSONException e) {
            lineTypes.clear();
        }
    }

    public void setDirectionsFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            directions.clear();
            for (int i = 0; i < array.length(); i++) {
                directions.add(array.getString(i));
            }
        } catch (JSONException e) {
            directions.clear();
        }
    }

    public void setStartStationsFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            startStations.clear();
            for (int i = 0; i < array.length(); i++) {
                startStations.add(array.getString(i));
            }
        } catch (JSONException e) {
            startStations.clear();
        }
    }

    public void setEndStationsFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            endStations.clear();
            for (int i = 0; i < array.length(); i++) {
                endStations.add(array.getString(i));
            }
        } catch (JSONException e) {
            endStations.clear();
        }
    }

    public void setDepartureTimesFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            departureTimes.clear();
            for (int i = 0; i < array.length(); i++) {
                departureTimes.add(array.getString(i));
            }
        } catch (JSONException e) {
            departureTimes.clear();
        }
    }

    public void setCollectTimesFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            collectTimes.clear();
            for (int i = 0; i < array.length(); i++) {
                collectTimes.add(array.getString(i));
            }
        } catch (JSONException e) {
            collectTimes.clear();
        }
    }

    public String getLineName(int index) {
        if (index >= 0 && index < lineNames.size()) {
            return lineNames.get(index);
        }
        return "";
    }

    public String getStartStation(int index) {
        if (index >= 0 && index < startStations.size()) {
            return startStations.get(index);
        }
        return "";
    }

    public String getEndStation(int index) {
        if (index >= 0 && index < endStations.size()) {
            return endStations.get(index);
        }
        return "";
    }

    public String getDepartureTime(int index) {
        if (index >= 0 && index < departureTimes.size()) {
            return departureTimes.get(index);
        }
        return "";
    }

    public String getCollectTime(int index) {
        if (index >= 0 && index < collectTimes.size()) {
            return collectTimes.get(index);
        }
        return "";
    }

    public List<LineInfo> getLines() {
        List<LineInfo> lines = new ArrayList<>();
        for (int i = 0; i < lineIds.size(); i++) {
            lines.add(new LineInfo(
                    lineIds.get(i),
                    stationIds.get(i),
                    getLineName(i),
                    i < lineTypes.size() ? lineTypes.get(i) : "",
                    getStartStation(i),
                    getEndStation(i),
                    getDepartureTime(i),
                    getCollectTime(i)
            ));
        }
        return lines;
    }

    public void removeLineByIndex(int index) {
        if (index >= 0 && index < lineIds.size()) {
            lineIds.remove(index);
            if (index < stationIds.size()) stationIds.remove(index);
            if (index < lineNames.size()) lineNames.remove(index);
            if (index < lineTypes.size()) lineTypes.remove(index);
            if (index < directions.size()) directions.remove(index);
            if (index < startStations.size()) startStations.remove(index);
            if (index < endStations.size()) endStations.remove(index);
            if (index < departureTimes.size()) departureTimes.remove(index);
            if (index < collectTimes.size()) collectTimes.remove(index);
        }
    }
}