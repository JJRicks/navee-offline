package com.jjricks.volt;

public final class LockSliderGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        for (float density : new float[]{1, 2.75f, 3.5f}) {
            for (int[] size : new int[][]{{117,182},{150,182},{312,374},{312,54},{48,48},{144,54}}) {
                float width=size[0]*density, height=size[1]*density;
                LockSliderGeometry g=new LockSliderGeometry(width,height,58*density,5*density);
                require(g.radius>0 && g.travel>=width/2, "Thumb retains a visible radius and horizontal travel");
                for (float position : new float[]{0,.25f,.5f,.75f,1}) {
                    float center=g.centerFor(position);
                    require(center-g.radius>=0 && center+g.radius<=width,
                            "Thumb escapes card at "+size[0]+"x"+size[1]);
                    require(height/2-g.radius>=0 && height/2+g.radius<=height, "Thumb clips vertically");
                    require(Math.abs(position-g.positionFor(center))<.0001f, "Touch and drawing disagree");
                }
                require(g.positionFor(-100)==0 && g.positionFor(width+100)==1, "Touch clamps at endpoints");
            }
        }
        LockSliderGeometry ordinary=new LockSliderGeometry(312,54,58,5);
        require(ordinary.firstCenter==27 && ordinary.radius==22 && ordinary.travel==258,
                "Ordinary horizontal switch changed shape");
        System.out.println("LockSliderGeometry: "+checks+" checks passed");
    }
    private static void require(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
}
