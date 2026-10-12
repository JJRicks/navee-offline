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
        try{List<?> features=(List<?>)call(info,"getDisplayFeatures");Rect next=new Rect();String nextPosture=posture;layoutCallbacks++;
            for(Object f:features){Rect r=(Rect)call(f,"getBounds");if(r.width()==0||r.height()==0||r.width()<100||r.height()<100){next=new Rect(r);try{int state=(Integer)call(f,"getState");nextPosture=state==2?"Half folded":"Unfolded";}catch(Exception ignored){nextPosture="Unfolded";}}}
            if(!next.equals(hinge)||!nextPosture.equals(posture)){hinge=next;posture=nextPosture;activity.runOnUiThread(listener::changed);}
        }catch(Throwable ignored){}
    }
    private static Object call(Object object,String name)throws Exception{Method method=object.getClass().getMethod(name);method.setAccessible(true);return method.invoke(object);}
    public void stop(){if(sensors!=null)sensors.unregisterListener(this);if(component!=null&&consumer!=null)try{for(Method m:component.getClass().getMethods())if(m.getName().equals("removeWindowLayoutInfoListener")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(consumer)){m.setAccessible(true);m.invoke(component,consumer);break;}}catch(Throwable ignored){}}
    @Override public void onSensorChanged(SensorEvent e){float previous=angle;angle=e.values[0];String next=angle<30?"Folded":angle<150?"Half folded":"Unfolded";if(!next.equals(posture)||Math.abs(angle-previous)>20){posture=next;listener.changed();}}
    @Override public void onAccuracyChanged(Sensor s,int a){}
}
