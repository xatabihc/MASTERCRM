package com.mastercrm.offline;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Decode a private JPEG at a bounded display resolution, not its camera size. */
public final class PhotoBitmapDecoder {
    private PhotoBitmapDecoder() {}

    public static Bitmap decode(byte[] jpeg, int maxSide) {
        if (jpeg == null || jpeg.length < 4 || maxSide <= 0)
            return null;
        BitmapFactory.Options dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, dimensions);
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0)
            return null;

        int sample = 1;
        int longest = Math.max(dimensions.outWidth, dimensions.outHeight);
        while (longest / sample > maxSide && sample <= (1 << 29)) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, decode);
    }
}
