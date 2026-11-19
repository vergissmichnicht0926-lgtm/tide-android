package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** AI 主页。 */
public final class AiProfileActivity extends Activity {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_TITLE = "title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_ai_profile);
        String cid = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_message).setOnClickListener(v ->
                startActivity(new Intent(this, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, cid)
                        .putExtra(ChatActivity.EXTRA_TITLE,
                                TextUtils.isEmpty(title)
                                        ? AiProfileStore.name(this, "汐语") : title)));
        View bot = findViewById(R.id.btn_bot_settings);
        bot.setVisibility(new Session(this).isAdmin() ? View.VISIBLE : View.GONE);
        bot.setOnClickListener(v -> startActivity(new Intent(this, AdminSettingsActivity.class)));
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        String name = AiProfileStore.name(this, "汐语");
        ((TextView) findViewById(R.id.ai_profile_name)).setText(name);
        String signature = AiProfileStore.signature(this);
        ((TextView) findViewById(R.id.ai_profile_signature)).setText(
                TextUtils.isEmpty(signature)
                        ? getString(R.string.ai_profile_bio) : signature);
        TextView initial = findViewById(R.id.ai_profile_avatar_initial);
        initial.setText(TextUtils.isEmpty(name) ? "汐" : name.substring(0, 1));
        ImageView image = findViewById(R.id.ai_profile_avatar_image);
        Bitmap bitmap = AiProfileStore.avatarBitmap(this);
        if (bitmap == null) {
            image.setImageDrawable(null);
            initial.setVisibility(View.VISIBLE);
        } else {
            image.setImageBitmap(bitmap);
            initial.setVisibility(View.GONE);
        }
    }
}
