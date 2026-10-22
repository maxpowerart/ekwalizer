package com.ekwalizer.nightmode;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub releases, same model as GitKwaken and Kwalendar: the channel is the
 * update.json attached to the latest release,
 * {"version", "notes", "url" (may be relative to update.json), "sha256", "published"}.
 * The APK is verified against sha256 while downloading and installed through PackageInstaller,
 * so Android itself asks the user to confirm. Nothing is downloaded without that confirmation
 * flow being started by the user. No telemetry: only the manifest, the releases list (to detect a
 * withdrawn version) and the APK are fetched.
 */
final class Updater {
    enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, WITHDRAWN, DOWNLOADING, INSTALLING, FAILED }

    /** One release as described by update.json. */
    static final class Manifest {
        final String version;
        final String notes;
        final String url;
        final String sha256;
        final String published;

        Manifest(String version, String notes, String url, String sha256, String published) {
            this.version = version;
            this.notes = notes;
            this.url = url;
            this.sha256 = sha256;
            this.published = published;
        }
    }

    interface Listener {
        void onUpdaterChanged();
    }

    static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final String TAG = "Updater";
    private static final int MANIFEST_LIMIT = 1 << 20;
    private static final int TIMEOUT_MS = 20_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 60_000;
    private static final Pattern GITHUB_CHANNEL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)/releases/");
    private static final Pattern WITHDRAWN_LINE =
            Pattern.compile("^\\*\\*Withdrawn:\\*\\*\\s*(.*)$", Pattern.MULTILINE);

    private static Updater instance;

    private final Context app;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.IDLE;
    private volatile Manifest available;
    private volatile String error;
    private volatile String withdrawnReason;
    private volatile String withdrawnTarget;
    private volatile long doneBytes;
    private volatile long totalBytes;

    static synchronized Updater get(Context context) {
        if (instance == null) instance = new Updater(context.getApplicationContext());
        return instance;
    }

    private Updater(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
    }

    State state() { return state; }
    Manifest available() { return available; }
    String error() { return error; }
    String withdrawnReason() { return withdrawnReason; }
    /** Newest good release when the running version was withdrawn, or null. */
    String withdrawnTarget() { return withdrawnTarget; }
    long doneBytes() { return doneBytes; }
    long totalBytes() { return totalBytes; }

    static String currentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Channel URL: hidden override (for testing) > built-in; null for development builds. */
    String channel() {
        String override = prefs.updateUrl();
        if (override != null && !override.isEmpty()) return override;
        String builtIn = BuildConfig.UPDATE_CHANNEL;
        return builtIn.isEmpty() ? null : builtIn;
    }

    boolean isBusy() {
        State s = state;
        return s == State.CHECKING || s == State.DOWNLOADING || s == State.INSTALLING;
    }

    /** Background check at startup and every CHECK_EVERY_MS, if enabled in settings. */
    void checkIfDue() {
        if (!prefs.autoCheckUpdates() || channel() == null) return;
        if (System.currentTimeMillis() - prefs.lastUpdateCheck() < CHECK_EVERY_MS) return;
        check(false);
    }

    /**
     * A manual check always reports its result; a background one only announces something new
     * (and never the skipped version) and stays silent on errors.
     */
    void check(boolean manual) {
        if (isBusy()) return;
        String channel = channel();
        if (channel == null) {
            if (manual) fail(app.getString(R.string.update_dev_build));
            return;
        }
        if (manual) setState(State.CHECKING);
        worker.execute(() -> {
            try {
                Manifest m = fetchManifest(channel);
                prefs.setLastUpdateCheck(System.currentTimeMillis());
                findWithdrawn(channel);
                if (isNewer(m.version, currentVersion())
                        && (manual || !m.version.equals(prefs.skippedVersion()))) {
                    available = m;
                    error = null;
                    setState(State.AVAILABLE);
                } else if (withdrawnReason != null) {
                    available = null;
                    setState(State.WITHDRAWN);
                } else {
                    available = null;
                    setState(manual ? State.UP_TO_DATE : State.IDLE);
                }
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                if (manual) {
                    fail(app.getString(R.string.update_check_failed, describe(e)));
                } else if (state == State.CHECKING) {
                    setState(State.IDLE);
                }
            }
        });
    }

    /** Don't offer this version in background checks any more; a newer one is still offered. */
    void skipAvailable() {
        Manifest m = available;
        if (m == null) return;
        prefs.setSkippedVersion(m.version);
        available = null;
        setState(State.IDLE);
    }

    /** Downloads and verifies the offered APK, then hands it to PackageInstaller. */
    void install() {
        Manifest m = available;
        if (m == null || isBusy()) return;
        doneBytes = 0;
        totalBytes = 0;
        error = null;
        setState(State.DOWNLOADING);
        worker.execute(() -> {
            try {
                File apk = download(m);
                setState(State.INSTALLING);
                commit(apk);
            } catch (Exception e) {
                Log.w(TAG, "Update install failed", e);
                fail(describe(e));
            }
        });
    }

    /** Result of the PackageInstaller session, from InstallResultReceiver. */
    void onInstallResult(int status, String message) {
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                // The process is replaced right after this; MY_PACKAGE_REPLACED restarts the mode.
                setState(State.IDLE);
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                setState(available != null ? State.AVAILABLE : State.IDLE);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                fail(app.getString(R.string.update_signature_mismatch));
                break;
            default:
                fail(app.getString(R.string.update_install_failed,
                        message != null ? message : String.valueOf(status)));
                break;
        }
    }

    // --- network -------------------------------------------------------------------------

    private Manifest fetchManifest(String channel) throws Exception {
        String body = new String(get(channel, MANIFEST_LIMIT), StandardCharsets.UTF_8);
        return parseManifest(body, channel);
    }

    static Manifest parseManifest(String body, String channel) throws Exception {
        JSONObject o = new JSONObject(body);
        String version = o.getString("version").trim();
        String sha = o.getString("sha256").trim().toLowerCase(Locale.ROOT);
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IOException("в update.json нет SHA-256 — установка отменена");
        }
        String url = o.getString("url").trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(":") && !lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IOException("update.json: недопустимая ссылка " + url);
        }
        String resolved = new URL(new URL(channel), url).toString();
        return new Manifest(version, o.optString("notes", "").trim(), resolved, sha,
                o.optString("published", "").trim());
    }

    /**
     * Sets withdrawnReason/withdrawnTarget when the running version was marked "(withdrawn)" by
     * withdraw.yml. Only for GitHub channels; errors are ignored (it is an extra).
     */
    private void findWithdrawn(String channel) {
        withdrawnReason = null;
        withdrawnTarget = null;
        Matcher gh = GITHUB_CHANNEL.matcher(channel);
        if (!gh.find()) return;
        String api = "https://api.github.com/repos/" + gh.group(1) + "/" + gh.group(2)
                + "/releases?per_page=50";
        try {
            JSONArray list = new JSONArray(new String(get(api, MANIFEST_LIMIT), StandardCharsets.UTF_8));
            String current = currentVersion();
            String reason = null;
            String target = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                String version = stripV(r.optString("tag_name"));
                boolean withdrawn = r.optString("name").toLowerCase(Locale.ROOT).contains("(withdrawn)");
                if (withdrawn && version.equals(current)) {
                    Matcher m = WITHDRAWN_LINE.matcher(r.optString("body"));
                    reason = m.find() ? m.group(1).trim() : "";
                } else if (!withdrawn && !r.optBoolean("prerelease") && !version.equals(current)
                        && (target == null || compareVersions(version, target) > 0)) {
                    target = version;
                }
            }
            withdrawnReason = reason;
            withdrawnTarget = reason != null ? target : null;
        } catch (Exception e) {
            Log.w(TAG, "Releases list unavailable", e);
        }
    }

    private File download(Manifest m) throws Exception {
        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File part = new File(dir, "ekwalizer-" + m.version + ".apk.part");
        File done = new File(dir, "ekwalizer-" + m.version + ".apk");
        HttpURLConnection c = open(m.url, DOWNLOAD_READ_TIMEOUT_MS);
        try {
            check(c);
            totalBytes = Math.max(0, c.getContentLengthLong());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long lastNotify = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    doneBytes += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 200) {
                        lastNotify = now;
                        notifyListeners();
                    }
                }
            }
            if (totalBytes > 0 && doneBytes != totalBytes) {
                part.delete();
                throw new IOException("загрузка оборвалась: " + doneBytes + " из " + totalBytes + " байт");
            }
            if (!hex(sha.digest()).equals(m.sha256)) {
                part.delete();
                throw new IOException(app.getString(R.string.update_hash_mismatch));
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(done)) throw new IOException("не удалось сохранить обновление");
        notifyListeners();
        return done;
    }

    private void commit(File apk) throws IOException {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("ekwalizer.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // The system fills in the status extras, so the PendingIntent must be mutable.
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(app, id,
                    new Intent(app, InstallResultReceiver.class), flags);
            session.commit(result.getIntentSender());
        }
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = open(url, TIMEOUT_MS);
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) throw new IOException("ответ слишком большой: " + url);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ekwalizer-updater/" + currentVersion());
        c.setRequestProperty("Accept", url.startsWith("https://api.github.com/")
                ? "application/vnd.github+json" : "*/*");
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code + " — " + c.getURL());
        }
    }

    // --- versions ------------------------------------------------------------------------

    static boolean isNewer(String candidate, String current) {
        return compareVersions(candidate, current) > 0;
    }

    /** Semver precedence on MAJOR.MINOR.PATCH[-pre]; a leading "v" is ignored. */
    static int compareVersions(String a, String b) {
        String[] pa = stripV(a).split("-", 2);
        String[] pb = stripV(b).split("-", 2);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < na.length ? parseInt(na[i]) : 0;
            int y = i < nb.length ? parseInt(nb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        boolean preA = pa.length > 1;
        boolean preB = pb.length > 1;
        if (preA != preB) return preA ? -1 : 1;
        return preA ? pa[1].compareTo(pb[1]) : 0;
    }

    private static String stripV(String v) {
        v = v.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- state ---------------------------------------------------------------------------

    private void fail(String message) {
        error = message;
        setState(State.FAILED);
    }

    private void setState(State s) {
        state = s;
        notifyListeners();
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onUpdaterChanged();
        });
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
