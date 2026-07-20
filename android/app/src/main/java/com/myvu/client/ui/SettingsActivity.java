package com.myvu.client.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.myvu.client.R;
import com.myvu.client.ai.ClaudeClient;
import com.myvu.client.core.Prefs;

/**
 * App settings: the API keys and the assistant's system prompt.
 *
 * Everything here is plain SharedPreferences -- no service binding needed. Each
 * field saves as you type, and both the keys and the prompt are re-read at the
 * start of every AI turn, so edits take effect on the next question without a
 * reconnect or a restart.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText txtApiKey, txtGroqKey, txtSystemPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        txtApiKey = findViewById(R.id.txtApiKey);
        txtGroqKey = findViewById(R.id.txtGroqKey);
        txtSystemPrompt = findViewById(R.id.txtSystemPrompt);

        txtApiKey.setText(Prefs.claudeApiKey(this));
        txtGroqKey.setText(Prefs.groqApiKey(this));
        // An empty stored prompt means "use the shipped default" -- show that
        // default as the actual text so it is editable rather than invisible.
        String stored = Prefs.systemPrompt(this);
        txtSystemPrompt.setText(stored.isEmpty() ? ClaudeClient.DEFAULT_SYSTEM_PROMPT : stored);

        persist(txtApiKey, new Saver() {
            @Override public void save(String v) { Prefs.setClaudeApiKey(SettingsActivity.this, v); }
        });
        persist(txtGroqKey, new Saver() {
            @Override public void save(String v) { Prefs.setGroqApiKey(SettingsActivity.this, v); }
        });
        persist(txtSystemPrompt, new Saver() {
            @Override public void save(String v) {
                // Storing the default verbatim is the same as storing nothing;
                // keep it blank so future default changes still reach the user.
                Prefs.setSystemPrompt(SettingsActivity.this,
                        v.trim().equals(ClaudeClient.DEFAULT_SYSTEM_PROMPT) ? "" : v);
            }
        });

        findViewById(R.id.btnResetPrompt).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                txtSystemPrompt.setText(ClaudeClient.DEFAULT_SYSTEM_PROMPT);
                Prefs.setSystemPrompt(SettingsActivity.this, "");
            }
        });
        findViewById(R.id.btnPickApps).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, NotificationAppsActivity.class));
            }
        });
        findViewById(R.id.btnSettingsBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh after returning from the picker.
        int n = Prefs.allowedPackages(this).size();
        ((TextView) findViewById(R.id.txtAllowedSummary)).setText(n == 0
                ? "No apps selected — nothing is mirrored"
                : n + " app" + (n == 1 ? "" : "s") + " selected");
    }

    private interface Saver {
        void save(String value);
    }

    private static void persist(TextView field, final Saver saver) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                saver.save(e.toString());
            }
        });
    }
}
