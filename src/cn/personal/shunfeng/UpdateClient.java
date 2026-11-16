package cn.personal.shunfeng;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Public-release updater. Never reads or uploads a ledger, account, device identifier, or token. */
public final class UpdateClient {
    public static final String OWNER = "RoadKai233";
    public static final String REPOSITORY = "shuncheng-ledger";
    public static final String REPO_URL = "https://github.com/" + OWNER + "/" + REPOSITORY;
    public static final String LATEST_API = "https://api.github.com/repos/" + OWNER + "/" + REPOSITORY + "/releases/latest";
    public static final String APP_PACKAGE = "cn.personal.shunfeng";
    public static final String APK_FILENAME = "verified-update.apk";
    private static final String METADATA_FILENAME = "verified-update.json";
    public static final String APK_MIME = "application/vnd.android.package-archive";
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static final int MAX_RELEASE_BYTES = 1024 * 1024;
    private static final String ASSET_PATH = "/" + OWNER + "/" + REPOSITORY + "/releases/download/";
    private static final int CONNECT_TIMEOUT = 10000, READ_TIMEOUT = 15000, MAX_REDIRECTS = 3;
    private UpdateClient() {}

    public static final class Release {
        public final int versionCode;
        public final String versionName, apkUrl, sha256, notes;
        public final long size;
        private Release(int code, String name, String url, String hash, long bytes, String notes) {
            versionCode = code; versionName = name; apkUrl = url; sha256 = hash; size = bytes; this.notes = notes;
        }
    }

    /** Run on a worker thread. No newer version returns null; the caller handles automatic failures silently. */
    public static Release check(Context context) throws IOException {
        JSONObject latest = object(readText(LATEST_API, MAX_RELEASE_BYTES));
        try {
            if (!Boolean.FALSE.equals(latest.get("draft")) || !Boolean.FALSE.equals(latest.get("prerelease")))
                throw new IOException("发布版本状态不正确");
            String tag = string(latest, "tag_name");
            if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IOException("发布版本标签不正确");
            Object rawAssets = latest.get("assets");
            if (!(rawAssets instanceof JSONArray)) throw new IOException("发布版本缺少更新文件");
            JSONArray assets = (JSONArray) rawAssets;
            if (assets.length() > 1000) throw new IOException("发布文件列表过大");
            String manifestUrl = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if ("update.json".equals(string(asset, "name"))) {
                    if (manifestUrl != null) throw new IOException("存在重复更新说明文件");
                    manifestUrl = string(asset, "browser_download_url");
                    if (!isAllowedReleaseUrl(manifestUrl) || !assetPath(manifestUrl).equals(ASSET_PATH + tag + "/update.json"))
                        throw new IOException("更新说明来源不正确");
                    long bytes = integer(asset, "size");
                    if (bytes < 1 || bytes > MAX_MANIFEST_BYTES) throw new IOException("更新说明文件大小不正确");
                }
            }
            if (manifestUrl == null) throw new IOException("发布版本尚未提供更新说明");
            Release release = parseManifest(readText(manifestUrl, MAX_MANIFEST_BYTES));
            boolean found = false;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (release.apkUrl.equals(string(asset, "browser_download_url"))) {
                    if (found || integer(asset, "size") != release.size || !"uploaded".equals(string(asset, "state")))
                        throw new IOException("安装包发布信息不一致");
                    found = true;
                }
            }
            if (!found || !assetPath(release.apkUrl).startsWith(ASSET_PATH + tag + "/"))
                throw new IOException("安装包不属于当前发布版本");
            return release.versionCode > installedVersion(context) ? release : null;
        } catch (JSONException e) { throw new IOException("无法解析发布版本信息", e); }
    }

    /** Strict, side-effect-free manifest validation; useful for release tooling and instrumentation tests. */
    public static Release parseManifest(String json) throws IOException {
        if (json == null || json.length() > MAX_MANIFEST_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
            throw new IOException("更新说明文件过大或为空");
        JSONObject data = object(json);
        try {
            long code = integer(data, "versionCode"), bytes = integer(data, "size");
            if (code < 1 || code > Integer.MAX_VALUE) throw new IOException("更新版本号无效");
            if (bytes < 1024 || bytes > MAX_APK_BYTES) throw new IOException("安装包大小不在支持范围内");
            String name = string(data, "versionName");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")) throw new IOException("更新版本名称无效");
            String url = string(data, "apkUrl");
            if (!isAllowedReleaseUrl(url) || !assetPath(url).endsWith(".apk")) throw new IOException("安装包下载地址不受信任");
            String hash = string(data, "sha256");
            if (!hash.matches("[A-Fa-f0-9]{64}")) throw new IOException("安装包校验值无效");
            String notes = data.has("notes") ? string(data, "notes") : "";
            if (notes.length() > 8000 || notes.indexOf('\u0000') >= 0) throw new IOException("更新说明内容过长或无效");
            return new Release((int) code, name, url, hash.toLowerCase(Locale.ROOT), bytes, notes);
        } catch (JSONException e) { throw new IOException("更新说明格式不正确", e); }
    }

    /** Only this repository's direct release asset URLs, without credentials, query, fragment, or path escapes. */
    public static boolean isAllowedReleaseUrl(String value) {
        URI uri = secureUri(value);
        if (uri == null || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getRawQuery() != null) return false;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(ASSET_PATH)) return false;
        String suffix = path.substring(ASSET_PATH.length());
        return suffix.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    /** CDN URLs are allowed only as redirects from a previously trusted repository asset request. */
    public static boolean isAllowedRedirect(String original, String from, String destination) {
        if (!isAllowedReleaseUrl(original)) return false;
        if (!original.equals(from) && !isAssetCdn(from)) return false;
        return original.equals(destination) || isAssetCdn(destination);
    }
    private static boolean isAssetCdn(String value) {
        URI uri = secureUri(value);
        if (uri == null) return false;
        String host = uri.getHost();
        if (!"release-assets.githubusercontent.com".equalsIgnoreCase(host) && !"objects.githubusercontent.com".equalsIgnoreCase(host)) return false;
        String path = uri.getRawPath();
        return path != null && path.matches("/github-production-release-asset(?:-[A-Za-z0-9]+)?/[0-9]+/[A-Za-z0-9._-]+");
    }
    private static URI secureUri(String value) {
        if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null ||
                    uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return uri;
        } catch (URISyntaxException e) { return null; }
    }
    private static String assetPath(String value) throws IOException {
        URI uri = secureUri(value);
        if (uri == null) throw new IOException("下载地址无效");
        return uri.getRawPath();
    }

    /** Downloads into private cache and publishes only after all checks. Does not launch an installer. */
    public static synchronized File downloadAndVerify(Context context, Release release) throws IOException {
        if (release == null) throw new IOException("缺少更新信息");
        if (release.versionCode <= installedVersion(context)) throw new IOException("该版本无需重复安装");
        File directory = updateDirectory(context);
        File finished = verifiedFile(context);
        if (finished.isFile()) {
            boolean valid = false;
            try { verifyDownloadedApk(context, release, finished); valid = true; }
            catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException invalidCachedFile) { if (!finished.delete()) throw new IOException("无法清理旧安装包"); }
            if (valid) { saveDownloadedRelease(context, release); return finished; }
        }
        File partial = File.createTempFile("download-", ".part", directory);
        boolean published = false;
        HttpURLConnection connection = null;
        try {
            long deadline = SystemClock.elapsedRealtime() + 180000L;
            connection = open(release.apkUrl, deadline);
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != release.size) throw new IOException("安装包长度与发布信息不一致");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768]; long total = 0; int read;
                while ((read = input.read(buffer)) != -1) {
                    checkDeadline(deadline);
                    total += read;
                    if (total > release.size || total > MAX_APK_BYTES) throw new IOException("安装包超过允许大小");
                    output.write(buffer, 0, read);
                }
                if (total != release.size) throw new IOException("安装包下载不完整");
                output.flush(); output.getFD().sync();
            }
            verifyDownloadedApk(context, release, partial);
            if (!partial.renameTo(finished)) throw new IOException("无法保存已验证的安装包");
            published = true;
            saveDownloadedRelease(context, release);
            return finished;
        } finally {
            if (connection != null) connection.disconnect();
            if (!published) partial.delete();
        }
    }

    /** Worker-thread recovery after Android terminates this process while changing installation permission.
     * Metadata is only a hint: every recovery verifies the APK again and never restores an authorization grant. */
    public static synchronized Release loadDownloadedRelease(Context context) throws IOException {
        File metadata = metadataFile(context);
        if (!metadata.isFile()) return null;
        try {
            if (metadata.length() < 1 || metadata.length() > MAX_MANIFEST_BYTES) throw new IOException("更新缓存信息大小无效");
            String json;
            try (InputStream input = new FileInputStream(metadata); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消");
                    if (output.size() + read > MAX_MANIFEST_BYTES) throw new IOException("更新缓存信息过大");
                    output.write(buffer, 0, read);
                }
                json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
            }
            Release release = parseManifest(json);
            verifyDownloadedApk(context, release, verifiedFile(context));
            return release;
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException | RuntimeException invalidCache) {
            // Delete only this fixed hint. A valid APK can still be reused by a later explicit download.
            metadata.delete();
            return null;
        }
    }

    private static void saveDownloadedRelease(Context context, Release release) throws IOException {
        byte[] bytes;
        try {
            JSONObject json = new JSONObject();
            json.put("versionCode", release.versionCode); json.put("versionName", release.versionName);
            json.put("apkUrl", release.apkUrl); json.put("sha256", release.sha256);
            json.put("size", release.size); json.put("notes", release.notes);
            bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) { throw new IOException("无法保存更新信息", e); }
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("更新缓存信息过大");
        File target = metadataFile(context);
        File partial = File.createTempFile("metadata-", ".part", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                output.write(bytes); output.flush(); output.getFD().sync();
            }
            // Same-directory rename publishes the whole UTF-8 document or keeps the previous complete file.
            if (!partial.renameTo(target)) throw new IOException("无法保存更新缓存信息");
        } finally { partial.delete(); }
    }

    /** Re-run immediately before handing the APK to Android's confirmation screen. */
    public static void verifyDownloaded(Context context, Release release, File apk) throws IOException {
        verifyDownloadedApk(context, release, apk);
    }

    /** Check bytes, identity, upgrade version and the exact current signing-certificate set. */
    public static void verifyDownloadedApk(Context context, Release release, File apk) throws IOException {
        if (release == null || apk == null || !apk.isFile() || apk.length() != release.size) throw new IOException("安装包大小校验失败");
        String actualHash = sha256(apk);
        if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII), release.sha256.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("安装包 SHA-256 校验失败");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        try {
            PackageInfo installed = pm.getPackageInfo(APP_PACKAGE, flags);
            PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (candidate == null || !APP_PACKAGE.equals(candidate.packageName)) throw new IOException("安装包不属于顺程记");
            long code = packageVersion(candidate);
            if (code != release.versionCode || code <= packageVersion(installed) || !release.versionName.equals(candidate.versionName))
                throw new IOException("安装包版本校验失败");
            if (candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
                throw new IOException("当前安卓版本无法安装这个更新");
            if (!signers(installed).equals(signers(candidate))) throw new IOException("安装包签名与当前应用不一致");
        } catch (PackageManager.NameNotFoundException e) { throw new IOException("无法读取当前应用信息", e); }
        catch (RuntimeException e) { throw new IOException("无法验证安装包", e); }
    }
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) throw new IOException("安装包缺少签名");
            // Deliberately require the current signer(s), not an ancestor: key rotation needs a reviewed release change.
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length == 0 || signatures.length > 32) throw new IOException("安装包缺少有效签名");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) {
            if (signature == null) throw new IOException("安装包签名无效");
            result.add(hex(digest().digest(signature.toByteArray())));
        }
        if (result.size() != signatures.length) throw new IOException("安装包签名列表无效");
        return result;
    }
    public static String sha256(File file) throws IOException {
        MessageDigest hash = digest(); byte[] buffer = new byte[32768];
        try (InputStream input = new FileInputStream(file)) {
            int read; while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消");
                hash.update(buffer, 0, read);
            }
        }
        return hex(hash.digest());
    }
    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("设备不支持 SHA-256", e); }
    }
    private static String hex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray(); char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; out[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(out);
    }
    private static long installedVersion(Context context) throws IOException {
        try { return packageVersion(context.getPackageManager().getPackageInfo(APP_PACKAGE, 0)); }
        catch (PackageManager.NameNotFoundException e) { throw new IOException("无法读取当前应用版本", e); }
    }
    private static long packageVersion(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    static File updateDirectory(Context context) throws IOException {
        File cache = context.getCacheDir().getCanonicalFile();
        File directory = new File(cache, "updates");
        if (!directory.getCanonicalFile().equals(directory)) throw new IOException("更新缓存路径不正确");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新缓存");
        return directory;
    }
    static File verifiedFile(Context context) throws IOException {
        File file = new File(updateDirectory(context), APK_FILENAME);
        if (!file.getCanonicalFile().equals(file)) throw new IOException("安装包缓存路径不正确");
        return file;
    }
    /** Fixed private APK path; callers must verify it before offering installation. */
    public static File getVerifiedFile(Context context) throws IOException { return verifiedFile(context); }
    private static File metadataFile(Context context) throws IOException {
        File file = new File(updateDirectory(context), METADATA_FILENAME);
        if (!file.getCanonicalFile().equals(file)) throw new IOException("更新信息缓存路径不正确");
        return file;
    }
    public static Intent createInstallIntent(Context context, File apk) throws IOException {
        File expected = verifiedFile(context);
        if (apk == null || !expected.equals(apk.getCanonicalFile()) || !expected.isFile()) throw new IOException("已验证的安装包不存在");
        Uri uri = Uri.parse("content://" + APP_PACKAGE + ".updates/" + APK_FILENAME);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME);
        intent.setClipData(ClipData.newRawUri("顺程记更新", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static String readText(String address, int maximum) throws IOException {
        HttpURLConnection connection = null;
        try {
            long deadline = SystemClock.elapsedRealtime() + 45000L;
            connection = open(address, deadline);
            long declared = connection.getContentLengthLong();
            if (declared > maximum) throw new IOException("更新信息文件过大");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    checkDeadline(deadline);
                    if (output.size() + read > maximum) throw new IOException("更新信息超过大小限制");
                    output.write(buffer, 0, read);
                }
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
            }
        } finally { if (connection != null) connection.disconnect(); }
    }
    private static HttpURLConnection open(String address, long deadline) throws IOException {
        if (!LATEST_API.equals(address) && !isAllowedReleaseUrl(address)) throw new IOException("更新来源不受信任");
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkDeadline(deadline);
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            boolean keep = false;
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(CONNECT_TIMEOUT); connection.setReadTimeout(READ_TIMEOUT);
                connection.setUseCaches(false); connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "ShunchengLedger-Android");
                connection.setRequestProperty("Accept", LATEST_API.equals(address) ? "application/vnd.github+json" : "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || hop == MAX_REDIRECTS) throw new IOException("更新下载重定向异常");
                    String next = new URL(new URL(current), location).toExternalForm();
                    if (!isAllowedRedirect(address, current, next)) throw new IOException("更新下载跳转到未授权来源");
                    current = next; continue;
                }
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("更新服务暂不可用（HTTP " + status + "）");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw new IOException("更新服务返回了不支持的编码");
                checkDeadline(deadline); keep = true; return connection;
            } finally { if (!keep) connection.disconnect(); }
        }
        throw new IOException("更新下载跳转次数过多");
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消");
        if (SystemClock.elapsedRealtime() >= deadline) throw new InterruptedIOException("更新连接超时");
    }
    private static JSONObject object(String json) throws IOException {
        if (json == null) throw new IOException("更新信息为空");
        // Bound nesting before Android's recursive parser sees untrusted network JSON.
        boolean quoted = false, escaped = false; int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 32) throw new IOException("更新信息嵌套过深"); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IOException("更新信息结构不正确"); }
        }
        if (quoted || depth != 0) throw new IOException("更新信息结构不正确");
        try {
            JSONTokener tokenizer = new JSONTokener(json); Object parsed = tokenizer.nextValue();
            if (!(parsed instanceof JSONObject) || tokenizer.nextClean() != 0) throw new IOException("更新信息格式不正确");
            return (JSONObject) parsed;
        } catch (JSONException e) { throw new IOException("更新信息格式不正确", e); }
    }
    private static String string(JSONObject data, String key) throws JSONException, IOException {
        Object value = data.get(key);
        if (!(value instanceof String)) throw new IOException("更新字段 " + key + " 必须是文字");
        return (String) value;
    }
    private static long integer(JSONObject data, String key) throws JSONException, IOException {
        Object value = data.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("更新字段 " + key + " 必须是整数");
        return ((Number) value).longValue();
    }
}
