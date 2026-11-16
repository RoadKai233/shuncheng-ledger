package cn.personal.shunfeng;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private offline ledger. Platform identities survive renames, archives and backup restores. */
public final class LedgerDb extends SQLiteOpenHelper {
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_PLATFORMS = 2000;
    private static final long MAX_TIMESTAMP = 253402300799999L;
    private static final String ENTRY_SELECT = "SELECT e.id,e.date,e.time,p.name,e.route,e.note," +
        "e.income,e.cost,e.createdAt,e.updatedAt,e.platformId FROM entries e JOIN platforms p ON p.id=e.platformId";
    private static final String[] DEFAULT_PLATFORMS = {"其他", "哈啰", "滴滴", "嘀嗒"};

    public static final class Entry {
        public String id, date, time, platform, platformId, route, note;
        public long income, cost, createdAt, updatedAt;
        public Entry() {
            id = UUID.randomUUID().toString();
            date = LocalDate.now().toString();
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            platform = "其他"; route = ""; note = "";
            createdAt = System.currentTimeMillis(); updatedAt = createdAt;
        }
        public void validate() {
            validId(id);
            if (platformId != null && !platformId.isEmpty()) validId(platformId);
            date = LedgerRules.date(date); time = LedgerRules.time(time);
            platform = LedgerRules.text(platform, 30, "平台名称");
            route = LedgerRules.text(route, 120, "路线");
            note = LedgerRules.text(note, 500, "备注");
            if (platform.isEmpty()) platform = "其他";
            LedgerRules.checkCents(income, false); LedgerRules.checkCents(cost, true);
            if (createdAt <= 0 || createdAt > MAX_TIMESTAMP || updatedAt < createdAt || updatedAt > MAX_TIMESTAMP)
                throw new IllegalArgumentException("记录时间戳无效");
        }
    }

    public static final class Platform {
        public String id, name;
        public boolean archived;
        public long entryCount;
        private String mergedInto;
    }

    public LedgerDb(Context context) {
        super(context.getApplicationContext(), "shunlu-ledger.db", null, 2);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        createPlatforms(db);
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL,date TEXT NOT NULL,time TEXT NOT NULL," +
            "platform TEXT NOT NULL,platformId TEXT NOT NULL REFERENCES platforms(id),route TEXT NOT NULL,note TEXT NOT NULL," +
            "income INTEGER NOT NULL CHECK(income BETWEEN 1 AND 99999999)," +
            "cost INTEGER NOT NULL CHECK(cost BETWEEN 0 AND 99999999),createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX entries_date_time ON entries(date DESC,time DESC,createdAt DESC)");
        db.execSQL("CREATE INDEX entries_platform ON entries(platformId)");
        seedPlatforms(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new SQLiteException("账本版本不兼容，请先保留备份再升级");
        createPlatforms(db);
        seedPlatforms(db);
        db.execSQL("ALTER TABLE entries ADD COLUMN platformId TEXT REFERENCES platforms(id)");
        // SQLiteOpenHelper runs this entire migration in its own transaction.
        List<String> legacyNames = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT DISTINCT platform FROM entries", null)) {
            while (c.moveToNext()) legacyNames.add(c.getString(0));
        }
        for (String original : legacyNames) {
            String name = LedgerRules.text(original, 30, "平台名称");
            if (name.isEmpty()) name = "其他";
            Platform p = byName(db, name);
            if (p == null) { p = definition(UUID.randomUUID().toString(), name, false, null); insertPlatform(db, p); }
            ContentValues v = new ContentValues(); v.put("platformId", p.id); v.put("platform", p.name);
            db.update("entries", v, "platform=?", new String[]{original});
        }
        db.execSQL("CREATE INDEX entries_platform ON entries(platformId)");
    }
    private static void createPlatforms(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE platforms(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL," +
            "archived INTEGER NOT NULL CHECK(archived IN (0,1)),mergedInto TEXT REFERENCES platforms(id))");
        db.execSQL("CREATE UNIQUE INDEX platform_live_name ON platforms(name) WHERE mergedInto IS NULL");
        db.execSQL("CREATE TABLE platform_names(name TEXT PRIMARY KEY NOT NULL,platformId TEXT NOT NULL REFERENCES platforms(id))");
    }
    private static void seedPlatforms(SQLiteDatabase db) {
        for (String name : DEFAULT_PLATFORMS) {
            String id = UUID.nameUUIDFromBytes(("shunlu:platform:" + name).getBytes(StandardCharsets.UTF_8)).toString();
            insertPlatform(db, definition(id, name, false, null));
        }
    }

    public synchronized List<Platform> listPlatforms(boolean includeArchived) {
        try {
            List<Platform> result = new ArrayList<>();
            String sql = "SELECT p.id,p.name,p.archived,p.mergedInto,COUNT(e.id) FROM platforms p " +
                "LEFT JOIN entries e ON e.platformId=p.id WHERE p.mergedInto IS NULL" +
                (includeArchived ? "" : " AND p.archived=0") + " GROUP BY p.id ORDER BY p.archived,p.rowid";
            try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
                while (c.moveToNext()) { Platform p = platformCursor(c); p.entryCount = c.getLong(4); result.add(p); }
            }
            return result;
        } catch (SQLiteException e) { throw readError(e); }
    }
    public synchronized Platform getPlatform(String id) {
        validId(id);
        try {
            SQLiteDatabase db = getReadableDatabase(); Platform p = resolve(db, id);
            if (p != null) p.entryCount = platformCount(db, p.id);
            return p;
        } catch (SQLiteException e) { throw readError(e); }
    }
    public synchronized Platform addPlatform(String name) {
        name = platformName(name);
        SQLiteDatabase db = writableTransaction();
        try {
            if (byHistoricalName(db, name) != null) throw new IllegalArgumentException("该名称已由平台或其历史名称使用，请使用或恢复该平台");
            checkPlatformCapacity(db, 1);
            Platform p = definition(UUID.randomUUID().toString(), name, false, null);
            insertPlatform(db, p); db.setTransactionSuccessful(); return p;
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }
    public synchronized void renamePlatform(String id, String name) {
        name = platformName(name);
        SQLiteDatabase db = writableTransaction();
        try {
            Platform p = requirePlatform(db, id), duplicate = byHistoricalName(db, name);
            if (duplicate != null && !duplicate.id.equals(p.id)) throw new IllegalArgumentException("该名称已由其他平台或其历史名称使用");
            rememberName(db, name, p.id);
            ContentValues v = new ContentValues(); v.put("name", name);
            db.update("platforms", v, "id=?", new String[]{p.id});
            // The legacy column is kept coherent for diagnostic tools; reads use the ID join.
            ContentValues ev = new ContentValues(); ev.put("platform", name);
            db.update("entries", ev, "platformId=?", new String[]{p.id});
            db.setTransactionSuccessful();
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }
    public synchronized void archivePlatform(String id) { setArchived(id, true); }
    public synchronized void restorePlatform(String id) { setArchived(id, false); }
    private void setArchived(String id, boolean archived) {
        SQLiteDatabase db = writableTransaction();
        try {
            Platform p = requirePlatform(db, id);
            if (archived && !p.archived && activeCount(db) <= 1)
                throw new IllegalArgumentException("请至少保留一个启用的平台");
            ContentValues v = new ContentValues(); v.put("archived", archived ? 1 : 0);
            db.update("platforms", v, "id=?", new String[]{p.id}); db.setTransactionSuccessful();
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }
    public synchronized void deletePlatform(String id, String targetId) {
        SQLiteDatabase db = writableTransaction();
        try {
            Platform source = requirePlatform(db, id);
            if (!source.archived && activeCount(db) <= 1) throw new IllegalArgumentException("请至少保留一个启用的平台");
            long records = platformCount(db, source.id);
            Platform target = null;
            if (targetId != null && !targetId.isEmpty()) target = requirePlatform(db, targetId);
            if (target == null && records > 0) throw new IllegalArgumentException("该平台仍有记录，请选择一个启用的平台接收记录");
            if (target == null) {
                try (Cursor c = db.rawQuery("SELECT id FROM platforms WHERE mergedInto IS NULL AND archived=0 AND id<>? ORDER BY rowid LIMIT 1", new String[]{source.id})) {
                    if (c.moveToFirst()) target = requirePlatform(db, c.getString(0));
                }
            }
            if (target == null || target.archived || source.id.equals(target.id))
                throw new IllegalArgumentException("请选择另一个启用的平台接收记录");
            ContentValues ev = new ContentValues(); ev.put("platformId", target.id); ev.put("platform", target.name);
            db.update("entries", ev, "platformId=?", new String[]{source.id});
            ContentValues pv = new ContentValues(); pv.put("mergedInto", target.id); pv.put("archived", 1);
            db.update("platforms", pv, "id=?", new String[]{source.id});
            // Flatten older aliases so repeated merges never form deep chains.
            db.update("platforms", pv, "mergedInto=?", new String[]{source.id});
            db.setTransactionSuccessful();
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }

    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(ENTRY_SELECT + " ORDER BY e.date DESC,e.time DESC,e.createdAt DESC,e.id ASC", null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        } catch (SQLiteException e) { throw readError(e); }
        return out;
    }
    public synchronized Entry get(String id) {
        validId(id);
        try { return find(getReadableDatabase(), id); }
        catch (SQLiteException e) { throw readError(e); }
    }
    public synchronized void save(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("记录不能为空");
        entry.validate();
        SQLiteDatabase db = writableTransaction();
        try {
            Entry existing = find(db, entry.id);
            Platform p;
            if (entry.platformId == null || entry.platformId.isEmpty()) {
                p = byHistoricalName(db, platformName(entry.platform));
                if (p == null) {
                    checkPlatformCapacity(db, 1);
                    p = definition(UUID.randomUUID().toString(), platformName(entry.platform), false, null);
                    insertPlatform(db, p);
                }
            } else p = requirePlatform(db, entry.platformId);
            if (p.archived && (existing == null || !p.id.equals(existing.platformId)))
                throw new IllegalArgumentException("该平台已停用，请恢复平台或选择其他平台");
            entry.platformId = p.id; entry.platform = p.name;
            long now = System.currentTimeMillis();
            if (existing == null) {
                if (count(db) >= MAX_ENTRIES) throw new IllegalStateException("账本已达到 20000 条记录上限，请先导出备份");
                entry.createdAt = now; entry.updatedAt = now;
                db.insertOrThrow("entries", null, values(entry));
            } else {
                entry.createdAt = existing.createdAt; entry.updatedAt = Math.max(now, existing.updatedAt);
                if (db.update("entries", values(entry), "id=?", new String[]{entry.id}) != 1) throw new SQLiteException("记录更新失败");
            }
            db.setTransactionSuccessful();
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }
    public synchronized void delete(String id) {
        validId(id);
        try { getWritableDatabase().delete("entries", "id=?", new String[]{id}); }
        catch (SQLiteException e) { throw writeError(e); }
    }

    public synchronized byte[] exportJson() {
        try {
            JSONObject root = new JSONObject(); root.put("format", "shunlu-ledger"); root.put("version", 2);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray platforms = new JSONArray();
            for (Platform p : definitions(getReadableDatabase()).values()) {
                JSONObject o = new JSONObject(); o.put("id", p.id); o.put("name", p.name); o.put("archived", p.archived);
                o.put("mergedInto", p.mergedInto == null ? JSONObject.NULL : p.mergedInto); platforms.put(o);
            }
            root.put("platforms", platforms);
            JSONArray namedAliases = new JSONArray();
            for (Map.Entry<String, String> alias : historicalNames(getReadableDatabase()).entrySet()) {
                JSONObject a = new JSONObject(); a.put("name", alias.getKey()); a.put("platformId", alias.getValue()); namedAliases.put(a);
            }
            root.put("platformNames", namedAliases);
            JSONArray rows = new JSONArray();
            for (Entry e : all()) {
                JSONObject o = new JSONObject(); o.put("id", e.id); o.put("date", e.date); o.put("time", e.time);
                o.put("platform", e.platform); o.put("platformId", e.platformId); o.put("route", e.route); o.put("note", e.note);
                o.put("income", e.income); o.put("cost", e.cost); o.put("createdAt", e.createdAt); o.put("updatedAt", e.updatedAt);
                rows.put(o);
            }
            root.put("entries", rows);
            byte[] result = root.toString().getBytes(StandardCharsets.UTF_8);
            if (result.length > BackupCrypto.MAX_BYTES - 52) throw new IllegalStateException("账本数据超过单份备份容量，请先导出 CSV 保存明细");
            return result;
        } catch (JSONException e) { throw new IllegalStateException("备份生成失败，账本数据未改变", e); }
        catch (SQLiteException e) { throw readError(e); }
    }

    /** Atomic append. Existing local platform decisions and existing entry IDs always win. */
    public synchronized int importJson(byte[] bytes) {
        Backup backup = parseBackup(bytes);
        SQLiteDatabase db = writableTransaction();
        try {
            if (backup.version == 2 && pristine(db)) {
                db.delete("platform_names", null, null);
                db.delete("platforms", null, null);
            }
            Map<String, Platform> local = definitions(db);
            ImportPlan plan = new ImportPlan(local, backup.platforms, historicalNames(db));
            for (String id : backup.platforms.keySet()) plan.resolveIncoming(id);
            checkPlatformCapacity(db, plan.additions.size());
            for (Map.Entry<String, String> alias : backup.names.entrySet()) {
                if (!plan.names.containsKey(alias.getKey())) plan.names.put(alias.getKey(), plan.resolveIncoming(alias.getValue()));
            }
            List<Entry> incoming = new ArrayList<>();
            for (Entry e : backup.entries) {
                String mapped = plan.resolveIncoming(e.platformId);
                Platform p = plan.combined.get(mapped);
                e.platformId = p.id; e.platform = p.name;
                if (find(db, e.id) == null) incoming.add(e);
            }
            if (count(db) + incoming.size() > MAX_ENTRIES)
                throw new IllegalArgumentException("合并后超过 20000 条记录上限，未导入任何记录");
            // Insert canonical definitions before aliases (also valid with foreign keys enabled).
            for (Platform p : plan.additions.values()) if (p.mergedInto == null) insertPlatform(db, p);
            for (Platform p : plan.additions.values()) if (p.mergedInto != null) insertPlatform(db, p);
            for (Map.Entry<String, String> alias : plan.names.entrySet()) rememberName(db, alias.getKey(), alias.getValue());
            for (Entry e : incoming) db.insertOrThrow("entries", null, values(e));
            db.setTransactionSuccessful(); return incoming.size();
        } catch (SQLiteException e) { throw writeError(e); }
        finally { db.endTransaction(); }
    }
    public synchronized String exportCsv() {
        StringBuilder csv = new StringBuilder("\uFEFF日期,时间,平台,路线,收入（元）,成本（元）,净收入（元）,备注\r\n");
        for (Entry e : all()) {
            csv.append(LedgerRules.csv(e.date)).append(',').append(LedgerRules.csv(e.time)).append(',')
                .append(LedgerRules.csv(e.platform)).append(',').append(LedgerRules.csv(e.route)).append(',')
                .append(LedgerRules.format(e.income)).append(',').append(LedgerRules.format(e.cost)).append(',')
                .append(LedgerRules.format(e.income - e.cost)).append(',').append(LedgerRules.csv(e.note)).append("\r\n");
        }
        return csv.toString();
    }

    private static final class Backup {
        long version;
        final List<Entry> entries = new ArrayList<>();
        final Map<String, Platform> platforms = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
    }
    private static final class ImportPlan {
        final Map<String, Platform> local, source, combined, additions = new LinkedHashMap<>();
        final Map<String, String> mappings = new HashMap<>(), names = new HashMap<>();
        final Set<String> visiting = new HashSet<>();
        ImportPlan(Map<String, Platform> local, Map<String, Platform> source, Map<String, String> oldNames) {
            this.local = local; this.source = source; combined = new LinkedHashMap<>(local);
            for (Map.Entry<String, String> name : oldNames.entrySet()) names.put(name.getKey(), canonical(local, name.getValue()));
            for (Platform p : local.values()) if (p.mergedInto == null) names.put(p.name, p.id);
        }
        String resolveIncoming(String id) {
            if (mappings.containsKey(id)) return mappings.get(id);
            if (local.containsKey(id)) {
                String target = canonical(local, id); mappings.put(id, target); return target;
            }
            if (!visiting.add(id)) throw new IllegalArgumentException("备份平台合并关系存在循环");
            Platform p = source.get(id);
            if (p == null) throw new IllegalArgumentException("备份记录引用的平台不存在");
            String target;
            if (p.mergedInto != null) target = resolveIncoming(p.mergedInto);
            else if (names.containsKey(p.name)) target = names.get(p.name);
            else {
                target = p.id;
                Platform created = definition(p.id, p.name, p.archived, null);
                additions.put(created.id, created); combined.put(created.id, created); names.put(created.name, created.id);
            }
            if (!target.equals(id)) {
                Platform alias = definition(id, p.name, true, target);
                additions.put(id, alias); combined.put(id, alias);
            }
            mappings.put(id, target); visiting.remove(id); return target;
        }
    }
    private static Backup parseBackup(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > BackupCrypto.MAX_BYTES - 52)
            throw new IllegalArgumentException("备份为空或超过大小限制");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokener = new JSONTokener(json); Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("备份格式不正确");
            JSONObject root = (JSONObject) value;
            long version = integer(root, "version");
            if (!"shunlu-ledger".equals(string(root, "format")) || (version != 1 && version != 2))
                throw new IllegalArgumentException("不支持这个备份格式或版本");
            Backup backup = new Backup(); backup.version = version;
            if (version == 2) {
                JSONArray defs = array(root, "platforms");
                if (defs.length() == 0 || defs.length() > MAX_PLATFORMS) throw new IllegalArgumentException("备份平台数量无效");
                Set<String> names = new HashSet<>(); int active = 0;
                for (int i = 0; i < defs.length(); i++) {
                    JSONObject o = object(defs.get(i)); String id = string(o, "id"); validId(id);
                    String name = platformName(string(o, "name")); Object archived = o.get("archived");
                    if (!(archived instanceof Boolean)) throw new IllegalArgumentException("平台停用状态无效");
                    Object merged = o.get("mergedInto"); String mergedInto = null;
                    if (merged != JSONObject.NULL) {
                        if (!(merged instanceof String)) throw new IllegalArgumentException("平台合并引用无效");
                        mergedInto = (String) merged; validId(mergedInto);
                        if (!((Boolean) archived)) throw new IllegalArgumentException("已合并的平台必须停用");
                    } else {
                        if (!names.add(name)) throw new IllegalArgumentException("备份中有重名平台");
                        if (!((Boolean) archived)) active++;
                    }
                    if (backup.platforms.put(id, definition(id, name, (Boolean) archived, mergedInto)) != null)
                        throw new IllegalArgumentException("备份中有重复平台编号");
                }
                if (active == 0) throw new IllegalArgumentException("备份必须保留至少一个启用的平台");
                for (String id : backup.platforms.keySet()) canonical(backup.platforms, id);
                if (root.has("platformNames")) {
                    JSONArray named = array(root, "platformNames");
                    if (named.length() > 10000) throw new IllegalArgumentException("平台历史名称数量过多");
                    for (int i = 0; i < named.length(); i++) {
                        JSONObject a = object(named.get(i));
                        String name = platformName(string(a, "name")), pid = string(a, "platformId");
                        validId(pid); canonical(backup.platforms, pid);
                        if (backup.names.put(name, pid) != null) throw new IllegalArgumentException("备份包含重复平台历史名称");
                    }
                }
                // Current names must agree with their definitions. Legacy aliases remain optional.
                for (Platform p : backup.platforms.values()) if (p.mergedInto == null) {
                    String alias = backup.names.get(p.name);
                    if (alias != null && !canonical(backup.platforms, alias).equals(p.id))
                        throw new IllegalArgumentException("平台名称与历史名称关联冲突");
                    backup.names.put(p.name, p.id);
                }
                for (Platform p : backup.platforms.values()) if (!backup.names.containsKey(p.name)) backup.names.put(p.name, p.id);
            }
            JSONArray rows = array(root, "entries");
            if (rows.length() > MAX_ENTRIES) throw new IllegalArgumentException("单份备份最多支持 20000 条记录");
            Set<String> ids = new HashSet<>(); Map<String, String> legacyNames = new HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                try {
                    JSONObject o = object(rows.get(i)); Entry e = new Entry();
                    e.id = string(o, "id"); e.date = string(o, "date"); e.time = string(o, "time");
                    e.platform = string(o, "platform"); e.route = string(o, "route"); e.note = string(o, "note");
                    e.income = integer(o, "income"); e.cost = integer(o, "cost");
                    e.createdAt = integer(o, "createdAt"); e.updatedAt = integer(o, "updatedAt");
                    if (version == 2) {
                        e.platformId = string(o, "platformId"); validId(e.platformId);
                        canonical(backup.platforms, e.platformId);
                    }
                    e.validate();
                    if (version == 1) {
                        if (!legacyNames.containsKey(e.platform)) {
                            String pid = UUID.nameUUIDFromBytes(("shunlu:legacy-platform:" + e.platform).getBytes(StandardCharsets.UTF_8)).toString(); legacyNames.put(e.platform, pid);
                            backup.platforms.put(pid, definition(pid, e.platform, false, null));
                            backup.names.put(e.platform, pid);
                        }
                        e.platformId = legacyNames.get(e.platform);
                    }
                    if (!ids.add(e.id)) throw new IllegalArgumentException("备份中有重复记录编号");
                    backup.entries.add(e);
                } catch (JSONException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("备份第 " + (i + 1) + " 条记录无效，未导入任何记录：" + e.getMessage(), e);
                }
            }
            if (backup.platforms.size() > MAX_PLATFORMS) throw new IllegalArgumentException("备份平台数量过多");
            return backup;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("无法读取备份内容，账本数据未改变", e); }
    }

    private SQLiteDatabase writableTransaction() {
        try { SQLiteDatabase db = getWritableDatabase(); db.beginTransaction(); return db; }
        catch (SQLiteException e) { throw writeError(e); }
    }
    private static JSONObject object(Object value) {
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("备份对象格式不正确");
        return (JSONObject) value;
    }
    private static JSONArray array(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("备份缺少列表：" + key);
        return (JSONArray) value;
    }
    private static String string(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("字段 " + key + " 必须是文字");
        return (String) value;
    }
    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("字段 " + key + " 必须是整数");
        return ((Number) value).longValue();
    }
    private static void validId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("记录或平台编号无效");
    }
    private static String platformName(String name) {
        String result = LedgerRules.text(name, 30, "平台名称");
        if (result.isEmpty()) throw new IllegalArgumentException("请输入平台名称");
        return result;
    }
    private static Platform definition(String id, String name, boolean archived, String mergedInto) {
        Platform p = new Platform(); p.id = id; p.name = name; p.archived = archived; p.mergedInto = mergedInto; return p;
    }
    private static Platform platformCursor(Cursor c) {
        return definition(c.getString(0), c.getString(1), c.getInt(2) != 0, c.isNull(3) ? null : c.getString(3));
    }
    private static Map<String, Platform> definitions(SQLiteDatabase db) {
        Map<String, Platform> result = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery("SELECT id,name,archived,mergedInto FROM platforms ORDER BY rowid", null)) {
            while (c.moveToNext()) { Platform p = platformCursor(c); result.put(p.id, p); }
        }
        return result;
    }
    private static String canonical(Map<String, Platform> defs, String id) {
        Set<String> seen = new HashSet<>(); String current = id;
        while (true) {
            if (!seen.add(current)) throw new IllegalArgumentException("平台合并关系存在循环");
            if (seen.size() > 100) throw new IllegalArgumentException("平台合并关系过深，未导入任何记录");
            Platform p = defs.get(current);
            if (p == null) throw new IllegalArgumentException("记录或合并关系引用的平台不存在");
            if (p.mergedInto == null) return current;
            current = p.mergedInto;
        }
    }
    private static Platform resolve(SQLiteDatabase db, String id) {
        Set<String> seen = new HashSet<>(); String current = id;
        while (true) {
            if (!seen.add(current)) throw new IllegalStateException("平台合并关系异常，请保留备份");
            try (Cursor c = db.rawQuery("SELECT id,name,archived,mergedInto FROM platforms WHERE id=?", new String[]{current})) {
                if (!c.moveToFirst()) return null;
                Platform p = platformCursor(c); if (p.mergedInto == null) return p; current = p.mergedInto;
            }
        }
    }
    private static Platform requirePlatform(SQLiteDatabase db, String id) {
        validId(id); Platform p = resolve(db, id);
        if (p == null) throw new IllegalArgumentException("平台不存在，请重新选择");
        return p;
    }
    private static Platform byName(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT id,name,archived,mergedInto FROM platforms WHERE name=? AND mergedInto IS NULL", new String[]{name})) {
            return c.moveToFirst() ? platformCursor(c) : null;
        }
    }
    private static void insertPlatform(SQLiteDatabase db, Platform p) {
        ContentValues v = new ContentValues(); v.put("id", p.id); v.put("name", p.name); v.put("archived", p.archived ? 1 : 0);
        if (p.mergedInto == null) v.putNull("mergedInto"); else v.put("mergedInto", p.mergedInto);
        db.insertOrThrow("platforms", null, v);
        if (p.mergedInto == null) rememberName(db, p.name, p.id);
    }
    private static boolean pristine(SQLiteDatabase db) {
        if (count(db) != 0) return false;
        Map<String, Platform> platforms = definitions(db);
        Map<String, String> names = historicalNames(db);
        if (platforms.size() != DEFAULT_PLATFORMS.length || names.size() != DEFAULT_PLATFORMS.length) return false;
        for (String name : DEFAULT_PLATFORMS) {
            String id = UUID.nameUUIDFromBytes(("shunlu:platform:" + name).getBytes(StandardCharsets.UTF_8)).toString();
            Platform p = platforms.get(id);
            if (p == null || p.archived || p.mergedInto != null || !name.equals(p.name) || !id.equals(names.get(name))) return false;
        }
        return true;
    }
    private static Map<String, String> historicalNames(SQLiteDatabase db) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery("SELECT name,platformId FROM platform_names ORDER BY name", null)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
        }
        return out;
    }
    private static void rememberName(SQLiteDatabase db, String name, String id) {
        try (Cursor c = db.rawQuery("SELECT platformId FROM platform_names WHERE name=?", new String[]{name})) {
            if (c.moveToFirst()) return; // Existing local name decisions always win on restore.
        }
        if (queryCount(db, "SELECT COUNT(*) FROM platform_names", null) >= 10000)
            throw new IllegalStateException("平台历史名称数量超过上限，未保存更改");
        ContentValues v = new ContentValues(); v.put("name", name); v.put("platformId", id);
        db.insertOrThrow("platform_names", null, v);
    }
    private static Platform byHistoricalName(SQLiteDatabase db, String name) {
        Platform current = byName(db, name);
        if (current != null) return current;
        try (Cursor c = db.rawQuery("SELECT platformId FROM platform_names WHERE name=?", new String[]{name})) {
            return c.moveToFirst() ? resolve(db, c.getString(0)) : null;
        }
    }
    private static long activeCount(SQLiteDatabase db) {
        return queryCount(db, "SELECT COUNT(*) FROM platforms WHERE mergedInto IS NULL AND archived=0", null);
    }
    private static long platformCount(SQLiteDatabase db, String id) {
        return queryCount(db, "SELECT COUNT(*) FROM entries WHERE platformId=?", new String[]{id});
    }
    private static void checkPlatformCapacity(SQLiteDatabase db, long additions) {
        if (queryCount(db, "SELECT COUNT(*) FROM platforms", null) + additions > MAX_PLATFORMS)
            throw new IllegalStateException("平台数量超过上限，未保存更改");
    }
    private static long queryCount(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) { c.moveToFirst(); return c.getLong(0); }
    }
    private static long count(SQLiteDatabase db) { return queryCount(db, "SELECT COUNT(*) FROM entries", null); }
    private static Entry find(SQLiteDatabase db, String id) {
        try (Cursor c = db.rawQuery(ENTRY_SELECT + " WHERE e.id=? LIMIT 1", new String[]{id})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }
    private static Entry fromCursor(Cursor c) {
        Entry e = new Entry(); e.id = c.getString(0); e.date = c.getString(1); e.time = c.getString(2);
        e.platform = c.getString(3); e.route = c.getString(4); e.note = c.getString(5);
        e.income = c.getLong(6); e.cost = c.getLong(7); e.createdAt = c.getLong(8); e.updatedAt = c.getLong(9);
        e.platformId = c.getString(10); return e;
    }
    private static ContentValues values(Entry e) {
        ContentValues v = new ContentValues(); v.put("id", e.id); v.put("date", e.date); v.put("time", e.time);
        v.put("platform", e.platform); v.put("platformId", e.platformId); v.put("route", e.route); v.put("note", e.note);
        v.put("income", e.income); v.put("cost", e.cost); v.put("createdAt", e.createdAt); v.put("updatedAt", e.updatedAt); return v;
    }
    private static IllegalStateException readError(Exception e) {
        return new IllegalStateException("无法读取本机账本，请检查手机存储空间；请勿清除应用数据", e);
    }
    private static IllegalStateException writeError(Exception e) {
        return new IllegalStateException("保存失败，请检查手机剩余空间后重试", e);
    }
}
