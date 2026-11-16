package cn.personal.shunfeng.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline, emulator-only updater checks. Synthetic APKs are verified, never installed. */
public final class UpdateChecks extends Instrumentation {
    private Class<?> client,releaseClass;
    private boolean liveCheck,seedE2e;
    private Context target;
    private int passed;
    private final StringBuilder log=new StringBuilder();
    private File scratch,verified,original,metadataFile,originalMetadata;
    private static final String REPO="https://github.com/RoadKai233/shuncheng-ledger";
    private static final String RELEASE=REPO+"/releases/download/test-fixture/synthetic.apk";
    private interface Checked{void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);liveCheck=args!=null&&"true".equals(args.getString("live_check"));seedE2e=args!=null&&"true".equals(args.getString("seed_e2e"));start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            if(!(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.FINGERPRINT.contains("generic")))throw new IllegalStateException("Updater checks require a disposable emulator.");
            target=getTargetContext();client=target.getClassLoader().loadClass("cn.personal.shunfeng.UpdateClient");releaseClass=target.getClassLoader().loadClass("cn.personal.shunfeng.UpdateClient$Release");
            scratch=new File(target.getCacheDir(),"update-checks-"+System.nanoTime());if(!scratch.mkdirs())throw new IOException("Cannot create isolated test directory");
            if(seedE2e){seedCandidate();}else if(liveCheck){Object latest=invoke("check",new Class[]{Context.class},target);long installed=target.getPackageManager().getPackageInfo(target.getPackageName(),0).getLongVersionCode();check(latest==null||((Integer)field(latest,"versionCode"))>installed,"Live GitHub API, release manifest and asset metadata are accepted; installed or older release is not offered");result.putString("latest",latest==null?"installed release is current":String.valueOf(field(latest,"versionCode")));}else{manifestChecks();urlChecks();artifactChecks();}result.putString("status","PASS");
        }catch(Throwable failure){code=Activity.RESULT_CANCELED;StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));result.putString("status","FAIL");result.putString("error",trace.toString());}
        finally{if(metadataFile!=null)metadataFile.delete();if(originalMetadata!=null&&originalMetadata.exists()&&!originalMetadata.renameTo(metadataFile)){result.putString("restoreError","Could not restore original metadata");code=Activity.RESULT_CANCELED;}if(verified!=null)verified.delete();if(original!=null&&original.exists()&&!original.renameTo(verified)){result.putString("restoreError","Could not restore original cached update");code=Activity.RESULT_CANCELED;}deleteTree(scratch);}
        result.putInt("passed",passed);result.putString("stream","\n"+result.getString("status")+": "+passed+" updater checks.\n"+log+"\n"+result.getString("error",""));finish(code,result);
    }
    private JSONObject base()throws Exception{return new JSONObject().put("versionCode",8).put("versionName","1.6-test").put("apkUrl",RELEASE).put("sha256",repeat('a',64)).put("size",4096).put("notes","Synthetic test release.");}
    private Object parse(JSONObject json)throws Exception{return invoke("parseManifest",new Class[]{String.class},json.toString());}
    private void manifestChecks()throws Exception{
        Object release=parse(base());check((Integer)field(release,"versionCode")==8&&"1.6-test".equals(field(release,"versionName")),"Valid manifest parses version and name");
        JSONObject normalized=base().put("sha256",repeat('A',64));check(repeat('a',64).equals(field(parse(normalized),"sha256")),"SHA256 text is normalized");
        JSONObject optional=base();optional.remove("notes");check(parse(optional)!=null,"Release notes are optional");
        for(String key:new String[]{"versionCode","versionName","apkUrl","sha256","size"}){JSONObject doc=base();doc.remove(key);reject(()->parse(doc),"Missing required manifest field rejected: "+key);}
        for(Object value:new Object[]{0,-1,1.5,"8",2147483648L,JSONObject.NULL}){JSONObject doc=base().put("versionCode",value);reject(()->parse(doc),"Invalid versionCode rejected: "+value);}
        for(Object value:new Object[]{""," v1",repeat('x',65),"1/6","1\n6",JSONObject.NULL,7}){JSONObject doc=base().put("versionName",value);reject(()->parse(doc),"Invalid version name rejected");}
        for(Object value:new Object[]{1023,104857601,12.5,"4096",JSONObject.NULL}){JSONObject doc=base().put("size",value);reject(()->parse(doc),"Invalid APK size rejected: "+value);}
        check(parse(base().put("size",1024))!=null&&parse(base().put("size",104857600))!=null,"Manifest size boundaries are accepted");
        for(Object value:new Object[]{repeat('0',63),repeat('z',64),JSONObject.NULL,1}){JSONObject doc=base().put("sha256",value);reject(()->parse(doc),"Invalid SHA256 field rejected");}
        reject(()->parse(base().put("notes",repeat('x',8001))),"Oversized release notes rejected");
        reject(()->parse(base().put("notes",4)),"Non-text release notes rejected");
        reject(()->parse(base().put("notes","bad\u0000notes")),"NUL in release notes is rejected");String normal=base().toString();String nested=normal.substring(0,normal.length()-1)+",\"extra\":"+repeat('[',40)+"0"+repeat(']',40)+"}";reject(()->invoke("parseManifest",new Class[]{String.class},nested),"Excessively nested JSON is rejected before recursive parsing");
        reject(()->invoke("parseManifest",new Class[]{String.class},"{not-json}"),"Malformed manifest JSON rejected");
        reject(()->invoke("parseManifest",new Class[]{String.class},base().toString()+" trailing"),"Trailing non-JSON content rejected");
        reject(()->parse(base().put("extra",repeat('x',65536))),"Manifest byte-size limit is enforced");
    }
    private void urlChecks()throws Exception{
        check(allowed(RELEASE),"Official repository release URL is accepted");
        check(allowed(REPO+"/releases/download/test/update.json"),"Repository manifest asset URL is allowed");reject(()->parse(base().put("apkUrl",REPO+"/releases/download/test/a.txt")),"APK manifest cannot point to a non-APK asset");
        for(String url:new String[]{"http://github.com/RoadKai233/shuncheng-ledger/releases/download/test/a.apk","https://github.com.evil.invalid/RoadKai233/shuncheng-ledger/releases/download/test/a.apk","https://github.com@evil.invalid/RoadKai233/shuncheng-ledger/releases/download/test/a.apk","https://github.com/another-owner/shuncheng-ledger/releases/download/test/a.apk","https://github.com/RoadKai233/other-repo/releases/download/test/a.apk",REPO+"/blob/main/a.apk",REPO+"/releases/download/../a.apk","file:///tmp/a.apk","content://invalid/a.apk"}){
            check(!allowed(url),"Untrusted or malformed release URL rejected: "+url);JSONObject doc=base().put("apkUrl",url);reject(()->parse(doc),"Manifest cannot embed untrusted APK URL");
        }
        String cdn="https://release-assets.githubusercontent.com/github-production-release-asset/12345/test-asset?token=test";check(redirect(RELEASE,RELEASE,cdn),"Trusted release may redirect to the official asset CDN");check(!allowed(cdn)&&!redirect(cdn,cdn,cdn),"CDN URL cannot be accepted as an initial update source");
        check(!redirect(RELEASE,RELEASE,"http://release-assets.githubusercontent.com/file.apk"),"Redirect cannot downgrade HTTPS");
        check(!redirect(RELEASE,RELEASE,"https://release-assets.githubusercontent.com.evil.invalid/file.apk"),"Redirect cannot use lookalike asset host");
        check(!redirect(RELEASE,RELEASE,"https://evil.invalid/file.apk"),"Redirect cannot leave GitHub's allowed hosts");
    }
    private void artifactChecks()throws Exception{
        JSONObject fixtures=new JSONObject(new String(readAsset("fixtures.json"),StandardCharsets.UTF_8));JSONArray items=fixtures.getJSONArray("fixtures");Map<String,JSONObject> metadata=new HashMap<>();Map<String,File> files=new HashMap<>();
        for(int i=0;i<items.length();i++){JSONObject entry=items.getJSONObject(i);String name=entry.getString("file");File file=new File(scratch,name);write(file,readAsset(name));metadata.put(name,entry);files.put(name,file);}
        File valid=files.get("valid.apk");JSONObject validMeta=metadata.get("valid.apk");Object validRelease=fixtureRelease(validMeta);
        String calculated=(String)invoke("sha256",new Class[]{File.class},valid);check(validMeta.getString("sha256").equals(calculated),"File SHA256 matches independently generated fixture digest");
        verify(validRelease,valid);check(true,"Newer same-package, same-signature APK verifies successfully");
        Object badHash=parse(manifest(validMeta).put("sha256",repeat('0',64)));reject(()->verify(badHash,valid),"Incorrect downloaded APK hash is rejected");
        Object badSize=parse(manifest(validMeta).put("size",valid.length()+1));reject(()->verify(badSize,valid),"Incorrect downloaded APK size is rejected");
        Object badVersion=parse(manifest(validMeta).put("versionCode",validMeta.getInt("versionCode")+1));reject(()->verify(badVersion,valid),"Actual APK version must match update manifest");
        Object badName=parse(manifest(validMeta).put("versionName","different.1"));reject(()->verify(badName,valid),"Actual APK version name must match update manifest");
        for(String name:new String[]{"wrong-package.apk","wrong-signature.apk","same-version.apk","downgrade.apk","unsupported-sdk.apk"}){Object candidate=fixtureRelease(metadata.get(name));File file=files.get(name);reject(()->verify(candidate,file),"Unsafe update artifact is rejected: "+name);}
        File corrupt=new File(scratch,"corrupt.apk");byte[] bytes=new byte[4096];Arrays.fill(bytes,(byte)'x');write(corrupt,bytes);JSONObject corruptDoc=base().put("versionCode",validMeta.getInt("versionCode")).put("size",corrupt.length()).put("sha256",invoke("sha256",new Class[]{File.class},corrupt));Object corruptRelease=parse(corruptDoc);reject(()->verify(corruptRelease,corrupt),"Non-APK payload is rejected despite matching size and hash");
        File updates=new File(target.getCacheDir(),"updates");if(!updates.exists()&&!updates.mkdirs())throw new IOException("Cannot create updater cache");verified=new File(updates,"verified-update.apk");
        if(verified.exists()){original=new File(updates,"test-original-"+System.nanoTime()+".apk");if(!verified.renameTo(original))throw new IOException("Cannot preserve existing update cache");}
        metadataFile=new File(updates,"verified-update.json");if(metadataFile.exists()){originalMetadata=new File(updates,"test-original-"+System.nanoTime()+".json");if(!metadataFile.renameTo(originalMetadata))throw new IOException("Cannot preserve existing update metadata");}
        write(verified,readFile(valid));check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)==null,"APK without saved metadata is not offered after restart");
        File reused=(File)invoke("downloadAndVerify",new Class[]{Context.class,releaseClass},target,validRelease);check(reused.getCanonicalFile().equals(verified.getCanonicalFile())&&metadataFile.isFile(),"Verified cached APK is reused and metadata is persisted without networking");
        Object restored=invoke("loadDownloadedRelease",new Class[]{Context.class},target);check(restored!=null&&field(restored,"versionCode").equals(field(validRelease,"versionCode")),"Persisted release restores after full APK verification");
        byte[] savedMetadata=readFile(metadataFile),altered=readFile(valid);altered[altered.length/2]^=1;write(verified,altered);check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)==null,"Restart rejects modified APK and discards its saved manifest");write(verified,readFile(valid));
        write(metadataFile,"{broken}".getBytes(StandardCharsets.UTF_8));check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)==null,"Malformed saved metadata cannot resume installation");
        write(metadataFile,new byte[65537]);check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)==null,"Oversized saved metadata is rejected");
        write(metadataFile,new byte[]{(byte)0xc3,(byte)0x28});check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)==null,"Malformed UTF-8 saved metadata is rejected");
        write(metadataFile,savedMetadata);check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)!=null,"Valid saved update can still resume after rejected corrupt candidates");
        Intent install=(Intent)invoke("createInstallIntent",new Class[]{Context.class,File.class},target,verified);Uri uri=install.getData();
        check((Intent.ACTION_VIEW.equals(install.getAction())||Intent.ACTION_INSTALL_PACKAGE.equals(install.getAction()))&&"application/vnd.android.package-archive".equals(install.getType()),"Install intent delegates APK confirmation to Android");
        check(uri!=null&&"content".equals(uri.getScheme())&&"cn.personal.shunfeng.updates".equals(uri.getAuthority())&&"/verified-update.apk".equals(uri.getPath()),"Installer receives only the restricted content URI");
        check((install.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&(install.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0,"Installer URI grants read access only");
        ProviderInfo provider=target.getPackageManager().resolveContentProvider(uri.getAuthority(),0);check(provider!=null&&!provider.exported&&provider.grantUriPermissions,"APK provider is private with temporary URI grants");
        try(InputStream input=target.getContentResolver().openInputStream(uri)){check(Arrays.equals(readFile(valid),readAll(input)),"Provider serves the verified APK bytes");}
        try(Cursor cursor=target.getContentResolver().query(uri,null,null,null,null)){check(cursor!=null&&cursor.moveToFirst()&&cursor.getLong(cursor.getColumnIndexOrThrow("_size"))==verified.length(),"Provider reports the correct APK size");}
        for(String path:new String[]{"/","/other.apk","/../verified-update.apk","/%2e%2e/verified-update.apk","/verified-update.apk/extra"}){Uri bad=Uri.parse("content://cn.personal.shunfeng.updates"+path);rejectAccess(()->{try(InputStream input=target.getContentResolver().openInputStream(bad)){if(input!=null)input.read();}},"Provider rejects non-whitelisted path: "+path);}
        for(String mode:new String[]{"w","rw","wt"})rejectAccess(()->{try(android.os.ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(uri,mode)){}},"Provider refuses writable mode: "+mode);
        rejectAccess(()->invoke("createInstallIntent",new Class[]{Context.class,File.class},target,valid),"Installer cannot share an arbitrary cache path");
        check(Arrays.equals(readFile(valid),readFile(verified)),"Provider rejection tests leave APK bytes unchanged");
    }
    private void seedCandidate()throws Exception{
        File candidate=new File(target.getFilesDir(),"e2e-update-v8.apk");if(!candidate.isFile())throw new IOException("Place the disposable same-signer code+1 candidate in the target private files directory first.");
        PackageInfo info=target.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(),0);JSONObject document=base().put("versionCode",info.getLongVersionCode()).put("versionName",info.versionName).put("size",candidate.length()).put("sha256",invoke("sha256",new Class[]{File.class},candidate));Object release=parse(document);verify(release,candidate);
        File destination=(File)invoke("getVerifiedFile",new Class[]{Context.class},target);write(destination,readFile(candidate));invoke("downloadAndVerify",new Class[]{Context.class,releaseClass},target,release);check(invoke("loadDownloadedRelease",new Class[]{Context.class},target)!=null,"Emulator E2E candidate is persisted using the production cache API");
        target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean("automatic_updates",false).putLong("update_checked_at",System.currentTimeMillis()).commit();
    }
    private JSONObject manifest(JSONObject fixture)throws Exception{return base().put("versionCode",fixture.getInt("versionCode")).put("versionName",fixture.getString("versionName")).put("size",fixture.getLong("size")).put("sha256",fixture.getString("sha256"));}
    private Object fixtureRelease(JSONObject fixture)throws Exception{return parse(manifest(fixture));}
    private boolean allowed(String url)throws Exception{return (Boolean)invoke("isAllowedReleaseUrl",new Class[]{String.class},url);}
    private boolean redirect(String original,String from,String to)throws Exception{return (Boolean)invoke("isAllowedRedirect",new Class[]{String.class,String.class,String.class},original,from,to);}
    private void verify(Object release,File file)throws Exception{invoke("verifyDownloadedApk",new Class[]{Context.class,releaseClass,File.class},target,release,file);}
    private Object invoke(String name,Class<?>[] types,Object... args)throws Exception{try{return client.getMethod(name,types).invoke(null,args);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw wrapped;}}
    private Object field(Object value,String name)throws Exception{return value.getClass().getField(name).get(value);}
    private void reject(Checked action,String name)throws Exception{try{action.run();}catch(IOException expected){check(true,name);return;}throw new AssertionError(name);}
    private void rejectAccess(Checked action,String name)throws Exception{try{action.run();}catch(IOException|SecurityException|IllegalArgumentException expected){check(true,name);return;}throw new AssertionError(name);}
    private void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;log.append("PASS ").append(name).append('\n');Bundle progress=new Bundle();progress.putString("stream","PASS "+name+"\n");sendStatus(1,progress);}
    private byte[] readAsset(String name)throws Exception{try(InputStream input=getContext().getAssets().open("update-fixtures/"+name)){return readAll(input);}}
    private static byte[] readFile(File file)throws Exception{try(InputStream input=new FileInputStream(file)){return readAll(input);}}
    private static byte[] readAll(InputStream input)throws IOException{if(input==null)throw new IOException("Missing stream");try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private static void write(File file,byte[] bytes)throws IOException{try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}}
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
    private static void deleteTree(File file){if(file==null||!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();}
}
