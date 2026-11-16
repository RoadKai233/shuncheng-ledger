package cn.personal.shunfeng.tests;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Tests actual SQLite migrations and platform lifecycle in isolated test databases. */
public final class PlatformChecks {
    public static final class Result { public int count; public final StringBuilder log=new StringBuilder(); }
    private interface Checked { void run()throws Exception; }
    private final Context context;
    private final Class<?> databaseClass,entryClass;
    private final List<SQLiteOpenHelper> opened=new ArrayList<>();
    private final File folder;
    private final Result result=new Result();
    private static final Class<?>[] NONE=new Class<?>[0];
    private PlatformChecks(Context context)throws Exception {
        this.context=context;databaseClass=context.getClassLoader().loadClass("cn.personal.shunfeng.LedgerDb");entryClass=context.getClassLoader().loadClass("cn.personal.shunfeng.LedgerDb$Entry");
        folder=new File(context.getCacheDir(),"platform-checks-"+UUID.randomUUID());if(!folder.mkdirs())throw new IllegalStateException("Cannot create isolated DB directory");
    }
    public static Result run(Context context)throws Exception {
        PlatformChecks suite=new PlatformChecks(context);
        try{suite.migration();suite.platformLifecycle();suite.backupRules();suite.freshDefaultRestore();suite.lastActive();return suite.result;}
        catch(Exception|AssertionError failure){throw new AssertionError(suite.result.log+"Platform suite failed: "+failure,failure);}
        finally{for(SQLiteOpenHelper helper:suite.opened)helper.close();suite.remove(suite.folder);}
    }
    private Object database(String name)throws Exception {
        Object db=databaseClass.getConstructor(Context.class).newInstance(new IsolatedContext(context,new File(folder,name)));
        opened.add((SQLiteOpenHelper)db);return db;
    }
    private void migration()throws Exception {
        IsolatedContext legacyContext=new IsolatedContext(context,new File(folder,"migration"));
        File path=legacyContext.getDatabasePath("shunlu-ledger.db");path.getParentFile().mkdirs();
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(path,null)){
            old.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL,date TEXT NOT NULL,time TEXT NOT NULL,platform TEXT NOT NULL,route TEXT NOT NULL,note TEXT NOT NULL,income INTEGER NOT NULL CHECK(income BETWEEN 1 AND 99999999),cost INTEGER NOT NULL CHECK(cost BETWEEN 0 AND 99999999),createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)");
            old.execSQL("CREATE INDEX entries_date_time ON entries(date DESC,time DESC,createdAt DESC)");
            old.execSQL("INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?,?)",new Object[]{first,"2024-02-29","08:30","哈啰","旧路线","旧备注",12880L,1880L,1709166600000L,1709166600100L});
            old.execSQL("INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?,?)",new Object[]{second,"2024-03-01","18:45","旧自定义平台","旧自定义路线","保留此笔",6530L,500L,1709290000000L,1709290000100L});old.setVersion(1);
        }
        Object db=database("migration");List<?> rows=rows(db);
        check(rows.size()==2,"v1 schema upgrade preserves both records");
        check(((SQLiteOpenHelper)db).getReadableDatabase().getVersion()==2,"SQLite user_version upgraded to v2");
        Object a=get(db,first),b=get(db,second);
        check(((Long)f(a,"income"))==12880L&&((Long)f(a,"cost"))==1880L,"Migration preserves monetary cents");
        check(first.equals(f(a,"id"))&&"2024-02-29".equals(f(a,"date"))&&"08:30".equals(f(a,"time")),"Migration preserves IDs and trip dates");
        check(((Long)f(a,"createdAt"))==1709166600000L&&((Long)f(a,"updatedAt"))==1709166600100L,"Migration preserves timestamps");
        check("旧备注".equals(f(a,"note"))&&"旧路线".equals(f(a,"route")),"Migration preserves route and note");
        check(f(a,"platformId")!=null&&f(b,"platformId")!=null,"Migration assigns stable platform IDs");
        check("旧自定义平台".equals(f(platform(db,(String)f(b,"platformId")),"name")),"Migration retains legacy custom platform name");
        String aid=(String)f(a,"platformId");rename(db,aid,"哈啰改名验证");
        check("哈啰改名验证".equals(f(get(db,first),"platform")),"Platform rename immediately updates historical display");
        check(aid.equals(f(get(db,first),"platformId")),"Rename preserves historical association ID");
        check(((String)call(db,"exportCsv",NONE)).contains("哈啰改名验证"),"CSV reflects renamed historical platform");
    }
    private void platformLifecycle()throws Exception {
        Object db=database("lifecycle"),source=add(db,"测试平台"),target=add(db,"转入平台");String sid=id(source),tid=id(target);
        check(findByName(db,"测试平台")!=null,"Custom platform can be added");
        reject(()->add(db," 测试平台 "),"Trimmed duplicate platform names rejected");
        reject(()->add(db,"  "),"Empty platform name rejected");
        reject(()->add(db,"1234567890123456789012345678901"),"Overlength platform name rejected");
        reject(()->rename(db,sid,"转入平台"),"Rename cannot collide with another platform");
        Object entry=entry(sid,"平台历史",12345,2345);save(db,entry);String eid=(String)f(entry,"id");
        check(((Long)f(platform(db,sid),"entryCount"))==1L,"Platform reports associated record count");
        call(db,"archivePlatform",new Class[]{String.class},sid);
        check((Boolean)f(platform(db,sid),"archived"),"Platform can be archived");
        check(!contains(platforms(db,false),sid)&&contains(platforms(db,true),sid),"Archived platform excluded only from active choices");
        check(sid.equals(f(get(db,eid),"platformId")),"Archiving preserves historical association");
        reject(()->save(db,entry(sid,"禁止新增",100,0)),"New record cannot select archived platform");
        Object existing=get(db,eid);s(existing,"cost",2500L);save(db,existing);
        check(((Long)f(get(db,eid),"cost"))==2500L,"Existing record on archived platform remains editable");
        Object other=entry(tid,"其他行程",4567,567);save(db,other);s(other,"platformId",sid);
        reject(()->save(db,other),"Existing record cannot move onto archived platform");
        call(db,"restorePlatform",new Class[]{String.class},sid);check(contains(platforms(db,false),sid),"Archived platform can be restored");
        long income=total(db,"income"),cost=total(db,"cost");int count=rows(db).size();
        reject(()->removePlatform(db,sid,null),"Deleting platform with history requires transfer target");
        reject(()->removePlatform(db,sid,sid),"Platform cannot merge into itself");
        check(sid.equals(f(get(db,eid),"platformId")),"Rejected deletion preserves history link");
        removePlatform(db,sid,tid);
        check(!contains(platforms(db,true),sid),"Merged source disappears from platform list");
        check(tid.equals(f(get(db,eid),"platformId")),"Merge transfers historical record to destination ID");
        check("转入平台".equals(f(get(db,eid),"platform")),"Merge updates historical display name");
        check(rows(db).size()==count&&total(db,"income")==income&&total(db,"cost")==cost,"Merge preserves all records and money totals");
        check(tid.equals(id(platform(db,sid))),"Merged ID resolves to destination platform");
        Object empty=add(db,"可以删除的空平台");String emptyId=id(empty);removePlatform(db,emptyId,null);
        check(!contains(platforms(db,true),emptyId),"Unused platform can be deleted without explicit transfer");
    }
    private void backupRules()throws Exception {
        Object db=database("backup-source");String sid=id(add(db,"待合并平台")),tid=id(add(db,"保留平台"));
        String empty=id(add(db,"无记录的平台")),inactive=id(add(db,"停用空平台"));call(db,"archivePlatform",new Class[]{String.class},inactive);
        Object entry=entry(sid,"合并前历史",9876,876);save(db,entry);String eid=(String)f(entry,"id");
        byte[] before=export(db);JSONObject beforeDoc=json(before);check(beforeDoc.getInt("version")==2,"Backup exports v2 platform-aware schema");check(beforeDoc.has("platforms"),"v2 backup includes unused platform metadata");
        removePlatform(db,sid,tid);rename(db,tid,"本地新名字");byte[] after=export(db);
        importBytes(db,before);check("本地新名字".equals(f(platform(db,tid),"name")),"Old backup cannot overwrite local platform rename");check(!contains(platforms(db,true),sid),"Old backup cannot revive merged platform");
        check(tid.equals(f(get(db,eid),"platformId")),"Old backup cannot restore obsolete history association");
        JSONObject absent=new JSONObject(beforeDoc.toString());String unseen=UUID.randomUUID().toString();absent.getJSONArray("entries").getJSONObject(0).put("id",unseen);
        check(importBytes(db,bytes(absent))==1,"Old backup can add a missing historical record");
        check(tid.equals(f(get(db,unseen),"platformId")),"Missing old record follows local merge tombstone");
        check("本地新名字".equals(f(get(db,unseen),"platform")),"Missing old record uses local renamed display");
        Object restored=database("backup-restored");importBytes(restored,after);
        check(findByName(restored,"无记录的平台")!=null,"v2 restore retains platform with zero records");
        Object restoredInactive=findByName(restored,"停用空平台");check(restoredInactive!=null&&(Boolean)f(restoredInactive,"archived"),"v2 restore retains archived zero-record platform");
        check(!contains(platforms(restored,true),sid)&&tid.equals(f(get(restored,eid),"platformId")),"v2 restore preserves merge and historical destination");
        check(total(restored,"income")==9876L&&total(restored,"cost")==876L,"v2 restore preserves account totals");
        JSONObject legacy=legacy(entry("unused","v1兼容路线",1111,111));legacy.put("platform","旧版自定义渠道");String legacyId=legacy.getString("id");
        byte[] v1=bytes(new JSONObject().put("format","shunlu-ledger").put("version",1).put("entries",new JSONArray().put(legacy)));
        check(importBytes(restored,v1)==1,"v1 backup still imports into v2");check("旧版自定义渠道".equals(f(get(restored,legacyId),"platform")),"v1 custom platform becomes linked platform");
        check(f(get(restored,legacyId),"platformId")!=null,"v1 restored entry receives stable platform ID");
        JSONObject oldMerge=legacy(entry("unused","v1旧合并平台",2222,222));oldMerge.put("platform","待合并平台");String oldMergeId=oldMerge.getString("id");
        importBytes(db,bytes(new JSONObject().put("format","shunlu-ledger").put("version",1).put("entries",new JSONArray().put(oldMerge))));
        check(tid.equals(f(get(db,oldMergeId),"platformId")),"v1 legacy name follows local platform merge");
        Object collisionDb=database("collision");String localId=id(add(collisionDb,"无记录的平台"));rename(collisionDb,localId,"无记录的平台");importBytes(collisionDb,after);
        check(findByName(collisionDb,"无记录的平台")!=null&&id(findByName(collisionDb,"无记录的平台")).equals(localId),"Import keeps existing same-name local platform");
        rename(collisionDb,localId,"同名合并后的本地名");importBytes(collisionDb,after);
        check(findByName(collisionDb,"无记录的平台")==null&&"同名合并后的本地名".equals(f(platform(collisionDb,localId),"name")),"Saved alias prevents old backup reviving old same-name platform");
        JSONObject malformed=json(after);JSONArray p=malformed.getJSONArray("platforms");p.put(new JSONObject(p.getJSONObject(0).toString()));int count=rows(restored).size();
        reject(()->importBytes(restored,bytes(malformed)),"Duplicate platform IDs invalidate full backup");check(rows(restored).size()==count,"Invalid platform metadata imports no records");
        JSONObject missing=json(after);missing.getJSONArray("entries").getJSONObject(0).put("platformId",UUID.randomUUID().toString());reject(()->importBytes(restored,bytes(missing)),"Missing referenced platform rejects v2 backup");
        JSONObject cycle=json(after);JSONArray cp=cycle.getJSONArray("platforms");JSONObject p0=cp.getJSONObject(0),p1=cp.getJSONObject(1);p0.put("mergedInto",p1.getString("id"));p1.put("mergedInto",p0.getString("id"));reject(()->importBytes(restored,bytes(cycle)),"Platform merge cycles rejected before import");
    }
    private void freshDefaultRestore()throws Exception {
        Object source=database("default-source");String hid=id(findByName(source,"哈啰"));Object trip=entry(hid,"默认平台历史",2468,468);save(source,trip);String eid=(String)f(trip,"id");rename(source,hid,"我的哈啰");call(source,"archivePlatform",new Class[]{String.class},hid);byte[] snapshot=export(source);
        Object fresh=database("default-fresh");importBytes(fresh,snapshot);
        check("我的哈啰".equals(f(platform(fresh,hid),"name")),"Fresh restore replaces default seed name with backed-up rename");
        check((Boolean)f(platform(fresh,hid),"archived"),"Fresh restore replaces active default seed with archived state");
        check("我的哈啰".equals(f(get(fresh,eid),"platform"))&&((Long)f(get(fresh,eid),"income"))==2468L,"Fresh default restore preserves renamed historical record");
        Object local=database("default-local");rename(local,hid,"本机哈啰名称");importBytes(local,snapshot);
        check("本机哈啰名称".equals(f(platform(local,hid),"name")),"Existing local default rename takes priority over backup");
        check(!(Boolean)f(platform(local,hid),"archived"),"Existing locally customized platform keeps local active state");
        Object merged=database("default-merged-source");String original=id(findByName(merged,"哈啰")),destination=id(findByName(merged,"滴滴"));Object mtrip=entry(original,"默认平台合并历史",3579,579);save(merged,mtrip);String mid=(String)f(mtrip,"id");removePlatform(merged,original,destination);byte[] mergedBackup=export(merged);
        Object restored=database("default-merged-fresh");importBytes(restored,mergedBackup);
        check(!contains(platforms(restored,true),original),"Fresh restore does not revive merged default seed platform");
        check(destination.equals(f(get(restored,mid),"platformId")),"Fresh restore follows merged default history destination");
        check(destination.equals(id(platform(restored,original))),"Fresh restore retains default-platform merge alias");
        check(total(restored,"income")==3579L&&total(restored,"cost")==579L,"Fresh default merge restore preserves all amounts");
    }
    private void lastActive()throws Exception {
        Object db=database("last-active");List<?> active=platforms(db,false);String keep=id(active.get(0));for(int i=1;i<active.size();i++)call(db,"archivePlatform",new Class[]{String.class},id(active.get(i)));
        check(platforms(db,false).size()==1,"Archiving can leave exactly one active platform");reject(()->call(db,"archivePlatform",new Class[]{String.class},keep),"Last active platform cannot be archived");reject(()->removePlatform(db,keep,null),"Last active platform cannot be deleted");check(platforms(db,false).size()==1,"Rejected last-platform changes preserve usable platform");
    }
    private JSONObject legacy(Object e)throws Exception {JSONObject j=new JSONObject();for(String name:new String[]{"id","date","time","platform","route","note","income","cost","createdAt","updatedAt"})j.put(name,f(e,name));return j;}
    private Object entry(String platformId,String route,long amount,long cost)throws Exception{Object e=entryClass.getConstructor().newInstance();s(e,"platformId",platformId);s(e,"route",route);s(e,"income",amount);s(e,"cost",cost);return e;}
    private List<?> rows(Object db)throws Exception{return (List<?>)call(db,"all",NONE);}
    private List<?> platforms(Object db,boolean archived)throws Exception{return (List<?>)call(db,"listPlatforms",new Class[]{boolean.class},archived);}
    private Object platform(Object db,String id)throws Exception{return call(db,"getPlatform",new Class[]{String.class},id);}
    private Object get(Object db,String id)throws Exception{return call(db,"get",new Class[]{String.class},id);}
    private Object add(Object db,String name)throws Exception{return call(db,"addPlatform",new Class[]{String.class},name);}
    private void rename(Object db,String id,String name)throws Exception{call(db,"renamePlatform",new Class[]{String.class,String.class},id,name);}
    private void removePlatform(Object db,String id,String target)throws Exception{call(db,"deletePlatform",new Class[]{String.class,String.class},id,target);}
    private void save(Object db,Object entry)throws Exception{call(db,"save",new Class[]{entryClass},entry);}
    private byte[] export(Object db)throws Exception{return (byte[])call(db,"exportJson",NONE);}
    private int importBytes(Object db,byte[] data)throws Exception{return (Integer)call(db,"importJson",new Class[]{byte[].class},data);}
    private JSONObject json(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private byte[] bytes(JSONObject json){return json.toString().getBytes(StandardCharsets.UTF_8);}
    private String id(Object obj)throws Exception{return (String)f(obj,"id");}
    private Object findByName(Object db,String name)throws Exception{for(Object p:platforms(db,true))if(name.equals(f(p,"name")))return p;return null;}
    private boolean contains(List<?> objects,String id)throws Exception{for(Object o:objects)if(id.equals(id(o)))return true;return false;}
    private long total(Object db,String name)throws Exception{long sum=0;for(Object e:rows(db))sum+=(Long)f(e,name);return sum;}
    private Object f(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private void s(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(target,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;throw e;}}
    private void check(boolean condition,String name){if(!condition)throw new AssertionError(name);result.count++;result.log.append("PASS ").append(name).append('\n');}
    private void reject(Checked action,String name)throws Exception{try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){check(true,name);return;}throw new AssertionError("Expected rejection: "+name);}
    private void remove(File f){if(f.isDirectory()){File[] files=f.listFiles();if(files!=null)for(File child:files)remove(child);}f.delete();}
    private static final class IsolatedContext extends ContextWrapper {
        private final File folder;
        IsolatedContext(Context base,File folder){super(base);this.folder=folder;folder.mkdirs();}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getDatabasePath(String name){return new File(folder,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openDatabase(getDatabasePath(name).getAbsolutePath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
        @Override public boolean deleteDatabase(String name){return SQLiteDatabase.deleteDatabase(getDatabasePath(name));}
    }
}
