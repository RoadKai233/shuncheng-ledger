package cn.personal.shunfeng.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only runtime checks. Test APK is not included in the delivered app. */
public final class RuntimeChecks extends Instrumentation {
    private int passed;
    private Activity activity;
    private Object db;
    private Class<?> entryClass,platformClass;
    private final List<String> createdPlatformIds = new ArrayList<>();
    private final List<String> createdIds = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private String screenshotPath,lastNightDiagnostics="";
    private boolean visualOnly,themeOnly,sessionOnly,updateOnly,updateRecoveryOnly;
    private String testPin;
    private SharedPreferences updatePreferences;private boolean hadAutomaticPreference,previousAutomaticPreference;
    private interface Checked { void run() throws Exception; }
    private static final Class<?>[] NONE = new Class<?>[0];
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);visualOnly=arguments!=null&&"true".equals(arguments.getString("visual_only"));themeOnly=arguments!=null&&"true".equals(arguments.getString("theme_only"));sessionOnly=arguments!=null&&"true".equals(arguments.getString("session_only"));testPin=arguments==null?null:arguments.getString("test_pin");updateOnly=arguments!=null&&"true".equals(arguments.getString("update_only"));updateRecoveryOnly=arguments!=null&&"true".equals(arguments.getString("update_recovery_only"));start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")))
                throw new IllegalStateException("Emulator-only tests: refusing to change a physical device's data.");
            if(!((PowerManager)getTargetContext().getSystemService(Context.POWER_SERVICE)).isInteractive()||((KeyguardManager)getTargetContext().getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked())throw new IllegalStateException("Wake and unlock the emulator before UI tests; sleeping activities defer real Configuration updates.");
            if((sessionOnly||updateOnly||updateRecoveryOnly)&&(testPin==null||!testPin.matches("[0-9]{4,12}")))throw new IllegalArgumentException("Session and updater UI checks require test_pin for a disposable emulator; no default credential is provided.");
            updatePreferences=getTargetContext().getSharedPreferences("MainActivity",Context.MODE_PRIVATE);hadAutomaticPreference=updatePreferences.contains("automatic_updates");previousAutomaticPreference=updatePreferences.getBoolean("automatic_updates",true);updatePreferences.edit().putBoolean("automatic_updates",false).commit();
            ClassLoader loader = getTargetContext().getClassLoader();
            entryClass = loader.loadClass("cn.personal.shunfeng.LedgerDb$Entry");platformClass=loader.loadClass("cn.personal.shunfeng.LedgerDb$Platform");
            db = loader.loadClass("cn.personal.shunfeng.LedgerDb").getConstructor(Context.class).newInstance(getTargetContext());
            if(updateRecoveryOnly){activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();ui(()->check(!(Boolean)field(activity,"unlocked")&&field(activity,"downloadedUpdate")==null,"Process restart does not restore authorization or expose cached update before PIN"));completeUpdateAuthentication();updateRecoveryChecks();}
            else if(updateOnly){activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();completeUpdateAuthentication();waitForUpdateWork();updateUiChecks();}
            else if(sessionOnly){activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();completeSystemPromptForTest();sessionChecks();}
            else if(themeOnly){activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();cancelSystemPromptForTest();save(entry("机场 → 市区",12880,1800,0));save(entry("家附近 → 公司附近",4520,600,0));save(entry("高铁站 → 科技园",8600,1200,1));save(entry("市中心 → 大学城",6200,800,3));themeChecks();}
            else if(visualOnly) { visualSnapshotsOnly(); }
            else {PlatformChecks.Result platformResult=PlatformChecks.run(getTargetContext());passed+=platformResult.count;log.append(platformResult.log);
            databaseChecks(); cryptoChecks(loader); uiChecks(); themeChecks();}
            result.putString("status", "PASS");
        } catch (Throwable e) {
            code = Activity.RESULT_CANCELED;
            StringWriter trace = new StringWriter(); e.printStackTrace(new PrintWriter(trace));
            result.putString("status", "FAIL"); result.putString("error", trace.toString());
        } finally {
            try { if(db!=null) for(String id:createdIds) call(db,"delete",new Class[]{String.class},id); } catch(Exception ignored) {}
            try { if(db!=null)for(String id:createdPlatformIds){boolean visible=false;for(Object p:(List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true))if(id.equals(field(p,"id")))visible=true;if(visible)call(db,"deletePlatform",new Class[]{String.class,String.class},id,null);} }catch(Exception ignored){}
            try { if(activity!=null) ui(()->activity.finish()); } catch(Exception ignored) {}
            if(updatePreferences!=null){SharedPreferences.Editor restore=updatePreferences.edit();if(hadAutomaticPreference)restore.putBoolean("automatic_updates",previousAutomaticPreference);else restore.remove("automatic_updates");restore.commit();}
        }
        result.putInt("passed", passed);result.putString("checks",log.toString());
        if(screenshotPath!=null)result.putString("screenshot",screenshotPath);
        result.putString("stream", "\n"+result.getString("status")+": "+passed+" runtime checks.\n"+log+"\n"+result.getString("error", ""));
        finish(code,result);
    }
    private Object entry(String route,long amount,long cost,int daysAgo) throws Exception {
        Object e=entryClass.getConstructor().newInstance();createdIds.add((String)field(e,"id"));
        set(e,"route",route);set(e,"income",amount);set(e,"cost",cost);set(e,"date",LocalDate.now().minusDays(daysAgo).toString());set(e,"time","08:30");set(e,"platform","哈啰");
        return e;
    }
    private void save(Object e)throws Exception{call(db,"save",new Class[]{entryClass},e);}
    private Object get(String id)throws Exception{return call(db,"get",new Class[]{String.class},id);}
    private List<?> rows()throws Exception{return (List<?>)call(db,"all",NONE);}
    private void databaseChecks() throws Exception {
        int initial=rows().size();
        Object e=entry("运行验证测试路线",12880,1880,0);String id=(String)field(e,"id");save(e);
        check(rows().size()==initial+1,"SQLite inserts one record");
        Object stored=get(id);check(((Long)field(stored,"income"))==12880L,"SQLite preserves exact cents");
        check(((Long)field(stored,"cost"))==1880L,"SQLite preserves cost");
        set(e,"income",12990L);save(e);check(rows().size()==initial+1,"Update does not duplicate record");
        check(((Long)field(get(id),"income"))==12990L,"Update persists new amount");
        set(e,"income",0L);reject(()->save(e),"Zero income rejected before DB write");
        check(((Long)field(get(id),"income"))==12990L,"Invalid update leaves existing record unchanged");
        set(e,"income",12990L);set(e,"note","=HYPERLINK(\"https://example.invalid\")");save(e);
        String csv=(String)call(db,"exportCsv",NONE);check(csv.startsWith("\uFEFF日期"),"CSV is UTF-8 BOM compatible");check(csv.contains("\"'=HYPERLINK"),"CSV neutralizes formula notes");
        byte[] backup=(byte[])call(db,"exportJson",NONE);JSONObject document=new JSONObject(new String(backup,StandardCharsets.UTF_8));
        check(document.getInt("version")==2,"JSON backup has v2 schema");
        check(((Integer)call(db,"importJson",new Class[]{byte[].class},backup))==0,"Import skips all existing IDs");
        JSONObject original=null;JSONArray entries=document.getJSONArray("entries");for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("id").equals(id))original=entries.getJSONObject(i);
        if(original==null)throw new AssertionError("Export omitted test entry");
        JSONObject a=new JSONObject(original.toString());String aid=UUID.randomUUID().toString();createdIds.add(aid);a.put("id",aid);
        JSONObject b=new JSONObject(original.toString());String bid=UUID.randomUUID().toString();createdIds.add(bid);b.put("id",bid);b.put("income",1.5);
        byte[] invalid=doc(new JSONArray().put(a).put(b));
        reject(()->call(db,"importJson",new Class[]{byte[].class},invalid),"Fractional cents rejected");
        check(get(aid)==null&&get(bid)==null,"Invalid second row imports no first row");
        b.put("income","12990");reject(()->call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(b))),"Numeric strings rejected");
        b.put("income",12990);b.put("date","2023-02-29");reject(()->call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(b))),"Invalid calendar date rejected");
        b.put("date",LocalDate.now().toString());b.put("route",JSONObject.NULL);reject(()->call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(b))),"Null text field rejected");
        reject(()->call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(a).put(a))),"Duplicate IDs within backup rejected");
        check(get(aid)==null,"Duplicate backup rejection leaves database untouched");
        check(((Integer)call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(a))))==1,"Valid missing record imported");
        check(((Integer)call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(a))))==0,"Second import is idempotent");
        check(((Long)field(get(aid),"income"))==12990L,"Imported amount matches backup");
        a.put("income",9999);call(db,"importJson",new Class[]{byte[].class},doc(new JSONArray().put(a)));check(((Long)field(get(aid),"income"))==12990L,"Duplicate import never overwrites existing amount");
        reject(()->call(db,"importJson",new Class[]{byte[].class},"{} extra".getBytes(StandardCharsets.UTF_8)),"Trailing invalid JSON rejected");
        reject(()->call(db,"importJson",new Class[]{byte[].class},new byte[]{(byte)0xc3,0x28}),"Malformed UTF-8 rejected");
        call(db,"delete",new Class[]{String.class},id);call(db,"delete",new Class[]{String.class},aid);check(rows().size()==initial,"Delete removes test records only");
    }
    private byte[] doc(JSONArray entries)throws Exception{return new JSONObject().put("format","shunlu-ledger").put("version",1).put("entries",entries).toString().getBytes(StandardCharsets.UTF_8);}
    private void cryptoChecks(ClassLoader loader)throws Exception {
        Class<?> crypto=loader.loadClass("cn.personal.shunfeng.BackupCrypto");
        Method encrypt=crypto.getMethod("encrypt",byte[].class,char[].class),decrypt=crypto.getMethod("decrypt",byte[].class,char[].class);
        byte[] plain="真实 Android 密码备份往返，收入128.80".getBytes(StandardCharsets.UTF_8);char[] password="android-test-pass-001".toCharArray();
        byte[] blob=(byte[])invoke(encrypt,null,plain,password);
        check(Arrays.equals(plain,(byte[])invoke(decrypt,null,blob,password)),"Android PBKDF2/AES-GCM round trip");
        byte[] again=(byte[])invoke(encrypt,null,plain,password);check(!Arrays.equals(blob,again),"Android encryption uses random salt and IV");
        reject(()->invoke(decrypt,null,blob,"incorrect-password".toCharArray()),"Android rejects wrong backup password");
        blob[blob.length-1]^=1;reject(()->invoke(decrypt,null,blob,password),"Android rejects damaged authentication tag");Arrays.fill(password,'\0');
    }
    private void uiChecks()throws Exception {
        Intent launch=new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=startActivitySync(launch);waitForIdleSync();cancelSystemPromptForTest();
        ui(()->{check(!(Boolean)field(activity,"unlocked"),"App starts locked");check((Boolean)field(activity,"gate"),"Locked app displays gate");check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"App window blocks screenshots by default");unlockForTest();});
        int before=rows().size();
        ui(()->{TextView add=findText((View)field(activity,"root"),"＋  记一笔收入");check(add!=null,"Add income button is reachable");add.performClick();((EditText)field(activity,"fareInput")).setText("68.80");((EditText)field(activity,"costInput")).setText("8.80");((EditText)field(activity,"routeInput")).setText("运行验证新增路线");findText((View)field(activity,"root"),"保存这一笔").performClick();});
        waitForIdleSync();check(rows().size()==before+1,"UI saves a new trip");Object uiEntry=null;for(Object e:rows())if("运行验证新增路线".equals(field(e,"route")))uiEntry=e;
        if(uiEntry==null)throw new AssertionError("UI record not found");String uiId=(String)field(uiEntry,"id");createdIds.add(uiId);check(((Long)field(uiEntry,"income"))==6880L,"UI amount stored in cents");
        final Object selected=uiEntry;
        ui(()->{call(activity,"showForm",new Class[]{entryClass},selected);((EditText)field(activity,"fareInput")).setText("72.50");call(activity,"saveEntry",NONE);});
        check(((Long)field(get(uiId),"income"))==7250L,"UI edits existing trip");
        final AlertDialog[] stale=new AlertDialog[1];final Button[] staleButton=new Button[1];
        ui(()->{call(activity,"showForm",new Class[]{entryClass},get(uiId));findText((View)field(activity,"root"),"删除这笔记录").performClick();Set<?> dialogs=(Set<?>)field(activity,"openDialogs");check(dialogs.size()==1,"Delete confirmation is tracked");stale[0]=(AlertDialog)dialogs.iterator().next();staleButton[0]=stale[0].getButton(AlertDialog.BUTTON_POSITIVE);check(stale[0].isShowing(),"Delete confirmation is visible");call(activity,"onPause",NONE);check(!(Boolean)field(activity,"unlocked"),"Pausing Activity immediately relocks app");call(activity,"onStop",NONE);check(!(Boolean)field(activity,"unlocked"),"Stopping Activity keeps app locked");check(!stale[0].isShowing(),"Lock dismisses delete confirmation");check(((Set<?>)field(activity,"openDialogs")).isEmpty(),"Lock clears tracked dialogs");cancelAuthenticationForTest();staleButton[0].performClick();});
        waitForIdleSync();check(get(uiId)!=null,"Stale delete callback cannot change locked database");
        ui(()->{call(activity,"showHome",NONE);check((Boolean)field(activity,"gate"),"showHome refuses access while locked");call(activity,"showForm",new Class[]{entryClass},selected);check((Boolean)field(activity,"gate"),"showForm refuses access while locked");call(activity,"passwordPrompt",new Class[]{boolean.class,byte[].class},true,null);check(((Set<?>)field(activity,"openDialogs")).isEmpty(),"Password prompt refuses access while locked");unlockForTest();call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("123.45");((EditText)field(activity,"costInput")).setText("12.30");((EditText)field(activity,"routeInput")).setText("旋转草稿路线");((EditText)field(activity,"noteInput")).setText("未保存的草稿");});
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        ui(()->{check("123.45".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Live rotation preserves income draft");});
        recreate();cancelSystemPromptForTest();
        ui(()->{check(!(Boolean)field(activity,"unlocked"),"Rotation recreates app in locked state");unlockForTest();check("123.45".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Rotation preserves income draft after reauth");check("12.30".equals(((EditText)field(activity,"costInput")).getText().toString()),"Rotation preserves cost draft");check("旋转草稿路线".equals(((EditText)field(activity,"routeInput")).getText().toString()),"Rotation preserves route draft");check("未保存的草稿".equals(((EditText)field(activity,"noteInput")).getText().toString()),"Rotation preserves note draft");});
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);ui(()->{unlockForTest();call(activity,"showHome",NONE);});
        platformUiChecks();
        Object sample1=entry("机场 → 市区",12880,1800,0), sample2=entry("家附近 → 公司附近",4520,600,0),sample3=entry("高铁站 → 科技园",8600,1200,1),sample4=entry("市中心 → 大学城",6200,800,3);
        save(sample1);save(sample2);save(sample3);save(sample4);
        navigationChecks();
        ui(()->{set(activity,"tab",0);call(activity,"showHome",NONE);activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);});waitForIdleSync();Thread.sleep(300);
        File screenshot=new File(getTargetContext().getExternalFilesDir(null),"screenshot.png");
        ui(()->{View view=activity.getWindow().getDecorView();check(view.getWidth()>0&&view.getHeight()>0,"Android UI has nonzero viewport");Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream output=new FileOutputStream(screenshot)){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Android UI screenshot captured");}bitmap.recycle();activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);});screenshotPath=screenshot.getAbsolutePath();
        ui(()->{call(activity,"showPlatforms",NONE);activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);});waitForIdleSync();Thread.sleep(200);
        File platformShot=new File(getTargetContext().getExternalFilesDir(null),"platform-management.png");
        ui(()->{View view=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream output=new FileOutputStream(platformShot)){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Platform management screenshot captured");}bitmap.recycle();activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);});
        ui(()->{set(activity,"tab",0);call(activity,"showHome",NONE);});captureOverviewScroll();scrollBottom();captureVisual("glass-overview-bottom.png");
        ui(()->{set(activity,"tab",1);set(activity,"allMonths",true);call(activity,"showHome",NONE);check(findText((View)field(activity,"root"),"收入明细")!=null,"Records tab renders");});captureVisual("glass-records.png");scrollBottom();captureVisual("glass-records-bottom.png");
        ui(()->{set(activity,"tab",2);call(activity,"showHome",NONE);check(findText((View)field(activity,"root"),"保存加密备份")!=null,"Backup action is reachable");});captureVisual("glass-settings.png");scrollBottom();captureVisual("glass-settings-bottom.png");
        ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);});scrollBottom();captureVisual("glass-entry-buttons.png");
        ui(()->{EditText fare=(EditText)field(activity,"fareInput");fare.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(fare,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});Thread.sleep(700);captureVisual("glass-entry-keyboard.png");
        ui(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));Thread.sleep(700);captureVisual("glass-entry-landscape.png");
        ui(()->{call(activity,"hideKeyboard",NONE);activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});Thread.sleep(500);
        ui(()->{set(activity,"tab",2);call(activity,"showHome",NONE);findText((View)field(activity,"root"),"立即锁定账本").performClick();check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate"),"Manual lock returns to locked gate");});
    }
    private void updateRecoveryChecks()throws Exception{
        waitForUpdateWork();waitForIdleSync();
        ui(()->{check(!(Boolean)call(activity,"automaticUpdates",NONE),"Downloaded update recovery works while automatic checking is disabled");check(field(activity,"downloadedRelease")!=null&&field(activity,"downloadedUpdate")!=null,"Fresh process restores verified download metadata and APK");check(activeDialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("继续安装"),"Verified cached download offers installation only after successful PIN");});captureDialogVisual("update-restored-ready.png");
        check(activity.getPackageManager().canRequestPackageInstalls(),"Unknown-source permission survives the process killed by Android");
        ui(()->activeDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitForUpdateWork();check(waitAccessibilityText("安装","Install")!=null,"Restored APK reaches Android's real install confirmation through its private provider");
        ui(()->check(!(Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"foreground"),"Resumed update installer locks and hides the ledger"));captureVisual("update-android-installer.png");
        android.view.accessibility.AccessibilityNodeInfo cancel=waitAccessibilityText("取消","Cancel");if(cancel!=null)cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);else shellText("input keyevent 4");Thread.sleep(500);waitForIdleSync();cancelSystemPromptForTest();
        check(activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode()==7,"Cancel keeps the real installed release at version code 7");
        new File(getTargetContext().getCacheDir(),"updates/verified-update.json").delete();new File(getTargetContext().getCacheDir(),"updates/verified-update.apk").delete();
    }
    private void updateUiChecks()throws Exception {
        final int previousMinutes=(Integer)field(activity,"verificationMinutes");
        File candidate=new File(getTargetContext().getFilesDir(),"e2e-update-v8.apk"),finalApk=null,originalApk=null;
        try {
            ui(()->{call(activity,"setVerificationMinutes",new Class[]{int.class},0);activity.getPreferences(Context.MODE_PRIVATE).edit().remove("automatic_updates").putLong("update_checked_at",System.currentTimeMillis()).commit();check((Boolean)call(activity,"automaticUpdates",NONE),"Automatic update checking defaults to enabled");set(activity,"tab",2);call(activity,"showHome",NONE);TextView settings=findText((View)field(activity,"root"),"应用更新");check(settings!=null,"Update settings are reachable from My Account");settings.performClick();});waitForIdleSync();
            ui(()->{AlertDialog menu=activeDialog();check(menu.getListView().getCount()==5,"Update menu exposes check, toggle, repository, about and resume-install actions");menu.getListView().performItemClick(null,1,1);});waitForIdleSync();ui(()->check(!(Boolean)call(activity,"automaticUpdates",NONE)&&!activity.getPreferences(Context.MODE_PRIVATE).getBoolean("automatic_updates",true),"Update toggle persists disabled state"));
            ui(()->call(activity,"showUpdateSettings",NONE));waitForIdleSync();ui(()->activeDialog().getListView().performItemClick(null,1,1));waitForIdleSync();ui(()->{check((Boolean)call(activity,"automaticUpdates",NONE),"Update toggle can enable automatic checks again");activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("automatic_updates",false).putLong("update_checked_at",1L).commit();call(activity,"showUpdateSettings",NONE);});waitForIdleSync();
            final int[] backupDone={0};final Runnable backup=()->backupDone[0]++;
            ui(()->{activeDialog().getListView().performItemClick(null,0,0);check((Boolean)field(activity,"updateWorking")&&activity.getPreferences(Context.MODE_PRIVATE).getLong("update_checked_at",0)>1,"Manual check starts even when automatic checking is disabled");check(!(Boolean)field(activity,"busy"),"Network update check does not occupy the backup worker state");set(activity,"afterUnlock",backup);call(activity,"onPause",NONE);});waitForUpdateWork();
            ui(()->{check(!(Boolean)field(activity,"unlocked")&&((Set<?>)field(activity,"openDialogs")).isEmpty(),"Update completion does not show private UI while app is backgrounded");check(field(activity,"afterUnlock")==backup,"Update completion preserves an unrelated backup continuation");check(field(activity,"pendingUpdateUi")!=null,"Manual-check result waits for unlocked foreground UI");call(activity,"onResume",NONE);});completeUpdateAuthentication();waitForIdleSync();
            ui(()->{check(backupDone[0]==1&&field(activity,"afterUnlock")==null,"Reauthentication resumes the original backup continuation once");for(Object item:new ArrayList<>((Set<?>)field(activity,"openDialogs")))((Dialog)item).dismiss();});waitForIdleSync();
            final int[] notifications={0};ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("96.50");call(activity,"queueUpdateUi",new Class[]{Runnable.class},(Runnable)()->notifications[0]++);check(notifications[0]==0&&field(activity,"pendingUpdateUi")!=null,"Update prompt waits while an unsaved entry is open");check("96.50".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Queued update leaves unsaved amount intact");call(activity,"showHome",NONE);call(activity,"resumeUpdateUi",NONE);call(activity,"resumeUpdateUi",NONE);check(notifications[0]==1&&field(activity,"pendingUpdateUi")==null,"Deferred update prompt is consumed exactly once on returning home");});
            final ListView[] staleMenu={null};ui(()->{call(activity,"showUpdateSettings",NONE);staleMenu[0]=activeDialog().getListView();call(activity,"onPause",NONE);unlockForTest();call(activity,"onResume",NONE);staleMenu[0].performItemClick(null,1,1);check(!(Boolean)call(activity,"automaticUpdates",NONE),"Dismissed update menu cannot change settings after relocking and reauth");});
            if(!candidate.isFile())throw new AssertionError("Push the isolated same-signer code+1 APK to the target private files directory as e2e-update-v8.apk before updater UI checks.");
            final Class<?> updateClass=getTargetContext().getClassLoader().loadClass("cn.personal.shunfeng.UpdateClient"),releaseType=getTargetContext().getClassLoader().loadClass("cn.personal.shunfeng.UpdateClient$Release");
            android.content.pm.PackageInfo apkInfo=activity.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(),0);
            JSONObject manifest=new JSONObject().put("versionCode",apkInfo.getLongVersionCode()).put("versionName",apkInfo.versionName).put("apkUrl","https://github.com/RoadKai233/shuncheng-ledger/releases/download/test-fixture/e2e.apk").put("size",candidate.length()).put("sha256",invoke(updateClass.getMethod("sha256",File.class),null,candidate)).put("notes","Emulator-only installation confirmation test; cancel installation.");
            final Object release=invoke(updateClass.getMethod("parseManifest",String.class),null,manifest.toString());
            invoke(updateClass.getMethod("verifyDownloadedApk",Context.class,releaseType,File.class),null,getTargetContext(),release,candidate);check(true,"Real code+1 candidate passes the same production verifier before installer UI testing");
            File folder=new File(getTargetContext().getCacheDir(),"updates");if(!folder.exists()&&!folder.mkdirs())throw new IOException("Cannot prepare updater test cache");finalApk=new File(folder,"verified-update.apk");
            if(finalApk.exists()){originalApk=new File(folder,"e2e-original-"+System.nanoTime()+".apk");if(!finalApk.renameTo(originalApk))throw new IOException("Cannot preserve prior downloaded update");}
            try(FileOutputStream out=new FileOutputStream(finalApk)){out.write(readFile(candidate));}final File prepared=finalApk;
            final Button[] staleDownload={null};ui(()->{call(activity,"showAvailableUpdate",new Class[]{releaseType},release);staleDownload[0]=activeDialog().getButton(AlertDialog.BUTTON_POSITIVE);call(activity,"onPause",NONE);unlockForTest();call(activity,"onResume",NONE);staleDownload[0].performClick();check(!(Boolean)field(activity,"updateWorking")&&field(activity,"downloadedUpdate")==null,"Dismissed download confirmation cannot start work after relocking");set(activity,"downloadedUpdate",prepared);set(activity,"downloadedRelease",release);});
            ui(()->{call(activity,"showInstallReady",NONE);});waitForIdleSync();captureDialogVisual("update-ready.png");
            check(activity.getPackageManager().canRequestPackageInstalls(),"Previously approved Android unknown-source authorization is available after process restart");
            // The real APK is reverified, then Android displays its own confirmation. Never click Install.
            ui(()->activeDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitForUpdateWork();
            android.view.accessibility.AccessibilityNodeInfo install=waitAccessibilityText("安装","Install");check(install!=null,"Android package installer shows an explicit Install confirmation");
            ui(()->check(!(Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"foreground"),"Opening system installer locks the ledger again"));captureVisual("update-android-installer.png");
            android.view.accessibility.AccessibilityNodeInfo cancel=waitAccessibilityText("取消","Cancel");if(cancel!=null)cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);else shellText("input keyevent 4");Thread.sleep(500);waitForIdleSync();cancelSystemPromptForTest();
            check(activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode()<apkInfo.getLongVersionCode(),"Installer cancellation leaves the installed release unchanged");
        }finally{
            try{ui(()->{set(activity,"pendingUpdateUi",null);set(activity,"downloadedUpdate",null);set(activity,"downloadedRelease",null);set(activity,"afterUnlock",null);unlockForTest();call(activity,"setVerificationMinutes",new Class[]{int.class},previousMinutes);activity.getPreferences(Context.MODE_PRIVATE).edit().putLong("update_checked_at",System.currentTimeMillis()).commit();call(activity,"lockImmediately",NONE);});}catch(Exception ignored){}
            if(finalApk!=null)finalApk.delete();if(originalApk!=null&&originalApk.exists()&&!originalApk.renameTo(finalApk))throw new IOException("Could not restore prior update cache");
        }
    }
    private void waitForUpdateWork()throws Exception{long deadline=SystemClock.uptimeMillis()+55000;while((Boolean)field(activity,"updateWorking")&&SystemClock.uptimeMillis()<deadline){Thread.sleep(100);waitForIdleSync();}if((Boolean)field(activity,"updateWorking"))throw new AssertionError("Update worker exceeded its bounded deadline");waitForIdleSync();}
    private void completeUpdateAuthentication()throws Exception{
        long deadline=SystemClock.uptimeMillis()+8000;final boolean[] ready={false};
        while(SystemClock.uptimeMillis()<deadline){ui(()->ready[0]=(Boolean)field(activity,"authPending")&&!activity.hasWindowFocus());if(ready[0])break;Thread.sleep(100);}if(!ready[0])throw new AssertionError("Expected real system verification prompt");Thread.sleep(300);shellText("input text "+testPin);shellText("input keyevent 66");deadline=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<deadline){waitForIdleSync();ui(()->ready[0]=(Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"authPending")&&(Boolean)field(activity,"foreground"));if(ready[0])return;Thread.sleep(100);}throw new AssertionError("System authentication did not return to unlocked foreground ledger");
    }
    private android.view.accessibility.AccessibilityNodeInfo waitAccessibilitySwitch()throws Exception{long until=SystemClock.uptimeMillis()+6000;do{android.view.accessibility.AccessibilityNodeInfo result=findAccessibility(getUiAutomation().getRootInActiveWindow(),null,true);if(result!=null)return result;Thread.sleep(100);}while(SystemClock.uptimeMillis()<until);return null;}
    private android.view.accessibility.AccessibilityNodeInfo waitAccessibilityText(String... labels)throws Exception{long until=SystemClock.uptimeMillis()+6000;do{for(String label:labels){android.view.accessibility.AccessibilityNodeInfo result=findAccessibility(getUiAutomation().getRootInActiveWindow(),label,false);if(result!=null)return result;}Thread.sleep(100);}while(SystemClock.uptimeMillis()<until);return null;}
    private android.view.accessibility.AccessibilityNodeInfo findAccessibility(android.view.accessibility.AccessibilityNodeInfo node,String text,boolean toggle){if(node==null)return null;if(toggle&&node.getClassName()!=null&&node.getClassName().toString().contains("Switch"))return node;if(!toggle&&text.contentEquals(node.getText()==null?"":node.getText()))return node;for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=findAccessibility(node.getChild(i),text,toggle);if(child!=null)return child;}return null;}
    private void sessionChecks()throws Exception {
        if(!((KeyguardManager)getTargetContext().getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure())throw new IllegalStateException("Session checks require the emulator's real demonstration PIN.");
        final int previousMinutes=(Integer)field(activity,"verificationMinutes");final String previousAppearance=(String)field(activity,"appearance");
        final int previousNight=((UiModeManager)getTargetContext().getSystemService(Context.UI_MODE_SERVICE)).getNightMode();final String beforeData=ledgerSnapshot();
        boolean pinRemoved=false;
        try {
            ui(()->{unlockForTest();check((Boolean)call(activity,"setVerificationMinutes",new Class[]{int.class},0),"Every-entry verification is accepted");check(((Long)field(activity,"lastVerifiedAt"))==-1L&&!(Boolean)call(activity,"isVerificationWindowOpen",NONE),"Zero-minute setting clears cached verification");set(activity,"tab",2);call(activity,"showHome",NONE);TextView settings=findText((View)field(activity,"root"),"验证有效期");check(settings!=null,"Verification duration settings are reachable");settings.performClick();});waitForIdleSync();
            ui(()->{ListView choices=activeDialog().getListView();check(choices.getCount()==6&&"每次进入都验证".equals(choices.getItemAtPosition(0).toString())&&"1 分钟".equals(choices.getItemAtPosition(1).toString())&&"5 分钟".equals(choices.getItemAtPosition(2).toString())&&"15 分钟".equals(choices.getItemAtPosition(3).toString())&&"30 分钟".equals(choices.getItemAtPosition(4).toString()),"Verification settings expose every-entry, four presets and custom input");});captureDialogVisual("verification-settings.png");
            ui(()->{ListView choices=activeDialog().getListView();choices.performItemClick(choices.getChildAt(5),5,choices.getItemIdAtPosition(5));});waitForIdleSync();
            for(String invalid:new String[]{"","0","1441","-1","1.5","abcd","999999999999"}){
                ui(()->{AlertDialog dialog=activeDialog();EditText input=findEdit(dialog.getWindow().getDecorView());input.setText(invalid);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();check(dialog.isShowing()&&input.getError()!=null&&((Integer)field(activity,"verificationMinutes"))==0,"Custom duration rejects invalid input: "+(invalid.isEmpty()?"empty":invalid));});
            }
            ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("1");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();
            ui(()->check(((Integer)field(activity,"verificationMinutes"))==1&&activity.getPreferences(Context.MODE_PRIVATE).getInt("verification_minutes",-1)==1,"Minimum custom duration saves one minute"));
            ui(()->call(activity,"showCustomVerificationTime",NONE));waitForIdleSync();ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("1440");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();
            ui(()->check(((Integer)field(activity,"verificationMinutes"))==1440&&activity.getPreferences(Context.MODE_PRIVATE).getInt("verification_minutes",-1)==1440,"Maximum custom duration saves 1440 minutes"));
            ui(()->{check(!(Boolean)call(activity,"setVerificationMinutes",new Class[]{int.class},-1)&&!(Boolean)call(activity,"setVerificationMinutes",new Class[]{int.class},1441)&&((Integer)field(activity,"verificationMinutes"))==1440,"Setter rejects out-of-range durations without changing preference");call(activity,"showCustomVerificationTime",NONE);});waitForIdleSync();ui(()->findEdit(activeDialog().getWindow().getDecorView()).setText("12"));captureDialogVisual("verification-custom.png");ui(()->activeDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitForIdleSync();captureVisual("verification-account.png");
            ui(()->{call(activity,"setVerificationMinutes",new Class[]{int.class},5);unlockForTest();});final long authenticatedAt=(Long)field(activity,"lastVerifiedAt");
            ui(()->{check(authenticatedAt>=0&&SystemClock.elapsedRealtime()-authenticatedAt<2000L,"Successful authentication records monotonic verification time");check((Boolean)call(activity,"hasReusableVerification",NONE),"Active window is reusable on a secure, unlocked and awake phone");call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("172.80");((EditText)field(activity,"costInput")).setText("12.80");((EditText)field(activity,"routeInput")).setText("验证窗口草稿");((EditText)field(activity,"noteInput")).setText("返回后继续编辑");call(activity,"onPause",NONE);check(!(Boolean)field(activity,"unlocked")&&((View)field(activity,"root")).getVisibility()!=View.VISIBLE,"Backgrounding hides ledger even within reusable verification window");call(activity,"onStop",NONE);call(activity,"onResume",NONE);check((Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"authPending"),"Returning inside window unlocks without opening system verification");check("172.80".equals(((EditText)field(activity,"fareInput")).getText().toString())&&"返回后继续编辑".equals(((EditText)field(activity,"noteInput")).getText().toString()),"Cached verification preserves unsaved form fields");});
            for(int i=0;i<2;i++)ui(()->{call(activity,"onPause",NONE);call(activity,"onResume",NONE);});
            ui(()->check(((Long)field(activity,"lastVerifiedAt"))==authenticatedAt,"Repeated returns never slide the authentication window"));
            ui(()->{call(activity,"setVerificationMinutes",new Class[]{int.class},15);check(((Long)field(activity,"lastVerifiedAt"))==authenticatedAt,"Changing positive duration does not renew successful-authentication time");});
            recreate();waitForIdleSync();ui(()->{check((Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"authPending"),"Activity recreation reuses an unexpired in-process verification");check(((Integer)field(activity,"verificationMinutes"))==15&&((Long)field(activity,"lastVerifiedAt"))==authenticatedAt,"Activity recreation preserves duration and original authentication time");});
            ui(()->{call(activity,"setAppearance",new Class[]{String.class},"system");});systemNight("no");
            final String[] selectedPlatform={null};ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("172.80");((EditText)field(activity,"routeInput")).setText("后台换主题草稿");selectedPlatform[0]=(String)call(activity,"selectedFormPlatformId",NONE);call(activity,"onPause",NONE);});systemNight("yes");
            ui(()->{check((Boolean)field(activity,"gate")&&!(Boolean)field(activity,"unlocked"),"Background theme update renders a secure gate");call(activity,"onResume",NONE);check((Boolean)field(activity,"unlocked")&&"172.80".equals(((EditText)field(activity,"fareInput")).getText().toString())&&"后台换主题草稿".equals(((EditText)field(activity,"routeInput")).getText().toString())&&selectedPlatform[0].equals(call(activity,"selectedFormPlatformId",NONE)),"Cached return after background theme change restores draft and platform");check(((Long)field(activity,"lastVerifiedAt"))==authenticatedAt,"Theme refresh does not renew verification timestamp");});
            final int[] resumedAction={0};ui(()->{call(activity,"onPause",NONE);set(activity,"afterUnlock",(Runnable)()->resumedAction[0]++);call(activity,"onResume",NONE);call(activity,"onResume",NONE);check(resumedAction[0]==1&&field(activity,"afterUnlock")==null,"Cached unlock executes deferred completion exactly once");});
            final int platformCount=((List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true)).size();final Button[] stale={null};
            ui(()->{call(activity,"platformNameDialog",new Class[]{platformClass,boolean.class},null,true);AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("免验证不允许后台写入");stale[0]=dialog.getButton(AlertDialog.BUTTON_POSITIVE);call(activity,"onPause",NONE);check(!dialog.isShowing()&&((Set<?>)field(activity,"openDialogs")).isEmpty(),"Cached session still dismisses private dialogs when backgrounded");stale[0].performClick();});
            check(((List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true)).size()==platformCount,"Cached session does not authorize stale background dialog callbacks");ui(()->call(activity,"onResume",NONE));ui(()->stale[0].performClick());check(((List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true)).size()==platformCount,"Resumed cached session still rejects a dismissed platform dialog callback");
            final Button[] staleDuration={null};final int oldDuration=(Integer)field(activity,"verificationMinutes");ui(()->{call(activity,"showCustomVerificationTime",NONE);AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("30");staleDuration[0]=dialog.getButton(AlertDialog.BUTTON_POSITIVE);call(activity,"onPause",NONE);call(activity,"onResume",NONE);staleDuration[0].performClick();check(((Integer)field(activity,"verificationMinutes"))==oldDuration,"Resumed cached session rejects a dismissed duration-settings callback");});
            final DatePickerDialog[] staleDate={null};final String[] originalDate={null};ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);originalDate[0]=(String)field(activity,"formDate");((Button)field(activity,"dateField")).performClick();staleDate[0]=(DatePickerDialog)activeDialog();call(activity,"onPause",NONE);call(activity,"onResume",NONE);staleDate[0].getDatePicker().updateDate(2001,0,1);staleDate[0].onClick(staleDate[0],DialogInterface.BUTTON_POSITIVE);check(originalDate[0].equals(field(activity,"formDate")),"Resumed cached session rejects a dismissed date-picker callback");});
            ui(()->{((Button)field(activity,"dateField")).performClick();DatePickerDialog date=(DatePickerDialog)activeDialog();date.updateDate(2002,1,3);date.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();ui(()->check("2002-02-03".equals(field(activity,"formDate")),"Current date-picker confirmation still updates the form"));
            ui(()->{((Button)field(activity,"timeField")).performClick();TimePickerDialog time=(TimePickerDialog)activeDialog();time.updateTime(14,35);time.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();ui(()->check("14:35".equals(field(activity,"formTime")),"Current time-picker confirmation still updates the form"));
            final File exported=new File(getTargetContext().getCacheDir(),"session-return-export.txt");final byte[] exportBytes="local verification-session export".getBytes(StandardCharsets.UTF_8);
            ui(()->{set(activity,"pendingOutput",exportBytes);set(activity,"pendingExportType","csv");call(activity,"onPause",NONE);call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},12,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(exported)));call(activity,"onResume",NONE);});waitForWorker();
            check(exported.exists()&&Arrays.equals(exportBytes,readFile(exported)),"Returning from file picker inside window completes pending export");
            ui(()->{check(field(activity,"pendingSave")==null&&field(activity,"pendingOutput")==null,"Pending export is consumed after cached authentication");call(activity,"onResume",NONE);});waitForIdleSync();check(Arrays.equals(exportBytes,readFile(exported)),"A second resume does not alter or restart completed export");exported.delete();
            ui(()->{call(activity,"setVerificationMinutes",new Class[]{int.class},1);set(activity,"lastVerifiedAt",SystemClock.elapsedRealtime()-55_000L);check((Boolean)call(activity,"isVerificationWindowOpen",NONE),"Authentication window remains open before one-minute deadline");set(activity,"lastVerifiedAt",SystemClock.elapsedRealtime()-60_000L);check(!(Boolean)call(activity,"isVerificationWindowOpen",NONE),"Authentication window closes at its fixed deadline");set(activity,"lastVerifiedAt",SystemClock.elapsedRealtime()+1000L);check(!(Boolean)call(activity,"isVerificationWindowOpen",NONE),"Future monotonic timestamp is never accepted");set(activity,"lastVerifiedAt",SystemClock.elapsedRealtime()-60_001L);call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("188.60");check((Boolean)field(activity,"unlocked")&&"188.60".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Expiration does not interrupt active foreground editing");call(activity,"onPause",NONE);call(activity,"onResume",NONE);check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"authPending"),"Returning after deadline starts real system verification");});cancelSystemPromptForTest();
            ui(()->{check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate")&&((Long)field(activity,"lastVerifiedAt"))==-1L,"Cancelling expired verification clears cached session and keeps gate locked");call(activity,"onResume",NONE);check(!(Boolean)field(activity,"authPending"),"Cancelled verification does not immediately prompt again");unlockForTest();check("188.60".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Successful reauthentication restores expired-session draft");call(activity,"setVerificationMinutes",new Class[]{int.class},5);call(activity,"lockImmediately",NONE);check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate")&&((Long)field(activity,"lastVerifiedAt"))==-1L&&!(Boolean)call(activity,"hasReusableVerification",NONE),"Immediate lock invalidates even an unexpired verification window");call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},11,Activity.RESULT_OK,null);check(!(Boolean)field(activity,"unlocked")&&((Long)field(activity,"lastVerifiedAt"))==-1L,"Unsolicited successful AUTH result cannot unlock the ledger");set(activity,"authPending",true);call(activity,"lockImmediately",NONE);call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},11,Activity.RESULT_OK,null);check(!(Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"authPending")&&((Long)field(activity,"lastVerifiedAt"))==-1L,"Immediate lock revokes a pending AUTH and rejects its late success");check(!(Boolean)call(activity,"setVerificationMinutes",new Class[]{int.class},30),"Locked ledger cannot change verification duration");unlockForTest();call(activity,"setVerificationMinutes",new Class[]{int.class},0);check(((Long)field(activity,"lastVerifiedAt"))==-1L,"Switching to every-entry verification invalidates cached authentication");call(activity,"setVerificationMinutes",new Class[]{int.class},5);check(((Long)field(activity,"lastVerifiedAt"))==-1L&&!(Boolean)call(activity,"isVerificationWindowOpen",NONE),"Setting a positive duration cannot fabricate a new authentication");unlockForTest();});
            shellText("locksettings clear --old "+testPin);pinRemoved=true;ui(()->check(!(Boolean)call(activity,"hasReusableVerification",NONE)&&((Long)field(activity,"lastVerifiedAt"))==-1L,"Removing device PIN immediately invalidates reusable verification"));shellText("locksettings set-pin "+testPin);pinRemoved=false;
            check(beforeData.equals(ledgerSnapshot()),"Verification-window checks leave ledger data and platform settings unchanged");
        } finally {
            if(pinRemoved)try{shellText("locksettings set-pin "+testPin);}catch(Exception ignored){}
            try{ui(()->{unlockForTest();call(activity,"setVerificationMinutes",new Class[]{int.class},previousMinutes);call(activity,"setAppearance",new Class[]{String.class},previousAppearance);call(activity,"lockImmediately",NONE);});systemNight(previousNight==UiModeManager.MODE_NIGHT_YES?"yes":previousNight==UiModeManager.MODE_NIGHT_NO?"no":"auto");}catch(Exception ignored){}
        }
    }
    private byte[] readFile(File file)throws Exception{try(InputStream input=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[1024];int count;while((count=input.read(bytes))!=-1)out.write(bytes,0,count);return out.toByteArray();}}
    private String shellText(String command)throws Exception{try(ParcelFileDescriptor file=getUiAutomation().executeShellCommand(command);InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)out.write(bytes,0,count);return out.toString("UTF-8");}}
    private void waitForWorker()throws Exception{long deadline=SystemClock.uptimeMillis()+8000;while((Boolean)field(activity,"busy")&&SystemClock.uptimeMillis()<deadline){Thread.sleep(50);waitForIdleSync();}waitForIdleSync();if((Boolean)field(activity,"busy"))throw new AssertionError("Background file operation did not complete");}
    private void themeChecks()throws Exception {
        final String previousAppearance=(String)field(activity,"appearance");
        final int previousNight=((UiModeManager)getTargetContext().getSystemService(Context.UI_MODE_SERVICE)).getNightMode();
        final String beforeData=ledgerSnapshot();
        try {
            ui(()->{unlockForTest();call(activity,"showHome",NONE);});
            systemNight("no");chooseAppearance("dark",true);assertTheme(true,"Explicit dark mode renders dark surfaces and light system icons");ui(()->{set(activity,"tab",0);call(activity,"showHome",NONE);});captureVisual("dark-overview.png");clickTab(2);Thread.sleep(350);captureVisual("dark-settings.png");
            reopenActivity();ui(()->check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate"),"Reopening with saved appearance still requires authentication"));
            ui(()->{check("dark".equals(field(activity,"appearance")),"Dark appearance choice survives Activity reopening");unlockForTest();});assertTheme(true,"Saved dark appearance is rendered on reopened Activity");
            systemNight("no");assertTheme(true,"Explicit dark appearance ignores light system mode");
            chooseAppearance("light",false);assertTheme(false,"Explicit light appearance renders light surfaces and dark system icons");
            systemNight("yes");assertTheme(false,"Explicit light appearance ignores dark system mode");
            chooseAppearance("system",false);assertTheme(true,"Follow-system choice immediately adopts dark system mode");
            systemNight("no");assertTheme(false,"Follow-system responds to changing system mode to light");
            ui(()->check("system".equals(field(activity,"appearance")),"System mode change keeps follow-system preference"));
            final String[] chosenPlatform={null};
            ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("136.70");((EditText)field(activity,"costInput")).setText("16.20");((EditText)field(activity,"routeInput")).setText("主题草稿路线");((EditText)field(activity,"noteInput")).setText("切换外观不丢失");set(activity,"formDate",LocalDate.now().minusDays(2).toString());set(activity,"formTime","16:25");((Button)field(activity,"dateField")).setText((String)field(activity,"formDate"));((Button)field(activity,"timeField")).setText("16:25");Spinner p=(Spinner)field(activity,"platformInput");p.setSelection(p.getCount()-1);});waitForIdleSync();
            ui(()->chosenPlatform[0]=(String)call(activity,"selectedFormPlatformId",NONE));
            systemNight("yes");assertTheme(true,"Follow-system updates an open form to dark mode");
            ui(()->{check("136.70".equals(((EditText)field(activity,"fareInput")).getText().toString())&&"16.20".equals(((EditText)field(activity,"costInput")).getText().toString()),"Theme change preserves unsaved income and cost");check("主题草稿路线".equals(((EditText)field(activity,"routeInput")).getText().toString())&&"切换外观不丢失".equals(((EditText)field(activity,"noteInput")).getText().toString()),"Theme change preserves unsaved route and note");check(LocalDate.now().minusDays(2).toString().equals(field(activity,"formDate"))&&"16:25".equals(field(activity,"formTime")),"Theme change preserves chosen date and time");check(chosenPlatform[0].equals(call(activity,"selectedFormPlatformId",NONE)),"Theme change preserves chosen platform ID");});
            captureVisual("dark-form.png");
            ui(()->((Button)field(activity,"dateField")).performClick());waitForIdleSync();
            ui(()->check(activeDialog() instanceof DatePickerDialog,"Date picker remains reachable in dark form"));captureDialogVisual("dark-date-dialog.png");ui(()->activeDialog().dismiss());waitForIdleSync();
            ui(()->((Button)field(activity,"timeField")).performClick());waitForIdleSync();captureDialogVisual("dark-time-dialog.png");ui(()->activeDialog().dismiss());waitForIdleSync();
            final AlertDialog[] editingDialog={null};final EditText[] editingName={null};
            ui(()->{call(activity,"platformNameDialog",new Class[]{platformClass,boolean.class},null,true);editingDialog[0]=activeDialog();editingName[0]=findEdit(editingDialog[0].getWindow().getDecorView());editingName[0].setText("主题切换保留输入");});waitForIdleSync();captureDialogVisual("dark-platform-dialog.png");
            systemNight("no");ui(()->{check(editingDialog[0].isShowing()&&"主题切换保留输入".equals(editingName[0].getText().toString()),"System appearance change preserves open dialog and entered text");check((Boolean)field(activity,"darkMode"),"Appearance update waits while an input dialog is open");editingDialog[0].dismiss();});waitForIdleSync();Thread.sleep(500);assertTheme(false,"Deferred system appearance applies after dialog dismissal");
            ui(()->check("136.70".equals(((EditText)field(activity,"fareInput")).getText().toString())&&chosenPlatform[0].equals(call(activity,"selectedFormPlatformId",NONE)),"Deferred appearance change preserves underlying form draft"));
            ui(()->call(activity,"showPlatforms",NONE));systemNight("yes");ui(()->check((Boolean)field(activity,"platformManager")&&findText((View)field(activity,"root"),"＋ 添加平台")!=null,"System appearance change keeps platform manager open"));captureVisual("dark-platforms.png");
            final String platformId=(String)field(platformByName("哈啰"),"id");
            ui(()->{set(activity,"tab",1);set(activity,"filter","机场");set(activity,"platformFilter",platformId);set(activity,"allMonths",true);call(activity,"showHome",NONE);});waitForIdleSync();ui(()->set(activity,"visibleLimit",150));systemNight("no");
            ui(()->check(((Integer)field(activity,"tab"))==1&&"机场".equals(findEdit((View)field(activity,"body")).getText().toString())&&platformId.equals(field(activity,"platformFilter")),"Theme change preserves records tab, search and platform filter"));ui(()->check(((Integer)field(activity,"visibleLimit"))==150,"Theme rebuild preserves previously loaded record limit"));
            final Button[] stale={null};final int platformCount=((List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true)).size();
            ui(()->{call(activity,"platformNameDialog",new Class[]{platformClass,boolean.class},null,false);AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("锁定后不得新增平台");stale[0]=dialog.getButton(AlertDialog.BUTTON_POSITIVE);call(activity,"onPause",NONE);cancelAuthenticationForTest();});systemNight("yes");
            ui(()->{check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate")&&findText((View)field(activity,"root"),"收入明细")==null,"System appearance change never unlocks or exposes a locked ledger");check(((Set<?>)field(activity,"openDialogs")).isEmpty(),"Theme change after locking leaves no private dialogs visible");stale[0].performClick();});
            check(((List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true)).size()==platformCount,"Stale dialog cannot add a platform after theme change and lock");
            ui(()->{unlockForTest();set(activity,"filter","");set(activity,"platformFilter","");});chooseAppearance("dark",false);
            ui(()->{set(activity,"tab",0);call(activity,"showHome",NONE);});captureVisual("dark-overview.png");scrollBottom();captureVisual("dark-overview-bottom.png");
            clickTab(1);Thread.sleep(350);captureVisual("dark-records.png");scrollBottom();captureVisual("dark-records-bottom.png");
            clickTab(2);Thread.sleep(350);captureVisual("dark-settings.png");scrollBottom();captureVisual("dark-settings-bottom.png");
            ui(()->findText((View)field(activity,"root"),"外观设置").performClick());waitForIdleSync();captureDialogVisual("dark-appearance-dialog.png");ui(()->activeDialog().dismiss());
            check(beforeData.equals(ledgerSnapshot()),"Appearance checks leave all ledger records and platform settings unchanged");
            ui(()->check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Dark screenshots restore production screenshot protection"));
        } finally {
            try {ui(()->{unlockForTest();call(activity,"setAppearance",new Class[]{String.class},previousAppearance);});systemNight(previousNight==UiModeManager.MODE_NIGHT_YES?"yes":previousNight==UiModeManager.MODE_NIGHT_NO?"no":"auto");}catch(Exception ignored){}
        }
    }
    private void chooseAppearance(String mode,boolean inspectChoices)throws Exception {
        ui(()->{set(activity,"tab",2);call(activity,"showHome",NONE);TextView button=findText((View)field(activity,"root"),"外观设置");if(button==null)throw new AssertionError("Appearance settings entry not found");button.performClick();});waitForIdleSync();
        ui(()->{AlertDialog dialog=activeDialog();ListView choices=dialog.getListView();if(inspectChoices){check(choices!=null&&choices.getCount()==3,"Appearance settings provide exactly three choices");check("跟随系统".equals(choices.getItemAtPosition(0).toString())&&"浅色".equals(choices.getItemAtPosition(1).toString())&&"深色".equals(choices.getItemAtPosition(2).toString()),"Appearance choices are follow-system, light and dark");}int index=mode.equals("system")?0:mode.equals("light")?1:2;choices.performItemClick(choices.getChildAt(index),index,choices.getItemIdAtPosition(index));});waitForIdleSync();Thread.sleep(400);
        ui(()->check(mode.equals(field(activity,"appearance"))&&mode.equals(activity.getPreferences(Context.MODE_PRIVATE).getString("appearance","")),"Selecting "+mode+" appearance takes effect and persists through the real settings dialog"));
    }
    private void assertTheme(boolean dark,String name)throws Exception {
        ui(()->{View root=(View)field(activity,"root");android.graphics.drawable.Drawable background=root.getBackground();if(!(background instanceof android.graphics.drawable.ColorDrawable))throw new AssertionError("Root background is not a solid color");int color=((android.graphics.drawable.ColorDrawable)background).getColor();double brightness=(Color.red(color)*.2126+Color.green(color)*.7152+Color.blue(color)*.0722)/255d;int bars=activity.getWindow().getDecorView().getSystemUiVisibility();boolean lightIcons=(bars&(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR))==0;boolean valid=((Boolean)field(activity,"darkMode"))==dark&&(dark?brightness<.25:brightness>.75)&&lightIcons==dark;if(!valid)throw new AssertionError(name+" expectedDark="+dark+" darkMode="+field(activity,"darkMode")+" systemDark="+field(activity,"systemDark")+" uiMode="+activity.getResources().getConfiguration().uiMode+" background="+Integer.toHexString(color)+" bars="+Integer.toHexString(bars)+" destroyed="+activity.isDestroyed()+" finishing="+activity.isFinishing()+" focus="+activity.hasWindowFocus()+" attached="+root.isAttachedToWindow()+" system="+lastNightDiagnostics);check(true,name);});
    }
    private void systemNight(String mode)throws Exception {
        String response;try(ParcelFileDescriptor file=getUiAutomation().executeShellCommand("cmd uimode night "+mode);InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[512];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);response=output.toString("UTF-8");}if(!response.contains("Night mode: "+mode))throw new AssertionError("System night command did not apply: "+response);
        waitForIdleSync();Thread.sleep(550);waitForIdleSync();try(ParcelFileDescriptor file=getUiAutomation().executeShellCommand("dumpsys uimode");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[512];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);lastNightDiagnostics=output.toString("UTF-8");}try(ParcelFileDescriptor file=getUiAutomation().executeShellCommand("dumpsys activity activities");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);String dump=output.toString("UTF-8");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"theme-config-stack.txt"))){out.write(output.toByteArray());}for(String line:dump.split("\n"))if(line.contains("mGlobalConfig=")||line.contains("mOverrideConfig=")||line.contains("CurrentConfiguration=")||line.contains("ResumedActivity:"))lastNightDiagnostics+=line+"\n";}
    }
    private void reopenActivity()throws Exception {
        ui(()->activity.finish());waitForIdleSync();activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();cancelSystemPromptForTest();
    }
    private String ledgerSnapshot()throws Exception {
        StringBuilder result=new StringBuilder();for(Object e:rows())for(String key:new String[]{"id","date","time","platformId","income","cost","route","note"})result.append(key).append('=').append(field(e,key)).append('\n');for(Object p:(List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true))for(String key:new String[]{"id","name","archived","entryCount"})result.append(key).append('=').append(field(p,key)).append('\n');return result.toString();
    }
    private void captureDialogVisual(String name)throws Exception {
        final List<Window> windows=new ArrayList<>();ui(()->{for(Object item:(Set<?>)field(activity,"openDialogs")){Dialog dialog=(Dialog)item;if(dialog.isShowing()&&dialog.getWindow()!=null){windows.add(dialog.getWindow());dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}}});
        try {captureVisual(name);}finally{ui(()->{for(Window window:windows)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);});}
    }
    private void completeSystemPromptForTest()throws Exception {
        long deadline=SystemClock.uptimeMillis()+8000;final boolean[] ready={false};
        while(SystemClock.uptimeMillis()<deadline){ui(()->ready[0]=(Boolean)field(activity,"authPending")&&!activity.hasWindowFocus());if(ready[0])break;Thread.sleep(100);}
        if(!ready[0])throw new AssertionError("Real credential prompt did not become foreground");Thread.sleep(300);shellText("input text "+testPin);shellText("input keyevent 66");deadline=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<deadline){waitForIdleSync();ui(()->ready[0]=(Boolean)field(activity,"unlocked")&&!(Boolean)field(activity,"authPending")&&activity.hasWindowFocus());if(ready[0])break;Thread.sleep(100);}
        check(ready[0],"Real system PIN authenticates before session checks");
    }
    private void cancelSystemPromptForTest()throws Exception {
        waitForIdleSync();Thread.sleep(250);final boolean[] pending={false},focused={false};
        for(int attempt=0;attempt<4;attempt++){
            ui(()->{pending[0]=(Boolean)field(activity,"authPending");focused[0]=activity.hasWindowFocus();});
            if(!pending[0]&&focused[0])return;
            shellText("input keyevent 4");waitForIdleSync();Thread.sleep(350);
        }
        ui(()->{if((Boolean)field(activity,"authPending")||!activity.hasWindowFocus())throw new AssertionError("System credential prompt did not fully cancel back to MainActivity");});
    }
    private void clickTab(int index)throws Exception {ui(()->{View[] items=(View[])field(activity,"navItems");items[index].performClick();});}
    private void swipeNav(float from,float to,boolean cancel)throws Exception {
        final float[] points=new float[3];ui(()->{View nav=(View)field(activity,"glassNav");int[] position=new int[2];nav.getLocationOnScreen(position);float cell=(nav.getWidth()-nav.getPaddingLeft()-nav.getPaddingRight())/3f;points[0]=position[0]+nav.getPaddingLeft()+cell*(from+.5f);points[1]=position[0]+nav.getPaddingLeft()+cell*(to+.5f);points[2]=position[1]+nav.getHeight()/2f;});
        long down=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,points[0],points[2],0);sendPointerSync(event);event.recycle();
        for(int i=1;i<=12;i++){Thread.sleep(16);event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,points[0]+(points[1]-points[0])*i/12f,points[2],0);sendPointerSync(event);event.recycle();}
        event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,points[1],points[2],0);sendPointerSync(event);event.recycle();
    }
    private void navigationChecks()throws Exception {
        ui(()->{set(activity,"tab",0);set(activity,"allMonths",true);call(activity,"showHome",NONE);});waitForIdleSync();final Object panel=field(activity,"glassNav");
        clickTab(1);Thread.sleep(400);waitForIdleSync();
        ui(()->{check(((Integer)field(activity,"tab"))==1&&findText((View)field(activity,"root"),"收入明细")!=null,"Actual tab click shows records page");check(panel==field(activity,"glassNav"),"Tab transition reuses the same glass panel");});
        clickTab(2);Thread.sleep(40);clickTab(0);Thread.sleep(40);clickTab(1);Thread.sleep(450);waitForIdleSync();
        ui(()->{check(((Integer)field(activity,"tab"))==1&&"收入明细".equals(((TextView)field(activity,"homeTitle")).getText().toString()),"Rapid tab sequence settles on last requested page");View[] items=(View[])field(activity,"navItems");check(!items[0].isSelected()&&items[1].isSelected()&&!items[2].isSelected(),"Rapid tab sequence leaves one correct selected item");check(panel==field(activity,"glassNav"),"Rapid changes keep shared lens panel alive");EditText search=findEdit((View)field(activity,"body"));search.setText("机场");});waitForIdleSync();
        ui(()->check(findText((View)field(activity,"recordRows"),"机场 → 市区")!=null,"Record search works after animated tab changes"));
        clickTab(0);Thread.sleep(350);swipeNav(0f,2f,false);Thread.sleep(500);waitForIdleSync();
        ui(()->{View[] items=(View[])field(activity,"navItems");check(((Integer)field(activity,"tab"))==2&&items[2].isSelected()&&!items[0].isSelected()&&!items[1].isSelected(),"Real horizontal drag navigates from overview to account");});
        swipeNav(2f,1.8f,false);Thread.sleep(500);ui(()->check(((Integer)field(activity,"tab"))==2&&((Integer)call(field(activity,"glassNav"),"getSelectedIndex",NONE))==2,"Small same-item drag snaps back to current tab"));
        swipeNav(2f,0f,true);Thread.sleep(500);ui(()->check(((Integer)field(activity,"tab"))==2&&((Integer)call(field(activity,"glassNav"),"getSelectedIndex",NONE))==2,"Cancelled drag does not navigate"));
        clickTab(1);Thread.sleep(400);waitForIdleSync();
        final String[] chosenId={null};ui(()->{Spinner platforms=findSpinner((View)field(activity,"body"));List<?> choices=(List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true);for(int i=0;i<choices.size();i++)if("哈啰".equals(field(choices.get(i),"name"))){chosenId[0]=(String)field(choices.get(i),"id");platforms.setSelection(i+1);}});waitForIdleSync();
        ui(()->check(chosenId[0]!=null&&chosenId[0].equals(field(activity,"platformFilter"))&&findText((View)field(activity,"recordRows"),"机场 → 市区")!=null,"Platform filter remains usable after tab animation"));
        clickTab(2);Thread.sleep(300);clickTab(1);Thread.sleep(350);waitForIdleSync();
        ui(()->{check("机场".equals(((EditText)findEdit((View)field(activity,"body"))).getText().toString())&&chosenId[0].equals(field(activity,"platformFilter")),"Search and platform filter survive a round-trip tab switch");set(activity,"filter","");set(activity,"platformFilter","");});
        clickTab(0);Thread.sleep(70);ui(()->{call(activity,"onPause",NONE);cancelAuthenticationForTest();});Thread.sleep(450);waitForIdleSync();
        ui(()->{check(!(Boolean)field(activity,"unlocked")&&(Boolean)field(activity,"gate"),"Locking during transition remains safely locked after animation");check(findText((View)field(activity,"root"),"本月收入 · 元")==null,"Transition completion cannot reveal ledger behind locked gate");unlockForTest();});
    }
    private void visualSnapshotsOnly()throws Exception {
        Bundle progress=new Bundle();progress.putString("stream","Starting visual-only capture\n");sendStatus(1,progress);
        activity=startActivitySync(new Intent().setClassName("cn.personal.shunfeng","cn.personal.shunfeng.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();cancelSystemPromptForTest();
        save(entry("机场 → 市区",12880,1800,0));save(entry("家附近 → 公司附近",4520,600,0));save(entry("高铁站 → 科技园",8600,1200,1));save(entry("市中心 → 大学城",6200,800,3));
        ui(()->{unlockForTest();set(activity,"tab",0);call(activity,"showHome",NONE);});captureOverviewScroll();scrollBottom();captureVisual("glass-overview-bottom.png");
        ui(()->set(activity,"allMonths",true));clickTab(1);Thread.sleep(400);captureVisual("glass-records.png");scrollBottom();captureVisual("glass-records-bottom.png");
        clickTab(2);Thread.sleep(400);captureVisual("glass-settings.png");scrollBottom();captureVisual("glass-settings-bottom.png");captureTransition();
        ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);});scrollBottom();captureVisual("glass-entry-buttons.png");
        ui(()->{EditText fare=(EditText)field(activity,"fareInput");fare.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(fare,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});Thread.sleep(600);captureVisual("glass-entry-keyboard.png");
        ui(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));Thread.sleep(600);captureVisual("glass-entry-landscape.png");
        ui(()->{call(activity,"hideKeyboard",NONE);activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});Thread.sleep(500);
    }
    private void captureOverviewScroll()throws Exception {
        captureVisual("glass-overview.png");
        final long[] before=new long[2];final int[] scrollY=new int[2];
        ui(()->{Object panel=field(activity,"glassNav");before[0]=bitmapHash((Bitmap)field(panel,"sample"));before[1]=bitmapHash((Bitmap)field(panel,"refracted"));ScrollView scroll=findScroll((View)field(activity,"root"));scrollY[0]=scroll.getScrollY();scroll.scrollBy(0,Math.round(110*activity.getResources().getDisplayMetrics().density));});
        captureVisual("liquid-overview-refraction.png");
        ui(()->{Object panel=field(activity,"glassNav");scrollY[1]=findScroll((View)field(activity,"root")).getScrollY();check(scrollY[1]>scrollY[0],"Overview test scroll moves real content behind navigation");check(before[0]!=bitmapHash((Bitmap)field(panel,"sample")),"Scrolling updates sampled backdrop pixels");check(before[1]!=bitmapHash((Bitmap)field(panel,"refracted")),"Scrolling updates refracted backdrop pixels");});
    }
    private long bitmapHash(Bitmap bitmap) {
        if(bitmap==null||bitmap.isRecycled())throw new AssertionError("Backdrop bitmap is unavailable");
        int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());long hash=0xcbf29ce484222325L;for(int pixel:pixels){hash^=pixel;hash*=0x100000001b3L;}return hash;
    }
    private void captureTransition()throws Exception {
        clickTab(0);Thread.sleep(400);ui(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));waitForIdleSync();clickTab(1);Thread.sleep(125);
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("Transition screenshot unavailable");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"liquid-transition.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}Thread.sleep(400);ui(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
    }
    private void captureVisual(String name)throws Exception {
        ui(()->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));waitForIdleSync();Thread.sleep(350);
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("Screenshot unavailable: "+name);boolean visible=false;for(int y=0;y<bitmap.getHeight()&&!visible;y+=32)for(int x=0;x<bitmap.getWidth();x+=32)if((bitmap.getPixel(x,y)&0xffffff)!=0){visible=true;break;}if(!visible){bitmap.recycle();throw new AssertionError("Screenshot is all black; verify emulator display is awake and screenshot flags: "+name);}
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        ui(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
    }
    private void scrollBottom()throws Exception {waitForIdleSync();ui(()->{ScrollView view=findScroll((View)field(activity,"root"));if(view!=null)view.fullScroll(View.FOCUS_DOWN);});waitForIdleSync();Thread.sleep(350);}
    private ScrollView findScroll(View view){if(view instanceof ScrollView)return (ScrollView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ScrollView result=findScroll(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private void platformUiChecks()throws Exception {
        final String suffix=Long.toHexString(System.nanoTime());final String nameA="验证A"+suffix,nameB="验证B"+suffix,renamedB="改名B"+suffix;
        ui(()->{set(activity,"tab",2);call(activity,"showHome",NONE);TextView manage=findText((View)field(activity,"root"),"平台管理");check(manage!=null,"Platform management is reachable from settings");manage.performClick();check((Boolean)field(activity,"platformManager"),"Platform manager opens");findText((View)field(activity,"root"),"＋ 添加平台").performClick();});waitForIdleSync();ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText(nameA);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
        waitForIdleSync();Object a=platformByName(nameA);check(a!=null,"UI creates custom platform");String aid=(String)field(a,"id");createdPlatformIds.add(aid);
        ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);((EditText)field(activity,"fareInput")).setText("88.80");((EditText)field(activity,"routeInput")).setText("自定义平台UI行程");findText((View)field(activity,"root"),"＋ 添加平台").performClick();});waitForIdleSync();ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText(nameB);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
        waitForIdleSync();Object b=platformByName(nameB);String bid=(String)field(b,"id");createdPlatformIds.add(bid);
        ui(()->{check("88.80".equals(((EditText)field(activity,"fareInput")).getText().toString()),"Adding platform inline preserves entered income");check(bid.equals(call(activity,"selectedFormPlatformId",NONE)),"New inline platform is automatically selected");call(activity,"saveEntry",NONE);});
        Object entry=null;for(Object e:rows())if("自定义平台UI行程".equals(field(e,"route")))entry=e;if(entry==null)throw new AssertionError("Custom platform UI record missing");String eid=(String)field(entry,"id");createdIds.add(eid);check(bid.equals(field(entry,"platformId")),"New UI record stores chosen platform ID");
        ui(()->{call(activity,"platformNameDialog",new Class[]{platformClass,boolean.class},b,false);});waitForIdleSync();ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText(renamedB);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();
        check(renamedB.equals(field(get(eid),"platform")),"UI rename updates historical platform display");
        ui(()->{Object p=call(db,"getPlatform",new Class[]{String.class},bid);call(activity,"platformActions",new Class[]{platformClass},p);AlertDialog actions=activeDialog();actions.getListView().performItemClick(null,1,1);});waitForIdleSync();ui(()->activeDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitForIdleSync();
        check((Boolean)field(call(db,"getPlatform",new Class[]{String.class},bid),"archived"),"UI can archive platform with history");
        ui(()->{call(activity,"showForm",new Class[]{entryClass},(Object)null);boolean contains=false;for(Object p:(List<?>)field(activity,"formPlatforms"))if(bid.equals(field(p,"id")))contains=true;check(!contains,"New-record selector excludes archived platform");call(activity,"showForm",new Class[]{entryClass},get(eid));check(bid.equals(call(activity,"selectedFormPlatformId",NONE)),"Historical editor retains its archived platform");});
        final Button[] stale=new Button[1];ui(()->{call(activity,"platformNameDialog",new Class[]{platformClass,boolean.class},call(db,"getPlatform",new Class[]{String.class},bid),false);});waitForIdleSync();ui(()->{AlertDialog dialog=activeDialog();findEdit(dialog.getWindow().getDecorView()).setText("不得在锁定时改名");stale[0]=dialog.getButton(AlertDialog.BUTTON_POSITIVE);call(activity,"onPause",NONE);stale[0].performClick();});waitForIdleSync();
        check(renamedB.equals(field(call(db,"getPlatform",new Class[]{String.class},bid),"name")),"Stale platform dialog cannot rename after locking");
        ui(()->{unlockForTest();call(activity,"deletePlatformDialog",new Class[]{platformClass},call(db,"getPlatform",new Class[]{String.class},bid));AlertDialog dialog=activeDialog();Spinner target=findSpinner(dialog.getWindow().getDecorView());check(target!=null,"Deleting used platform shows destination selector");for(int i=0;i<target.getCount();i++)if(nameA.equals(target.getItemAtPosition(i).toString()))target.setSelection(i);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();
        check(aid.equals(field(get(eid),"platformId")),"UI merge transfers historical association");check(((Long)field(get(eid),"income"))==8880L,"UI merge preserves historical income");check(platformByName(renamedB)==null,"UI merged platform is removed from choices");
    }
    private Object platformByName(String name)throws Exception{for(Object p:(List<?>)call(db,"listPlatforms",new Class[]{boolean.class},true))if(name.equals(field(p,"name")))return p;return null;}
    private AlertDialog activeDialog()throws Exception{for(Object d:(Set<?>)field(activity,"openDialogs"))if(d instanceof AlertDialog&&((AlertDialog)d).isShowing())return (AlertDialog)d;throw new AssertionError("No active dialog");}
    private EditText findEdit(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText result=findEdit(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private Spinner findSpinner(View view){if(view instanceof Spinner)return (Spinner)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Spinner result=findSpinner(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private void cancelAuthenticationForTest()throws Exception {set(activity,"authPending",true);call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},11,Activity.RESULT_CANCELED,null);}
    private void unlockForTest()throws Exception {set(activity,"authPending",true);call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},11,Activity.RESULT_OK,null);}
    private void rotate(int orientation)throws Exception {
        int wanted=orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE?android.content.res.Configuration.ORIENTATION_LANDSCAPE:android.content.res.Configuration.ORIENTATION_PORTRAIT;
        ui(()->activity.setRequestedOrientation(orientation));long deadline=SystemClock.uptimeMillis()+5000;
        while(activity.getResources().getConfiguration().orientation!=wanted&&SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
        waitForIdleSync();check(activity.getResources().getConfiguration().orientation==wanted,"Phone changes display orientation");
    }
    private void recreate()throws Exception {
        final Activity old=activity;final AtomicReference<Activity> next=new AtomicReference<>();Application app=(Application)getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a!=old&&a.getClass().getName().equals("cn.personal.shunfeng.MainActivity"))next.set(a);}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        app.registerActivityLifecycleCallbacks(callback);
        try {ui(()->old.recreate());long deadline=SystemClock.uptimeMillis()+8000;while(next.get()==null&&SystemClock.uptimeMillis()<deadline)Thread.sleep(50);activity=next.get();if(activity==null){activity=old;throw new AssertionError("Explicit recreation did not create Activity");}waitForIdleSync();check(activity!=old,"Activity recreation delivers restored state");}
        finally {app.unregisterActivityLifecycleCallbacks(callback);}
    }
    private TextView findText(View root,String text){if(root instanceof TextView&&text.equals(((TextView)root).getText().toString()))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView v=findText(g.getChildAt(i),text);if(v!=null)return v;}}return null;}
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return invoke(m,target,args);}
    private Object invoke(Method method,Object target,Object... args)throws Exception{try{return method.invoke(target,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;throw e;}}
    private void ui(Checked operation)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{operation.run();}catch(Throwable e){failure.set(e);}});Throwable e=failure.get();if(e instanceof Exception)throw (Exception)e;if(e instanceof Error)throw (Error)e;}
    private void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;log.append("PASS ").append(name).append('\n');Bundle progress=new Bundle();progress.putString("stream","PASS "+name+"\n");sendStatus(1,progress);}
    private void reject(Checked operation,String name)throws Exception{try{operation.run();}catch(IllegalArgumentException|java.security.GeneralSecurityException e){check(true,name);return;}catch(Exception e){if(e instanceof java.security.GeneralSecurityException){check(true,name);return;}throw e;}throw new AssertionError("Expected rejection: "+name);}
}
