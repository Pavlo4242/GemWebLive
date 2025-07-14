// TranslationAdapter.java
package com.gemweblive

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.gemweblive.databinding.ItemTranslationBinding;
import java.util.ArrayList;
import java.util.List;

public class TranslationAdapter extends RecyclerView.Adapter<TranslationAdapter.TranslationViewHolder> {

    // Helper data class to replace Kotlin's Pair for clarity
    private static class TranslationItem {
        String text;
        boolean isUser;

        TranslationItem(String text, boolean isUser) {
            this.text = text;
            this.isUser = isUser;
        }
    }

    private final List<TranslationItem> translations = new ArrayList<>();
    private Boolean lastSpeakerIsUser = null;

    // ViewHolder class
    public static class TranslationViewHolder extends RecyclerView.ViewHolder {
        final ItemTranslationBinding binding;

        public TranslationViewHolder(ItemTranslationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    @NonNull
    @Override
    public TranslationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTranslationBinding binding = ItemTranslationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TranslationViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TranslationViewHolder holder, int position) {
        TranslationItem item = translations.get(position);
        holder.binding.translationText.setText(item.text);
        holder.binding.speakerLabel.setText(item.isUser ? "You said:" : "Translation:");
    }

    @Override
    public int getItemCount() {
        return translations.size();
    }

    public void addOrUpdateTranslation(String text, boolean isUser) {
        if (Boolean.valueOf(isUser).equals(lastSpeakerIsUser) && !translations.isEmpty()) {
            // Update last message
            translations.get(0).text = text;
            notifyItemChanged(0);
        } else {
            // Add new message
            translations.add(0, new TranslationItem(text, isUser));
            notifyItemInserted(0);
        }
        lastSpeakerIsUser = isUser;
    }
}
