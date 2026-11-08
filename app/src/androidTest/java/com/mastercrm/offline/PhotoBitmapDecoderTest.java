package com.mastercrm.offline;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PhotoBitmapDecoderTest {
    @Test public void largeCameraImageIsDownsampledForViewerAndThumbnail() {
        Bitmap source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 75, output));
        source.recycle();
        byte[] jpeg = output.toByteArray();

        Bitmap viewer = PhotoBitmapDecoder.decode(jpeg, 1600);
        assertNotNull(viewer);
        assertTrue(viewer.getWidth() <= 1600);
        assertTrue(viewer.getHeight() <= 1600);
        viewer.recycle();

        Bitmap thumb = PhotoBitmapDecoder.decode(jpeg, 320);
        assertNotNull(thumb);
        assertTrue(thumb.getWidth() <= 320);
        assertTrue(thumb.getHeight() <= 320);
        thumb.recycle();
    }

    @Test public void invalidBytesReturnNullWithoutCrashing() {
        assertNull(PhotoBitmapDecoder.decode(null, 1600));
        assertNull(PhotoBitmapDecoder.decode(new byte[]{1,2,3,4}, 1600));
        assertNull(PhotoBitmapDecoder.decode(new byte[]{1,2,3,4}, 0));
    }
}
