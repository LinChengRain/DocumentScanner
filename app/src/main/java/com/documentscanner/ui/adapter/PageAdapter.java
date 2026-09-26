package com.documentscanner.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.documentscanner.databinding.ItemPageBinding;
import com.documentscanner.model.ScanPage;

import java.util.List;

/** 页面网格：缩略图 + 页码 + 删除，长按进入拖拽排序。 */
public class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

    public interface Listener {
        void onPageClick(ScanPage page, int position);

        void onPageDelete(ScanPage page, int position);

        void onPageMenu(ScanPage page, int position);
    }

    private final List<ScanPage> pages;
    private final Listener listener;
    private final com.documentscanner.ui.PageImageLoader loader = new com.documentscanner.ui.PageImageLoader();

    public PageAdapter(List<ScanPage> pages, Listener listener) {
        this.pages = pages;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ScanPage page = pages.get(position);
        holder.binding.tvPageIndex.setText(String.valueOf(position + 1));
        holder.binding.tvPending.setVisibility(page.edited ? View.GONE : View.VISIBLE);
        holder.binding.imgPage.setImageDrawable(null);
        loader.loadInto(holder.binding.imgPage, page);

        holder.itemView.setOnClickListener(v -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) listener.onPageClick(pages.get(index), index);
        });
        holder.binding.btnDeletePage.setOnClickListener(v -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) listener.onPageDelete(pages.get(index), index);
        });
        holder.binding.btnPageMenu.setOnClickListener(v -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) listener.onPageMenu(pages.get(index), index);
        });
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    public ScanPage pageAt(int position) {
        return position >= 0 && position < pages.size() ? pages.get(position) : null;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final ItemPageBinding binding;

        Holder(ItemPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
