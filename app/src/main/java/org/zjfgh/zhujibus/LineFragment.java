package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LineFragment extends Fragment {
    private List<?> filteredLines = new ArrayList<>();
    private RecyclerView recyclerView;
    private LineAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.line_fragment, container, false);
    }

    public void searchLines(String keyword) {
        filteredLines.clear();
        Log.e("ZhuJiBus", "搜索线路" + keyword);
    }
}

