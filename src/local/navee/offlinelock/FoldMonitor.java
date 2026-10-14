package local.navee.offlinelock;

import android.app.Activity;
import android.graphics.Rect;
import android.hardware.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** OEM fold bounds when available, with hinge-angle and adaptive-window fallbacks. */
public final class FoldMonitor implements SensorEventListener {
    public interface Listener{void changed();}
    private final Activity activity;private final Listener listener;private final SensorManager sensors;
    private Object component,consumer;public Rect hinge=new Rect();public float angle=180;public String posture="Compact",source="Adaptive window";public int layoutCallbacks;
    private boolean separating,hasFoldFeature,hasAngleReading;
    /** A flat crease does not divide usable space; a physical hinge or half-open fold does. */
    public boolean isSeparating(){return separating;}
    public FoldMonitor(Activity a,Listener l){activity=a;listener=l;sensors=a.getSystemService(SensorManager.class);}
    public void start(){
        if(sensors!=null){Sensor s=sensors.getDefaultSensor(36);if(s!=null)sensors.registerListener(this,s,SensorManager.SENSOR_DELAY_NORMAL);}
        try{
            Class<?> provider=Class.forName("androidx.window.extensions.WindowExtensionsProvider");Object extension=provider.getMethod("getWindowExtensions").invoke(null);component=call(extension,"getWindowLayoutComponent");
            for(Method m:component.getClass().getMethods())if(m.getName().equals("addWindowLayoutInfoListener")&&m.getParameterCount()==2&&m.getParameterTypes()[0].isAssignableFrom(Activity.class)){
                Class<?> c=m.getParameterTypes()[1];
                if(c==Consumer.class)consumer=(Consumer<Object>)this::layout;
                else consumer=Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},(p,method,args)->{String name=method.getName();if(name.equals("hashCode"))return System.identityHashCode(p);if(name.equals("equals"))return args!=null&&args.length>0&&p==args[0];if(name.equals("toString"))return "NaveeFoldListener";if(name.equals("accept")&&args!=null&&args.length>0)layout(args[0]);return null;});
                m.setAccessible(true);m.invoke(component,activity,consumer);source="OEM fold bounds + hinge sensor";break;
            }
        }catch(Throwable ignored){component=null;}
    }
    private void layout(Object info){
        try{
            List<?> features=(List<?>)call(info,"getDisplayFeatures");
            Rect next=new Rect();String nextPosture=hasAngleReading?anglePosture():"Compact";
            boolean nextSeparating=false,nextHasFold=false;layoutCallbacks++;
            for(Object f:features){
                try{
                    // Window Extensions: TYPE_FOLD=1, TYPE_HINGE=2; STATE_FLAT=1, STATE_HALF_OPENED=2.
                    int type=(Integer)call(f,"getType"),state=(Integer)call(f,"getState");
                    Rect bounds=(Rect)call(f,"getBounds");
                    if((type!=1&&type!=2)||(state!=1&&state!=2)||bounds.width()<0||bounds.height()<0
                            ||(bounds.width()==0&&bounds.height()==0))continue;
                    boolean divides=type==2||state==2;
                    if(!nextHasFold||(!nextSeparating&&divides)){
                        next=new Rect(bounds);nextPosture=state==2?"Half folded":"Unfolded";
                        nextSeparating=divides;nextHasFold=true;
                    }
                }catch(ReflectiveOperationException|RuntimeException unsupportedFeature){/* Other display features do not imply a hinge. */}
            }
            boolean changed=!next.equals(hinge)||!nextPosture.equals(posture)||nextSeparating!=separating;
            hinge=next;posture=nextPosture;separating=nextSeparating;hasFoldFeature=nextHasFold;
            if(changed)activity.runOnUiThread(listener::changed);
        }catch(Throwable ignored){}
    }
    private static Object call(Object object,String name)throws Exception{Method method=object.getClass().getMethod(name);method.setAccessible(true);return method.invoke(object);}
    public void stop(){if(sensors!=null)sensors.unregisterListener(this);if(component!=null&&consumer!=null)try{for(Method m:component.getClass().getMethods())if(m.getName().equals("removeWindowLayoutInfoListener")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(consumer)){m.setAccessible(true);m.invoke(component,consumer);break;}}catch(Throwable ignored){}}
    private String anglePosture(){return angle<30?"Folded":angle<150?"Half folded":"Unfolded";}
    @Override public void onSensorChanged(SensorEvent e){float previous=angle;angle=e.values[0];hasAngleReading=true;String next=hasFoldFeature?posture:anglePosture();if(!next.equals(posture)||Math.abs(angle-previous)>20){posture=next;listener.changed();}}
    @Override public void onAccuracyChanged(Sensor s,int a){}
}
