package com.umutk.lrclrc;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {

    public interface OnLineClick {
        void onPlay(LyricsRepository.Song song, int seekSeconds);
    }

    private final List<LyricsRepository.Song> items = new ArrayList<>();
    private final OnLineClick listener;
    private final int highlightBg;
    private final int highlightFg;
    private final int contextColor;

    public ResultsAdapter(Context ctx, OnLineClick listener) {
        this.listener = listener;
        this.highlightBg = ctx.getColor(R.color.match_highlight);
        this.highlightFg = ctx.getColor(R.color.match_highlight_on);
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        this.contextColor = ctx.getColor(night ? R.color.context_text_dark : R.color.context_text_light);
    }

    public void submit(List<LyricsRepository.Song> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_result, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LyricsRepository.Song song = items.get(position);
        h.title.setText(song.title);
        h.subtitle.setText(buildSubtitle(song));
        h.snippet.setText(buildSnippet(song));
        h.snippet.setMovementMethod(LinkMovementMethod.getInstance());

        // Load album art
        AlbumArtLoader.getInstance().load(h.itemView.getContext(), song.audioPath, h.albumArt);

        int firstSeek = firstSeekSeconds(song);
        h.playButton.setOnClickListener(v -> listener.onPlay(song, firstSeek));
        h.itemView.setOnClickListener(v -> listener.onPlay(song, firstSeek));
    }

    private String buildSubtitle(LyricsRepository.Song song) {
        String hits = song.hits == 1 ? "1 hit" : song.hits + " hits";
        String name = (song.artist != null && !song.artist.isEmpty()) ? song.artist : song.folder;
        return (name != null && !name.isEmpty())
                ? name + "  ·  " + hits : hits;
    }

    private int firstSeekSeconds(LyricsRepository.Song song) {
        for (LyricsRepository.DisplayLine dl : song.displayLines) {
            if (dl.isMatch && dl.line.seekSeconds >= 0) return dl.line.seekSeconds;
        }
        return -1;
    }

    private CharSequence buildSnippet(LyricsRepository.Song song) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < song.displayLines.size(); i++) {
            LyricsRepository.DisplayLine dl = song.displayLines.get(i);
            int start = sb.length();
            sb.append(dl.line.text);
            int end = sb.length();

            if (dl.isMatch) {
                if (dl.matchStart >= 0) {
                    int hs = start + dl.matchStart;
                    int he = Math.min(end, hs + dl.matchLen);
                    if (hs < he) {
                        sb.setSpan(new BackgroundColorSpan(highlightBg), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new ForegroundColorSpan(highlightFg), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new StyleSpan(Typeface.BOLD), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                final int seek = dl.line.seekSeconds;
                sb.setSpan(new ClickableSpan() {
                    @Override public void onClick(@NonNull View w) { listener.onPlay(song, seek); }
                    @Override public void updateDrawState(@NonNull android.text.TextPaint ds) { ds.setUnderlineText(false); }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(contextColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < song.displayLines.size() - 1) sb.append("\n");
        }
        return sb;
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView albumArt;
        final TextView title, subtitle, snippet;
        final MaterialButton playButton;
        VH(View v) {
            super(v);
            albumArt = v.findViewById(R.id.albumArt);
            title = v.findViewById(R.id.songTitle);
            subtitle = v.findViewById(R.id.songSubtitle);
            snippet = v.findViewById(R.id.snippetText);
            playButton = v.findViewById(R.id.playButton);
        }
    }
}
