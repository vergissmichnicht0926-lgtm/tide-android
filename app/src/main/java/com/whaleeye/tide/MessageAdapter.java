package com.whaleeye.tide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 消息列表：文字、图片、文件、时间分隔、发送失败态。 */
public final class MessageAdapter extends BaseAdapter {
    private static final int TYPE_AI = 0;
    private static final int TYPE_USER = 1;

    public interface OnImageClickListener {
        void onImageClick(String attachmentId, String name);
    }

    public interface OnFileClickListener {
        void onFileClick(Attachment attachment);
    }

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message, View anchor);
    }

    public interface OnQuoteClickListener {
        void onQuoteClick(Message message);
    }

    public interface OnAvatarClickListener {
        void onAvatarClick(Message message);
    }

    private final Context context;
    private final TideClient client;
    private final String selfName;
    private final String selfInitial;
    private final LayoutInflater inflater;
    private final List<Message> items = new ArrayList<>();
    private final Set<String> downloaded = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tide-image");
        thread.setDaemon(true);
        return thread;
    });
    private final android.util.LruCache<String, Bitmap> imageCache;
    private final Handler voiceTicker = new Handler(Looper.getMainLooper());
    private MediaPlayer voicePlayer;
    private String voicePlayingId;
    private String voiceLoadingId;
    private final Runnable voiceTick = new Runnable() {
        @Override
        public void run() {
            if (voicePlayer == null || voicePlayingId == null) return;
            notifyDataSetChanged();
            voiceTicker.postDelayed(this, 100L);
        }
    };
    private OnImageClickListener imageClickListener;
    private OnFileClickListener fileClickListener;
    private OnMessageLongClickListener longClickListener;
    private OnQuoteClickListener quoteClickListener;
    private OnAvatarClickListener avatarClickListener;
    private long unreadDividerSeq;
    private String selfUserId;

    public MessageAdapter(Context ctx, TideClient client, String selfName) {
        this.context = ctx.getApplicationContext();
        this.client = client;
        this.selfName = selfName == null ? "" : selfName;
        this.selfInitial = ProfileStore.initial(this.context, initialOf(this.selfName, "我"));
        this.inflater = LayoutInflater.from(ctx);
        int cacheBytes = (int) Math.min(16 * 1024 * 1024L,
                Math.max(4 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 8));
        imageCache = new android.util.LruCache<String, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public void setOnImageClickListener(OnImageClickListener listener) {
        imageClickListener = listener;
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        fileClickListener = listener;
    }

    public void setOnMessageLongClickListener(OnMessageLongClickListener listener) {
        longClickListener = listener;
    }

    public void setOnQuoteClickListener(OnQuoteClickListener listener) {
        quoteClickListener = listener;
    }

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        avatarClickListener = listener;
    }

    public void setSelfUserId(String userId) {
        selfUserId = userId;
    }

    public Bitmap cachedImage(String attachmentId) {
        return imageCache.get(attachmentId);
    }

    public void markDownloaded(String attachmentId) {
        if (attachmentId == null) return;
        downloaded.add(attachmentId);
        notifyDataSetChanged();
    }

    public void shutdown() {
        stopVoice();
        voiceTicker.removeCallbacks(voiceTick);
        imageExecutor.shutdownNow();
    }

    public void addAll(List<Message> messages) {
        items.addAll(messages);
        notifyDataSetChanged();
    }

    public void setAll(List<Message> messages) {
        items.clear();
        if (messages != null) items.addAll(messages);
        notifyDataSetChanged();
    }

    public void add(Message message) {
        items.add(message);
        notifyDataSetChanged();
    }

    public void showUnreadDividerAfter(long lastReadSeq) {
        long seq = UnreadDivider.firstUnreadSeq(items, lastReadSeq);
        if (seq == unreadDividerSeq) return;
        unreadDividerSeq = seq;
        notifyDataSetChanged();
    }

    public void clearUnreadDivider() {
        if (unreadDividerSeq == 0L) return;
        unreadDividerSeq = 0L;
        notifyDataSetChanged();
    }

    public int positionOfUnreadDivider() {
        if (unreadDividerSeq == 0L) return -1;
        for (int i = 0; i < items.size(); i++) {
            Message message = items.get(i);
            if (!message.pending && message.seq == unreadDividerSeq) return i;
        }
        return -1;
    }

    public void removePending() {
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).pending) {
                items.remove(i);
                changed = true;
            }
        }
        if (changed) notifyDataSetChanged();
    }

    public void removePending(String clientMsgId) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Message m = items.get(i);
            if (m.pending && m.id.equals(clientMsgId)) {
                items.remove(i);
                notifyDataSetChanged();
                return;
            }
        }
    }

    public void markFailed(String clientMsgId) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Message m = items.get(i);
            if (m.pending && m.id.equals(clientMsgId)) {
                m.pending = false;
                m.failed = true;
                m.showDeliveryStatus = false;
                m.statusUntil = 0L;
                notifyDataSetChanged();
                return;
            }
        }
    }

    public void remove(Message message) {
        if (items.remove(message)) notifyDataSetChanged();
    }

    public void markRecalled(String messageId) {
        if (messageId == null) return;
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.id.equals(messageId)) {
                items.set(i, m.asRecalled());
                notifyDataSetChanged();
                return;
            }
        }
    }

    public int positionOfMessageId(String messageId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(messageId)) return i;
        }
        return -1;
    }

    public void confirmPending(String clientMsgId, Message real) {
        int pendingIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.pending && m.id.equals(clientMsgId)) {
                pendingIndex = i;
                break;
            }
        }
        if (pendingIndex >= 0) {
            if (containsSeq(real.seq)) {
                items.remove(pendingIndex);
                markDelivered(bySeq(real.seq));
            } else {
                markDelivered(real);
                items.set(pendingIndex, real);
            }
            notifyDataSetChanged();
            hideDeliveredLater();
            return;
        }
        if (!containsSeq(real.seq)) add(real);
    }

    public void confirmPendingByText(Message real) {
        if (!real.isUser()) {
            if (!containsSeq(real.seq)) add(real);
            return;
        }
        int pendingIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.pending && m.isUser() && m.text.equals(real.text)) {
                pendingIndex = i;
                break;
            }
        }
        if (pendingIndex >= 0) {
            if (containsSeq(real.seq)) {
                items.remove(pendingIndex);
                markDelivered(bySeq(real.seq));
            } else {
                markDelivered(real);
                items.set(pendingIndex, real);
            }
            notifyDataSetChanged();
            hideDeliveredLater();
            return;
        }
        if (!containsSeq(real.seq)) add(real);
    }

    public long lastSeq() {
        for (int i = items.size() - 1; i >= 0; i--) {
            Message m = items.get(i);
            if (!m.pending && m.seq > 0) return m.seq;
        }
        return 0;
    }

    public boolean containsSeq(long seq) {
        for (Message m : items) {
            if (!m.pending && m.seq == seq) return true;
        }
        return false;
    }

    private Message bySeq(long seq) {
        for (Message message : items) {
            if (!message.pending && message.seq == seq) return message;
        }
        return null;
    }

    private static void markDelivered(Message message) {
        if (message == null || !message.isUser()) return;
        message.pending = false;
        message.failed = false;
        message.showDeliveryStatus = true;
        message.statusUntil = System.currentTimeMillis() + 3_000L;
    }

    private void hideDeliveredLater() {
        main.postDelayed(this::notifyDataSetChanged, 3_200L);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Message getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return isMine(getItem(position)) ? TYPE_USER : TYPE_AI;
    }

    /** 自己发的靠右；AI 和其他成员都靠左（群聊里别人的气泡不能再贴右边）。 */
    private boolean isMine(Message message) {
        if (!message.isUser()) return false;
        return TextUtils.isEmpty(message.senderUserId)
                || message.senderUserId.equals(selfUserId);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Message message = getItem(position);
        message.mentionedMe = !TextUtils.isEmpty(selfUserId)
                && message.mentions.contains(selfUserId);
        View view = convertView;
        if (view == null) {
            int layout = isMine(message)
                    ? R.layout.item_message_user
                    : R.layout.item_message_ai;
            view = inflater.inflate(layout, parent, false);
        }

        TextView time = view.findViewById(R.id.time);
        if (shouldShowTime(position)) {
            time.setText(formatTime(message.createdAt));
            time.setVisibility(TextUtils.isEmpty(time.getText()) ? View.GONE : View.VISIBLE);
        } else {
            time.setVisibility(View.GONE);
        }

        TextView unreadDivider = view.findViewById(R.id.unread_divider);
        if (unreadDivider != null) {
            boolean show = unreadDividerSeq > 0 && !message.pending
                    && message.seq == unreadDividerSeq;
            unreadDivider.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        TextView bubble = view.findViewById(R.id.bubble);
        TextView replyQuote = view.findViewById(R.id.reply_quote);
        View mentionBar = view.findViewById(R.id.mention_bar);
        TextView avatar = view.findViewById(R.id.avatar);
        ImageView image = view.findViewById(R.id.image);
        LinearLayout files = view.findViewById(R.id.files);
        files.removeAllViews();
        Attachment imageAttachment = null;
        Attachment audioAttachment = null;
        List<Attachment> fileAttachments = new ArrayList<>();
        for (Attachment attachment : message.attachments) {
            if (attachment.isImage() && imageAttachment == null) {
                imageAttachment = attachment;
            } else if (attachment.isAudio() && message.voiceDurationMs > 0) {
                if (audioAttachment == null) audioAttachment = attachment;
            } else if (!attachment.isImage()) {
                fileAttachments.add(attachment);
            }
        }

        String label = message.recalled ? context.getString(R.string.message_recalled)
                : message.text;
        if (message.pending && label.isEmpty()) label = "…";
        bubble.setText(label);
        // 长串中英混排在 wrap_content 下可能溢出并被父布局横向裁切，强制按字符换行。
        bubble.setHorizontallyScrolling(false);
        bubble.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        bubble.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        bubble.scrollTo(0, 0);
        bubble.setAlpha(message.recalled ? 0.7f : (message.pending ? 0.7f : 1f));
        boolean isVoice = message.voiceDurationMs > 0 && !message.recalled;
        bubble.setVisibility(message.recalled || (!isVoice && !label.isEmpty())
                ? View.VISIBLE : View.GONE);
        bubble.setOnLongClickListener(v -> openMenu(message, v));

        LinearLayout voiceBubble = view.findViewById(R.id.voice_bubble);
        TextView voicePlay = view.findViewById(R.id.voice_play);
        LinearLayout voiceWave = view.findViewById(R.id.voice_wave);
        TextView voiceDuration = view.findViewById(R.id.voice_duration);
        if (voiceBubble != null) {
            voiceBubble.setVisibility(isVoice ? View.VISIBLE : View.GONE);
            if (isVoice) {
                bindVoiceBubble(
                        voiceBubble,
                        voicePlay,
                        voiceWave,
                        voiceDuration,
                        message,
                        audioAttachment
                );
            } else {
                voiceBubble.setOnClickListener(null);
                voiceBubble.setOnLongClickListener(null);
            }
        }

        boolean quoted = !TextUtils.isEmpty(message.replyToId);
        replyQuote.setVisibility(quoted ? View.VISIBLE : View.GONE);
        if (quoted) {
            String who = TextUtils.isEmpty(message.replyToName)
                    ? "" : message.replyToName + "：";
            String body = message.replyToRecalled
                    ? context.getString(R.string.message_recalled)
                    : (TextUtils.isEmpty(message.replyToText) ? "…" : message.replyToText);
            replyQuote.setText("回复 " + who + body);
            replyQuote.setOnClickListener(v -> {
                if (quoteClickListener != null) quoteClickListener.onQuoteClick(message);
            });
        } else {
            replyQuote.setOnClickListener(null);
        }
        boolean mentioned = !message.recalled && message.mentionedMe;
        mentionBar.setVisibility(mentioned ? View.VISIBLE : View.GONE);

        boolean mine = isMine(message);
        boolean otherHuman = message.isUser() && !mine;
        if (avatar != null) {
            if (otherHuman) {
                avatar.setText(initialOf(message.senderName, "?"));
                avatar.setBackgroundResource(R.drawable.bg_avatar_mute);
                UserAvatarLoader.apply(avatar, 32, message.senderUserId, null, true);
            } else if (message.isUser()) {
                AvatarRenderer.applyUserCircle(avatar, 32, selfInitial);
            } else {
                AvatarRenderer.applyAiCircle(avatar, 32);
            }
        }
        TextView senderName = view.findViewById(R.id.sender_name);
        if (senderName != null) {
            boolean show = otherHuman && !TextUtils.isEmpty(message.senderName);
            senderName.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) senderName.setText(message.senderName);
        }
        if (avatar != null) {
            avatar.setClickable(true);
            avatar.setOnClickListener(v -> {
                if (avatarClickListener != null) avatarClickListener.onAvatarClick(message);
            });
        }

        View failDot = view.findViewById(R.id.fail_dot);
        if (failDot != null) {
            failDot.setVisibility(View.GONE);
        }

        TextView status = view.findViewById(R.id.send_status);
        if (status != null) {
            int sendState = SendState.of(message, System.currentTimeMillis());
            if (sendState == SendState.SENDING) {
                status.setText(R.string.message_sending);
                status.setTextColor(context.getResources().getColor(R.color.ink_3));
                status.setVisibility(View.VISIBLE);
                status.setClickable(false);
            } else if (sendState == SendState.DELIVERED) {
                status.setText(R.string.message_delivered);
                status.setTextColor(context.getResources().getColor(R.color.ink_3));
                status.setVisibility(View.VISIBLE);
                status.setClickable(false);
            } else if (sendState == SendState.FAILED) {
                status.setText(R.string.message_failed_retry);
                status.setTextColor(context.getResources().getColor(R.color.badge));
                status.setVisibility(View.VISIBLE);
                status.setClickable(true);
                status.setOnClickListener(v -> openMenu(message, status));
            } else {
                status.setVisibility(View.GONE);
                status.setClickable(false);
            }
        }

        files.setVisibility(message.recalled || fileAttachments.isEmpty()
                ? View.GONE : View.VISIBLE);
        for (Attachment attachment : fileAttachments) {
            files.addView(fileRow(attachment, message, files), attachParams());
        }

        if (imageAttachment == null || message.recalled) {
            image.setOnClickListener(null);
            image.setOnLongClickListener(null);
            image.setVisibility(View.GONE);
            image.setImageDrawable(null);
        } else {
            final String attachmentId = imageAttachment.id;
            final String attachmentName = imageAttachment.name;
            image.setVisibility(View.VISIBLE);
            if (message.isSticker()) {
                image.setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(160)));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackground(null);
            } else {
                image.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundResource(mine
                        ? R.drawable.bg_image_user : R.drawable.bg_image_ai);
                image.setMaxWidth(dp(196));
                image.setMaxHeight(dp(196));
            }
            image.setAlpha(message.pending ? 0.55f : 1f);
            image.setTag(attachmentId);
            Bitmap cached = imageCache.get(attachmentId);
            if (cached != null) {
                image.setImageBitmap(cached);
            } else {
                image.setImageDrawable(null);
                loadImage(attachmentId, image);
            }
            image.setOnClickListener(v -> {
                if (imageClickListener != null) {
                    imageClickListener.onImageClick(attachmentId, attachmentName);
                }
            });
            image.setOnLongClickListener(v -> openMenu(message, v));
        }

        view.setOnLongClickListener(v -> openMenu(message, v));
        return view;
    }

    private boolean openMenu(Message message, View anchor) {
        if (longClickListener != null) {
            longClickListener.onMessageLongClick(message, anchor);
        }
        return true;
    }

    private LinearLayout.LayoutParams attachParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        return params;
    }

    private void bindVoiceBubble(LinearLayout voiceBubble, TextView voicePlay,
                                 LinearLayout voiceWave, TextView voiceDuration,
                                 Message message, Attachment audio) {
        int width = Math.max(dp(132), dp(60) + Math.round(
                Math.min(1f, message.voiceDurationMs / 60000f) * dp(140)));
        voiceBubble.setLayoutParams(new LinearLayout.LayoutParams(width, dp(48)));
        voiceDuration.setText(String.format(
                Locale.CHINA,
                "%d″",
                Math.max(1, Math.round(message.voiceDurationMs / 1000f))
        ));
        buildWave(voiceWave, message.isUser());
        boolean playing = audio != null && audio.id.equals(voicePlayingId);
        boolean loading = audio != null && audio.id.equals(voiceLoadingId);
        voicePlay.setText(loading ? "…" : (playing ? "❚❚" : "▶"));
        int active = 0;
        if (playing && voicePlayer != null) {
            try {
                int duration = Math.max(1, voicePlayer.getDuration());
                active = Math.min(12, Math.max(1,
                        (int) Math.ceil(12.0 * voicePlayer.getCurrentPosition() / duration)));
            } catch (Exception ignored) {
                active = 0;
            }
        }
        for (int i = 0; i < voiceWave.getChildCount(); i++) {
            voiceWave.getChildAt(i).setAlpha(playing && i < active ? 1f : 0.38f);
        }
        voiceBubble.setOnClickListener(v -> {
            if (audio == null || loading) return;
            toggleVoice(audio);
        });
        voiceBubble.setOnLongClickListener(v -> openMenu(message, v));
    }

    private void buildWave(LinearLayout wave, boolean mine) {
        wave.removeAllViews();
        int[] heights = {6, 11, 16, 8, 14, 20, 10, 17, 7, 13, 18, 9};
        int color = mine ? Color.WHITE : context.getResources().getColor(R.color.teal);
        for (int height : heights) {
            View bar = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(3), dp(height));
            params.setMargins(dp(1), 0, dp(1), 0);
            bar.setLayoutParams(params);
            bar.setBackgroundColor(color);
            bar.setAlpha(0.38f);
            wave.addView(bar);
        }
    }

    private void toggleVoice(Attachment attachment) {
        if (voicePlayingId != null && voicePlayingId.equals(attachment.id)) {
            stopVoice();
            notifyDataSetChanged();
            return;
        }
        if (voiceLoadingId != null) return;
        stopVoice();
        voiceLoadingId = attachment.id;
        notifyDataSetChanged();
        imageExecutor.execute(() -> {
            try {
                File file = voiceCacheFile(attachment);
                if (!file.exists()) {
                    byte[] bytes = client.downloadAttachment(attachment.id);
                    File temp = File.createTempFile("voice-", ".part", file.getParentFile());
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        out.write(bytes);
                    }
                    if (file.exists() && !file.delete()) throw new IOException("无法替换语音缓存");
                    if (!temp.renameTo(file)) throw new IOException("无法保存语音缓存");
                }
                main.post(() -> {
                    if (!attachment.id.equals(voiceLoadingId)) return;
                    voiceLoadingId = null;
                    try {
                        playVoice(file, attachment.id);
                    } catch (Exception error) {
                        stopVoice();
                        Toast.makeText(context, "语音播放失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (attachment.id.equals(voiceLoadingId)) voiceLoadingId = null;
                    notifyDataSetChanged();
                    Toast.makeText(context, "语音下载失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private File voiceCacheFile(Attachment attachment) throws IOException {
        File dir = new File(context.getCacheDir(), "voice");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建语音缓存目录");
        String ext = attachment.mimeType.contains("mpeg") ? ".mp3"
                : attachment.mimeType.contains("wav") ? ".wav"
                : attachment.mimeType.contains("mp4") || attachment.mimeType.contains("aac")
                ? ".m4a" : ".bin";
        return new File(dir, attachment.id + ext);
    }

    private void playVoice(File file, String attachmentId) throws IOException {
        stopVoice();
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        player.setDataSource(file.getAbsolutePath());
        player.prepare();
        player.setOnCompletionListener(mediaPlayer -> {
            stopVoice();
            notifyDataSetChanged();
        });
        player.start();
        voicePlayer = player;
        voicePlayingId = attachmentId;
        voiceTicker.removeCallbacks(voiceTick);
        voiceTicker.post(voiceTick);
        notifyDataSetChanged();
    }

    private void stopVoice() {
        voiceTicker.removeCallbacks(voiceTick);
        if (voicePlayer != null) {
            try { voicePlayer.stop(); } catch (Exception ignored) {}
            try { voicePlayer.release(); } catch (Exception ignored) {}
            voicePlayer = null;
        }
        voicePlayingId = null;
        voiceLoadingId = null;
    }

    private View fileRow(Attachment attachment, Message message, LinearLayout parent) {
        View row = inflater.inflate(R.layout.item_attach, parent, false);
        TextView type = row.findViewById(R.id.file_type);
        TextView name = row.findViewById(R.id.file_name);
        TextView meta = row.findViewById(R.id.file_meta);
        ImageView action = row.findViewById(R.id.file_action);

        String fileName = FileSafety.sanitizeFileName(attachment.name);
        String size = FileSafety.formatSize(attachment.size);
        type.setText(extension(fileName));
        name.setText(fileName);
        boolean me = message.isUser();
        boolean isDownloaded = downloaded.contains(attachment.id);
        if (message.pending) {
            meta.setText("发送中");
        } else if (me) {
            meta.setText(size.isEmpty() ? "已发送" : size + " · 已发送");
        } else if (isDownloaded) {
            meta.setText(size.isEmpty() ? "已下载" : size + " · 已下载");
        } else {
            meta.setText(size);
        }

        row.setBackgroundResource(me ? R.drawable.bubble_user : R.drawable.bubble_ai);
        if (me) {
            type.setBackgroundResource(R.drawable.bg_type_badge_me);
            type.setTextColor(Color.WHITE);
            name.setTextColor(Color.WHITE);
            meta.setTextColor(0xB3FFFFFF);
            action.setImageResource(isDownloaded ? R.drawable.ic_check : R.drawable.ic_check);
            action.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        } else {
            type.setBackgroundResource(R.drawable.bg_type_badge);
            type.setTextColor(context.getResources().getColor(R.color.teal_dark));
            name.setTextColor(context.getResources().getColor(R.color.ink));
            meta.setTextColor(context.getResources().getColor(R.color.ink_3));
            action.setImageResource(isDownloaded ? R.drawable.ic_check : R.drawable.ic_download);
            action.setImageTintList(android.content.res.ColorStateList.valueOf(
                    context.getResources().getColor(
                            isDownloaded ? R.color.teal : R.color.ink_2)));
        }
        row.setAlpha(message.pending ? 0.55f : 1f);
        row.setOnClickListener(v -> {
            if (fileClickListener != null) fileClickListener.onFileClick(attachment);
        });
        row.setOnLongClickListener(v -> openMenu(message, v));
        return row;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "文件";
        String value = name.substring(dot + 1).toUpperCase(Locale.US);
        return value.length() > 4 ? value.substring(0, 4) : value;
    }

    private static String initialOf(String value, String fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        return value.substring(0, 1);
    }

    private boolean shouldShowTime(int position) {
        if (position == 0) return true;
        Message current = items.get(position);
        Message previous = items.get(position - 1);
        if (current.createdAt <= 0 || previous.createdAt <= 0) return true;
        if (current.createdAt - previous.createdAt > 5 * 60 * 1000L) return true;
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(current.createdAt);
        b.setTimeInMillis(previous.createdAt);
        return a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
                || a.get(Calendar.YEAR) != b.get(Calendar.YEAR);
    }

    private static String formatTime(long millis) {
        if (millis <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        String clock = String.format(Locale.CHINA, "%02d:%02d",
                then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE));
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return clock;
        }
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + clock;
        }
        return String.format(Locale.CHINA, "%02d-%02d %s",
                then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH), clock);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void loadImage(String attachmentId, ImageView target) {
        imageExecutor.execute(() -> {
            try {
                byte[] bytes = client.downloadAttachment(attachmentId);
                Bitmap bitmap = decode(bytes, 1600);
                if (bitmap == null) return;
                imageCache.put(attachmentId, bitmap);
                main.post(() -> {
                    if (attachmentId.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static Bitmap decode(byte[] bytes, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, options);
        if (decoded == null) return null;
        int side = Math.max(decoded.getWidth(), decoded.getHeight());
        if (side <= maxDimension) return decoded;
        float scale = maxDimension / (float) side;
        Bitmap scaled = Bitmap.createScaledBitmap(
                decoded,
                Math.max(1, Math.round(decoded.getWidth() * scale)),
                Math.max(1, Math.round(decoded.getHeight() * scale)),
                true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }
}
