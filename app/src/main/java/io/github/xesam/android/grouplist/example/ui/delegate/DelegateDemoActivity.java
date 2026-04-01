package io.github.xesam.android.grouplist.example.ui.delegate;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.xesam.android.grouplist.example.R;
import io.github.xesam.android.grouplist.example.ui.common.BaseDemoViewModel;
import io.github.xesam.android.grouplist.example.ui.engine.EngineDemoViewModel;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * 组合路径 Demo：渲染层是“公司基类 + SnapshotAdapterDelegate”，与 Engine 示例页
 * 复用同一个 EngineDemoViewModel（数据/状态层完全一致，只有渲染接入方式不同）。
 */
public class DelegateDemoActivity extends AppCompatActivity {

    private BaseDemoViewModel viewModel;

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
        setTitle("组合接入示例（自有基类 + Delegate）");

        DelegatingDemoAdapter adapter = new DelegatingDemoAdapter(this::onFilterModeSelected, this::onToggleGroupRequested);

        RecyclerView recyclerView = findViewById(R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(EngineDemoViewModel.class);
        viewModel.getUiState().observe(this, adapter::submitSnapshot);
    }

    private void onFilterModeSelected(FeedUiModels.GroupFilterMode mode) {
        viewModel.onFilterModeSelected(mode);
    }

    private void onToggleGroupRequested(GroupKey groupKey) {
        viewModel.onToggleGroupRequested(groupKey);
    }
}
