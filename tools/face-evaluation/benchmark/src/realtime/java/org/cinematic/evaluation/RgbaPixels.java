package org.cinematic.evaluation;
import java.nio.ByteBuffer;
/** CameraX RGBA plane -> opaque ARGB. Pure seam for padded/strided synthetic fixtures. */
final class RgbaPixels {
    static int[] copy(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || width > 1920 || height > 1920 || pixelStride < 4 || rowStride < (long)width*pixelStride)
            throw new IllegalArgumentException("geometry");
        int base=buffer.position();
        if ((long)base+(height-1L)*rowStride+(width-1L)*pixelStride+4>buffer.limit()) throw new IllegalArgumentException("short plane");
        int[] result=new int[width*height];
        for (int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=base+y*rowStride+x*pixelStride;
            result[y*width+x]=0xff000000 | (buffer.get(i)&255)<<16 | (buffer.get(i+1)&255)<<8 | (buffer.get(i+2)&255);
        }
        return result;
    }
}
