package local.navee.offlinelock;

import android.app.Activity;
import android.graphics.Rect;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Physical hinges reported by the OEM window extensions. Only a hinge that always hides part of
 * the window (two displays with a gap) changes layout. A foldable's crease, flat or half-open,
 * never does: moving between unfolded, book and tabletop postures keeps the screen exactly as
 * it is, with no reload, re-split or replayed animation.
 */
public final class FoldMonitor {
    public interface Listener{void changed();}
    private final Activity activity;private final Listener listener;
    private Object component,consumer;public Rect hinge=new Rect();public String posture="Compact",source="Adaptive window";public int layoutCallbacks;
    private boolean separating;
    /** True only while a physical hinge divides the window; a crease never does. */
    public boolean isSeparating(){return separating;}
    public FoldMonitor(Activity a,Listener l){activity=a;listener=l;}
    public void start(){
        try{
            Class<?> provider=Class.forName("androidx.window.extensions.WindowExtensionsProvider");Object extension=provider.getMethod("getWindowExtensions").invoke(null);component=call(extension,"getWindowLayoutComponent");
            for(Method m:component.getClass().getMethods())if(m.getName().equals("addWindowLayoutInfoListener")&&m.getParameterCount()==2&&m.getParameterTypes()[0].isAssignableFrom(Activity.class)){
                Class<?> c=m.getParameterTypes()[1];
                if(c==Consumer.class)consumer=(Consumer<Object>)this::layout;
                else consumer=Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},(p,method,args)->{String name=method.getName();if(name.equals("hashCode"))return System.identityHashCode(p);if(name.equals("equals"))return args!=null&&args.length>0&&p==args[0];if(name.equals("toString"))return "NaveeFoldListener";if(name.equals("accept")&&args!=null&&args.length>0)layout(args[0]);return null;});
                m.setAccessible(true);m.invoke(component,activity,consumer);source="OEM fold bounds";break;
            }
        }catch(Throwable ignored){component=null;}
    }
    private void layout(Object info){
        try{
            List<?> features=(List<?>)call(info,"getDisplayFeatures");
            Rect next=new Rect();String nextPosture="Compact";boolean nextSeparating=false;layoutCallbacks++;
            for(Object f:features){
                try{
                    // Window Extensions: TYPE_FOLD=1, TYPE_HINGE=2; STATE_FLAT=1, STATE_HALF_OPENED=2.
                    int type=(Integer)call(f,"getType"),state=(Integer)call(f,"getState");
                    Rect bounds=(Rect)call(f,"getBounds");
                    if((type!=1&&type!=2)||(state!=1&&state!=2)||bounds.width()<0||bounds.height()<0
                            ||(bounds.width()==0&&bounds.height()==0))continue;
                    nextPosture=state==2?"Half folded":"Unfolded";
                    if(type==2&&!nextSeparating){next=new Rect(bounds);nextSeparating=true;}
                }catch(ReflectiveOperationException|RuntimeException unsupportedFeature){/* Other display features do not imply a hinge. */}
            }
            // Posture alone never notifies; only a physical hinge appearing, moving or leaving does.
            boolean changed=nextSeparating!=separating||(nextSeparating&&!next.equals(hinge));
            hinge=next;posture=nextPosture;separating=nextSeparating;
            if(changed)activity.runOnUiThread(listener::changed);
        }catch(Throwable ignored){}
    }
    private static Object call(Object object,String name)throws Exception{Method method=object.getClass().getMethod(name);method.setAccessible(true);return method.invoke(object);}
    public void stop(){if(component!=null&&consumer!=null)try{for(Method m:component.getClass().getMethods())if(m.getName().equals("removeWindowLayoutInfoListener")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(consumer)){m.setAccessible(true);m.invoke(component,consumer);break;}}catch(Throwable ignored){}}
}
