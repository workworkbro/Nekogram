package tw.nekomimi.nekogram;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String crashLog = getIntent() != null && getIntent().getStringExtra("crash_text") != null
                ? getIntent().getStringExtra("crash_text")
                : "No error details available";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Произошла ошибка при запуске Nekogram");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Color.parseColor("#D32F2F"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, pad / 2);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Текст ошибки автоматически сохранен в буфер обмена и в папку Загрузки (nekogram_crash.txt). Нажмите кнопку ниже, чтобы скопировать его:");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(Color.DKGRAY);
        desc.setPadding(0, 0, 0, pad / 2);
        root.addView(desc);

        Button copyBtn = new Button(this);
        copyBtn.setText("Скопировать ошибку в буфер обмена");
        copyBtn.setBackgroundColor(Color.parseColor("#1976D2"));
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog));
                    Toast.makeText(this, "Ошибка скопирована в буфер обмена!", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {}
        });
        root.addView(copyBtn);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.topMargin = pad / 2;
        scrollParams.bottomMargin = pad / 2;
        scroll.setLayoutParams(scrollParams);

        TextView logView = new TextView(this);
        logView.setText(crashLog);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#212121"));
        logView.setBackgroundColor(Color.parseColor("#F5F5F5"));
        logView.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll);

        Button closeBtn = new Button(this);
        closeBtn.setText("Закрыть");
        closeBtn.setOnClickListener(v -> {
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        root.addView(closeBtn);

        setContentView(root);
    }
}
