package com.umutk.lrclrc;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Shows all indexed songs before the user has typed a search query. */
public class BrowseAdapter extends RecyclerView.Adapter<BrowseAdapter.VH> {

    public interface OnItemClick {
        void onClick(LyricsRepository.Song song);
    }

    private final List<LyricsRepository.Song> items = new ArrayList<>();
    private final OnItemClick listener;

    public BrowseAdapter(OnItemClick listener) {
        this.listener = listener;
    }

    public void submit(List<LyricsRepository.Song> songs) {
        items.clear();
        items.addAll(songs);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_browse, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LyricsRepository.Song song = items.get(position);
        h.title.setText(song.title);
        h.subtitle.setText(song.folder);
        AlbumArtLoader.getInstance().load(h.itemView.getContext(), song.audioPath, h.art);
        h.itemView.setOnClickListener(v -> listener.onClick(song));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title, subtitle;
        VH(View v) {
            super(v);
            art = v.findViewById(R.id.browseAlbumArt);
            title = v.findViewById(R.id.browseTitle);
            subtitle = v.findViewById(R.id.browseSubtitle);
        }
    }
}
