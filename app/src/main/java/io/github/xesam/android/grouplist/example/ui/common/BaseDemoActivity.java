package io.github.xesam.android.grouplist.example.ui.common;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.xesam.android.grouplist.example.R;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * Core/Engine 两个 demo 的公共装配模板：只差 ViewModel 工厂和标题，
 * 渲染层（Adapter + 滚动协调器）完全一致。
 */
public abstract class BaseDemoActivity extends AppCompatActivity {

    protected DemoUiAdapter adapter;
    protected DemoGroupScrollCoordinator groupScrollCoordinator;
    private BaseDemoViewModel viewModel;

    protected abstract BaseDemoViewModel onCreateViewModel();

    protected abstract String demoTitle();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_demo_list);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        setTitle(demoTitle());

        adapter = new DemoUiAdapter(this::onFilterModeSelected, this::onToggleGroupRequested);

        RecyclerView recyclerView = findViewById(R.id.list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);
        groupScrollCoordinator = new DemoGroupScrollCoordinator(recyclerView, layoutManager, adapter);

        viewModel = onCreateViewModel();
        viewModel.getUiState().observe(this, snapshot -> {
            adapter.submitSnapshot(snapshot);
            groupScrollCoordinator.onSnapshotApplied();
        });
    }

    private void onFilterModeSelected(FeedUiModels.GroupFilterMode mode) {
        viewModel.onFilterModeSelected(mode);
    }

    private void onToggleGroupRequested(GroupKey groupKey) {
        groupScrollCoordinator.onGroupToggleRequested(groupKey);
        viewModel.onToggleGroupRequested(groupKey);
    }
}
