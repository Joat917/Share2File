package com.joat917.share2file;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareActivity extends Activity {

    private static final int REQUEST_CREATE_DOCUMENT = 1;
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final String FALLBACK_NAME = "shared_file";
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String TEXT_MIME = "text/plain";

    private static final String STATE_SOURCES = "sources";
    private static final String STATE_TEXT = "text";
    private static final String STATE_INDEX = "index";
    private static final String STATE_SAVED = "saved";
    private static final String STATE_ERROR = "error";
    private static final String STATE_LAUNCHED = "launched";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ArrayList<Uri> sources = new ArrayList<>();
    private String textContent;
    private int index;
    private int savedCount;
    private String firstError;
    private boolean pickerLaunched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            sources = toUriList(savedInstanceState.get(STATE_SOURCES));
            textContent = savedInstanceState.getString(STATE_TEXT);
            index = savedInstanceState.getInt(STATE_INDEX);
            savedCount = savedInstanceState.getInt(STATE_SAVED);
            firstError = savedInstanceState.getString(STATE_ERROR);
            pickerLaunched = savedInstanceState.getBoolean(STATE_LAUNCHED);
        } else {
            Intent intent = getIntent();
            if (intent == null || !isShareAction(intent.getAction())) {
                toast(getString(R.string.msg_unsupported_action,
                        intent == null ? "null" : String.valueOf(intent.getAction())));
                finishQuietly();
                return;
            }
            sources = resolveSources(intent);
            if (sources.isEmpty()) {
                textContent = plainTextOf(intent);
            }
            if (sources.isEmpty() && textContent == null) {
                toast(getString(R.string.msg_no_content, describeIntent(intent)));
                finishQuietly();
                return;
            }
        }

        // 重建后选择器可能已经在等结果，重复弹出会覆盖待返回的请求
        if (!pickerLaunched) {
            openFileManager();
        }
    }

    @Override
    // 框架给这个形参标了 @NonNull，但本工程不引注解库，无法重复标注
    @SuppressWarnings("NullableProblems")
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(STATE_SOURCES, sources);
        outState.putString(STATE_TEXT, textContent);
        outState.putInt(STATE_INDEX, index);
        outState.putInt(STATE_SAVED, savedCount);
        outState.putString(STATE_ERROR, firstError);
        outState.putBoolean(STATE_LAUNCHED, pickerLaunched);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CREATE_DOCUMENT) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        pickerLaunched = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishOnCancel();
            return;
        }
        final Uri target = data.getData();
        final String text = textContent;
        final Uri source = text != null ? null : sources.get(index);
        // 先推进游标：即使随后被重建，也不会再弹同一个文件的选择器
        index++;
        saveAsync(source, text, target);
    }

    private int itemCount() {
        return textContent != null ? 1 : sources.size();
    }

    private void openFileManager() {
        if (io.isShutdown()) {
            return;
        }
        if (index >= itemCount()) {
            toast(getString(R.string.msg_queue_empty, index));
            finishQuietly();
            return;
        }
        if (textContent != null) {
            launchPicker(getString(R.string.default_text_name), TEXT_MIME);
            return;
        }
        // provider 查询放在后台线程，云盘 provider 的 query 可能很慢
        final Uri source = sources.get(index);
        io.execute(() -> {
            final String name = nameFor(source);
            final String mime = mimeFor(source);
            runOnUiThread(() -> launchPicker(name, mime));
        });
    }

    private void launchPicker(String name, String mime) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            pickerLaunched = true;
            startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.msg_no_file_manager));
            finishQuietly();
        }
    }

    /** 串行处理：写完一个再弹下一个选择器，避免并发写同一个目录。 */
    private void saveAsync(final Uri source, final String text, final Uri target) {
        io.execute(() -> {
            String error = save(source, text, target);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (error == null) {
                    savedCount++;
                } else if (firstError == null) {
                    firstError = error;
                }
                if (index < itemCount()) {
                    openFileManager();
                } else {
                    finishWithSummary();
                }
            });
        });
    }

    private void finishOnCancel() {
        if (savedCount > 0) {
            toast(getString(R.string.msg_cancelled, savedCount, itemCount()));
        }
        finishQuietly();
    }

    private void finishWithSummary() {
        int total = itemCount();
        int failed = index - savedCount;
        if (failed == 0) {
            toast(getString(R.string.msg_saved_count, savedCount));
        } else if (savedCount == 0) {
            toast(total == 1
                    ? getString(R.string.msg_save_failed, firstError)
                    : getString(R.string.msg_all_failed, failed, firstError));
        } else {
            toast(getString(R.string.msg_partial_failed, savedCount, total, failed, firstError));
        }
        finishQuietly();
    }

    private void finishQuietly() {
        io.shutdown();
        finish();
    }

    /** 文本与流复制合流：成功返回 null，失败返回错误描述。 */
    private String save(Uri source, String text, Uri target) {
        ContentResolver resolver = getContentResolver();
        try {
            if (text != null) {
                try (OutputStream out = require(resolver.openOutputStream(target), "openOutputStream")) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                // 先开输入再开输出：provider 给不出源流时，不白建一个空目标文件
                try (InputStream in = require(resolver.openInputStream(source), "openInputStream");
                     OutputStream out = require(resolver.openOutputStream(target), "openOutputStream")) {
                    pump(in, out);
                }
            }
            return null;
        } catch (Exception e) {
            return describeError(e);
        }
    }

    /** provider 返回 null 流时统一成异常，省掉两条路径各写一遍判空。 */
    private static <T> T require(T stream, String what) throws IOException {
        if (stream == null) {
            throw new IOException(what + "-> null");
        }
        return stream;
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int length; (length = in.read(buffer)) != -1; ) {
            out.write(buffer, 0, length);
        }
    }

    private static String describeError(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String describeIntent(Intent intent) {
        return "action=" + intent.getAction()
                + ", type=" + intent.getType();
    }

    private static boolean isShareAction(String action) {
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    private static String plainTextOf(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) {
            return null;
        }
        String value = text.toString();
        return value.isEmpty() ? null : value;
    }

    /**
     * 三层兜底：EXTRA_STREAM → ClipData → getData()。
     * EXTRA_STREAM 在 SEND 下是单个 Uri、在 SEND_MULTIPLE 下是 ArrayList&lt;Uri&gt;，
     * 还有应用会塞错槽位，所以统一按 Object 判断，不依赖已废弃的类型化取值方法。
     */
    private static ArrayList<Uri> resolveSources(Intent intent) {
        Bundle extras = intent.getExtras();
        ArrayList<Uri> result = toUriList(extras == null ? null : extras.get(Intent.EXTRA_STREAM));
        ClipData clipData = intent.getClipData();
        if (result.isEmpty() && clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    result.add(uri);
                }
            }
        }
        if (result.isEmpty() && intent.getData() != null) {
            result.add(intent.getData());
        }
        return result;
    }

    private static ArrayList<Uri> toUriList(Object value) {
        ArrayList<Uri> result = new ArrayList<>();
        if (value instanceof Uri) {
            result.add((Uri) value);
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Uri) {
                    result.add((Uri) item);
                }
            }
        }
        return result;
    }

    /** per-file 的真实类型只有 provider 知道，意图里的 type 只做兜底。 */
    private String mimeFor(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null || type.isEmpty() || "*/*".equals(type)) {
            Intent intent = getIntent();
            type = intent == null ? null : intent.getType();
        }
        return type == null || type.isEmpty() || "*/*".equals(type) ? OCTET_STREAM : type;
    }

    private String nameFor(Uri uri) {
        try (Cursor cursor = getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 个别 provider 不支持 query
        }
        return FALLBACK_NAME;
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
