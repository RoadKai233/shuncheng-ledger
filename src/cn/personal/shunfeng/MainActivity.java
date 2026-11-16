package cn.personal.shunfeng;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private int BG=0xfff5f6f2, INK=0xff203b32, GREEN=0xff17775c, MUTED=0xff748178, MINT=0xffdcebc5, WHITE=0xffffffff;
    private int NAV_TEXT=0xff52605b, HINT=0xff9ba69e, DISABLED=0xffc7cdc6, BADGE=0xffe6eee1, STATUS=0xffedf3e6, DANGER=0xffad4c43, CHART_BAR=0xffdcebc5;
    private String appearance="system";
    private boolean darkMode, systemDark, appearanceRefreshPending;
    private Context uiContext;
    private static final int AUTH=11, SAVE_FILE=12, OPEN_FILE=13, INSTALL_PERMISSION=14;
    private LinearLayout root, body, recordRows;
    private GlassStyle.Panel glassNav;
    private ScrollView homeScroll;
    private TextView homeTitle, homeSubtitle;
    private final LinearLayout[] navItems=new LinearLayout[3];
    private final GlassStyle.TabIcon[] navIcons=new GlassStyle.TabIcon[3];
    private final TextView[] navLabels=new TextView[3];
    private static final String[] TAB_NAMES={"总览","明细","我的"};
    private LedgerDb db;
    private List<LedgerDb.Entry> entries=new ArrayList<>();
    private boolean unlocked=false, authPending=false, authCancelled=false, gate=true, busy=false;
    // Only the duration is persisted. A process restart always requires a fresh credential check.
    private static long lastVerifiedAt=-1L;
    private int verificationMinutes=0;
    private static final int MAX_VERIFICATION_MINUTES=1440;
    private long interactionEpoch=0;
    private int tab=0, visibleLimit=50;
    private YearMonth month=YearMonth.now();
    private boolean allMonths=false;
    private String filter="", platformFilter="";
    private boolean platformManager=false;
    private List<LedgerDb.Platform> formPlatforms=new ArrayList<>();
    private byte[] pendingOutput;
    private Uri pendingInput, pendingSave;
    private String pendingExportType;
    private Runnable afterUnlock;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker=Executors.newSingleThreadExecutor();
    private boolean foreground, updateWorking, updateCacheLoaded;
    private Runnable pendingUpdateUi;
    private UpdateClient.Release downloadedRelease;
    private File downloadedUpdate;
    private LedgerDb.Entry editing;
    private EditText fareInput,costInput,routeInput,noteInput;
    private String formDate,formTime;
    private Button dateField,timeField;
    private Spinner platformInput;
    private Bundle restoredDraft;
    private final Set<Dialog> openDialogs=new HashSet<>();

    @Override public void onCreate(Bundle state) {
        appearance=getPreferences(MODE_PRIVATE).getString("appearance","system");
        if(!"light".equals(appearance)&&!"dark".equals(appearance))appearance="system";
        systemDark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        setTheme(resolveDarkMode()?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        verificationMinutes=readVerificationMinutes();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        applyThemePalette(resolveDarkMode());
        db=new LedgerDb(this);
        if(state!=null){authPending=state.getBoolean("authPending",false);tab=state.getInt("tab",0);allMonths=state.getBoolean("allMonths",false);filter=state.getString("filter","");platformFilter=state.getString("platformFilter","");restoredDraft=state.getBundle("draft");try{month=YearMonth.parse(state.getString("month",YearMonth.now().toString()));}catch(Exception ignored){}}
        showGate();
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;
        verificationMinutes=readVerificationMinutes();
        applySystemAppearance(getResources().getConfiguration());
        if (!unlocked&&!authPending&&!authCancelled) {
            if(hasReusableVerification())openUnlockedLedger();else requestUnlock();
        }
        else if (unlocked) { root.setVisibility(View.VISIBLE); resumePending(); }
        resumeUpdateUi();maybeCheckUpdates();
    }
    private int readVerificationMinutes(){
        int value=getPreferences(MODE_PRIVATE).getInt("verification_minutes",0);
        return value>=0&&value<=MAX_VERIFICATION_MINUTES?value:0;
    }
    private boolean isVerificationWindowOpen(){
        if(verificationMinutes<=0||lastVerifiedAt<0)return false;
        long age=SystemClock.elapsedRealtime()-lastVerifiedAt;
        return age>=0&&age<verificationMinutes*60_000L;
    }
    private boolean hasReusableVerification(){
        KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(!keyguard.isDeviceSecure()){lastVerifiedAt=-1L;return false;}
        return isVerificationWindowOpen()&&!keyguard.isKeyguardLocked()
            &&((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();
    }
    private void openUnlockedLedger(){
        unlocked=true;authCancelled=false;
        if(gate){showHome();restoreDraft();}else root.setVisibility(View.VISIBLE);
        resumePending();resumeUpdateUi();maybeCheckUpdates();
    }
    private void lockImmediately(){
        interactionEpoch++;lastVerifiedAt=-1L;unlocked=false;authPending=false;authCancelled=true;
        hideKeyboard();settleHomeMotion();dismissPrivateDialogs();showGate();
    }
    private boolean isDialogInteractionAllowed(long epoch){return unlocked&&epoch==interactionEpoch;}
    private void dismissPrivateDialogs(){
        for(Dialog dialog:new ArrayList<>(openDialogs))dialog.dismiss();
        openDialogs.clear();
    }
    private String verificationLabel(){return verificationMinutes==0?"每次进入都验证":"验证后 "+verificationMinutes+" 分钟内免验证";}
    private boolean setVerificationMinutes(int minutes){
        if(!unlocked||minutes<0||minutes>MAX_VERIFICATION_MINUTES)return false;
        verificationMinutes=minutes;
        if(minutes==0)lastVerifiedAt=-1L;
        getPreferences(MODE_PRIVATE).edit().putInt("verification_minutes",minutes).apply();
        // Changing a duration never fabricates a new successful authentication time.
        showHome();toast(minutes==0?"已设为每次进入都验证":"已设为验证后 "+minutes+" 分钟内免验证");
        return true;
    }
    private void showVerificationSettings(){
        if(!unlocked)return;
        final int[] durations={0,1,5,15,30};
        final String[] choices={"每次进入都验证","1 分钟","5 分钟","15 分钟","30 分钟","自定义时间…"};
        int selected=5;for(int i=0;i<durations.length;i++)if(verificationMinutes==durations[i])selected=i;
        if(selected==5)choices[5]="自定义："+verificationMinutes+" 分钟";
        new SafeBuilder(this).setTitle("验证有效期").setSingleChoiceItems(choices,selected,(dialog,which)->{
            if(!unlocked)return;dialog.dismiss();
            if(which==durations.length)showCustomVerificationTime();else setVerificationMinutes(durations[which]);
        }).setNegativeButton("取消",null).show();
    }
    private void showCustomVerificationTime(){
        if(!unlocked)return;
        final long epoch=interactionEpoch;
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),0);
        box.addView(label("输入 1–1440 的整数，单位为分钟。",14,INK,false));space(box,12);
        EditText minutes=input("例如：10",false);minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        if(verificationMinutes>0)minutes.setText(Integer.toString(verificationMinutes));
        box.addView(minutes);space(box,12);
        box.addView(label("从最近一次验证成功开始计算，到期后下次进入再验证。",12,MUTED,false));
        AlertDialog dialog=new SafeBuilder(this).setTitle("自定义验证时间").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            if(!isDialogInteractionAllowed(epoch)||!dialog.isShowing())return;String text=minutes.getText().toString().trim();int value=-1;
            if(text.matches("[0-9]{1,4}"))try{value=Integer.parseInt(text);}catch(NumberFormatException ignored){}
            if(value<1||value>MAX_VERIFICATION_MINUTES){minutes.setError("请输入 1–1440 的整数分钟");return;}
            dialog.dismiss();setVerificationMinutes(value);
        }));dialog.show();minutes.requestFocus();minutes.setSelection(minutes.length());
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        applySystemAppearance(configuration);
    }
    private boolean resolveDarkMode(){return "dark".equals(appearance)||("system".equals(appearance)&&systemDark);}
    private void applySystemAppearance(Configuration configuration){
        systemDark=(configuration.uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        refreshAppearance(false);
    }
    private void applyThemePalette(boolean dark){
        darkMode=dark;
        BG=dark?0xff101613:0xfff5f6f2;WHITE=dark?0xff1c2520:0xffffffff;
        INK=dark?0xffe8f1eb:0xff203b32;GREEN=dark?0xff8ad8b3:0xff17775c;
        MUTED=dark?0xffa0b1a6:0xff748178;MINT=dark?0xff293e30:0xffdcebc5;
        NAV_TEXT=dark?0xffb6c6bc:0xff52605b;HINT=dark?0xff82998b:0xff9ba69e;
        DISABLED=dark?0xff53665b:0xffc7cdc6;BADGE=dark?0xff26392e:0xffe6eee1;
        STATUS=dark?0xff2a4032:0xffedf3e6;DANGER=dark?0xffffa39a:0xffad4c43;
        CHART_BAR=dark?0xff6d9478:0xffdcebc5;
        Resources.Theme theme=getResources().newTheme();theme.applyStyle(dark?R.style.AppThemeDark:R.style.AppTheme,true);
        getTheme().setTo(theme);uiContext=new ContextThemeWrapper(this,theme);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        int lightFlags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int flags=getWindow().getDecorView().getSystemUiVisibility();
        getWindow().getDecorView().setSystemUiVisibility(dark?(flags&~lightFlags):(flags|lightFlags));
        if(Build.VERSION.SDK_INT>=29){getWindow().setStatusBarContrastEnforced(false);getWindow().setNavigationBarContrastEnforced(false);getWindow().getDecorView().setForceDarkAllowed(false);}
    }
    private void setAppearance(String mode){
        if(!unlocked||(!"system".equals(mode)&&!"light".equals(mode)&&!"dark".equals(mode)))return;
        if(appearance.equals(mode))return;
        appearance=mode;getPreferences(MODE_PRIVATE).edit().putString("appearance",mode).apply();
        refreshAppearance(true);
    }
    private void refreshAppearance(boolean force){
        force|=appearanceRefreshPending;
        if(root==null)return;
        if(darkMode==resolveDarkMode()&&!force)return;
        // Keep active modal input and asynchronous export/restore work intact.
        if(busy||!openDialogs.isEmpty()){appearanceRefreshPending=force;return;}
        appearanceRefreshPending=false;
        ScrollView oldScroll=findScrollView(root);int scrollY=oldScroll==null?0:oldScroll.getScrollY();
        boolean form=unlocked&&fareInput!=null&&fareInput.isAttachedToWindow();
        boolean platforms=unlocked&&platformManager&&!gate;
        View focused=getCurrentFocus();int focus=form?(focused==fareInput?1:focused==costInput?2:focused==routeInput?3:focused==noteInput?4:0):0;
        int selection=focused instanceof EditText?((EditText)focused).getSelectionStart():-1;
        if(form)restoredDraft=captureDraft();
        applyThemePalette(resolveDarkMode());
        if(!unlocked)showGate();else if(form)restoreDraft();else if(platforms)showPlatforms();else showHome();
        final LinearLayout refreshedRoot=root;final ScrollView nextScroll=findScrollView(root);
        final EditText nextFocus=focus==1?fareInput:focus==2?costInput:focus==3?routeInput:focus==4?noteInput:null;
        refreshedRoot.post(()->{
            if(root!=refreshedRoot)return;
            if(nextScroll!=null)nextScroll.scrollTo(0,scrollY);
            if(nextFocus!=null&&unlocked){nextFocus.requestFocus();if(selection>=0)nextFocus.setSelection(Math.min(selection,nextFocus.length()));}
            if(glassNav!=null)glassNav.refreshBackdrop();
        });
    }
    private ScrollView findScrollView(View view){
        if(view instanceof ScrollView)return (ScrollView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ScrollView scroll=findScrollView(group.getChildAt(i));if(scroll!=null)return scroll;}}
        return null;
    }
    private String appearanceName(){return "dark".equals(appearance)?"深色":"light".equals(appearance)?"浅色":"跟随系统";}
    private void showAppearanceSettings(){
        if(!unlocked)return;
        int selected="dark".equals(appearance)?2:"light".equals(appearance)?1:0;
        new SafeBuilder(this).setTitle("外观设置").setSingleChoiceItems(new String[]{"跟随系统","浅色","深色"},selected,(dialog,which)->{
            if(!unlocked)return;dialog.dismiss();setAppearance(new String[]{"system","light","dark"}[which]);
        }).setNegativeButton("取消",null).show();
    }
    private void dialogDismissed(Dialog dialog){
        openDialogs.remove(dialog);
        if(root!=null)root.post(()->{if(!isFinishing()&&!isDestroyed()){refreshAppearance(false);resumeUpdateUi();}});
    }
    @Override protected void onPause() { super.onPause(); foreground=false;interactionEpoch++;lockWhenLeaving(); }
    @Override protected void onStop() { super.onStop(); lockWhenLeaving(); }
    private void lockWhenLeaving() {
        if (!authPending) { unlocked=false; authCancelled=false;settleHomeMotion();dismissPrivateDialogs();if(root!=null) root.setVisibility(View.INVISIBLE); }
    }
    private void settleHomeMotion(){
        if(body!=null){body.animate().cancel();body.setAlpha(1f);body.setTranslationX(0f);}
        if(glassNav!=null)glassNav.setSelectedIndex(tab,false);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);state.putBoolean("authPending",authPending);state.putInt("tab",tab);state.putString("month",month.toString());state.putBoolean("allMonths",allMonths);state.putString("filter",filter);state.putString("platformFilter",platformFilter);
        if(fareInput!=null&&fareInput.isAttachedToWindow()){
            state.putBundle("draft",captureDraft());
        } else if(restoredDraft!=null)state.putBundle("draft",restoredDraft);
    }
    private Bundle captureDraft(){
        Bundle draft=new Bundle();draft.putString("id",editing==null?"":editing.id);draft.putString("date",formDate);draft.putString("time",formTime);draft.putString("income",fareInput.getText().toString());draft.putString("cost",costInput.getText().toString());draft.putString("route",routeInput.getText().toString());draft.putString("note",noteInput.getText().toString());draft.putString("platformId",selectedFormPlatformId());return draft;
    }
    private void restoreDraft(){
        if(restoredDraft==null)return;Bundle d=restoredDraft;restoredDraft=null;
        try{String id=d.getString("id","");LedgerDb.Entry entry=id.isEmpty()?null:db.get(id);if(!id.isEmpty()&&entry==null)return;
            showForm(entry);formDate=d.getString("date",formDate);formTime=d.getString("time",formTime);dateField.setText(formDate);timeField.setText(formTime);
            fareInput.setText(d.getString("income",""));costInput.setText(d.getString("cost",""));routeInput.setText(d.getString("route",""));noteInput.setText(d.getString("note",""));refreshFormPlatforms(d.getString("platformId",""));
        }catch(Exception e){showHome();toast("未能恢复未保存的草稿，已保存的账目不受影响");}
    }
    @Override protected void onDestroy(){super.onDestroy();worker.shutdown();updateWorker.shutdownNow();}
    private void requestUnlock() {
        if(authPending)return;
        lastVerifiedAt=-1L;
        KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (!km.isDeviceSecure()) { showGate(); authCancelled=true; return; }
        Intent i=km.createConfirmDeviceCredentialIntent("打开顺程记","使用手机锁屏密码，查看你的私人账目");
        if (i==null) { showGate(); authCancelled=true; return; }
        authPending=true;
        unlocked=false;if(root!=null)root.setVisibility(View.INVISIBLE);
        try { startActivityForResult(i,AUTH); } catch(Exception e) { authPending=false; authCancelled=true; showGate(); error("无法打开系统验证，请检查手机锁屏设置"); }
    }
    private void showGate() {
        if(fareInput!=null&&fareInput.isAttachedToWindow())restoredDraft=captureDraft();
        gate=true; setupRoot();
        LinearLayout panel=column(); panel.setGravity(Gravity.CENTER); panel.setPadding(dp(28),dp(40),dp(28),dp(40));
        root.addView(panel,new LinearLayout.LayoutParams(-1,-1));
        panel.addView(label("顺",52,GREEN,true)); space(panel,18);
        panel.addView(label("顺程记",28,INK,true)); space(panel,12);
        TextView hint=label("每一程，都有收获。\n账目仅保存在这台手机。",16,MUTED,false); hint.setGravity(Gravity.CENTER); panel.addView(hint); space(panel,36);
        boolean secure=((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isDeviceSecure();
        panel.addView(button(secure?"验证并打开账本":"先设置手机锁屏密码",()->{
            authCancelled=false;
            if (((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isDeviceSecure()) requestUnlock();
            else startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        },true));
        space(panel,16); TextView t=label("使用手机已有的锁屏密码验证\n知道该密码的人也能打开账本",12,MUTED,false); t.setGravity(Gravity.CENTER);panel.addView(t);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==AUTH) {
            if(!authPending)return;
            authPending=false;
            if (result==RESULT_OK) { lastVerifiedAt=SystemClock.elapsedRealtime();openUnlockedLedger(); }
            else { lastVerifiedAt=-1L;unlocked=false;authCancelled=true;showGate(); }
        } else if (request==SAVE_FILE) {
            if(result==RESULT_OK&&data!=null) pendingSave=data.getData();
            else {pendingOutput=null;pendingExportType=null;}
        } else if(request==OPEN_FILE&&result==RESULT_OK&&data!=null) pendingInput=data.getData();
        else if(request==INSTALL_PERMISSION&&downloadedUpdate!=null){
            pendingUpdateUi=()->{if(getPackageManager().canRequestPackageInstalls())showInstallReady();else error("尚未允许安装更新。你可稍后在“检查更新”中重试。");};
        }
    }
    private void resumePending() {
        if(!unlocked) return;
        if(afterUnlock!=null) {Runnable r=afterUnlock;afterUnlock=null;r.run();return;}
        if(pendingSave!=null) {Uri u=pendingSave;pendingSave=null;writeFile(u);}
        else if(pendingInput!=null) {Uri u=pendingInput;pendingInput=null;readBackup(u);}
    }
    private void setupRoot() {
        if(body!=null)body.animate().cancel();
        if(glassNav!=null)glassNav.cancelSelectionAnimation();
        glassNav=null;
        homeScroll=null;recordRows=null;
        root=column();root.setBackgroundColor(BG);root.setFitsSystemWindows(true);setContentView(root);
        if(Build.VERSION.SDK_INT>=35) root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(s.left,s.top,s.right,s.bottom);return insets;
        });
    }
    private void refreshData() {entries=db.all();}
    private void showHome() {
        if(!unlocked){showGate();return;}
        gate=false;editing=null;platformManager=false;
        try {refreshData();} catch(Exception e){error("账本读取失败，请保留 App 数据后重试");return;}
        setupRoot();
        LinearLayout top=row();top.setPadding(dp(22),dp(20),dp(22),dp(10));top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=column();homeTitle=label("",25,INK,true);titles.addView(homeTitle);
        homeSubtitle=label("",12,MUTED,false);homeSubtitle.setPadding(0,dp(6),0,0);titles.addView(homeSubtitle);updateHomeTitle();
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));TextView badge=label("本地",12,GREEN,true);badge.setPadding(dp(12),dp(7),dp(12),dp(7));badge.setBackground(bg(BADGE,20));top.addView(badge);root.addView(top);
        FrameLayout stage=new FrameLayout(uiContext);stage.setClipChildren(false);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(uiContext);homeScroll=scroll;scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=column();body.setPadding(dp(22),dp(8),dp(22),dp(112));scroll.addView(body);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(tab==0) overview();else if(tab==1) records();else settings();
        GlassStyle.Panel nav=new GlassStyle.Panel(uiContext,scroll,darkMode);glassNav=nav;nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(5),dp(5),dp(5),dp(5));
        FrameLayout.LayoutParams dock=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);dock.setMargins(dp(24),0,dp(24),dp(12));stage.addView(nav,dock);
        for(int n=0;n<3;n++){
            final int idx=n;boolean selected=tab==n;LinearLayout item=column();navItems[n]=item;item.setGravity(Gravity.CENTER);item.setPadding(dp(8),dp(7),dp(8),dp(7));item.setMinimumHeight(dp(58));item.setFocusable(true);item.setSelected(selected);item.setContentDescription(TAB_NAMES[n]+(selected?"，已选中":""));
            item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(darkMode?0x338ad8b3:0x1517775c),null,bg(WHITE,28)));item.setElevation(0);
            GlassStyle.TabIcon icon=new GlassStyle.TabIcon(uiContext,n,selected,darkMode);navIcons[n]=icon;icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));space(item,2);
            TextView label=label(TAB_NAMES[n],11,selected?GREEN:NAV_TEXT,selected);navLabels[n]=label;label.setGravity(Gravity.CENTER);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label);
            LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,-2,1);slot.setMargins(dp(1),0,dp(1),0);nav.addView(item,slot);
            View.OnClickListener select=v->selectHomeTab(idx);item.setOnClickListener(select);label.setOnClickListener(select);
        }
        nav.setSelectedIndex(tab,false);
        nav.setOnSelectionRequestedListener(index->selectHomeTab(index));
        scroll.setOnScrollChangeListener((View view,int x,int y,int oldX,int oldY)->nav.refreshBackdrop());nav.post(()->{nav.refreshBackdrop();resumeUpdateUi();});
    }

    private void updateHomeTitle(){
        homeTitle.setText(tab==0?"顺程记":tab==1?"收入明细":"我的账本");
        homeSubtitle.setText(tab==0?"把每一程的收获，记下来。":tab==1?"每一笔收入，都有迹可循。":"一人一本，安心记录。");
    }
    private void selectHomeTab(int index){
        if(!unlocked||glassNav==null||homeScroll==null||index==tab)return;
        hideKeyboard();int direction=index>tab?1:-1;tab=index;visibleLimit=50;
        glassNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        glassNav.setSelectedIndex(index,true);
        for(int i=0;i<3;i++){
            boolean selected=i==index;navItems[i].setSelected(selected);navItems[i].setContentDescription(TAB_NAMES[i]+(selected?"，已选中":""));
            navIcons[i].setSelected(selected);navLabels[i].setTextColor(selected?GREEN:NAV_TEXT);navLabels[i].setTypeface(Typeface.create(selected?"sans-serif-medium":"sans-serif",0));
        }
        updateHomeTitle();body.animate().cancel();body.setAlpha(1f);body.setTranslationX(0f);body.removeAllViews();recordRows=null;homeScroll.scrollTo(0,0);
        if(tab==0)overview();else if(tab==1)records();else settings();
        boolean animate=android.provider.Settings.Global.getFloat(getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;
        if(animate){body.setAlpha(.65f);body.setTranslationX(dp(8)*direction);body.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).setUpdateListener(a->{if(glassNav!=null)glassNav.refreshBackdrop();}).start();}
        body.post(()->{if(glassNav!=null)glassNav.refreshBackdrop();});
    }

    private void monthSelector(LinearLayout parent) {
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev=label("‹",30,GREEN,false);prev.setGravity(Gravity.CENTER);prev.setContentDescription("上个月");line.addView(prev,new LinearLayout.LayoutParams(dp(44),dp(48)));prev.setOnClickListener(v->{month=month.minusMonths(1);showHome();});
        TextView title=label(month.getYear()+" 年 "+month.getMonthValue()+" 月",17,INK,true);title.setGravity(Gravity.CENTER);line.addView(title,new LinearLayout.LayoutParams(0,-2,1));title.setOnClickListener(v->{month=YearMonth.now();showHome();});
        TextView next=label("›",30,month.isBefore(YearMonth.now())?GREEN:DISABLED,false);next.setGravity(Gravity.CENTER);next.setContentDescription("下个月");line.addView(next,new LinearLayout.LayoutParams(dp(44),dp(48)));next.setOnClickListener(v->{if(month.isBefore(YearMonth.now())){month=month.plusMonths(1);showHome();}});parent.addView(line);space(parent,10);
    }
    private List<LedgerDb.Entry> monthly() {List<LedgerDb.Entry> result=new ArrayList<>();for(LedgerDb.Entry e:entries)if(e.date.startsWith(month.toString()))result.add(e);return result;}
    private long sum(List<LedgerDb.Entry> list,boolean cost) {long n=0;for(LedgerDb.Entry e:list)n+=cost?e.cost:e.income;return n;}
    private void overview() {
        monthSelector(body);List<LedgerDb.Entry> list=monthly();long income=sum(list,false),cost=sum(list,true);
        LinearLayout hero=card(MINT);hero.addView(label("本月收入 · 元",14,GREEN,false));space(hero,10);
        TextView amount=label(LedgerRules.format(income),42,INK,true);amount.setMaxLines(1);amount.setAutoSizeTextTypeUniformWithConfiguration(24,42,1,android.util.TypedValue.COMPLEX_UNIT_SP);hero.addView(amount,new LinearLayout.LayoutParams(-1,dp(60)));space(hero,18);
        LinearLayout metrics=row();metric(metrics,"行程",list.size()+" 笔");metric(metrics,"已记成本",LedgerRules.format(cost));metric(metrics,"扣成本后",LedgerRules.format(income-cost));hero.addView(metrics);body.addView(hero);space(body,16);
        LinearLayout today=card(WHITE);LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);long todayIncome=0;int todayCount=0;for(LedgerDb.Entry e:entries)if(e.date.equals(LocalDate.now().toString())){todayIncome+=e.income;todayCount++;}
        LinearLayout t=column();t.addView(label("今天的收获",15,INK,true));t.addView(label(todayCount+" 笔行程",12,MUTED,false));line.addView(t,new LinearLayout.LayoutParams(0,-2,1));line.addView(label("¥ "+LedgerRules.format(todayIncome),23,GREEN,true));today.addView(line);body.addView(today);space(body,18);
        body.addView(button("＋  记一笔收入",()->showForm(null),true));space(body,24);
        LinearLayout chart=card(WHITE);chart.addView(label("最近 7 天",16,INK,true));space(chart,8);chart.addView(new TrendView(),new LinearLayout.LayoutParams(-1,dp(150)));body.addView(chart);space(body,24);
        LinearLayout heading=row();heading.addView(label("最近记录",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));TextView more=label("查看全部  ›",13,GREEN,false);heading.addView(more);more.setOnClickListener(v->{allMonths=true;selectHomeTab(1);});body.addView(heading);space(body,10);
        if(entries.isEmpty()) empty(body,"还没有收入记录","完成一趟顺风车后，记下第一笔收获。");else for(int i=0;i<Math.min(entries.size(),4);i++)addEntryRow(body,entries.get(i),true);
    }
    private void metric(LinearLayout row,String title,String value) {LinearLayout c=column();c.addView(label(title,12,GREEN,false));space(c,6);c.addView(label(value,15,INK,true));row.addView(c,new LinearLayout.LayoutParams(0,-2,1));}
    private void records() {
        LinearLayout switcher=row();TextView current=label("按月查看",14,allMonths?MUTED:GREEN,!allMonths),all=label("全部记录",14,allMonths?GREEN:MUTED,allMonths);current.setPadding(dp(12),dp(12),dp(12),dp(12));all.setPadding(dp(12),dp(12),dp(12),dp(12));switcher.addView(current);switcher.addView(all);body.addView(switcher);current.setOnClickListener(v->{allMonths=false;showHome();});all.setOnClickListener(v->{allMonths=true;showHome();});if(!allMonths)monthSelector(body);
        EditText search=input("搜索路线或备注",false);search.setText(filter);body.addView(search);space(body,10);
        Spinner platforms=new Spinner(uiContext);List<LedgerDb.Platform> options=db.listPlatforms(true);List<String> choices=new ArrayList<>();choices.add("全部平台");int selected=0;for(int i=0;i<options.size();i++){LedgerDb.Platform item=options.get(i);choices.add(item.name+(item.archived?"（已停用）":""));if(item.id.equals(platformFilter))selected=i+1;}if(selected==0)platformFilter="";platforms.setAdapter(new ArrayAdapter<>(uiContext,android.R.layout.simple_spinner_dropdown_item,choices));platforms.setSelection(selected);body.addView(platforms,new LinearLayout.LayoutParams(-1,dp(48)));
        space(body,8);recordRows=column();body.addView(recordRows);renderRecords();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filter=s.toString();visibleLimit=50;renderRecords();}public void afterTextChanged(Editable e){}});
        platforms.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(tab!=1)return;String nextPlatform=pos==0?"":options.get(pos-1).id;if(nextPlatform.equals(platformFilter))return;platformFilter=nextPlatform;visibleLimit=50;renderRecords();}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        space(body,16);body.addView(button("＋  记一笔收入",()->showForm(null),true));
    }
    private void renderRecords() {
        if(glassNav!=null)glassNav.refreshBackdrop();
        if(recordRows==null)return;recordRows.removeAllViews();List<LedgerDb.Entry> matches=new ArrayList<>();String q=filter.trim().toLowerCase(Locale.ROOT);
        for(LedgerDb.Entry e:entries)if((allMonths||e.date.startsWith(month.toString()))&&(platformFilter.isEmpty()||platformFilter.equals(e.platformId))&&(q.isEmpty()||(e.route+" "+e.note+" "+e.platform).toLowerCase(Locale.ROOT).contains(q)))matches.add(e);
        TextView summary=label(matches.size()+" 笔  ·  收入 ¥ "+LedgerRules.format(sum(matches,false)),13,MUTED,false);summary.setPadding(0,dp(8),0,dp(16));recordRows.addView(summary);
        if(matches.isEmpty()){empty(recordRows,"没有找到记录","试试其他月份，或记下新的一笔。");return;}
        String last="";for(int i=0;i<Math.min(visibleLimit,matches.size());i++){LedgerDb.Entry e=matches.get(i);if(!e.date.equals(last)){TextView date=label(e.date,13,MUTED,true);date.setPadding(0,dp(14),0,dp(10));recordRows.addView(date);last=e.date;}addEntryRow(recordRows,e,false);}
        if(matches.size()>visibleLimit)recordRows.addView(button("再显示 50 笔",()->{visibleLimit+=50;renderRecords();},false));
    }
    private void addEntryRow(LinearLayout parent,LedgerDb.Entry e,boolean showDate) {
        LinearLayout card=card(WHITE);card.setPadding(dp(16),dp(16),dp(16),dp(16));LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left=column();TextView route=label(e.route.isEmpty()?e.platform+"顺风车":e.route,15,INK,true);route.setMaxLines(2);left.addView(route);space(left,6);left.addView(label((showDate?e.date+"  ":"")+e.time+" · "+e.platform,12,MUTED,false));line.addView(left,new LinearLayout.LayoutParams(0,-2,1));TextView value=label("+"+LedgerRules.format(e.income),19,GREEN,true);value.setPadding(dp(12),0,0,0);line.addView(value);card.addView(line);parent.addView(card);space(parent,8);card.setOnClickListener(v->showForm(e));card.setContentDescription((e.route.isEmpty()?e.platform:e.route)+"，收入"+LedgerRules.format(e.income)+"元，点击编辑");
    }
    private void settings() {
        space(body,12);LinearLayout intro=card(MINT);intro.addView(label("账本只属于你",22,INK,true));space(intro,10);intro.addView(label("离线记录，无广告、无订阅。\n已保存 "+entries.size()+" 笔行程。",14,GREEN,false));body.addView(intro);space(body,24);
        body.addView(button("外观设置",()->showAppearanceSettings(),false));space(body,8);body.addView(label("当前："+appearanceName()+" · 可跟随系统或手动选择",12,MUTED,false));space(body,24);
        body.addView(button("验证有效期",()->showVerificationSettings(),false));space(body,8);body.addView(label("当前："+verificationLabel(),12,MUTED,false));space(body,6);body.addView(label("到期后下次进入再验证，不打断正在编辑的账目。\n应用被系统结束或手机重启后，需重新验证。",12,MUTED,false));space(body,24);
        body.addView(button("平台管理",()->showPlatforms(),false));space(body,8);body.addView(label("添加、改名、停用或合并平台，历史账目保持关联。",12,MUTED,false));space(body,24);
        body.addView(button("应用更新",()->showUpdateSettings(),false));space(body,8);body.addView(label("自动检查："+(automaticUpdates()?"已开启":"已关闭")+" · 当前版本 "+currentVersion(),12,MUTED,false));space(body,24);
        body.addView(label("备份与迁移",18,INK,true));space(body,10);
        body.addView(button("保存加密备份",()->passwordPrompt(true,null),true));space(body,10);body.addView(button("从备份恢复",()->{
            if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);try{startActivityForResult(i,OPEN_FILE);}catch(Exception e){error("手机未提供文件选择器");}
        },false));space(body,10);body.addView(button("导出 Excel 可读的 CSV",()->new SafeBuilder(this).setTitle("导出明文表格").setMessage("CSV 包含完整账目，没有密码保护。请选择手机本地文件夹并妥善保管。若用于换手机，建议使用加密备份。").setNegativeButton("取消",null).setPositiveButton("继续导出",(d,w)->{
            if(!unlocked)return;try{pendingOutput=db.exportCsv().getBytes(StandardCharsets.UTF_8);pendingExportType="csv";chooseSave("text/csv","顺程记-"+LocalDate.now()+".csv");}catch(Exception e){error("导出失败，请重试");}
        }).show(),false));space(body,16);
        String last=getPreferences(MODE_PRIVATE).getString("lastBackup","尚未备份");body.addView(label("上次保存加密备份："+last,12,MUTED,false));space(body,24);
        LinearLayout tips=card(WHITE);tips.addView(label("数据保管",16,INK,true));space(tips,10);tips.addView(label("• 卸载 App 或清除数据会删除账目。\n• 换手机前，保存加密备份并记住密码。\n• 建议每月备份，并另存一份到电脑。\n• 恢复时只追加缺少的记录，不覆盖已有记录。",13,MUTED,false));body.addView(tips);space(body,20);
        body.addView(button("立即锁定账本",()->lockImmediately(),false));space(body,18);TextView foot=label("顺程记 "+currentVersion()+"\n本机存储 · 系统锁屏验证\n编写者：Codex",12,MUTED,false);foot.setGravity(Gravity.CENTER);body.addView(foot);
    }
    private String currentVersion(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "1.6";}
    }
    private boolean automaticUpdates(){return getPreferences(MODE_PRIVATE).getBoolean("automatic_updates",true);}
    private void showUpdateSettings(){
        if(!unlocked)return;
        new SafeBuilder(this).setTitle("应用更新 · "+currentVersion()).setItems(new String[]{
            "检查更新",automaticUpdates()?"关闭自动检查":"开启自动检查","查看 GitHub 开源仓库","关于顺程记","继续安装已下载的更新"
        },(dialog,which)->{
            dialog.dismiss();
            if(which==0)checkUpdates(true);
            else if(which==1){getPreferences(MODE_PRIVATE).edit().putBoolean("automatic_updates",!automaticUpdates()).apply();showHome();}
            else if(which==2)openRepository();
            else if(which==4){if(downloadedUpdate!=null)showInstallReady();else toast("暂无已下载的更新，请先检查更新");}
            else new SafeBuilder(this).setTitle("顺程记").setMessage("每一程，都有收获。\n\n编写者：Codex\n开源维护：RoadKai233\n许可证：MIT\n\n账目只保存在本机，不上传到 GitHub。更新检查只访问公开版本信息；自动检查最多每 12 小时一次，下载前会提示。安装由 Android 确认。")
                .setPositiveButton("知道了",null).show();
        }).setNegativeButton("关闭",null).show();
    }
    private void openRepository(){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RoadKai233/shuncheng-ledger")));}
        catch(Exception ignored){error("没有可用的浏览器");}
    }
    private void maybeCheckUpdates(){
        if(!unlocked||!foreground||updateWorking)return;
        if(!updateCacheLoaded){restoreDownloadedUpdate();return;}
        if(!automaticUpdates()||pendingUpdateUi!=null)return;
        long age=System.currentTimeMillis()-getPreferences(MODE_PRIVATE).getLong("update_checked_at",0);
        if(age>=0&&age<12*60*60*1000L)return;
        checkUpdates(false);
    }
    private void restoreDownloadedUpdate(){
        updateCacheLoaded=true;updateWorking=true;
        updateWorker.execute(()->{
            UpdateClient.Release release=null;File file=null;
            try{release=UpdateClient.loadDownloadedRelease(getApplicationContext());if(release!=null)file=UpdateClient.getVerifiedFile(getApplicationContext());}catch(Exception ignored){}
            final UpdateClient.Release cachedRelease=release;final File cachedFile=file;
            runOnUiThread(()->{
                updateWorking=false;if(isFinishing()||isDestroyed())return;
                if(cachedRelease!=null&&cachedFile!=null){downloadedRelease=cachedRelease;downloadedUpdate=cachedFile;queueUpdateUi(()->showInstallReady());}
                else maybeCheckUpdates();
            });
        });
    }
    private void checkUpdates(boolean manual){
        if(!unlocked||!foreground)return;
        if(updateWorking){if(manual)toast("正在处理更新，请稍候");return;}
        updateWorking=true;getPreferences(MODE_PRIVATE).edit().putLong("update_checked_at",System.currentTimeMillis()).apply();
        if(manual)toast("正在检查 GitHub 新版本…");
        updateWorker.execute(()->{
            UpdateClient.Release found=null;boolean failed=false;
            try{found=UpdateClient.check(getApplicationContext());}catch(Exception ignored){failed=true;}
            final UpdateClient.Release release=found;final boolean errorOccurred=failed;
            runOnUiThread(()->{
                updateWorking=false;if(isFinishing()||isDestroyed()||(!manual&&!automaticUpdates()))return;
                if(errorOccurred){if(manual)queueUpdateUi(()->error("暂时无法检查更新，请检查网络后重试。账本可继续离线使用。"));}
                else if(release==null){if(manual)queueUpdateUi(()->toast("已是最新版本 "+currentVersion()));}
                else queueUpdateUi(()->showAvailableUpdate(release));
            });
        });
    }
    private void queueUpdateUi(Runnable action){pendingUpdateUi=action;resumeUpdateUi();}
    private void resumeUpdateUi(){
        if(!unlocked||!foreground||busy||!openDialogs.isEmpty()||isFinishing()||isDestroyed())return;
        // Do not interrupt an unsaved entry, platform dialog, or a pending backup flow.
        if((fareInput!=null&&fareInput.isAttachedToWindow())||platformManager||afterUnlock!=null||pendingInput!=null||pendingSave!=null)return;
        if(pendingUpdateUi!=null){Runnable action=pendingUpdateUi;pendingUpdateUi=null;action.run();}
    }
    private void showAvailableUpdate(UpdateClient.Release release){
        if(!unlocked)return;
        String size=String.format(Locale.ROOT,"%.1f",release.size/1024.0/1024.0);
        new SafeBuilder(this).setTitle("发现新版本 "+release.versionName)
            .setMessage((release.notes.isEmpty()?"有新版本可用。":release.notes)+"\n\n安装包："+size+" MB\n下载后将校验完整性与签名，保留本机账目。")
            .setNegativeButton("稍后",null).setPositiveButton("下载更新",(d,w)->downloadUpdate(release)).show();
    }
    private void downloadUpdate(UpdateClient.Release release){
        if(!unlocked||updateWorking)return;
        updateWorking=true;toast("正在下载更新，完成后会提示安装");
        updateWorker.execute(()->{
            File result=null;try{result=UpdateClient.downloadAndVerify(getApplicationContext(),release);}catch(Exception ignored){}
            final File file=result;runOnUiThread(()->{
                updateWorking=false;if(isFinishing()||isDestroyed())return;
                if(file==null)queueUpdateUi(()->error("更新下载或校验失败，请稍后重试。未安装任何更新。"));
                else{downloadedUpdate=file;downloadedRelease=release;queueUpdateUi(()->showInstallReady());}
            });
        });
    }
    private void showInstallReady(){
        if(!unlocked||downloadedUpdate==null||downloadedRelease==null)return;
        new SafeBuilder(this).setTitle("更新已准备好")
            .setMessage("顺程记 "+downloadedRelease.versionName+" 已下载并通过校验。继续后由 Android 确认安装，原有账目会保留。")
            .setNegativeButton("稍后",null).setPositiveButton("继续安装",(d,w)->installUpdate()).show();
    }
    private void installUpdate(){
        if(!unlocked||!foreground||updateWorking||downloadedUpdate==null||downloadedRelease==null)return;
        if(!getPackageManager().canRequestPackageInstalls()){
            new SafeBuilder(this).setTitle("允许安装应用更新")
                .setMessage("请在系统页面允许顺程记安装更新，返回并解锁后再继续安装。")
                .setNegativeButton("取消",null).setPositiveButton("前往设置",(d,w)->{
                    try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),INSTALL_PERMISSION);}
                    catch(Exception ignored){error("无法打开安装权限设置，请在手机设置中允许顺程记安装未知应用后重试。");}
                }).show();return;
        }
        final long epoch=interactionEpoch;final File file=downloadedUpdate;final UpdateClient.Release release=downloadedRelease;
        updateWorking=true;
        updateWorker.execute(()->{
            boolean valid=true;try{UpdateClient.verifyDownloaded(getApplicationContext(),release,file);}catch(Exception ignored){valid=false;}
            final boolean verified=valid;runOnUiThread(()->{
                updateWorking=false;if(isFinishing()||isDestroyed())return;
                if(!verified){downloadedUpdate=null;downloadedRelease=null;queueUpdateUi(()->error("安装包校验失败，请重新检查更新并下载。"));return;}
                if(!foreground||!isDialogInteractionAllowed(epoch)||busy||!openDialogs.isEmpty()||platformManager||(fareInput!=null&&fareInput.isAttachedToWindow())){queueUpdateUi(()->showInstallReady());return;}
                try{startActivity(UpdateClient.createInstallIntent(this,file));}
                catch(Exception ignored){error("无法打开系统安装器，请稍后重试。");}
            });
        });
    }
    private void showForm(LedgerDb.Entry entry) {
        if(!unlocked){showGate();return;}
        editing=entry;platformManager=false;setupRoot();gate=false;LinearLayout top=row();top.setPadding(dp(16),dp(12),dp(22),dp(8));top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=label("‹",32,GREEN,false);back.setGravity(Gravity.CENTER);top.addView(back,new LinearLayout.LayoutParams(dp(46),dp(48)));back.setOnClickListener(v->confirmLeave());top.addView(label(entry==null?"记一笔收入":"编辑这笔收入",22,INK,true));root.addView(top);
        ScrollView scroll=new ScrollView(uiContext);LinearLayout form=column();form.setPadding(dp(24),dp(16),dp(24),dp(32));scroll.addView(form);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        form.addView(label("实际到账收入 · 元",14,GREEN,true));space(form,8);fareInput=input("0.00",true);fareInput.setTextSize(38);fareInput.setBackground(bg(MINT,20));fareInput.setPadding(dp(20),dp(22),dp(20),dp(22));if(entry!=null)fareInput.setText(LedgerRules.format(entry.income));form.addView(fareInput);space(form,24);
        formDate=entry==null?LocalDate.now().toString():entry.date;formTime=entry==null?LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")):entry.time;
        fieldLabel(form,"日期与时间");LinearLayout dates=row();Button dateButton=button(formDate,()->{},false),timeButton=button(formTime,()->{},false);dateField=dateButton;timeField=timeButton;dates.addView(dateButton,new LinearLayout.LayoutParams(0,dp(54),2));View gap=new View(uiContext);dates.addView(gap,new LinearLayout.LayoutParams(dp(10),1));dates.addView(timeButton,new LinearLayout.LayoutParams(0,dp(54),1));form.addView(dates);
        dateButton.setOnClickListener(v->{if(!unlocked)return;final long epoch=interactionEpoch;LocalDate d=LocalDate.parse(formDate);DatePickerDialog picker=new DatePickerDialog(uiContext,(p,y,m,day)->{if(!isDialogInteractionAllowed(epoch)||!p.isShown())return;formDate=LocalDate.of(y,m+1,day).toString();dateButton.setText(formDate);},d.getYear(),d.getMonthValue()-1,d.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.getDatePicker().setMinDate(LocalDate.of(2000,1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());secureDialog(picker);picker.show();});
        timeButton.setOnClickListener(v->{if(!unlocked)return;final long epoch=interactionEpoch;LocalTime t=LocalTime.parse(formTime);TimePickerDialog picker=new TimePickerDialog(uiContext,(p,h,m)->{if(!isDialogInteractionAllowed(epoch)||!p.isShown())return;formTime=String.format(Locale.ROOT,"%02d:%02d",h,m);timeButton.setText(formTime);},t.getHour(),t.getMinute(),true);secureDialog(picker);picker.show();});
        fieldLabel(form,"平台");platformInput=new Spinner(uiContext);platformInput.setBackground(bg(WHITE,14));form.addView(platformInput,new LinearLayout.LayoutParams(-1,dp(54)));
        refreshFormPlatforms(entry==null?getPreferences(MODE_PRIVATE).getString("platformId",""):entry.platformId);
        space(form,8);form.addView(button("＋ 添加平台",()->platformNameDialog(null,true),false));
        fieldLabel(form,"路线（选填）");routeInput=input("例如：家附近 → 公司附近",false);routeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});if(entry!=null)routeInput.setText(entry.route);form.addView(routeInput);
        fieldLabel(form,"本次成本（选填）");costInput=input("0.00",true);if(entry!=null&&entry.cost>0)costInput.setText(LedgerRules.format(entry.cost));form.addView(costInput);TextView tip=label("可合计油费、电费、高速费等；不填按 0 计算。",12,MUTED,false);tip.setPadding(0,dp(8),0,0);form.addView(tip);
        fieldLabel(form,"备注（选填）");noteInput=input("记录这次行程的小事",false);noteInput.setSingleLine(false);noteInput.setMinLines(2);noteInput.setMaxLines(5);noteInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});if(entry!=null)noteInput.setText(entry.note);form.addView(noteInput);space(form,28);
        form.addView(button("保存这一笔",()->saveEntry(),true));if(entry!=null){space(form,12);Button del=button("删除这笔记录",()->new SafeBuilder(this).setTitle("删除这笔记录？").setMessage("收入 ¥ "+LedgerRules.format(entry.income)+"，删除后可通过此前备份找回。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(!unlocked)return;try{db.delete(entry.id);showHome();toast("记录已删除");}catch(Exception e){error("删除失败，请重试");}}).show(),false);del.setTextColor(DANGER);form.addView(del);}
        form.setFocusableInTouchMode(true);form.requestFocus();
    }
    private String selectedFormPlatformId(){
        int pos=platformInput==null?-1:platformInput.getSelectedItemPosition();return pos>=0&&pos<formPlatforms.size()?formPlatforms.get(pos).id:"";
    }
    private void refreshFormPlatforms(String selectedId){
        if(selectedId==null)selectedId="";
        if(!selectedId.isEmpty()){LedgerDb.Platform remembered=db.getPlatform(selectedId);if(remembered!=null)selectedId=remembered.id;}
        formPlatforms=new ArrayList<>(db.listPlatforms(false));
        if(editing!=null&&editing.platformId!=null){LedgerDb.Platform old=db.getPlatform(editing.platformId);if(old!=null&&old.archived)formPlatforms.add(old);}
        List<String> names=new ArrayList<>();int selected=0;String legacy=getPreferences(MODE_PRIVATE).getString("platform","其他");
        for(int i=0;i<formPlatforms.size();i++){LedgerDb.Platform item=formPlatforms.get(i);names.add(item.name+(item.archived?"（已停用，仅保留历史）":""));if(item.id.equals(selectedId)||(selectedId.isEmpty()&&item.name.equals(legacy)))selected=i;}
        platformInput.setAdapter(new ArrayAdapter<>(uiContext,android.R.layout.simple_spinner_dropdown_item,names));if(!names.isEmpty())platformInput.setSelection(selected);
    }
    private void showPlatforms(){
        if(!unlocked){showGate();return;}platformManager=true;editing=null;gate=false;
        final List<LedgerDb.Platform> platforms;
        try{refreshData();platforms=db.listPlatforms(true);}catch(Exception e){error("平台读取失败，请重试");return;}
        setupRoot();LinearLayout top=row();top.setPadding(dp(16),dp(12),dp(22),dp(8));top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=label("‹",32,GREEN,false);back.setGravity(Gravity.CENTER);top.addView(back,new LinearLayout.LayoutParams(dp(46),dp(48)));back.setOnClickListener(v->{tab=2;showHome();});top.addView(label("平台管理",22,INK,true));root.addView(top);
        ScrollView scroll=new ScrollView(uiContext);LinearLayout content=column();content.setPadding(dp(22),dp(12),dp(22),dp(28));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tip=card(MINT);tip.addView(label("历史账目，始终关联",19,INK,true));space(tip,8);tip.addView(label("改名会同步更新历史账目的平台名称。停用后仍可查账；删除有历史记录的平台时，可把账目转到另一个平台。",13,GREEN,false));content.addView(tip);space(content,16);
        content.addView(button("＋ 添加平台",()->platformNameDialog(null,false),true));space(content,22);
        Map<String,Long> totals=new HashMap<>();for(LedgerDb.Entry e:entries)totals.put(e.platformId,totals.getOrDefault(e.platformId,0L)+e.income);
        for(LedgerDb.Platform item:platforms){
            LinearLayout c=card(WHITE),line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView name=label(item.name,18,INK,true);name.setMaxLines(2);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView status=label(item.archived?"已停用":"使用中",12,item.archived?MUTED:GREEN,true);status.setPadding(dp(10),dp(6),dp(10),dp(6));status.setBackground(bg(item.archived?BG:STATUS,12));line.addView(status);c.addView(line);space(c,8);
            c.addView(label("历史 "+item.entryCount+" 笔 · 收入 ¥ "+LedgerRules.format(totals.getOrDefault(item.id,0L)),13,MUTED,false));space(c,12);
            LinearLayout actions=row();platformAction(actions,"查看账目",()->{tab=1;allMonths=true;platformFilter=item.id;filter="";showHome();});platformAction(actions,"管理",()->platformActions(item));c.addView(actions);content.addView(c);space(content,12);
        }
        content.addView(label("至少保留一个启用的平台。停用的平台仍可重新启用。",12,MUTED,false));
    }
    private void platformAction(LinearLayout row,String title,Runnable action){
        TextView t=label(title,14,GREEN,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(12),dp(8),dp(12));GlassStyle.applyChip(t,false,darkMode);LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,-2,1);slot.setMargins(dp(3),0,dp(3),0);row.addView(t,slot);t.setOnClickListener(v->{if(unlocked)action.run();});
    }
    private void platformNameDialog(LedgerDb.Platform platform,boolean fromForm){
        if(!unlocked)return;final long epoch=interactionEpoch;LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),0);EditText name=input("输入平台名称",false);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});if(platform!=null)name.setText(platform.name);box.addView(name);space(box,8);box.addView(label(platform==null?"最多 30 个字，平台名称不能重复。":"历史账目会同步显示新名称，金额和日期保持不变。",12,MUTED,false));
        AlertDialog dialog=new SafeBuilder(this).setTitle(platform==null?"添加平台":"修改平台名称").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{
            if(!isDialogInteractionAllowed(epoch)||!dialog.isShowing())return;try{String id;if(platform==null)id=db.addPlatform(name.getText().toString()).id;else{db.renamePlatform(platform.id,name.getText().toString());id=platform.id;}dialog.dismiss();hideKeyboard();if(fromForm)refreshFormPlatforms(id);else showPlatforms();toast(platform==null?"平台已添加":"名称及历史关联已更新");}catch(IllegalArgumentException|IllegalStateException e){name.setError(e.getMessage());}catch(Exception e){error("平台保存失败，请重试");}
        }));dialog.show();
    }
    private void platformActions(LedgerDb.Platform item){
        if(!unlocked)return;new SafeBuilder(this).setTitle(item.name).setItems(new String[]{"修改名称",item.archived?"重新启用":"停用，保留历史账目","删除平台"},(d,which)->{
            if(!unlocked)return;if(which==0){platformNameDialog(item,false);return;}if(which==2){deletePlatformDialog(item);return;}
            if(item.archived){try{db.restorePlatform(item.id);showPlatforms();toast("平台已启用");}catch(Exception e){error(e.getMessage());}return;}
            new SafeBuilder(this).setTitle("停用「"+item.name+"」？").setMessage("原有 "+item.entryCount+" 笔账目保留关联，仍可查询和修改；新增账目将不再选择此平台。你可以随时重新启用。").setNegativeButton("取消",null).setPositiveButton("停用",(confirm,w)->{if(!unlocked)return;try{db.archivePlatform(item.id);showPlatforms();toast("平台已停用");}catch(Exception e){error(e.getMessage());}}).show();
        }).show();
    }
    private void deletePlatformDialog(LedgerDb.Platform item){
        if(!unlocked)return;
        if(item.entryCount==0){new SafeBuilder(this).setTitle("删除「"+item.name+"」？").setMessage("此平台没有关联账目，可以直接删除。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(!unlocked)return;try{db.deletePlatform(item.id,null);showPlatforms();toast("平台已删除");}catch(Exception e){error(e.getMessage());}}).show();return;}
        List<LedgerDb.Platform> targets=new ArrayList<>();for(LedgerDb.Platform p:db.listPlatforms(false))if(!p.id.equals(item.id))targets.add(p);
        if(targets.isEmpty()){error("请先添加或启用另一个平台，用于接收历史账目；也可以保留当前平台。");return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),0);box.addView(label("把「"+item.name+"」的 "+item.entryCount+" 笔账目关联到：",14,INK,false));space(box,12);Spinner target=new Spinner(uiContext);List<String> names=new ArrayList<>();for(LedgerDb.Platform p:targets)names.add(p.name);target.setAdapter(new ArrayAdapter<>(uiContext,android.R.layout.simple_spinner_dropdown_item,names));box.addView(target,new LinearLayout.LayoutParams(-1,dp(52)));space(box,12);box.addView(label("收入、成本、日期和备注全部保留；原平台将从列表移除。",13,MUTED,false));
        new SafeBuilder(this).setTitle("转移账目并删除平台").setView(box).setNegativeButton("取消",null).setPositiveButton("转移并删除",(d,w)->{if(!unlocked)return;String targetId=targets.get(target.getSelectedItemPosition()).id;try{db.deletePlatform(item.id,targetId);if(platformFilter.equals(item.id))platformFilter=targetId;showPlatforms();toast("历史账目已转移，原平台已删除");}catch(Exception e){error(e.getMessage());}}).show();
    }
    private void saveEntry() {
        if(!unlocked||busy)return;
        try {
            LedgerDb.Entry e=new LedgerDb.Entry();if(editing!=null){e.id=editing.id;e.createdAt=editing.createdAt;}
            e.date=LedgerRules.date(formDate);e.time=LedgerRules.time(formTime);e.income=LedgerRules.money(fareInput.getText().toString(),false);String cost=costInput.getText().toString().trim();e.cost=LedgerRules.money(cost.isEmpty()?"0":cost,true);e.route=routeInput.getText().toString();e.note=noteInput.getText().toString();if(formPlatforms.isEmpty())throw new IllegalArgumentException("请先添加或启用一个平台");LedgerDb.Platform selected=formPlatforms.get(platformInput.getSelectedItemPosition());e.platformId=selected.id;e.platform=selected.name;
            db.save(e);getPreferences(MODE_PRIVATE).edit().putString("platformId",e.platformId).putString("platform",e.platform).apply();hideKeyboard();showHome();toast("收入已记下");
        }catch(IllegalArgumentException e){error(e.getMessage());}catch(Exception e){error("保存失败，请检查手机剩余空间后重试");}
    }
    private void confirmLeave() {new SafeBuilder(this).setTitle("返回账本？").setMessage("当前未保存的修改会丢失。").setNegativeButton("继续编辑",null).setPositiveButton("返回",(d,w)->{hideKeyboard();showHome();}).show();}
    @Override public void onBackPressed() {if(!unlocked){finish();return;}if(fareInput!=null&&fareInput.isAttachedToWindow()){confirmLeave();return;}if(platformManager){tab=2;showHome();return;}if(tab!=0){if(glassNav!=null)selectHomeTab(0);else{tab=0;showHome();}return;}super.onBackPressed();}
    private void passwordPrompt(boolean export,byte[] encrypted) {
        if(!unlocked||busy)return;final long epoch=interactionEpoch;LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),0);box.addView(label(export?"设置至少 8 位的备份密码。请妥善保存，密码遗失无法恢复。":"输入创建这份备份时设置的密码。",14,MUTED,false));space(box,12);EditText pass=input("备份密码",false);pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);pass.setTransformationMethod(PasswordTransformationMethod.getInstance());box.addView(pass);
        EditText confirm=input("再输入一次密码",false);confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);confirm.setTransformationMethod(PasswordTransformationMethod.getInstance());if(export){space(box,10);box.addView(confirm);}
        AlertDialog dialog=new SafeBuilder(this).setTitle(export?"加密备份":"恢复账本").setView(box).setNegativeButton("取消",null).setPositiveButton(export?"保存备份":"恢复",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{if(!isDialogInteractionAllowed(epoch)||!dialog.isShowing())return;String p=pass.getText().toString();if(export&&(p.length()<8||!p.equals(confirm.getText().toString()))){pass.setError(p.length()<8?"至少输入 8 位":"两次输入的密码不一致");return;}char[] chars=p.toCharArray();pass.setText("");confirm.setText("");dialog.dismiss();if(export)encryptBackup(chars);else restoreBackup(encrypted,chars);}));dialog.setOnDismissListener(v->{pass.setText("");confirm.setText("");dialogDismissed(dialog);});dialog.show();
    }
    private void encryptBackup(char[] password) {
        final byte[] plain;try{plain=db.exportJson();}catch(Exception e){Arrays.fill(password,'\0');error("无法读取账目，请重试");return;}
        async("正在加密备份…",()->{try{return BackupCrypto.encrypt(plain,password);}finally{Arrays.fill(password,'\0');Arrays.fill(plain,(byte)0);}},result->{pendingOutput=(byte[])result;pendingExportType="backup";chooseSave("application/octet-stream","顺程记-"+LocalDate.now()+".sfbook");},"加密备份失败，请重试");
    }
    private void chooseSave(String mime,String name) {
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,name);intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        try{startActivityForResult(intent,SAVE_FILE);}catch(Exception e){pendingOutput=null;error("手机未提供文件保存功能");}
    }
    private void writeFile(Uri uri) {
        if(pendingOutput==null){error("导出已中断，请重新导出");return;}
        final byte[] bytes=pendingOutput;final String type=pendingExportType;pendingOutput=null;pendingExportType=null;
        async("正在保存文件…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(bytes);out.flush();}return null;},result->{if("backup".equals(type))getPreferences(MODE_PRIVATE).edit().putString("lastBackup",LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).apply();showHome();toast("文件已保存");},"文件保存失败，请选择其他本地文件夹重试");
    }
    private void readBackup(Uri uri) {
        async("正在读取备份…",()->{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException();byte[] buffer=new byte[8192];int read;while((read=in.read(buffer))!=-1){if(out.size()+read>BackupCrypto.MAX_BYTES)throw new IOException();out.write(buffer,0,read);}return out.toByteArray();}},result->passwordPrompt(false,(byte[])result),"读取失败，请选择小于 12 MB 的 .sfbook 备份文件");
    }
    private void restoreBackup(byte[] encrypted,char[] password) {
        async("正在解密并恢复…",()->{byte[] plain=null;try{plain=BackupCrypto.decrypt(encrypted,password);return db.importJson(plain);}finally{Arrays.fill(password,'\0');if(plain!=null)Arrays.fill(plain,(byte)0);}},result->{showHome();new SafeBuilder(this).setTitle("恢复完成").setMessage("新增 "+result+" 笔记录。已有账目不会被覆盖；平台及历史关联一并恢复，本机已有的平台设置优先保留。").setPositiveButton("知道了",null).show();},"恢复失败：密码不正确、文件损坏或备份格式不兼容。原有账目保持不变。");
    }
    private interface Work {Object run()throws Exception;}
    private interface Done {void accept(Object result);}
    private void async(String message,Work task,Done done,String errorMessage) {
        if(busy)return;busy=true;ProgressDialog progress=new ProgressDialog(uiContext);progress.setMessage(message);progress.setCancelable(false);secureDialog(progress);progress.show();
        worker.execute(()->{Object result=null;Exception error=null;try{result=task.run();}catch(Exception e){error=e;}final Object value=result;final boolean failed=error!=null;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;progress.dismiss();Runnable action=()->{if(failed)error(errorMessage);else done.accept(value);};if(unlocked)action.run();else afterUnlock=action;});});
    }
    private void secureDialog(Dialog dialog){
        openDialogs.add(dialog);if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d->dialogDismissed(dialog));
    }
    private class SafeBuilder extends AlertDialog.Builder {
        private final long epoch=interactionEpoch;
        SafeBuilder(Context context){super(uiContext);}
        private DialogInterface.OnClickListener guarded(DialogInterface.OnClickListener listener){
            if(listener==null)return null;
            return (dialog,which)->{if(isDialogInteractionAllowed(epoch)&&(!(dialog instanceof Dialog)||((Dialog)dialog).isShowing()))listener.onClick(dialog,which);};
        }
        @Override public SafeBuilder setPositiveButton(CharSequence text,DialogInterface.OnClickListener listener){super.setPositiveButton(text,guarded(listener));return this;}
        @Override public SafeBuilder setNegativeButton(CharSequence text,DialogInterface.OnClickListener listener){super.setNegativeButton(text,guarded(listener));return this;}
        @Override public SafeBuilder setItems(CharSequence[] items,DialogInterface.OnClickListener listener){super.setItems(items,guarded(listener));return this;}
        @Override public SafeBuilder setSingleChoiceItems(CharSequence[] items,int selected,DialogInterface.OnClickListener listener){super.setSingleChoiceItems(items,selected,guarded(listener));return this;}
        @Override public AlertDialog create(){AlertDialog d=super.create();secureDialog(d);return d;}
        @Override public AlertDialog show(){AlertDialog d=super.show();secureDialog(d);return d;}
    }
    private void hideKeyboard(){View v=getCurrentFocus();if(v!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);}
    private void fieldLabel(LinearLayout p,String s){space(p,20);TextView t=label(s,14,INK,true);p.addView(t);space(p,9);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(uiContext);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(uiContext);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView label(String s,float size,int color,boolean bold){TextView t=new TextView(uiContext);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));return t;}
    private GradientDrawable bg(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
    private LinearLayout card(int color){LinearLayout c=column();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(bg(color,22));return c;}
    private void space(LinearLayout p,int size){View v=new View(uiContext);p.addView(v,new LinearLayout.LayoutParams(1,dp(size)));}
    private Button button(String text,Runnable action,boolean primary){Button b=new Button(uiContext);b.setText(text);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(primary?WHITE:GREEN);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setPadding(dp(16),dp(14),dp(16),dp(14));b.setMinHeight(dp(54));GlassStyle.applyButton(b,primary,darkMode);b.setOnClickListener(v->{if(unlocked||gate)action.run();});b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;}
    private EditText input(String hint,boolean numeric){EditText e=new EditText(uiContext);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(HINT);e.setHint(hint);e.setPadding(dp(16),dp(14),dp(16),dp(14));e.setBackground(bg(WHITE,14));e.setSingleLine(true);e.setInputType(numeric?android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:android.text.InputType.TYPE_CLASS_TEXT);if(numeric)e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});return e;}
    private void empty(LinearLayout parent,String title,String hint){LinearLayout c=card(WHITE);c.setGravity(Gravity.CENTER);c.addView(label(title,17,INK,true));space(c,10);TextView t=label(hint,13,MUTED,false);t.setGravity(Gravity.CENTER);c.addView(t);parent.addView(c);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void error(String message){if(!isFinishing())new SafeBuilder(this).setTitle("提示").setMessage(message).setPositiveButton("知道了",null).show();}
    private class TrendView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final long[] values=new long[7];private final String[] labels=new String[7];
        TrendView(){super(MainActivity.this.uiContext);StringBuilder desc=new StringBuilder("最近七天收入：");for(int i=0;i<7;i++){LocalDate d=LocalDate.now().minusDays(6-i);labels[i]=d.getMonthValue()+"/"+d.getDayOfMonth();for(LedgerDb.Entry e:entries)if(e.date.equals(d.toString()))values[i]+=e.income;desc.append(labels[i]).append("，").append(LedgerRules.format(values[i])).append("元；");}setContentDescription(desc.toString());setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        protected void onDraw(Canvas c){super.onDraw(c);long max=1;for(long v:values)max=Math.max(max,v);float unit=getWidth()/7f,base=getHeight()-dp(25),height=getHeight()-dp(53);paint.setTextAlign(Paint.Align.CENTER);for(int i=0;i<7;i++){float x=unit*(i+.5f),bar=values[i]==0?dp(3):Math.max(dp(6),height*values[i]/max);paint.setColor(i==6?GREEN:CHART_BAR);c.drawRoundRect(x-dp(12),base-bar,x+dp(12),base,dp(5),dp(5),paint);paint.setTextSize(dp(10));paint.setColor(MUTED);c.drawText(labels[i],x,getHeight()-dp(6),paint);if(values[i]>0){paint.setTextSize(dp(9));c.drawText(values[i]>=100000?String.format(Locale.ROOT,"%.1fk",values[i]/100000f):LedgerRules.format(values[i]),x,base-bar-dp(7),paint);}}}
    }
}
