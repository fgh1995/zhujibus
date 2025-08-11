package org.zjfgh.zhujibus;


import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

// StationFragment.java
public class StationFragment extends Fragment {
    private final List<?> filteredLines = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.line_fragment, container, false);
    }

    public void searchStations(String keyword) {
        filteredLines.clear();
        Log.e("ZhuJiBus", "搜索站点" + keyword);
    }
}

