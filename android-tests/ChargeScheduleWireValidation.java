package local.navee.offlinelock;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Disposable emulator only. All packets go to an in-process GATT stand-in, never a radio. */
public final class ChargeScheduleWireValidation extends Instrumentation {
    private BleController ble;
    private ScooterProfiles profiles;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private final List<Boolean> outcomes=new CopyOnWriteArrayList<>();
    private int checks;
    private boolean admitted;
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            admit();admitted=true;
            main(()->{
                prefs("app_settings").edit().putBoolean("auto_connect",false).putBoolean("background_connection",false).commit();
                profiles=new ScooterProfiles(getTargetContext());profile=profiles.create("Synthetic charging wire fixture","000000000000",0);
                profile.serial="Q2538ZZ9US7777777";profile.mac="C0:FF:EE:00:25:38";profiles.update(profile);
            });
            clockAcknowledgement();timeConfirmation();forcedSwitch();rejected();timedOut();disconnectCancellation();queuedSafety();refused();
            result.putString("result","PASS");
        }catch(Throwable error){result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace();}
        finally{
            result.putInt("assertions",checks);result.putBoolean("physical_device_tested",false);
            if(admitted)main(()->{if(ble!=null){require(field(ble,"scanner")==null,"Unexpected Bluetooth scan");ble.destroy();}});
            finish("PASS".equals(result.getString("result"))?0:1,result);
        }
    }
    private void admit(){
        String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Physical device refused");
        for(String permission:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,"Grant emulator Nearby permission first");
        BluetoothAdapter adapter=getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter!=null&&!adapter.isEnabled(),"Bluetooth must remain off");
        for(String name:new String[]{"app_settings","profiles","scooter","pending_documents","control_evidence"})
            require(prefs(name).getAll().isEmpty(),"Existing preferences refused");
        File[] files=getTargetContext().getFilesDir().listFiles();require(files!=null&&files.length==0&&getTargetContext().databaseList().length==0,"Existing app data refused");
    }
    private void fresh(){
        main(()->{
            if(ble!=null)ble.destroy();
            ble=new BleController(getTargetContext(),profiles);ble.visibility(true,false);outcomes.clear();link=new FakeLink();link.attach();
        });
        await(()->onMain(()->ble.ready()&&ble.stationary()),"Synthetic link never ready");
    }
    private Consumer<Boolean> completion(){return success->{require(Looper.myLooper()==Looper.getMainLooper(),"Callback off main thread");require(ble.pending()==null,"Callback reentered a pending mutation");outcomes.add(success);};}
    private void clockAcknowledgement(){
        fresh();main(()->ble.syncClock(completion()));await(()->link.writes.size()==1,"Clock wasn't transmitted");
        require(link.writes.get(0)[5]==6,"Wrong clock selector");require(outcomes.isEmpty(),"Clock accepted before ACK");
        main(()->link.notifyPacket(0x6f,0,new byte[0]));await(()->outcomes.size()==1,"Clock ACK completion missing");
        require(outcomes.get(0),"Clock ACK rejected");require(onMain(()->"Accepted; no clock readback available".equals(ble.commandResults().get("clock_sync"))),"Clock overstated its evidence");
        duplicateAndCheck();
    }
    private void timeConfirmation(){
        fresh();long seconds=System.currentTimeMillis()/1000+7200;int offset=TimeZone.getDefault().getOffset(System.currentTimeMillis())/1000;
        main(()->ble.setChargeStart(seconds,offset,completion()));await(()->link.writes.size()==1,"Time wasn't transmitted");
        require(link.writes.get(0)[5]==3,"Wrong start-time selector");
        main(()->{link.setTime(seconds+offset);link.status();});settle();require(outcomes.isEmpty(),"Readback without ACK accepted");
        main(()->{link.setTime(seconds+offset+60);link.notifyPacket(0x6f,0,new byte[0]);link.status();});settle();require(outcomes.isEmpty(),"Mismatching time accepted");
        main(()->{link.setTime(seconds+offset);link.status();});await(()->outcomes.size()==1,"Time readback completion missing");
        require(outcomes.get(0),"Correct time not confirmed");duplicateAndCheck();
    }
    private void forcedSwitch(){
        fresh();main(()->ble.setChargeSchedule(false,completion()));await(()->link.writes.size()==1,"Off command skipped because readback was already zero");
        require(link.writes.get(0)[5]==1&&link.writes.get(0)[6]==0,"Wrong off packet");
        main(()->link.notifyPacket(0x6f,0,new byte[0]));settle();require(outcomes.isEmpty(),"Switch accepted on ACK alone");
        main(()->link.status());await(()->outcomes.size()==1,"Off readback completion missing");require(outcomes.get(0),"Off not confirmed");duplicateAndCheck();
        fresh();main(()->ble.setChargeSchedule(true,completion()));await(()->link.writes.size()==1,"On command absent");
        main(()->{link.notifyPacket(0x6f,0,new byte[0]);link.status();});settle();require(outcomes.isEmpty(),"Old zero confirmed new on");
        main(()->{link.state[33]=1;link.status();});await(()->outcomes.size()==1,"On completion missing");require(outcomes.get(0),"On not confirmed");duplicateAndCheck();
    }
    private void rejected(){
        fresh();main(()->ble.setChargeSchedule(true,completion()));await(()->link.writes.size()==1,"Rejected setup absent");
        main(()->link.notifyPacket(0x6f,7,new byte[0]));await(()->outcomes.size()==1,"Rejection callback missing");require(!outcomes.get(0),"Rejection became success");duplicateAndCheck();
    }
    private void timedOut(){
        fresh();main(()->ble.setChargeSchedule(true,completion()));await(()->link.writes.size()==1,"Timeout setup absent");
        main(()->link.notifyPacket(0x6f,0,new byte[0]));settle();
        main(()->{Object mutation=field(ble,"mutation");require(mutation!=null&&Boolean.TRUE.equals(field(mutation,"ack")),"ACK wasn't recorded");invoke(ble,"mutationTimeout",new Class<?>[]{int.class,long.class},ble.connectionGeneration(),((Number)field(mutation,"token")).longValue());});
        await(()->outcomes.size()==1,"Timeout callback missing");require(!outcomes.get(0),"Timeout became success");require(onMain(()->!ble.connected()),"Timeout didn't close uncertain session");duplicateAndCheck();
    }
    private void disconnectCancellation(){
        fresh();main(()->ble.setChargeSchedule(true,completion()));await(()->link.writes.size()==1,"Disconnect setup absent");main(()->ble.disconnect());
        await(()->outcomes.size()==1,"Disconnect callback missing");require(!outcomes.get(0),"Disconnect became success");duplicateAndCheck();
        fresh();main(()->ble.setChargeSchedule(true,completion()));await(()->link.writes.size()==1,"Suspend setup absent");main(()->ble.visibility(false,false));
        await(()->outcomes.size()==1,"Background suspend callback missing");require(!outcomes.get(0),"Suspend became success");duplicateAndCheck();
    }
    private void queuedSafety(){
        fresh();main(()->{set(ble,"writing",true);ble.setChargeSchedule(true,completion());require(ble.pending()!=null,"Mutation wasn't queued");set(ble,"speedRaw",10);set(ble,"driving",1);set(ble,"motionMs",SystemClock.elapsedRealtime());set(ble,"writing",false);invoke(ble,"pump",new Class<?>[0]);});
        await(()->outcomes.size()==1,"Unsafe queued callback missing");require(!outcomes.get(0)&&link.writes.isEmpty(),"Unsafe queued mutation escaped the gate");
    }
    private void refused(){
        fresh();main(()->{ble.disconnect();ble.setChargeSchedule(true,completion());});await(()->outcomes.size()==1,"Disconnected refusal callback missing");
        require(!outcomes.get(0)&&link.writes.isEmpty(),"Disconnected write reached fake link");
        fresh();main(()->ble.setChargeStart(-1,0,completion()));await(()->outcomes.size()==1,"Invalid time callback missing");
        require(!outcomes.get(0)&&link.writes.isEmpty(),"Invalid timestamp transmitted");
    }
    private void duplicateAndCheck(){main(()->{link.notifyPacket(0x6f,0,new byte[0]);link.status();});settle();require(outcomes.size()==1,"Completion delivered more than once");}

    private final class FakeLink {
        final byte[] state=new byte[44];
        final List<byte[]> writes=new CopyOnWriteArrayList<>();
        final Handler handler=new Handler(Looper.getMainLooper());
        BluetoothGatt gatt;BluetoothGattCharacteristic writer,notifier;BluetoothGattCallback callbacks;
        FakeLink(){state[1]=3;state[2]=0;state[3]=1;state[6]=2;state[7]=1;state[19]=3;state[25]=50;state[31]=100;state[33]=0;state[34]=3;}
        void attach(){try{
            BluetoothDevice device=getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field unsafe=unsafeType.getDeclaredField("theUnsafe");unsafe.setAccessible(true);
            gatt=(BluetoothGatt)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe.get(null),BluetoothGatt.class);
            Class<?> iface=Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport=(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class){if(method.getName().equals("equals"))return proxy==args[0];if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);return "ChargingFixtureGatt";}
                if(method.getName().equals("writeCharacteristic")){
                    byte[] frame=null;if(args!=null)for(Object arg:args)if(arg instanceof byte[])frame=((byte[])arg).clone();
                    set(gatt,"mDeviceBusy",false);final byte[] packet=frame;
                    if(packet!=null)handler.postDelayed(()->received(packet),10);return 0;
                }
                Class<?> type=method.getReturnType();return type==int.class?0:type==boolean.class?false:type==long.class?0L:null;
            };
            set(gatt,"mService",Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},transport));
            set(gatt,"mClientRegistered",true);set(gatt,"mDeviceBusy",false);set(gatt,"mDeviceBusyLock",new Object());set(gatt,"mStateLock",new Object());set(gatt,"mDevice",device);set(gatt,"mServices",new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService service=new BluetoothGattService(BleController.SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);set(service,"mDevice",device);
            writer=new BluetoothGattCharacteristic(BleController.WRITE,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier=new BluetoothGattCharacteristic(BleController.NOTIFY,BluetoothGattCharacteristic.PROPERTY_NOTIFY,0);service.addCharacteristic(writer);service.addCharacteristic(notifier);
            callbacks=(BluetoothGattCallback)field(ble,"callbacks");set(ble,"profile",profile);set(ble,"gatt",gatt);set(ble,"writer",writer);set(ble,"serial",profile.serial);
            set(ble,"authenticated",true);set(ble,"discovered",true);set(ble,"subscribed",true);set(ble,"wanted",false);set(ble,"state",BleController.State.READY);
            status();notifyPacket(0x91,0,new byte[]{0,0,0});
        }catch(Exception error){throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator",error);}}
        void received(byte[] frame){callbacks.onCharacteristicWrite(gatt,writer,0);if(frame.length>7)writes.add(frame);}
        void setTime(long raw){for(int i=0;i<4;i++)state[27+i]=(byte)(raw>>>(24-i*8));}
        void status(){notifyPacket(0x70,0,state);}
        void notifyPacket(int cmd,int status,byte[] payload){
            byte[] packet=new byte[payload.length+9];packet[0]=0x55;packet[1]=(byte)0xaa;packet[3]=(byte)cmd;packet[4]=(byte)(payload.length+1);packet[5]=(byte)status;
            System.arraycopy(payload,0,packet,6,payload.length);int sum=0;for(int i=0;i<packet.length-3;i++)sum+=packet[i]&255;
            packet[packet.length-3]=(byte)sum;packet[packet.length-2]=(byte)0xfe;packet[packet.length-1]=(byte)0xfd;
            handler.postDelayed(()->callbacks.onCharacteristicChanged(gatt,notifier,packet),10);
        }
    }
    private SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}
    private void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void main(Runnable task){final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new IllegalStateException(failure[0]);}
    private boolean onMain(BooleanSupplier query){final boolean[] result={false};main(()->result[0]=query.getAsBoolean());return result[0];}
    private void await(BooleanSupplier query,String message){long end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){if(query.getAsBoolean())return;SystemClock.sleep(25);}throw new AssertionError(message);}
    private void settle(){SystemClock.sleep(350);waitForIdleSync();}
    private static Field reflected(Object object,String name){Class<?> type=object.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new IllegalStateException("Missing field "+name);}
    private static Object field(Object object,String name){try{return reflected(object,name).get(object);}catch(Exception error){throw new IllegalStateException(error);}}
    private static void set(Object object,String name,Object value){try{reflected(object,name).set(object,value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static Object invoke(Object object,String name,Class<?>[] signature,Object... args){try{Method m=object.getClass().getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(object,args);}catch(Exception error){throw new IllegalStateException(error);}}
}
