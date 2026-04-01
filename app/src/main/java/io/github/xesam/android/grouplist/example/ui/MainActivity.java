package io.github.xesam.android.grouplist.example.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import io.github.xesam.android.grouplist.example.R;
import io.github.xesam.android.grouplist.example.ui.compose.ComposeDemoActivity;
import io.github.xesam.android.grouplist.example.ui.core.CoreDemoActivity;
import io.github.xesam.android.grouplist.example.ui.delegate.DelegateDemoActivity;
import io.github.xesam.android.grouplist.example.ui.engine.EngineDemoActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        setTitle("GroupList 示例");
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Button coreButton = findViewById(R.id.btn_core_demo);
        Button engineButton = findViewById(R.id.btn_engine_demo);
        Button delegateButton = findViewById(R.id.btn_delegate_demo);

        Button composeButton = findViewById(R.id.btn_compose_demo);

        coreButton.setOnClickListener(v -> startActivity(new Intent(this, CoreDemoActivity.class)));
        engineButton.setOnClickListener(v -> startActivity(new Intent(this, EngineDemoActivity.class)));
        delegateButton.setOnClickListener(v -> startActivity(new Intent(this, DelegateDemoActivity.class)));
        composeButton.setOnClickListener(v -> startActivity(new Intent(this, ComposeDemoActivity.class)));
    }
}
