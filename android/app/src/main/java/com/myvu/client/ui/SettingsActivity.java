package com.myvu.client.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.myvu.client.R;
import com.myvu.client.ai.AiClient;
import com.myvu.client.ai.AiProvider;
import com.myvu.client.core.Prefs;
import com.myvu.client.service.ConnectionManager;
import com.myvu.client.service.MyvuService;

/**
 * App settings: the AI provider, its API key and model, and the assistant's
 * system prompt.
 *
 * Everything here is plain SharedPreferences -- no service binding needed. Each
 * field saves as you type, and the provider, keys, model and prompt are all
 * re-read at the start of every AI turn, so edits take effect on the next
 * question without a reconnect or a restart.
 *
 * The key and model fields are SHARED between providers: picking a provider
 * loads its own stored key and model into them, so switching back later
 * remembers what was entered.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextInputLayout layApiKey, layModel;
    private TextInputEditText txtApiKey, txtModel, txtGroqKey, txtSystemPrompt;

    /** The provider whose key and model the shared fields are showing. */
    private AiProvider provider;
    /** True while fields are loaded programmatically, so the save watchers stay quiet. */
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        layApiKey = findViewById(R.id.layApiKey);
        layModel = findViewById(R.id.layModel);
        txtApiKey = findViewById(R.id.txtApiKey);
        txtModel = findViewById(R.id.txtModel);
        txtGroqKey = findViewById(R.id.txtGroqKey);
        txtSystemPrompt = findViewById(R.id.txtSystemPrompt);

        provider = AiProvider.fromId(Prefs.aiProvider(this));
        MaterialButtonToggleGroup group = findViewById(R.id.btnProviderGroup);
        group.check(buttonFor(provider));
        group.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup g, int checkedId,
                                        boolean isChecked) {
                if (!isChecked) return;
                provider = providerFor(checkedId);
                Prefs.setAiProvider(SettingsActivity.this, provider.id);
                bindProviderFields();
            }
        });
        bindProviderFields();

        txtGroqKey.setText(Prefs.groqApiKey(this));
        // An empty stored prompt means "use the shipped default" -- show that
        // default as the actual text so it is editable rather than invisible.
        String stored = Prefs.systemPrompt(this);
        txtSystemPrompt.setText(stored.isEmpty() ? AiClient.DEFAULT_SYSTEM_PROMPT : stored);

        persist(txtApiKey, new Saver() {
            @Override public void save(String v) {
                if (!binding) Prefs.setAiApiKey(SettingsActivity.this, provider.id, v);
            }
        });
        persist(txtModel, new Saver() {
            @Override public void save(String v) {
                if (!binding) Prefs.setAiModel(SettingsActivity.this, provider.id, v.trim());
            }
        });
        persist(txtGroqKey, new Saver() {
            @Override public void save(String v) { Prefs.setGroqApiKey(SettingsActivity.this, v); }
        });
        persist(txtSystemPrompt, new Saver() {
            @Override public void save(String v) {
                // Storing the default verbatim is the same as storing nothing;
                // keep it blank so future default changes still reach the user.
                Prefs.setSystemPrompt(SettingsActivity.this,
                        v.trim().equals(AiClient.DEFAULT_SYSTEM_PROMPT) ? "" : v);
            }
        });

        findViewById(R.id.btnResetPrompt).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                txtSystemPrompt.setText(AiClient.DEFAULT_SYSTEM_PROMPT);
                Prefs.setSystemPrompt(SettingsActivity.this, "");
            }
        });
        wireWeather();
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

    /**
     * The weather card. Uses MyvuService.activeConnection() rather than binding
     * the service, the same way MirrorNotificationListener does -- this screen
     * is otherwise pure SharedPreferences and does not need a binding.
     */
    private void wireWeather() {
        MaterialSwitch sw = findViewById(R.id.swWeather);
        TextInputEditText place = findViewById(R.id.txtWeatherPlace);

        sw.setChecked(Prefs.weatherEnabled(this));
        place.setText(Prefs.weatherPlace(this));

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setWeatherEnabled(SettingsActivity.this, checked);
                // Switching off leaves the cycle to lapse on its next tick;
                // switching on has to restart it, since a disabled refresh()
                // returns without rescheduling.
                if (checked) syncWeatherNow(false);
            }
        });
        persist(place, new Saver() {
            @Override public void save(String v) {
                Prefs.setWeatherPlace(SettingsActivity.this, v);
            }
        });
        findViewById(R.id.btnSyncWeather).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { syncWeatherNow(true); }
        });
    }

    private void syncWeatherNow(boolean announce) {
        ConnectionManager c = MyvuService.activeConnection();
        if (c == null) {
            if (announce) {
                Toast.makeText(this, "Connect to the glasses first", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        c.syncWeatherNow();
        if (announce) Toast.makeText(this, "Syncing weather…", Toast.LENGTH_SHORT).show();
    }

    /** Loads the selected provider's key and model into the shared fields. */
    private void bindProviderFields() {
        binding = true;
        layApiKey.setHint(provider.label + " API key");
        layApiKey.setHelperText("Used to answer questions — create one at " + provider.console);
        txtApiKey.setText(Prefs.aiApiKey(this, provider.id));
        layModel.setHelperText("Blank uses " + provider.defaultModel);
        txtModel.setText(Prefs.aiModel(this, provider.id));
        binding = false;
    }

    private static int buttonFor(AiProvider p) {
        switch (p) {
            case OPENAI: return R.id.btnProviderOpenai;
            case GEMINI: return R.id.btnProviderGemini;
            default:     return R.id.btnProviderClaude;
        }
    }

    private static AiProvider providerFor(int buttonId) {
        if (buttonId == R.id.btnProviderOpenai) return AiProvider.OPENAI;
        if (buttonId == R.id.btnProviderGemini) return AiProvider.GEMINI;
        return AiProvider.CLAUDE;
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
