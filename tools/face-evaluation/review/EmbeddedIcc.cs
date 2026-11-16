using System;
using System.IO;
using System.IO.Compression;
using System.Collections.Generic;
using System.Windows.Media;
using System.Windows.Media.Imaging;

// Inspect container ICC, not WIC's EXIF-derived fallback contexts. No external IO.
public static class FaceEmbeddedIcc {
    const int Limit=16777216;
    static int U16(byte[] b,int p) { return b[p]*256+b[p+1]; }
    static uint U32(byte[] b,int p) { return ((uint)b[p]<<24)|((uint)b[p+1]<<16)|((uint)b[p+2]<<8)|b[p+3]; }
    static bool Match(byte[] b,int p,byte[] s) {
        if(p<0 || p+s.Length>b.Length)return false;
        for(int i=0;i<s.Length;i++)if(b[p+i]!=s[i])return false;return true;
    }
    static byte[] Read(Stream s,int n) {
        byte[] b=new byte[n];int p=0,r;
        while(p<n && (r=s.Read(b,p,n-p))>0)p+=r;
        if(p!=n)throw new InvalidDataException("Truncated container");return b;
    }
    static byte[] Jpeg(Stream s) {
        var chunks=new SortedDictionary<int,byte[]>();int total=0,size=0;
        while(true) {
            if(s.ReadByte()!=255)throw new InvalidDataException("JPEG marker");
            int m;do {m=s.ReadByte();}while(m==255);
            if(m<0 || m==0 || m==216)throw new InvalidDataException("JPEG marker");
            if(m==218 || m==217)break;
            if(m==1 || (m>=208 && m<=215))continue;
            int n=U16(Read(s,2),0)-2;if(n<0)throw new InvalidDataException("JPEG segment");
            byte[] b=Read(s,n);
            if(m!=226 || !Match(b,0,System.Text.Encoding.ASCII.GetBytes("ICC_PROFILE\0")))continue;
            if(n<14 || b[12]<1 || b[13]<1 || b[12]>b[13] || (total!=0 && total!=b[13]) || chunks.ContainsKey(b[12]))
                throw new InvalidDataException("Ambiguous ICC chunks");
            total=b[13];byte[] part=new byte[n-14];Array.Copy(b,14,part,0,part.Length);
            size+=part.Length;if(size>Limit)throw new InvalidDataException("ICC limit");
            chunks.Add(b[12],part);
        }
        if(total==0 || chunks.Count!=total)throw new InvalidDataException("Missing/incomplete ICC");
        using(var o=new MemoryStream()){foreach(var p in chunks.Values)o.Write(p,0,p.Length);return o.ToArray();}
    }
    static uint Crc(byte[] b,int p,int n) {
        uint c=0xffffffff;for(int i=p;i<p+n;i++){c^=b[i];for(int j=0;j<8;j++)c=(c&1)!=0?0xedb88320^(c>>1):c>>1;}return c^0xffffffff;
    }
    static byte[] Png(Stream s) {
        byte[] profile=null;bool seenData=false;
        while(true) {
            byte[] head=Read(s,8);uint length=U32(head,0);
            if(length>134217728 || s.Position+length+4>s.Length)throw new InvalidDataException("PNG length");
            string type=System.Text.Encoding.ASCII.GetString(head,4,4);
            if(type=="iCCP") {
                if(profile!=null || seenData || length>Limit)throw new InvalidDataException("Ambiguous ICC");
                byte[] b=Read(s,(int)length);uint crc=U32(Read(s,4),0);
                byte[] all=new byte[b.Length+4];Array.Copy(head,4,all,0,4);Array.Copy(b,0,all,4,b.Length);
                if(Crc(all,0,all.Length)!=crc)throw new InvalidDataException("ICC CRC");
                int z=Array.IndexOf(b,(byte)0);
                if(z<1 || z>79 || z+8>b.Length || b[z+1]!=0)throw new InvalidDataException("ICC compression");
                int start=z+2;
                if((b[start]&15)!=8 || (b[start]>>4)>7 || ((b[start]*256+b[start+1])%31)!=0 || (b[start+1]&32)!=0)
                    throw new InvalidDataException("ICC zlib");
                using(var input=new MemoryStream(b,start+2,b.Length-start-6))
                using(var d=new DeflateStream(input,CompressionMode.Decompress))
                using(var output=new MemoryStream()) {
                    byte[] buffer=new byte[4096];int n;
                    while((n=d.Read(buffer,0,buffer.Length))>0){if(output.Length+n>Limit)throw new InvalidDataException("ICC limit");output.Write(buffer,0,n);}
                    profile=output.ToArray();
                }
                uint a=1,c=0;foreach(byte v in profile){a=(a+v)%65521;c=(c+a)%65521;}
                if((c<<16|a)!=U32(b,b.Length-4))throw new InvalidDataException("ICC Adler");
            } else {
                if(type=="IDAT")seenData=true;
                s.Seek((long)length+4,SeekOrigin.Current);
            }
            if(type=="IEND")break;
        }
        if(profile==null)throw new InvalidDataException("Missing ICC");return profile;
    }
    public static byte[] Extract(Stream s) {
        s.Position=0;byte[] sig=Read(s,2);byte[] p;
        if(sig[0]==255 && sig[1]==216)p=Jpeg(s);
        else {
            s.Position=0;
            if(!Match(Read(s,8),0,new byte[]{137,80,78,71,13,10,26,10}))throw new InvalidDataException("Container");
            p=Png(s);
        }
        if(p.Length<128 || U32(p,0)!=p.Length || !Match(p,36,System.Text.Encoding.ASCII.GetBytes("acsp")))
            throw new InvalidDataException("ICC header");
        s.Position=0;return p;
    }
    public static ColorContext Select(BitmapFrame frame,byte[] profile) {
        if(frame.ColorContexts!=null)foreach(ColorContext context in frame.ColorContexts) {
            using(Stream s=context.OpenProfileStream()) {
                if(s==null)continue;
                using(var o=new MemoryStream()) {
                    byte[] b=new byte[4096];int n;
                    while((n=s.Read(b,0,b.Length))>0){if(o.Length+n>Limit)throw new InvalidDataException("ICC limit");o.Write(b,0,n);}
                    byte[] candidate=o.ToArray();
                    if(candidate.Length==profile.Length && Match(candidate,0,profile))return context;
                }
            }
        }
        throw new InvalidDataException("Embedded ICC unavailable to WIC");
    }
}