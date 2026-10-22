package cn.gmiya.malodylink;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Grants the system installer read-only access to one verified cached APK. */
public final class UpdateFileProvider extends ContentProvider {
    private File file(Uri uri)throws FileNotFoundException {
        List<String> parts=uri.getPathSegments();if(parts.size()!=2||!parts.get(0).equals("apk")||!parts.get(1).matches("[a-f0-9]{64}\\.apk"))throw new FileNotFoundException("Invalid update path");File target=new File(new File(getContext().getCacheDir(),"updates"),parts.get(1));if(!target.isFile())throw new FileNotFoundException("Update not found");return target;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read-only update");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]="Malody-Link.apk";else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);return cursor;}catch(FileNotFoundException missing){return null;}}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException("Read-only");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException("Read-only");}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException("Read-only");}
}
