package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable empty emulator only. No real scooter, owner credentials, routes, or recordings. */
public final class LearnedRangeValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle arguments;
    private String profile;
    private int checks;
    @Override public void onCreate(Bundle args) { arguments=args==null?new Bundle():args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                    .putBoolean("developer_options",true).putString("theme",arguments.getString("theme","dark")).commit(),"Fixture preferences failed");
            seedProfileAndCache();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,"Service absent"); service=activity.service();
            await(()->service.displayReadings().get("battery_percent").number!=null,"Cache absent");
            main(()->require(!service.usesLearnedRange(),"New profiles must default to scooter"));
            String a=ride(8,2), b=ride(8,1.2), c=ride(4,1.2);
            main(()->service.invalidateCapacityEstimate());
            LearnedRangeEstimate.Result estimate=estimate();
            StringBuilder audit=new StringBuilder();
            for(RideStore.Summary summary:service.rides.list())audit.append(" [").append(summary.batteryDropPercent).append(" pts, ").append(summary.distanceKm).append(" km: ").append(LearnedRangeEstimate.inspect(summary,service.rides.fullTrend(summary.id)).rejection).append("]");
            require(estimate.rides==3 && estimate.fullRangeKm!=null && Math.abs(estimate.fullRangeKm-22)<.001,"Worker did not learn full synthetic journals: rides="+estimate.rides+", rejected="+estimate.rejectedRides+", points="+estimate.batteryPoints+", range="+estimate.fullRangeKm+", reason="+estimate.reason+audit);
            main(()->{
                require(service.dashboardReadings().get("remaining_range_km").number==30,"Opt-out replaced reported range");
                service.useLearnedRange(true);
                TelemetryHistory.Reading learned=service.dashboardReadings().get("remaining_range_km");
                require(Math.abs(learned.number-11)<.001&&!learned.live,"Learned reading or stale state wrong");
                require(service.displayReadings().get("remaining_range_km").number==30,"Learning overwrote raw telemetry");
                require(settings().getString("range_source_"+profile,"").equals("learned"),"Range source not per-profile persisted");
                ScooterProfiles.Profile other=service.profiles.create("Second synthetic","111111111111",0);
                require(!service.usesLearnedRange(),"Second scooter inherited source selection");
                service.profiles.select(profile);
                require(service.usesLearnedRange(),"Original source choice lost");
                require(service.setSimulation(true),"Cannot enable sample mode");
            });
            require(estimate().reason.equals("Sample data"),"Simulation read real ride evidence");
            main(()->{
                require(service.dashboardReadings().get("remaining_range_km").number.equals(service.displayReadings().get("remaining_range_km").number),"Simulation applied real learned model");
                service.setSimulation(false);
            });
            require(estimate().rides==3,"Simulation exit lost genuine rides");
            currentRangeUi();
            service.rides.delete(c);main(()->service.invalidateCapacityEstimate());
            require(estimate().fullRangeKm==null,"Deleting a ride left learned evidence active");
            main(()->require(service.dashboardReadings().get("remaining_range_km").number==30,"Insufficient history did not fall back"));
            ride(4,1.2);main(()->service.invalidateCapacityEstimate());require(estimate().fullRangeKm!=null,"Completed/restored ride did not relearn");
            backendFailureAndGeneration();
            pickerUi();
            result.putString("result","PASS");
        } catch(Throwable error) { result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace(); }
        finally {result.putInt("assertions",checks);if(activity!=null)main(()->{activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    /** Different reported/learned fixtures expose inconsistent consumers, including all unit choices. */
    private void currentRangeUi() {
        main(() -> {
            invoke(activity,"select",new Class<?>[]{String.class},"home");
            DashboardScreen dashboard=(DashboardScreen)field(activity,"screen");
            DashboardConfig config=(DashboardConfig)field(dashboard,"config");
            List<DashboardConfig.Widget> saved=new ArrayList<>(config.widgets);
            String oldUnits=settings().getString("units","mi");
            Set<String> oldFavourites=new LinkedHashSet<>(settings().getStringSet("stats_favourites",Collections.emptySet()));
            try {
                config.widgets.clear();
                for(String type:new String[]{"scooter","battery","overview","metric","gauge","readings","tiles","live","history"}) {
                    DashboardConfig.Widget widget=new DashboardConfig.Widget(type);
                    widget.metric="remaining_range_km";widget.keys.clear();widget.keys.add("remaining_range_km");config.widgets.add(widget);
                }
                invoke(dashboard,"render",new Class<?>[0]);
                for(boolean learned:new boolean[]{false,true}) for(String units:new String[]{"km","mi","dual"}) {
                    settings().edit().putString("units",units).putStringSet("stats_favourites",new LinkedHashSet<>(Arrays.asList("remaining_range_km"))).commit();
                    service.useLearnedRange(learned);dashboard.refresh();
                    double km=learned?11:30;
                    String precise=DisplayUnits.metricText(String.format(Locale.US,"%.2f km",km),units);
                    String roundedKm=Math.round(km)+" km",roundedMi=Math.round(km/RideStore.KM_PER_MILE)+" mi";
                    String rounded=units.equals("km")?roundedKm:units.equals("dual")?roundedMi+" / "+roundedKm:roundedMi;
                    for(Object tile:(List<?>)field(dashboard,"tiles")) {
                        DashboardConfig.Widget widget=(DashboardConfig.Widget)field(tile,"widget");
                        if(Arrays.asList("scooter","readings","tiles").contains(widget.type)) {
                            TextView value=(TextView)((Map<?,?>)field(tile,"amounts")).get("remaining_range_km");
                            require(value.getText().toString().equals(precise),widget.type+" ignored source/units: "+value.getText());
                            require(value.getContentDescription().toString().startsWith("Estimated remaining range"),"Current range incorrectly claims scooter-only source");
                        } else if(widget.type.equals("metric"))
                            require(((TextView)field(tile,"big")).getText().toString().equals(precise),"Single-reading range ignored source/units");
                        else if(widget.type.equals("gauge")) {
                            Object gauge=field(tile,"gauge");TelemetryHistory.Reading reading=(TelemetryHistory.Reading)field(gauge,"reading");
                            require(Math.abs(reading.number-km)<.001&&((View)gauge).getContentDescription().toString().contains(precise),"Gauge range ignored source/units");
                        } else if(widget.type.equals("battery"))
                            require(field(field(tile,"battery"),"detail").equals("about "+rounded),"Battery range ignored source/units");
                        else if(widget.type.equals("overview"))
                            require(((TextView)field(field(tile,"overview"),"range")).getText().toString().equals("about "+rounded+" left"),"Overview range ignored source/units");
                        else {
                            require(((TextView)field(tile,"subtitle")).getContentDescription().toString().startsWith("Scooter's estimated remaining range"),"Recorded range chart lost source label");
                        }
                    }
                    StatsScreen stats=new StatsScreen(activity);
                    try {
                        stats.refresh();
                        require(((TextView)field(stats,"batteryHeadline")).getText().toString().equals("≈ "+rounded+" left"),"Stats overview ignored source/units");
                        Object favourite=((Map<?,?>)field(stats,"favouriteRows")).get("remaining_range_km");
                        require(((TextView)field(favourite,"name")).getText().toString().equals("Scooter range"),"Raw favourite must identify its source");
                        invoke(stats,"selectSection",new Class<?>[]{String.class},"Readings");stats.refresh();
                        Object row=((Map<?,?>)field(stats,"rows")).get("remaining_range_km");
                        require(((TextView)field(row,"name")).getText().toString().equals("Scooter range"),"Raw reading must identify its source");
                        String reported=((TextView)field(row,"amount")).getText().toString();
                        require(reported.contains(units.equals("km")?"30 km":"18.64 mi"),"Raw scooter reading was replaced by learned range");
                        require(invoke(field(stats,"chartBrowser"),"label",new Class<?>[]{String.class},"remaining_range_km").equals("Scooter range"),"Stored chart must identify its source");
                    } finally {stats.close();settings().edit().putString("stats_section","Overview").commit();}
                    require(service.displayReadings().get("remaining_range_km").number==30,"UI preference changed recorded source data");
                }
            } finally {
                settings().edit().putString("units",oldUnits).putStringSet("stats_favourites",oldFavourites).putString("stats_section","Overview").commit();
                service.useLearnedRange(true);config.widgets.clear();config.widgets.addAll(saved);invoke(dashboard,"render",new Class<?>[0]);
            }
        });
    }
    private void pickerUi() throws Exception {
        main(()->{
            service.useLearnedRange(false);
            require(settings().edit().putBoolean("range_follow_mode_"+profile,false).remove("range_learned_intro_seen").commit(),"Picker fixture preferences failed");
            invoke(activity,"select",new Class<?>[]{String.class},"more");
            invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"App");
            require(((View)field(activity,"root")).findViewWithTag("preference-range-estimate")==null,"Duplicate range setting remains in App preferences");
            invoke(activity,"select",new Class<?>[]{String.class},"controls");
        });
        waitForIdleSync();
        java.util.concurrent.CountDownLatch release=holdWorker();
        try {
            main(()->{
                service.invalidateCapacityEstimate();
                View row=((View)field(activity,"root")).findViewWithTag("scooter-range-estimate");
                require(row!=null&&row.isEnabled(),"Disconnected Scooter range setting missing or disabled");row.performClick();
                Object sheet=picker();View root=pickerRoot();
                @SuppressWarnings("unchecked") List<View> options=(List<View>)field(sheet,"options");
                require(options.size()==2,"Range menu must have exactly two choices");
                require(((TextView)((ViewGroup)options.get(0)).getChildAt(0)).getText().toString().equals("Scooter's estimate"),"Scooter choice label changed");
                require(((TextView)((ViewGroup)options.get(1)).getChildAt(0)).getText().toString().equals("Volt's estimate (learned)"),"Learned choice label changed");
                require(Boolean.TRUE.equals(field(sheet,"pending")),"Opening picker did not check availability");
                require(((TextView)root.findViewWithTag("range-learning-status")).getText().toString().equals("Checking saved rides…"),"Pending analysis presented as insufficient history");
                require(!service.followsRidingMode(),"Opening picker changed legacy fixed range mode");
                root.findViewWithTag("range-source-volt").performClick();
                require(!service.usesLearnedRange()&&field(sheet,"intro")==null,"Pending choice selected learned range or showed a false insufficient popup");
                require(!settings().getBoolean("range_learned_intro_seen",false),"Pending tap consumed first-use explanation");
            });
            waitForIdleSync();main(()->screenshot(pickerRoot(),"range-pending.png"));
        } finally {release.countDown();}
        awaitPicker(LearnedRangeEstimate.Status.READY);
        // A tap can arrive before a scheduled redraw after history changes. It must not
        // select the obsolete READY result while the replacement calculation is blocked.
        release=holdWorker();
        try {
            main(()->{
                Object sheet=picker();service.invalidateCapacityEstimate();
                pickerRoot().findViewWithTag("range-source-volt").performClick();
                require(Boolean.TRUE.equals(field(sheet,"pending")),"Tap after invalidation did not refresh old evidence");
                require(!service.usesLearnedRange()&&!explanationVisible(sheet),"Tap used stale READY evidence");
                require(!settings().getBoolean("range_learned_intro_seen",false),"Stale evidence consumed first-use explanation");
            });
        } finally {release.countDown();}
        awaitPicker(LearnedRangeEstimate.Status.READY);
        waitForIdleSync();
        main(()->{
            Object sheet=picker();View root=pickerRoot();screenshot(root,"range-preference.png");
            require(root.findViewWithTag("range-source-volt").getStateDescription().toString().equals("Not selected"),"Ready choice stayed disabled");
            root.findViewWithTag("range-source-volt").performClick();
            require(service.usesLearnedRange(),"Ready learned choice was not selected");
            require(!service.followsRidingMode(),"Choosing Volt changed a legacy fixed firmware preference");
            AlertDialog intro=(AlertDialog)field(sheet,"intro");
            require(intro!=null&&intro.isShowing()&&intro.getWindow().getDecorView().findViewWithTag("range-learned-intro")!=null,"First ready selection omitted the explanation");
            require(settings().getBoolean("range_learned_intro_seen",false),"First-use explanation was not remembered");
        });
        waitForIdleSync();
        main(()->{
            Object sheet=picker();AlertDialog intro=(AlertDialog)field(sheet,"intro");View root=intro.getWindow().getDecorView();screenshot(root,"range-intro.png");
            root.findViewWithTag("range-intro-done").performClick();
            pickerRoot().findViewWithTag("range-source-scooter").performClick();
            require(!service.usesLearnedRange()&&service.followsRidingMode(),"Explicit Scooter selection did not restore drive-mode following");
            pickerRoot().findViewWithTag("range-source-volt").performClick();
            require(service.usesLearnedRange()&&!explanationVisible(sheet),"First-use explanation repeated");
            ((AlertDialog)field(sheet,"dialog")).dismiss();
            invoke(activity,"quickAction",new Class<?>[]{String.class},"feature:range_algorithm");
            require(pickerRoot().findViewWithTag("range-source-sheet")!=null,"Disconnected quick access did not open the same picker");
        });
        awaitPicker(LearnedRangeEstimate.Status.READY);
        main(()->{
            Object sheet=picker();pickerRoot().findViewWithTag("range-source-volt").performClick();
            require(!explanationVisible(sheet),"Reopening picker repeated one-time explanation");
            ((AlertDialog)field(sheet,"dialog")).dismiss();
            service.useLearnedRange(false);settings().edit().remove("range_learned_intro_seen").apply();
        });
        service.rides.delete(service.rides.list().get(0).id);
        main(()->{service.invalidateCapacityEstimate();invoke(activity,"quickAction",new Class<?>[]{String.class},"feature:range_algorithm");});
        awaitPicker(LearnedRangeEstimate.Status.INSUFFICIENT);
        main(()->{
            Object sheet=picker();View option=pickerRoot().findViewWithTag("range-source-volt");
            require(option.isEnabled()&&option.isClickable(),"Unavailable learned choice cannot explain why");
            require(option.getStateDescription().toString().contains("more rides needed"),"Insufficient choice lacks accessible availability explanation");
            @SuppressWarnings("unchecked") List<TextView> names=(List<TextView>)field(sheet,"names");
            require(names.get(0).getCurrentTextColor()!=names.get(1).getCurrentTextColor(),"Insufficient choice was not greyed out");
            option.performClick();
            AlertDialog more=(AlertDialog)field(sheet,"intro");
            require(more!=null&&more.isShowing()&&more.getWindow().getDecorView().findViewWithTag("range-more-rides")!=null,"Insufficient tap omitted brief explanation");
            require(!service.usesLearnedRange()&&!settings().getBoolean("range_learned_intro_seen",false),"Insufficient tap selected range or consumed first-use explanation");
        });
        waitForIdleSync();
        main(()->{
            Object sheet=picker();AlertDialog more=(AlertDialog)field(sheet,"intro");View root=more.getWindow().getDecorView();screenshot(root,"range-more-rides.png");
            root.findViewWithTag("range-intro-done").performClick();
            service.useLearnedRange(true);invoke(sheet,"refresh",new Class<?>[]{});
            require(service.usesLearnedRange()&&service.dashboardReadings().get("remaining_range_km").number==30,"Deleting history changed saved source instead of falling back");
            require(((TextView)pickerRoot().findViewWithTag("range-learning-status")).getText().toString().equals("Using scooter's estimate for now."),"Selected unavailable source lacks fallback state");
            service.useLearnedRange(false);
        });
        AtomicReference<Object> openInsufficient=new AtomicReference<>();
        main(()->openInsufficient.set(picker()));
        // Complete the third genuine journal through the public service stop path. The
        // existing picker must observe the revision without reopening or a manual refresh.
        completeActiveRideFromService();
        awaitPicker(LearnedRangeEstimate.Status.READY);
        main(()->{
            Object sheet=picker();require(sheet==openInsufficient.get(),"Completing ride replaced rather than refreshed the open picker");
            require(((LearnedRangeEstimate.Result)field(sheet,"result")).rides==3,"Completed third ride did not reach open picker");
            require(pickerRoot().findViewWithTag("range-source-volt").getStateDescription().toString().equals("Not selected"),"Completed ride did not enable learned choice");
            require(!service.usesLearnedRange()&&!settings().getBoolean("range_learned_intro_seen",false),"Availability refresh selected range or consumed explanation");
            ((AlertDialog)field(sheet,"dialog")).dismiss();
        });
        release=holdWorker();
        try {
            main(()->{
                service.invalidateCapacityEstimate();invoke(activity,"quickAction",new Class<?>[]{String.class},"feature:range_algorithm");
                Object oldSheet=picker();View staleChoice=pickerRoot().findViewWithTag("range-source-volt");
                AlertDialog oldDialog=(AlertDialog)field(oldSheet,"dialog");
                ScooterProfiles.Profile other=service.profiles.create("Picker switch fixture","222222222222",0);
                invoke(activity,"refreshBodyNow",new Class<?>[]{});
                require(!oldDialog.isShowing(),"Changing scooter left old picker open");
                staleChoice.performClick();require(!service.usesLearnedRange(),"Stale picker changed the new scooter's preference");
                require(!settings().contains("range_source_"+other.id),"Stale picker wrote a choice for the new scooter");
                service.profiles.select(profile);
            });
        } finally {release.countDown();}
        require(estimate().status==LearnedRangeEstimate.Status.READY,"Dismissed picker left worker unavailable");
        main(()->{
            require(field(activity,"rangeSourceSheet")==null,"Completed stale callback reopened picker");
            require(!service.usesLearnedRange(),"Completed stale callback changed source preference");
            require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Local range selection touched Bluetooth");
            require(service.setSimulation(true),"Could not enable simulation for picker test");
            activity.rebuild();
            invoke(activity,"quickAction",new Class<?>[]{String.class},"feature:range_algorithm");
        });
        awaitPicker(LearnedRangeEstimate.Status.UNAVAILABLE);
        main(()->{
            Object sheet=picker();View root=pickerRoot();
            require(((TextView)root.findViewWithTag("range-learning-status")).getText().toString().equals("Exit simulation to use saved rides."),"Simulation was described as a storage failure");
            require(root.findViewWithTag("range-source-volt").getStateDescription().toString().contains("unavailable in simulation"),"Simulation availability is not accessible");
            root.findViewWithTag("range-source-volt").performClick();
            require(!service.usesLearnedRange()&&!explanationVisible(sheet)&&!settings().getBoolean("range_learned_intro_seen",false),"Simulation selected learned range or consumed introduction");
            require(((Map<?,?>)field(service,"rangePending")).isEmpty()&&((Map<?,?>)field(service,"rangeCache")).isEmpty(),"Simulation analyzed or cached real ride evidence");
            ((AlertDialog)field(sheet,"dialog")).dismiss();require(service.setSimulation(false),"Could not exit picker simulation");
        });
        require(estimate().status==LearnedRangeEstimate.Status.READY,"Leaving simulation did not restore genuine evidence");
    }
    private void completeActiveRideFromService(){
        main(()->{
            try {
                long nowWall=System.currentTimeMillis(),nowMono=SystemClock.elapsedRealtime(),duration=120000;
                require(nowMono>duration,"Disposable emulator uptime too short for completed fixture");
                require(!service.isRecording(),"Unexpected active fixture recording");
                service.rides.start(profile,"Synthetic service completion",nowWall-duration,nowMono-duration);
                for(int sec=0;sec<=120;sec++){
                    long mono=nowMono-duration+sec*1000;
                    Map<String,RideStore.Sample> values=new LinkedHashMap<>();
                    values.put("battery_percent",new RideStore.Sample(80-8d*sec/120,mono));
                    values.put("odometer_km",new RideStore.Sample(100+2d*sec/120,mono));
                    values.put("speed_kmh",new RideStore.Sample(60,mono));
                    values.put("charging_state",new RideStore.Sample(0,mono));
                    service.rides.sample(nowWall-duration+sec*1000,mono,values);
                }
                require(service.isRecording(),"Synthetic journal was not active");
                service.stopRide();
                require(!service.isRecording(),"Service stop did not finish journal");
            }catch(IOException e){throw new IllegalStateException(e);}
        });
    }
    private boolean explanationVisible(Object sheet){AlertDialog shown=(AlertDialog)field(sheet,"intro");return shown!=null&&shown.isShowing();}
    private Object picker(){Object sheet=field(activity,"rangeSourceSheet");require(sheet!=null,"Range picker missing");return sheet;}
    private View pickerRoot(){AlertDialog dialog=(AlertDialog)field(picker(),"dialog");require(dialog.isShowing(),"Range picker not visible");return dialog.getWindow().getDecorView();}
    private void awaitPicker(LearnedRangeEstimate.Status expected){
        await(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->{Object sheet=field(activity,"rangeSourceSheet");if(sheet!=null){Object result=field(sheet,"result");ready.set(!Boolean.TRUE.equals(field(sheet,"pending"))&&result instanceof LearnedRangeEstimate.Result&&((LearnedRangeEstimate.Result)result).status==expected);}});return ready.get();},"Picker did not reach "+expected);
    }
    private java.util.concurrent.CountDownLatch holdWorker() throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(service,"capacityWorker");
        worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        require(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"Could not hold range worker for UI test");return release;
    }
    private void backendFailureAndGeneration() throws Exception {
        File ridesDirectory=new File(getTargetContext().getFilesDir(),"rides");
        File unavailable=new File(getTargetContext().getFilesDir(),"rides-test-unavailable");
        require(ridesDirectory.renameTo(unavailable),"Move synthetic store to simulate unavailable storage");
        try {
            main(()->service.invalidateCapacityEstimate());
            LearnedRangeEstimate.Result failed=estimate();
            require(failed.status==LearnedRangeEstimate.Status.UNAVAILABLE,"Storage failure masqueraded as insufficient rides");
            main(()->{
                for(int i=0;i<5;i++)require(service.dashboardReadings().get("remaining_range_km").number==30,"Storage failure did not fall back");
                require(((Map<?,?>)field(service,"rangePending")).isEmpty(),"Home repeatedly retried failed analysis");
            });
        } finally { require(unavailable.renameTo(ridesDirectory),"Restore synthetic ride store"); }
        require(estimate().status==LearnedRangeEstimate.Status.READY,"Explicit retry did not bypass cached error immediately");

        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(service,"capacityWorker");
        worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        require(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"Could not hold range worker");
        AtomicReference<LearnedRangeEstimate.Result> completed=new AtomicReference<>();
        try {
            main(()->{service.invalidateCapacityEstimate();service.requestLearnedRangeEstimate(completed::set);
                require(completed.get()==null,"Pending analysis returned false zero/insufficient result");});
            List<RideStore.Summary> list=service.rides.list();service.rides.delete(list.get(0).id);
            main(()->service.invalidateCapacityEstimate());
        } finally {release.countDown();}
        await(()->completed.get()!=null,"Generation retry did not complete");
        require(completed.get().status==LearnedRangeEstimate.Status.INSUFFICIENT&&completed.get().rides==2,
                "History change returned/cached obsolete ready evidence");
        ride(8,2);main(()->service.invalidateCapacityEstimate());
        require(estimate().status==LearnedRangeEstimate.Status.READY,"Changed history did not recover");
    }
    private LearnedRangeEstimate.Result estimate() { AtomicReference<LearnedRangeEstimate.Result> out=new AtomicReference<>();main(()->service.requestLearnedRangeEstimate(out::set));await(()->out.get()!=null,"Range worker timeout");return out.get(); }
    private String ride(double drop,double km) throws Exception {
        RideStore fixture=new RideStore(new File(getTargetContext().getCacheDir(),"range-"+UUID.randomUUID()));
        long start=System.currentTimeMillis()-1200000, mono=1000;String id=fixture.start(profile,"Synthetic range",start,mono);
        for(int sec=0;sec<=600;sec++) {long t=mono+sec*1000;Map<String,RideStore.Sample> values=new LinkedHashMap<>();
            values.put("battery_percent",new RideStore.Sample(80-drop*sec/600,t));values.put("odometer_km",new RideStore.Sample(100+km*sec/600,t));
            values.put("speed_kmh",new RideStore.Sample(km*6,t));values.put("charging_state",new RideStore.Sample(0,t));
            fixture.sample(start+sec*1000,t,values);
        }fixture.stop(start+600000,mono+600000);fixture.exportCsv(id,new File(new File(getTargetContext().getFilesDir(),"rides"),id+".csv"));return id;
    }
    private void seedProfileAndCache() {
        AtomicReference<TelemetryHistory> store=new AtomicReference<>();AtomicReference<TelemetryHistory.Snapshot> done=new AtomicReference<>();
        main(()->{ScooterProfiles profiles=new ScooterProfiles(getTargetContext());profile=profiles.create("Synthetic range fixture","000000000000",0).id;
            long now=System.currentTimeMillis()-60000;Map<String,TelemetryHistory.Reading> values=new LinkedHashMap<>();
            values.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","50 %","%",50d,now,true));
            values.put("remaining_range_km",new TelemetryHistory.Reading("remaining_range_km","Scooter range","Ride","30 km","km",30d,now,true));
            store.set(new TelemetryHistory(getTargetContext()));store.get().record(profile,new TelemetryHistory.Snapshot(values,Collections.emptyMap(),now),1,true);store.get().load(profile,done::set);
        });await(()->done.get()!=null,"History seed timeout");main(()->store.get().close());
    }
    private void admit() {
        require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical device");
        for(String p:new String[]{"app_settings","profiles","scooter","pending_documents","control_evidence"})require(getTargetContext().getSharedPreferences(p,Context.MODE_PRIVATE).getAll().isEmpty(),"Refusing existing preferences");
        require(getTargetContext().getFilesDir().list().length==0&&getTargetContext().databaseList().length==0,"Refusing existing data");
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})require(getTargetContext().checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED,"Nearby permission missing");
        BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Disable emulator Bluetooth");
    }
    private void screenshot(View view,String name) {
        try {File dir=new File(getTargetContext().getExternalFilesDir(null),"learned-range-validation");require(dir.isDirectory()||dir.mkdirs(),"Screenshot directory");
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE);}
    private void main(Runnable r){runOnMainSync(r);}
    private void require(boolean condition,String message){checks++;if(!condition)throw new IllegalStateException(message);}
    private void await(BooleanSupplier ready,String message){long end=SystemClock.uptimeMillis()+15000;while(!ready.getAsBoolean()&&SystemClock.uptimeMillis()<end)SystemClock.sleep(30);require(ready.getAsBoolean(),message);}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new IllegalStateException(e);}}
    private Object invoke(Object target,String name,Class<?>[] types,Object... args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new IllegalStateException(e);}}
}
