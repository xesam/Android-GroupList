package io.github.xesam.android.grouplist.recyclerview.binder;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

public interface ItemBinder {

    @NonNull
    RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent);

    void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position);

    default void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            @NonNull ListSnapshot.Row<?> row,
            int position,
            @NonNull List<Object> payloads
    ) {
        onBindViewHolder(holder, row, position);
    }
}
