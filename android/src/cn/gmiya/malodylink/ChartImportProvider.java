package cn.gmiya.malodylink;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** Only the explicitly granted, prepared package is available to Malody. */
public final class ChartImportProvider extends ContentProvider {
    private File file(Uri u)throws FileNotFoundException {
        List<String> p=u.getPathSegments();if(p.size()!=2||!p.get(0).matches("[a-f0-9-]{36}")||!ChartPackage.preparedName(p.get(1)))throw new FileNotFoundException();
        File f=new File(new File(new File(getContext().getCacheDir(),"chart-imports"),p.get(0)),p.get(1));if(!f.isFile())throw new FileNotFoundException();return f;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri u){return "application/octet-stream";}
    static boolean wasOpened(Context c,Uri u){return c.getSharedPreferences("chart_import_receipts",0).getBoolean(u.getPath(),false);}
    static void resetReceipt(Context c,Uri u){c.getSharedPreferences("chart_import_receipts",0).edit().remove(u.getPath()).apply();}
    @Override public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException();
        ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);
        getContext().getSharedPreferences("chart_import_receipts",0).edit().putBoolean(u.getPath(),true).apply();return descriptor;
    }
    @Override public Cursor query(Uri u,String[] projection,String s,String[] a,String order){try{
        File f=file(u);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(cols);Object[] row=new Object[cols.length];for(int i=0;i<cols.length;i++){if(cols[i].equals(OpenableColumns.DISPLAY_NAME))row[i]=f.getName();else if(cols[i].equals(OpenableColumns.SIZE))row[i]=f.length();}result.addRow(row);return result;
    }catch(FileNotFoundException e){return null;}}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
