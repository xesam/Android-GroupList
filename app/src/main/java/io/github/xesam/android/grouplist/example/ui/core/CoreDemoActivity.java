package io.github.xesam.android.grouplist.example.ui.core;

import androidx.lifecycle.ViewModelProvider;

import io.github.xesam.android.grouplist.example.ui.common.BaseDemoActivity;
import io.github.xesam.android.grouplist.example.ui.common.BaseDemoViewModel;

/** Core demo 演示手动构造 CoreRow 的进阶路径。 */
public class CoreDemoActivity extends BaseDemoActivity {

    @Override
    protected BaseDemoViewModel onCreateViewModel() {
        return new ViewModelProvider(this).get(CoreDemoViewModel.class);
    }

    @Override
    protected String demoTitle() {
        return "Core 示例（进阶 / 底层能力）";
    }
}
