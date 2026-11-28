using System;
using System.Windows.Media;
using System.Windows.Media.Imaging;

// Windows WIC/WPF color-management only. No file, network, or device IO.
public static class FaceNormalizePixels {
    public static byte[] Convert(BitmapSource source, ColorContext profile, int orientation,
                                 int maxPixels, out int width, out int height) {
        if(source==null || profile==null || orientation<1 || orientation>8 || maxPixels<1 || maxPixels>4000000)
            throw new ArgumentException("Invalid normalization input");
        long count=(long)source.PixelWidth*source.PixelHeight;
        if(count<1 || count>64000000) throw new ArgumentException("Input pixel limit");
        BitmapSource converted=new ColorConvertedBitmap(source,profile,
            new ColorContext(PixelFormats.Bgra32),PixelFormats.Pbgra32);
        double scale=Math.Min(1.0,Math.Min(Math.Sqrt((double)maxPixels/count),
            Math.Min(8192.0/source.PixelWidth,8192.0/source.PixelHeight)));
        int tw=Math.Max(1,(int)(source.PixelWidth*scale)), th=Math.Max(1,(int)(source.PixelHeight*scale));
        if(tw!=source.PixelWidth || th!=source.PixelHeight)
            converted=new TransformedBitmap(converted,new ScaleTransform((double)tw/source.PixelWidth,(double)th/source.PixelHeight));
        int w=converted.PixelWidth,h=converted.PixelHeight;
        if((long)w*h>maxPixels || w>8192 || h>8192) throw new ArgumentException("Output pixel limit");
        byte[] bgra=new byte[w*h*4];converted.CopyPixels(bgra,w*4,0);
        width=orientation>=5?h:w;height=orientation>=5?w:h;
        byte[] rgba=new byte[width*height*4];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int dx=x,dy=y;
            switch(orientation) {
                case 2:dx=w-1-x;break;
                case 3:dx=w-1-x;dy=h-1-y;break;
                case 4:dy=h-1-y;break;
                case 5:dx=y;dy=x;break;
                case 6:dx=h-1-y;dy=x;break;
                case 7:dx=h-1-y;dy=w-1-x;break;
                case 8:dx=y;dy=w-1-x;break;
            }
            int i=(y*w+x)*4,j=(dy*width+dx)*4;
            // Pbgra is premultiplied: dropping alpha composites onto black.
            rgba[j]=bgra[i+2];rgba[j+1]=bgra[i+1];rgba[j+2]=bgra[i];rgba[j+3]=255;
        }
        return rgba;
    }
}
