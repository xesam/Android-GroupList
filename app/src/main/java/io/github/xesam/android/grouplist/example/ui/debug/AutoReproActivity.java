package io.github.xesam.android.grouplist.example.ui.debug;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.xesam.android.grouplist.example.R;
import io.github.xesam.android.grouplist.example.ui.common.DemoUiAdapter;
import io.github.xesam.android.grouplist.example.ui.core.CoreDemoViewModel;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * Debug-only deterministic repro harness (exported for adb):
 * automates the scripted expansion sequence behind the on-device crash
 * (expand four groups one by one, each triggering a 1.6s loading resolve),
 * without needing touch-event injection.
 *
 * Usage: adb shell am start -n io.github.xesam.android.grouplist.example/.ui.debug.AutoReproActivity
 */
public class AutoReproActivity extends AppCompatActivity {

    private CoreDemoViewModel viewModel;
    private final Handler scriptHandler = new Handler(Looper.getMainLooper());

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
        setTitle("自动复现：展开 + loading resolve");

        DemoUiAdapter adapter = new DemoUiAdapter(mode -> { }, key -> { });

        RecyclerView recyclerView = findViewById(R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // notify-stream probe: dump every dispatch the adapter pushes to RecyclerView
        android.util.Log.i("ReproProbe", "observers attached");
        adapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { android.util.Log.i("ReproProbe", "onChanged (reload)"); }
            @Override public void onItemRangeChanged(int p, int c) { android.util.Log.i("ReproProbe", "changed " + p + "+" + c); }
            @Override public void onItemRangeChanged(int p, int c, Object payload) { android.util.Log.i("ReproProbe", "changed " + p + "+" + c + " payload=" + payload); }
            @Override public void onItemRangeInserted(int p, int c) { android.util.Log.i("ReproProbe", "inserted " + p + "+" + c); }
            @Override public void onItemRangeRemoved(int p, int c) { android.util.Log.i("ReproProbe", "removed " + p + "+" + c); }
            @Override public void onItemRangeMoved(int f, int t, int c) { android.util.Log.i("ReproProbe", "moved " + f + "->" + t); }
        });

        viewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(CoreDemoViewModel.class);
        viewModel.getUiState().observe(this, adapter::submitSnapshot);

        // first frame settles (initial RELOAD layout), then the scripted user actions:
        // expand three comment groups + one topic group, 600ms apart — overlapping
        // loading resolves land their UPDATE batches across the INSERT frames.
        // Pending steps are cancelled in onDestroy: the surviving ViewModel keeps its
        // state across a rotation, so re-running the script on it would double-toggle.
        scriptHandler.postDelayed(() -> viewModel.onToggleGroupRequested(GroupKey.of("comment:1")), 600);
        scriptHandler.postDelayed(() -> viewModel.onToggleGroupRequested(GroupKey.of("comment:2")), 1200);
        scriptHandler.postDelayed(() -> viewModel.onToggleGroupRequested(GroupKey.of("comment:3")), 1800);
        scriptHandler.postDelayed(() -> viewModel.onToggleGroupRequested(GroupKey.of("topic:1")), 2400);
    }

    @Override
    protected void onDestroy() {
        scriptHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
