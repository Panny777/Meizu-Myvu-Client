package com.myvu.client.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.myvu.client.R;
import com.myvu.client.ai.AiClient;
import com.myvu.client.ai.AiProvider;
import com.myvu.client.ai.SttProvider;
import com.myvu.client.ai.TtsProvider;
import com.myvu.client.core.Prefs;

/** Settings for assistant providers, speech services, and notification mirroring. */
public class SettingsActivity extends AppCompatActivity {
    private TextInputLayout layApiKey;
    private TextInputLayout layModel;
    private TextInputLayout layAiEndpoint;
    private TextInputLayout laySttApiKey;
    private TextInputLayout laySttEndpoint;
    private TextInputLayout laySttModel;
    private TextInputLayout layTtsEndpoint;
    private TextInputLayout layTtsApiKey;
    private TextInputLayout layTtsModel;
    private TextInputLayout layTtsVoice;

    private TextInputEditText txtApiKey;
    private TextInputEditText txtModel;
    private TextInputEditText txtAiEndpoint;
    private TextInputEditText txtSttApiKey;
    private TextInputEditText txtSttEndpoint;
    private TextInputEditText txtSttModel;
    private TextInputEditText txtTtsEndpoint;
    private TextInputEditText txtTtsApiKey;
    private TextInputEditText txtTtsModel;
    private TextInputEditText txtTtsVoice;
    private TextInputEditText txtSystemPrompt;

    private AiProvider aiProvider;
    private SttProvider sttProvider;
    private TtsProvider ttsProvider;
    private boolean bindingAi;
    private boolean bindingStt;
    private boolean bindingTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        bindViews();
        configureProviderSelectors();
        bindStoredValues();
        configurePersistence();
        configureButtons();
    }

    private void bindViews() {
        layApiKey = findViewById(R.id.layApiKey);
        layModel = findViewById(R.id.layModel);
        layAiEndpoint = findViewById(R.id.layAiEndpoint);
        laySttApiKey = findViewById(R.id.laySttApiKey);
        laySttEndpoint = findViewById(R.id.laySttEndpoint);
        laySttModel = findViewById(R.id.laySttModel);
        layTtsEndpoint = findViewById(R.id.layTtsEndpoint);
        layTtsApiKey = findViewById(R.id.layTtsApiKey);
        layTtsModel = findViewById(R.id.layTtsModel);
        layTtsVoice = findViewById(R.id.layTtsVoice);

        txtApiKey = findViewById(R.id.txtApiKey);
        txtModel = findViewById(R.id.txtModel);
        txtAiEndpoint = findViewById(R.id.txtAiEndpoint);
        txtSttApiKey = findViewById(R.id.txtSttApiKey);
        txtSttEndpoint = findViewById(R.id.txtSttEndpoint);
        txtSttModel = findViewById(R.id.txtSttModel);
        txtTtsEndpoint = findViewById(R.id.txtTtsEndpoint);
        txtTtsApiKey = findViewById(R.id.txtTtsApiKey);
        txtTtsModel = findViewById(R.id.txtTtsModel);
        txtTtsVoice = findViewById(R.id.txtTtsVoice);
        txtSystemPrompt = findViewById(R.id.txtSystemPrompt);
    }

    private void configureProviderSelectors() {
        aiProvider = AiProvider.fromId(Prefs.aiProvider(this));
        MaterialButtonToggleGroup aiGroup = findViewById(R.id.btnProviderGroup);
        aiGroup.check(aiButtonFor(aiProvider));
        aiGroup.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                        boolean isChecked) {
                if (!isChecked) return;
                aiProvider = aiProviderFor(checkedId);
                Prefs.setAiProvider(SettingsActivity.this, aiProvider.id);
                bindAiFields();
            }
        });

        sttProvider = SttProvider.fromId(Prefs.sttProvider(this));
        MaterialButtonToggleGroup sttGroup = findViewById(R.id.btnSttProviderGroup);
        sttGroup.check(sttProvider == SttProvider.LOCAL
                ? R.id.btnSttLocal : R.id.btnSttGroq);
        sttGroup.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                        boolean isChecked) {
                if (!isChecked) return;
                sttProvider = checkedId == R.id.btnSttLocal
                        ? SttProvider.LOCAL : SttProvider.GROQ;
                Prefs.setSttProvider(SettingsActivity.this, sttProvider.id);
                bindSttFields();
            }
        });

        ttsProvider = TtsProvider.fromId(Prefs.ttsProvider(this));
        MaterialButtonToggleGroup ttsGroup = findViewById(R.id.btnTtsProviderGroup);
        ttsGroup.check(ttsProvider == TtsProvider.HTTP
                ? R.id.btnTtsHttp : R.id.btnTtsSystem);
        ttsGroup.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                        boolean isChecked) {
                if (!isChecked) return;
                ttsProvider = checkedId == R.id.btnTtsHttp
                        ? TtsProvider.HTTP : TtsProvider.SYSTEM;
                Prefs.setTtsProvider(SettingsActivity.this, ttsProvider.id);
                bindTtsFields();
            }
        });
    }

    private void bindStoredValues() {
        bindAiFields();
        bindSttFields();
        bindTtsFields();
        String prompt = Prefs.systemPrompt(this);
        txtSystemPrompt.setText(prompt.isEmpty() ? AiClient.DEFAULT_SYSTEM_PROMPT : prompt);
    }

    private void configurePersistence() {
        persist(txtApiKey, new Saver() {
            @Override public void save(String value) {
                if (!bindingAi) Prefs.setAiApiKey(SettingsActivity.this, aiProvider.id, value);
            }
        });
        persist(txtModel, new Saver() {
            @Override public void save(String value) {
                if (!bindingAi) Prefs.setAiModel(
                        SettingsActivity.this, aiProvider.id, value.trim());
            }
        });
        persist(txtAiEndpoint, new Saver() {
            @Override public void save(String value) {
                if (!bindingAi) Prefs.setAiEndpoint(
                        SettingsActivity.this, aiProvider.id, value.trim());
            }
        });
        persist(txtSttApiKey, new Saver() {
            @Override public void save(String value) {
                if (!bindingStt) Prefs.setSttApiKey(
                        SettingsActivity.this, sttProvider.id, value);
            }
        });
        persist(txtSttEndpoint, new Saver() {
            @Override public void save(String value) {
                if (!bindingStt) Prefs.setSttEndpoint(
                        SettingsActivity.this, sttProvider.id, value.trim());
            }
        });
        persist(txtSttModel, new Saver() {
            @Override public void save(String value) {
                if (!bindingStt) Prefs.setSttModel(
                        SettingsActivity.this, sttProvider.id, value.trim());
            }
        });
        persist(txtTtsEndpoint, new Saver() {
            @Override public void save(String value) {
                if (!bindingTts) Prefs.setTtsEndpoint(SettingsActivity.this, value.trim());
            }
        });
        persist(txtTtsApiKey, new Saver() {
            @Override public void save(String value) {
                if (!bindingTts) Prefs.setTtsApiKey(SettingsActivity.this, value);
            }
        });
        persist(txtTtsModel, new Saver() {
            @Override public void save(String value) {
                if (!bindingTts) Prefs.setTtsModel(SettingsActivity.this, value.trim());
            }
        });
        persist(txtTtsVoice, new Saver() {
            @Override public void save(String value) {
                if (!bindingTts) Prefs.setTtsVoice(SettingsActivity.this, value.trim());
            }
        });
        persist(txtSystemPrompt, new Saver() {
            @Override public void save(String value) {
                Prefs.setSystemPrompt(SettingsActivity.this,
                        value.trim().equals(AiClient.DEFAULT_SYSTEM_PROMPT) ? "" : value);
            }
        });
    }

    private void configureButtons() {
        findViewById(R.id.btnResetPrompt).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                txtSystemPrompt.setText(AiClient.DEFAULT_SYSTEM_PROMPT);
                Prefs.setSystemPrompt(SettingsActivity.this, "");
            }
        });
        findViewById(R.id.btnPickApps).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, NotificationAppsActivity.class));
            }
        });
        findViewById(R.id.btnSettingsBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
    }

    private void bindAiFields() {
        bindingAi = true;
        boolean local = aiProvider == AiProvider.LOCAL;
        layApiKey.setHint(aiProvider.label + " API key");
        layApiKey.setHelperText(local
                ? "Optional Bearer token"
                : "Create one at " + aiProvider.console);
        layModel.setHelperText(local
                ? "Required; use a model id exposed by the local server"
                : "Blank uses " + aiProvider.defaultModel);
        layAiEndpoint.setVisibility(local ? View.VISIBLE : View.GONE);
        txtApiKey.setText(Prefs.aiApiKey(this, aiProvider.id));
        txtModel.setText(Prefs.aiModel(this, aiProvider.id));
        txtAiEndpoint.setText(Prefs.aiEndpoint(this, aiProvider.id));
        bindingAi = false;
    }

    private void bindSttFields() {
        bindingStt = true;
        boolean local = sttProvider == SttProvider.LOCAL;
        laySttApiKey.setHint(sttProvider.label + " API key");
        laySttApiKey.setHelperText(local ? "Optional Bearer token" : "Create one at console.groq.com");
        laySttEndpoint.setVisibility(local ? View.VISIBLE : View.GONE);
        laySttModel.setHelperText("Blank uses " + sttProvider.defaultModel);
        txtSttApiKey.setText(Prefs.sttApiKey(this, sttProvider.id));
        txtSttEndpoint.setText(Prefs.sttEndpoint(this, sttProvider.id));
        txtSttModel.setText(Prefs.sttModel(this, sttProvider.id));
        bindingStt = false;
    }

    private void bindTtsFields() {
        bindingTts = true;
        boolean http = ttsProvider == TtsProvider.HTTP;
        int visibility = http ? View.VISIBLE : View.GONE;
        layTtsEndpoint.setVisibility(visibility);
        layTtsApiKey.setVisibility(visibility);
        layTtsModel.setVisibility(visibility);
        layTtsVoice.setVisibility(visibility);
        txtTtsEndpoint.setText(Prefs.ttsEndpoint(this));
        txtTtsApiKey.setText(Prefs.ttsApiKey(this));
        txtTtsModel.setText(Prefs.ttsModel(this));
        txtTtsVoice.setText(Prefs.ttsVoice(this));
        bindingTts = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        int count = Prefs.allowedPackages(this).size();
        ((TextView) findViewById(R.id.txtAllowedSummary)).setText(count == 0
                ? "No apps selected — nothing is mirrored"
                : count + " app" + (count == 1 ? "" : "s") + " selected");
    }

    private static int aiButtonFor(AiProvider provider) {
        switch (provider) {
            case OPENAI: return R.id.btnProviderOpenai;
            case GEMINI: return R.id.btnProviderGemini;
            case LOCAL: return R.id.btnProviderLocal;
            default: return R.id.btnProviderClaude;
        }
    }

    private static AiProvider aiProviderFor(int buttonId) {
        if (buttonId == R.id.btnProviderOpenai) return AiProvider.OPENAI;
        if (buttonId == R.id.btnProviderGemini) return AiProvider.GEMINI;
        if (buttonId == R.id.btnProviderLocal) return AiProvider.LOCAL;
        return AiProvider.CLAUDE;
    }

    private interface Saver {
        void save(String value);
    }

    private static void persist(TextView field, final Saver saver) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { saver.save(text.toString()); }
        });
    }
}
