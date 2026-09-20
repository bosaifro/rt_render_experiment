package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import dev.rt_render_experiment.contract.TextureInputs;


public final class TextureCompiler {
    public static long compiledBytes(TextureInputs.Texture source) {
        long bytes=0;
        for(var level:source.levels())bytes=Math.addExact(bytes,(long)level.width()*level.height()*4);
        if(source.generateMipmaps() && source.levels().size()==1) {
            int width=source.levels().getFirst().width(),height=source.levels().getFirst().height();
            while(width>1 || height>1) { width=Math.max(1,width/2);height=Math.max(1,height/2);bytes=Math.addExact(bytes,(long)width*height*4); }
        }
        return bytes;
    }
    public static boolean sameLayout(TextureInputs.Texture a,TextureInputs.Texture b) {
        if(!a.key().equals(b.key()) || a.encoding()!=b.encoding() || !a.sampling().equals(b.sampling()) || a.generateMipmaps()!=b.generateMipmaps()
            || a.levels().size()!=b.levels().size())return false;
        for(int i=0;i<a.levels().size();i++)if(a.levels().get(i).width()!=b.levels().get(i).width() || a.levels().get(i).height()!=b.levels().get(i).height())return false;
        return true;
    }
    public static boolean sameContent(TextureInputs.Texture a,TextureInputs.Texture b) {
        if(a==b)return true;
        if(a.revision()!=b.revision() || !sameLayout(a,b))return false;
        for(int i=0;i<a.levels().size();i++)if(a.levels().get(i).pixels().mismatch(b.levels().get(i).pixels())!=-1)return false;
        return true;
    }
    public TextureInputs.Texture compile(TextureInputs.Texture source) {
        if (!source.generateMipmaps() || source.levels().size()>1) return source;
        var levels=new ArrayList<>(source.levels());
        while (levels.getLast().width()>1 || levels.getLast().height()>1) {
            var previous=levels.getLast(); int width=Math.max(1,previous.width()/2),height=Math.max(1,previous.height()/2);
            var input=previous.pixels(); var output=ByteBuffer.allocate(width*height*4);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) for (int channel=0;channel<4;channel++) {
                double sum=0;

                int x0=x*previous.width()/width,x1=(x+1)*previous.width()/width;
                int y0=y*previous.height()/height,y1=(y+1)*previous.height()/height;
                for (int sy=y0;sy<y1;sy++) for (int sx=x0;sx<x1;sx++) {
                    double value=Byte.toUnsignedInt(input.get((sy*previous.width()+sx)*4+channel))/255.0;
                    sum+=channel<3 && source.encoding()==TextureInputs.Encoding.SRGB?decode(value):value;
                }
                double mean=sum/((x1-x0)*(y1-y0));
                if (channel<3 && source.encoding()==TextureInputs.Encoding.SRGB) mean=encode(mean);
                output.put((byte)Math.clamp(Math.round(mean*255),0,255));
            }
            levels.add(new TextureInputs.Level(width,height,output.flip()));
        }
        return new TextureInputs.Texture(source.key(),source.revision(),source.encoding(),source.sampling(),levels,false);
    }
    private static double decode(double value) { return value<=0.04045?value/12.92:Math.pow((value+0.055)/1.055,2.4); }
    private static double encode(double value) { return value<=0.0031308?value*12.92:1.055*Math.pow(value,1.0/2.4)-0.055; }
}
