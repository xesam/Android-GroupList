package io.github.xesam.android.grouplist.example.ui.engine;

import androidx.lifecycle.ViewModelProvider;

import io.github.xesam.android.grouplist.example.ui.common.BaseDemoActivity;
import io.github.xesam.android.grouplist.example.ui.common.BaseDemoViewModel;

/** Engine demo 是推荐的默认学习入口。 */
public class EngineDemoActivity extends BaseDemoActivity {

    @Override
    protected BaseDemoViewModel onCreateViewModel() {
        return new ViewModelProvider(this).get(EngineDemoViewModel.class);
    }

    @Override
    protected String demoTitle() {
        return "Engine 示例（默认推荐入口）";
    }
}
