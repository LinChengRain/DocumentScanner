package com.documentscanner.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.documentscanner.R;
import com.documentscanner.databinding.ItemSessionBinding;
import com.documentscanner.model.ScanSession;
import com.documentscanner.ui.PageImageLoader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 首页历史会话列表：一行一份会话，点击继续、右侧按钮展开操作。 */
public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {

    public interface Listener {
        void onSessionClick(ScanSession.Info info);

        void onSessionMenu(ScanSession.Info info);
    }

    private final List<ScanSession.Info> items;
    private final Listener listener;
    private final PageImageLoader loader = new PageImageLoader();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    public SessionAdapter(List<ScanSession.Info> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemSessionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ScanSession.Info info = items.get(position);
        holder.binding.tvSessionTitle.setText(TextUtils.isEmpty(info.title)
                ? holder.itemView.getContext().getString(R.string.main_session_untitled)
                : info.title);
        holder.binding.tvSessionMeta.setText(holder.itemView.getContext()
                .getString(R.string.main_session_meta, info.pages,
                        dateFormat.format(new Date(info.createdAt))));
        if (info.cover == null) {
            holder.binding.imgCover.setImageResource(R.drawable.ic_pdf);
        } else {
            loader.loadInto(holder.binding.imgCover, info.cover);
        }

        holder.itemView.setOnClickListener(v -> {
            int at = holder.getBindingAdapterPosition();
            if (at != RecyclerView.NO_POSITION) listener.onSessionClick(items.get(at));
        });
        holder.binding.btnSessionMenu.setOnClickListener(v -> {
            int at = holder.getBindingAdapterPosition();
            if (at != RecyclerView.NO_POSITION) listener.onSessionMenu(items.get(at));
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemSessionBinding binding;

        Holder(ItemSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
