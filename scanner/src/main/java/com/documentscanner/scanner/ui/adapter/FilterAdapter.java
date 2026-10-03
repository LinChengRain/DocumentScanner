package com.documentscanner.scanner.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.cv.FilterType;
import com.documentscanner.scanner.databinding.ScannerItemFilterBinding;

/** 滤镜选择条：横向列表，单选。 */
public class FilterAdapter extends RecyclerView.Adapter<FilterAdapter.Holder> {

    public interface OnPick {
        void onPick(FilterType type);
    }

    private final String[] labels;
    private final FilterType[] types = FilterType.values();
    private final OnPick listener;
    private int selected;

    public FilterAdapter(Context context, FilterType current, OnPick listener) {
        this.labels = context.getResources().getStringArray(R.array.scanner_filter_names);
        this.listener = listener;
        this.selected = current == null ? FilterType.DEFAULT.ordinal() : current.ordinal();
    }

    public void select(FilterType type) {
        int previous = selected;
        selected = type == null ? selected : type.ordinal();
        notifyItemChanged(previous);
        notifyItemChanged(selected);
    }

    public FilterType getSelected() {
        return FilterType.byOrdinal(selected);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ScannerItemFilterBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.binding.tvFilterName.setText(labels[position]);
        holder.binding.tvFilterName.setSelected(position == selected);
        holder.itemView.setOnClickListener(v -> {
            // 列表回收的一瞬间位置可能是 NO_POSITION，直接拿去索引数组会越界
            int at = holder.getBindingAdapterPosition();
            if (at == RecyclerView.NO_POSITION || at == selected) return;
            int previous = selected;
            selected = at;
            notifyItemChanged(previous);
            notifyItemChanged(selected);
            if (listener != null) listener.onPick(types[selected]);
        });
    }

    @Override
    public int getItemCount() {
        return Math.min(types.length, labels.length);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ScannerItemFilterBinding binding;

        Holder(ScannerItemFilterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
