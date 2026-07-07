package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class NoticeAdapter extends RecyclerView.Adapter<NoticeAdapter.NoticeViewHolder> {
    private final List<BusApiClient.BusAnnouncement> notices;
    private OnItemClickListener onItemClickListener;

    public interface OnItemClickListener {
        void onItemClick(BusApiClient.BusAnnouncement notice);
    }

    public NoticeAdapter(List<BusApiClient.BusAnnouncement> notices) {
        this.notices = notices;
    }

    @NonNull
    @Override
    public NoticeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notice, parent, false);
        return new NoticeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NoticeViewHolder holder, int position) {
        BusApiClient.BusAnnouncement notice = notices.get(position);
        holder.tvTitle.setText(notice.title == null ? "" : notice.title);
        holder.tvDate.setText(notice.publishDate == null ? "" : notice.publishDate);
        holder.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) {
                onItemClickListener.onItemClick(notice);
            }
        });
    }

    @Override
    public int getItemCount() {
        return notices.size();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    static class NoticeViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvDate;

        NoticeViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_notice_title);
            tvDate = itemView.findViewById(R.id.tv_notice_date);
        }
    }
}
