package org.zjfgh.zhujibus;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DirectionMarkerDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "direction_marker.db";
    private static final int DATABASE_VERSION = 4;

    private static final String TABLE_MARKERS = "direction_markers";
    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_MARKER_NAME = "marker_name";
    private static final String COLUMN_STATION_NAME = "station_name";
    private static final String COLUMN_LINE_IDS = "line_ids";
    private static final String COLUMN_STATION_IDS = "station_ids";
    private static final String COLUMN_LINE_NAMES = "line_names";
    private static final String COLUMN_LINE_TYPES = "line_types";
    private static final String COLUMN_DIRECTIONS = "directions";
    private static final String COLUMN_START_STATIONS = "start_stations";
    private static final String COLUMN_END_STATIONS = "end_stations";
    private static final String COLUMN_DEPARTURE_TIMES = "departure_times";
    private static final String COLUMN_COLLECT_TIMES = "collect_times";
    private static final String COLUMN_CREATE_TIME = "create_time";

    private static DirectionMarkerDatabaseHelper instance;

    public static synchronized DirectionMarkerDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DirectionMarkerDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DirectionMarkerDatabaseHelper(@Nullable Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE_MARKERS + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_MARKER_NAME + " TEXT NOT NULL, " +
                COLUMN_STATION_NAME + " TEXT NOT NULL, " +
                COLUMN_LINE_IDS + " TEXT NOT NULL, " +
                COLUMN_STATION_IDS + " TEXT NOT NULL, " +
                COLUMN_LINE_NAMES + " TEXT, " +
                COLUMN_LINE_TYPES + " TEXT, " +
                COLUMN_DIRECTIONS + " TEXT, " +
                COLUMN_START_STATIONS + " TEXT, " +
                COLUMN_END_STATIONS + " TEXT, " +
                COLUMN_DEPARTURE_TIMES + " TEXT, " +
                COLUMN_COLLECT_TIMES + " TEXT, " +
                COLUMN_CREATE_TIME + " INTEGER NOT NULL)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_MARKERS);
            onCreate(db);
        }
    }

    public long insertMarker(DirectionMarker marker) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_MARKER_NAME, marker.markerName);
        values.put(COLUMN_STATION_NAME, marker.stationName);
        values.put(COLUMN_LINE_IDS, marker.getLineIdsJson());
        values.put(COLUMN_STATION_IDS, marker.getStationIdsJson());
        values.put(COLUMN_LINE_NAMES, marker.getLineNamesJson());
        values.put(COLUMN_LINE_TYPES, marker.getLineTypesJson());
        values.put(COLUMN_DIRECTIONS, marker.getDirectionsJson());
        values.put(COLUMN_START_STATIONS, marker.getStartStationsJson());
        values.put(COLUMN_END_STATIONS, marker.getEndStationsJson());
        values.put(COLUMN_DEPARTURE_TIMES, marker.getDepartureTimesJson());
        values.put(COLUMN_COLLECT_TIMES, marker.getCollectTimesJson());
        values.put(COLUMN_CREATE_TIME, marker.createTime);
        return db.insert(TABLE_MARKERS, null, values);
    }

    public int updateMarker(DirectionMarker marker) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_MARKER_NAME, marker.markerName);
        values.put(COLUMN_LINE_IDS, marker.getLineIdsJson());
        values.put(COLUMN_STATION_IDS, marker.getStationIdsJson());
        values.put(COLUMN_LINE_NAMES, marker.getLineNamesJson());
        values.put(COLUMN_LINE_TYPES, marker.getLineTypesJson());
        values.put(COLUMN_DIRECTIONS, marker.getDirectionsJson());
        values.put(COLUMN_START_STATIONS, marker.getStartStationsJson());
        values.put(COLUMN_END_STATIONS, marker.getEndStationsJson());
        values.put(COLUMN_DEPARTURE_TIMES, marker.getDepartureTimesJson());
        values.put(COLUMN_COLLECT_TIMES, marker.getCollectTimesJson());
        return db.update(TABLE_MARKERS, values, COLUMN_ID + " = ?",
                new String[]{String.valueOf(marker.id)});
    }

    public int deleteMarker(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_MARKERS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public DirectionMarker getMarkerByStationAndMarkerName(String stationName, String markerName) {
        SQLiteDatabase db = getReadableDatabase();
        android.database.Cursor cursor = db.query(TABLE_MARKERS, null,
                COLUMN_STATION_NAME + " = ? AND " + COLUMN_MARKER_NAME + " = ?",
                new String[]{stationName, markerName},
                null, null, null);
        DirectionMarker marker = null;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                marker = cursorToMarker(cursor);
            }
            cursor.close();
        }
        return marker;
    }

    public List<DirectionMarker> getMarkersByStationName(String stationName) {
        List<DirectionMarker> markers = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        android.database.Cursor cursor = db.query(TABLE_MARKERS, null,
                COLUMN_STATION_NAME + " = ?", new String[]{stationName},
                null, null, COLUMN_CREATE_TIME + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                markers.add(cursorToMarker(cursor));
            }
            cursor.close();
        }
        return markers;
    }

    public List<DirectionMarker> getAllMarkers() {
        List<DirectionMarker> markers = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        android.database.Cursor cursor = db.query(TABLE_MARKERS, null,
                null, null, null, null, COLUMN_CREATE_TIME + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                markers.add(cursorToMarker(cursor));
            }
            cursor.close();
        }
        return markers;
    }

    private DirectionMarker cursorToMarker(android.database.Cursor cursor) {
        DirectionMarker marker = new DirectionMarker();
        marker.id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
        marker.markerName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MARKER_NAME));
        marker.stationName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATION_NAME));
        marker.setLineIdsFromJson(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LINE_IDS)));
        marker.setStationIdsFromJson(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATION_IDS)));

        int lineNamesIdx = cursor.getColumnIndex(COLUMN_LINE_NAMES);
        if (lineNamesIdx >= 0) {
            String lineNames = cursor.getString(lineNamesIdx);
            if (lineNames != null && !lineNames.isEmpty()) {
                marker.setLineNamesFromJson(lineNames);
            }
        }

        int lineTypesIdx = cursor.getColumnIndex(COLUMN_LINE_TYPES);
        if (lineTypesIdx >= 0) {
            String lineTypes = cursor.getString(lineTypesIdx);
            if (lineTypes != null && !lineTypes.isEmpty()) {
                marker.setLineTypesFromJson(lineTypes);
            }
        }

        int directionsIdx = cursor.getColumnIndex(COLUMN_DIRECTIONS);
        if (directionsIdx >= 0) {
            String directions = cursor.getString(directionsIdx);
            if (directions != null && !directions.isEmpty()) {
                marker.setDirectionsFromJson(directions);
            }
        }

        int startStationsIdx = cursor.getColumnIndex(COLUMN_START_STATIONS);
        if (startStationsIdx >= 0) {
            String startStations = cursor.getString(startStationsIdx);
            if (startStations != null && !startStations.isEmpty()) {
                marker.setStartStationsFromJson(startStations);
            }
        }

        int endStationsIdx = cursor.getColumnIndex(COLUMN_END_STATIONS);
        if (endStationsIdx >= 0) {
            String endStations = cursor.getString(endStationsIdx);
            if (endStations != null && !endStations.isEmpty()) {
                marker.setEndStationsFromJson(endStations);
            }
        }

        int departureTimesIdx = cursor.getColumnIndex(COLUMN_DEPARTURE_TIMES);
        if (departureTimesIdx >= 0) {
            String departureTimes = cursor.getString(departureTimesIdx);
            if (departureTimes != null && !departureTimes.isEmpty()) {
                marker.setDepartureTimesFromJson(departureTimes);
            }
        }

        int collectTimesIdx = cursor.getColumnIndex(COLUMN_COLLECT_TIMES);
        if (collectTimesIdx >= 0) {
            String collectTimes = cursor.getString(collectTimesIdx);
            if (collectTimes != null && !collectTimes.isEmpty()) {
                marker.setCollectTimesFromJson(collectTimes);
            }
        }

        marker.createTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATE_TIME));
        return marker;
    }
}