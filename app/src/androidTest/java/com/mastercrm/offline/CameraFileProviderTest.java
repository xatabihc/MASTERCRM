package com.mastercrm.offline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

/** Tests temporary camera output sharing, without opening external camera hardware. */
@RunWith(AndroidJUnit4.class)
public class CameraFileProviderTest {
    @Test public void cameraOutputStaysInPrivateCacheAndIsReadable() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File folder=new File(context.getCacheDir(),"camera");
        assertTrue(folder.exists()||folder.mkdirs());
        File file=File.createTempFile("camera-test-", ".jpg", folder);
        try {
            Uri uri=FileProvider.getUriForFile(
                    context,context.getPackageName()+".fileprovider",file);
            assertEquals("content",uri.getScheme());
            assertFalse(uri.toString().contains(context.getDatabasePath(CrmStore.DATABASE_NAME).getPath()));
            Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,bytes));
            } finally {bitmap.recycle();}
            try(OutputStream os=context.getContentResolver().openOutputStream(uri,"w")) {
                assertNotNull(os);
                os.write(bytes.toByteArray());
            }
            try(InputStream is=context.getContentResolver().openInputStream(uri)) {
                assertNotNull(is);
                Bitmap photo=BitmapFactory.decodeStream(is);
                assertNotNull(photo);
                assertEquals(32,photo.getWidth());
                assertEquals(32,photo.getHeight());
                photo.recycle();
            }
        } finally {
            assertTrue(file.delete()||!file.exists());
        }
    }
}
