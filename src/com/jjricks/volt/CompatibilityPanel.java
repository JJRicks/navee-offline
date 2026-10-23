package com.jjricks.volt;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * More › Developer options › Compatibility: the scooter's identity, the read-only check, its results, any running or
 * unfinished try or older guided test (review, Restore, Keep, Dismiss) and the report preview, which includes the
 * journalled tries and tests. Trying settings lives in More › Experimental controls (its Help verify group replaced
 * this panel's Try a setting). Nothing leaves the phone unless the owner sends the email that Email to Volt (under
 * Run again) drafts, or taps Share or Save in the preview; then the report's tries count as reported.
 */
final class CompatibilityPanel {
    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout root;
    private final boolean recoveryOnly;
    private String shown="";
    private TextView progressText;
    private View progressFill,progressRest;
    private final Set<String> expanded=new HashSet<>();
    private boolean preview,showJson;
    private CompatibilityReport report;

    CompatibilityPanel(ScreenHost host,LinearLayout destination,boolean recoveryOnly){
        this.host=host;ui=host.ui();this.recoveryOnly=recoveryOnly;
        root=new LinearLayout(host.activity());root.setOrientation(LinearLayout.VERTICAL);
        destination.addView(root,new LinearLayout.LayoutParams(-1,-2));
        expanded.add("Reads");expanded.add("Controls");
        refresh();
    }

    private BleController ble(){ScooterService s=host.service();return s==null?null:s.ble;}

    void refresh(){
        BleController ble=ble();
        if(ble==null){rebuild("none",null);return;}
        DiscoveryCoordinator check=ble.discovery();
        boolean ready=ble.ready(),running=ble.discovering();
        if(running||check==null){preview=false;report=null;}
        // The preview shows the journal as it is: a restore or try finished meanwhile rebuilds it (acknowledging still checks).
        if(preview&&report!=null&&ble.reportOutdated(report)){CompatibilityReport fresh=ble.compatibilityReport(AppVersion.name(host.activity()));if(fresh!=null)report=fresh;}
        DeviceDescriptor d=ble.descriptor();
        String key=(ready?"ready":"offline")+"|"+running+"|"+(check==null?"":Integer.toHexString(System.identityHashCode(check)))+"|"+preview+"|"+showJson+"|"+expanded
            +"|"+ble.modelUnidentified()+"|"+ModelRegistry.modelLine(d)+"|"+d.pidSource()+"|"+d.region+"|"+d.firmware.meter+"|"+ble.resolution()+"|"+testKey(ble)
            +"|"+(report==null?"":Integer.toHexString(System.identityHashCode(report)));
        rebuild(key,ble);
        if(running&&check!=null)progress(check);
    }

    private void rebuild(String key,BleController ble){
        if(key.equals(shown))return;
        shown=key;root.removeAllViews();progressText=null;
        if(ble==null){root.addView(note("Your local scooter service is starting."));return;}
        DiscoveryCoordinator check=ble.discovery();boolean running=ble.discovering();
        // A pre-upgrade or interrupted test must remain recoverable even when developer options
        // are hidden. This route never offers a new check, test or report.
        if(recoveryOnly){
            if(!ble.ready())root.addView(ui.button("Connect",host::connect));
            tests(ble);
            if(ble.guidedTest()==null&&ble.unresolvedTests().isEmpty()&&ble.unresolvedTestsElsewhere()==0
                    &&ble.orphanedTests().isEmpty()&&ble.testRecordsHealthy())root.addView(note("No unfinished tests."));
            return;
        }
        identity(ble);
        if(running&&check!=null)running();
        else if(ble.ready()&&!ble.modelUnidentified()&&!ble.testing())root.addView(ui.button(check==null?"Run check":"Run again",()->{ble.startDiscovery();toastIfIdle(ble);host.redraw();}));
        // A finished check is one tap from Volt's inbox: the offer's "Which scooter is this?" sheet, then an email draft.
        if(!running&&check!=null){TextView email=ui.secondaryButton("Email to Volt",()->emailNow(ble));email.setTag("compatibility-email");root.addView(email);}
        if(!running)tests(ble);
        if(!running&&check!=null){
            results(ble,check);
            if(preview)preview(ble);
            else root.addView(ui.secondaryButton("Preview report",()->{report=ble.compatibilityReport(AppVersion.name(host.activity()));if(report==null){host.toast("No finished check yet");return;}preview=true;showJson=false;refresh();}));
        }
        TextView foot=ui.caption("The check only reads. Tries are in More › Experimental controls, and the report includes their results. Never changes firmware.");foot.setPadding(ui.dp(4),ui.dp(8),ui.dp(4),ui.dp(12));root.addView(foot);
    }
    private void toastIfIdle(BleController ble){if(!ble.discovering())host.toast(ble.message());}

    // ---- Identity -------------------------------------------------------------------------

    private void identity(BleController ble){
        LinearLayout card=ui.card();root.addView(card);
        if(!ble.ready()){
            TextView t=ui.text("Connect to check this scooter.",15,false);card.addView(t);
            card.addView(ui.button("Connect",host::connect));
            return;
        }
        DeviceDescriptor d=ble.descriptor();ModelRegistry.Resolution r=ble.resolution();
        String model=ModelRegistry.modelLine(d);
        TextView title=ui.text(model.isEmpty()?"Model ID not captured":model,17,true);card.addView(title);
        if(d.simulated)card.addView(muted("Simulated scooter"));
        line(card,"Model ID",d.pidSource()==DeviceDescriptor.PidSource.ADVERTISED?"Advertised":d.pidSource()==DeviceDescriptor.PidSource.SERIAL?"From serial (unconfirmed)":"Not captured");
        if(r.conflict)line(card,"Check","Model ID mismatch");
        line(card,"Region",d.region==null?"Unknown":d.region+" (from serial)");
        line(card,"Firmware",d.firmware.meter==null?"Not read yet":d.firmware.meter);
        line(card,"Rules",layer(r.layer));
        String said=ModelRegistry.ownerModel(d);if(!said.isEmpty())line(card,"Your answer",said);
        if(ble.modelUnidentified()){
            card.addView(muted("Identify reads the model ID from the scooter's broadcast. It sends no command."));
            card.addView(ui.button("Identify",()->{ble.identifyModel();host.toast(ble.message());host.redraw();}));
        }
    }
    static String layer(ModelRegistry.Layer layer){
        switch(layer){
            case UT5_MAX: return "UT5 MAX";
            case OEM_GROUP: return "OEM-mapped (untested)";
            case SHARED: return "Lock and readings";
            default: return "Not supported";
        }
    }

    // ---- Running and unfinished tries and tests (recovery) -------------------------------------

    /** Everything the test cards show, so the panel rebuilds only when one of them changes. */
    private String testKey(BleController ble){
        StringBuilder k=new StringBuilder();GuidedTest t=ble.guidedTest();
        if(t!=null)k.append(t.phase()).append(t.outcomes().size()).append(t.note()).append(t.baseline());
        for(TestJournal.Entry e:ble.unresolvedTests()){GuidedTest.Review r=ble.reviewTest(e.testId);k.append('|').append(e.testId).append(e.phase).append(r==null?"":r.state+"/"+r.current+"/"+r.restorable+"/"+decision(ble,e.isTry(),e.feature).reason);}
        for(TestJournal.Entry e:ble.orphanedTests())k.append("|orphan").append(e.testId);
        k.append('|').append(ble.unresolvedTestsElsewhere()).append(ble.testParked()).append(ble.testRecordsHealthy());
        return k.toString();
    }
    /** A try is reviewed and restored under the try rule; an older guided test under the guided-test table. */
    private static CapabilityResolver.Decision decision(BleController ble,boolean tried,String feature){return tried?ble.tryDecision(feature):ble.testDecision(feature);}
    private void tests(BleController ble){
        GuidedTest t=ble.guidedTest();
        if(t!=null)liveTest(ble,t);
        List<TestJournal.Entry> open=ble.unresolvedTests();
        for(TestJournal.Entry e:open)unfinished(ble,e);
        int elsewhere=ble.unresolvedTestsElsewhere();
        if(elsewhere>0)root.addView(note(elsewhere==1?"1 unfinished test on another scooter. Select it to review.":elsewhere+" unfinished tests on other scooters. Select one to review."));
        for(TestJournal.Entry e:ble.orphanedTests())orphaned(ble,e);
        // An unreadable journal may hide an unfinished test, so say so whether or not a scooter is connected.
        if(!ble.testRecordsHealthy())root.addView(note(TestJournal.UNAVAILABLE_NOTE));
    }
    private void liveTest(BleController ble,GuidedTest t){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(t.feature).label;CapabilityResolver.Decision d=decision(ble,t.keeps(),t.feature);
        card.addView(ui.text((t.keeps()?"Try · ":"Test · ")+label,16,true));
        Integer base=t.baseline();String from=base==null?"":d.label(base),to=d.label(t.target);
        TextView status;
        switch(t.phase()){
            // A try approves itself once its baseline matches; no new guided test starts any more.
            case PRECHECK: case BASELINE: card.addView(muted("Reading the current value…"));card.addView(ui.secondaryButton("Cancel",()->{ble.cancelTest();refresh();}));return;
            case TARGET_PENDING: case TARGET_AWAIT: status=muted("Changing to "+to+"…");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(status);return;
            case RESTORE_PENDING: case RESTORE_AWAIT: status=muted(t.verifying()?"Checking it is unchanged…":"Setting it back to "+from+"…");
                status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(status);return;
            case OBSERVE:
                card.addView(ui.text(question(t.feature,to),15,false));
                card.addView(ui.button("Yes",()->{ble.testEffect(true);refresh();}));
                card.addView(ui.secondaryButton("No",()->{ble.testEffect(false);refresh();}));
                card.addView(ui.secondaryButton("Skip",()->{ble.testEffect(null);refresh();}));
                return;
            default:
                if(!t.note().isEmpty())card.addView(muted(t.note()));
                if(!t.nothingWritten()){
                    card.addView(muted(join(t.outcomeTexts())));
                    String r=t.restorationStatus();boolean kept=t.keeps()&&r.equals(TestJournal.DECLINED);
                    TextView restored=ui.text(kept?"Kept · Set back is in Experimental controls":r.equals("confirmed")?"Restoration confirmed":"Restoration outstanding · review below",13.5f,true);
                    restored.setTextColor(r.equals("confirmed")||kept?ui.ok:ui.text);restored.setPadding(0,ui.dp(6),0,0);card.addView(restored);
                }
                card.addView(ui.secondaryButton("Close",()->{ble.closeTest();refresh();}));
        }
    }
    private void unfinished(BleController ble,TestJournal.Entry e){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(e.feature).label;CapabilityResolver.Decision d=decision(ble,e.isTry(),e.feature);
        card.addView(ui.text((e.isTry()?"Unfinished try · ":"Unfinished test · ")+label,16,true));
        line(card,e.isTry()?"Before the try":"Before the test",d.label(e.baseline));line(card,e.isTry()?"Value tried":"Test value",d.label(e.target));
        if(!e.outcomes.isEmpty()){List<String> texts=new java.util.ArrayList<>();for(String o:e.outcomes)texts.add(GuidedTest.Outcome.describe(o));card.addView(muted(join(texts)));}
        GuidedTest.Review r=ble.reviewTest(e.testId);
        if(r==null)return;
        card.addView(ui.text(r.text,14,false));
        boolean restore=(r.state==GuidedTest.ReviewState.RESTORE_NEEDED||r.state==GuidedTest.ReviewState.CHANGED_OUTSIDE)&&r.restorable;
        if(restore&&d.reason==CapabilityResolver.Reason.PARKED_UNCONFIRMED)card.addView(ui.toggleRow("Scooter is parked","No motion report yet on this connection.",ble.testParked(),true,ble::setTestParked));
        else if(restore&&!d.available())card.addView(muted(d.reasonText()));
        switch(r.state){
            case FIRMWARE_CHANGED: card.addView(ui.secondaryButton("Continue anyway",()->{ble.confirmTestFirmware(e.testId);refresh();}));break;
            case ALREADY_RESTORED: card.addView(ui.button("Mark restored",()->{ble.markTestRestored(e.testId);refresh();}));break;
            case RESTORE_NEEDED:
                if(restore)addRestore(card,ble,e,d,"Restore to "+d.label(e.baseline));
                // A try may have worked: the owner can keep the value tried, as it would have stayed.
                if(e.isTry())card.addView(ui.secondaryButton(unbroken("Keep "+d.label(e.target)),()->{ble.keepTest(e.testId);refresh();}));
                break;
            case CHANGED_OUTSIDE:
                if(r.current!=null)card.addView(ui.secondaryButton(unbroken("Keep "+d.label(r.current)),()->{ble.keepTest(e.testId);refresh();}));
                if(restore)addRestore(card,ble,e,d,"Set "+d.label(e.baseline));
                break;
            default: break;
        }
        TextView dismiss=ui.secondaryButton("Dismiss · leave as is",()->{ble.dismissTest(e.testId);refresh();});
        dismiss.setContentDescription("Dismiss the unfinished "+label+(e.isTry()?" try":" test")+" without restoring it");card.addView(dismiss);
    }
    /** An unfinished test whose scooter profile was removed: nothing can be checked or set back, only dismissed. */
    private void orphaned(BleController ble,TestJournal.Entry e){
        LinearLayout card=ui.card();root.addView(card);
        String label=ScooterCommands.find(e.feature).label;
        card.addView(ui.text("Unfinished test · "+label,16,true));
        if(!e.outcomes.isEmpty()){List<String> texts=new java.util.ArrayList<>();for(String o:e.outcomes)texts.add(GuidedTest.Outcome.describe(o));card.addView(muted(join(texts)));}
        card.addView(ui.text("Its scooter was forgotten, so Volt can't check this setting or set it back. Check it on the scooter itself.",14,false));
        TextView dismiss=ui.secondaryButton("Dismiss",()->{ble.dismissTest(e.testId);refresh();});
        dismiss.setContentDescription("Dismiss the unfinished "+label+" test of a forgotten scooter");card.addView(dismiss);
    }
    private void addRestore(LinearLayout card,BleController ble,TestJournal.Entry e,CapabilityResolver.Decision d,String text){
        TextView b=ui.button(unbroken(text),()->{ble.restoreTest(e.testId);if(!ble.testing())host.toast(ble.message());refresh();});b.setEnabled(d.available());card.addView(b);
    }
    /** What the owner should see while the test value is set. */
    static String expectation(String feature,String value){
        switch(feature){
            case "display_units": return "the dashboard shows "+(value.contains("mph")?"mph":"km/h")+".";
            case "headlight": return "the tail light is "+value.toLowerCase(java.util.Locale.ROOT)+".";
            case "turn_sound": return "the turn signal sound is "+value.toLowerCase(java.util.Locale.ROOT)+".";
            case "auto_sensor": return "automatic lights are "+value.toLowerCase(java.util.Locale.ROOT)+".";
            default: return "the range estimate on the dashboard changes.";
        }
    }
    static String question(String feature,String value){
        switch(feature){
            case "display_units": return "Does the dashboard show "+(value.contains("mph")?"mph":"km/h")+"?";
            case "headlight": return "Is the tail light "+value.toLowerCase(java.util.Locale.ROOT)+"?";
            case "turn_sound": return "With a turn signal on, is the sound "+value.toLowerCase(java.util.Locale.ROOT)+"?";
            case "auto_sensor": return "Are automatic lights "+value.toLowerCase(java.util.Locale.ROOT)+"?";
            case "range_algorithm": return "Did the range estimate on the dashboard change?";
            default: return "Did you see it change?";
        }
    }
    /** Button labels: a word joiner keeps "km/h" on one line when large text wraps the label. */
    static String unbroken(String text){return text.replace("km/h","km/\u2060h");}
    private static String join(List<String> parts){StringBuilder b=new StringBuilder();for(String p:parts)b.append(b.length()==0?"":" · ").append(p);return b.toString();}

    // ---- Running --------------------------------------------------------------------------

    private void running(){
        LinearLayout card=ui.card();root.addView(card);
        progressText=ui.text("",15,false);progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(progressText);
        LinearLayout bar=new LinearLayout(host.activity());bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(ui.shape(ui.raised,100,0));bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        progressFill=new View(host.activity());progressFill.setBackground(ui.shape(ui.accent,100,0));
        progressRest=new View(host.activity());
        bar.addView(progressFill,new LinearLayout.LayoutParams(0,-1,0));bar.addView(progressRest,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,ui.dp(6));bp.topMargin=ui.dp(12);bp.bottomMargin=ui.dp(4);card.addView(bar,bp);
        root.addView(ui.secondaryButton("Stop",()->{BleController ble=ble();if(ble!=null)ble.stopDiscovery();refresh();}));
    }
    private void progress(DiscoveryCoordinator check){
        if(progressText==null)return;
        int total=DiscoveryCoordinator.ORDER.length,done=check.resolved();
        String text=done>=total?"Listening for scooter reports…":"Reading "+Math.min(total,done+1)+" of "+total+"…";
        if(!text.contentEquals(progressText.getText()))progressText.setText(text);
        float fraction=Math.min(1f,done/(float)total);
        // Steps in place; no animation, so reduced motion needs nothing extra.
        ((LinearLayout.LayoutParams)progressFill.getLayoutParams()).weight=fraction;((LinearLayout.LayoutParams)progressRest.getLayoutParams()).weight=1f-fraction;
        progressFill.requestLayout();
    }

    // ---- Results --------------------------------------------------------------------------

    private void results(BleController ble,DiscoveryCoordinator check){
        if(check.interrupted())root.addView(note("The check was interrupted. Results so far:"));
        LinearLayout reads=group("Reads");
        if(reads!=null){
            for(DiscoveryCoordinator.Read r:check.reads())row(reads,CompatibilityReport.hex(r.cmd)+" · "+readName(r.cmd),readValue(r),r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED?ui.ok:ui.muted);
            StringBuilder seen=new StringBuilder();
            for(DiscoveryCoordinator.Read p:check.passive())if(p.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED)seen.append(seen.length()==0?"":", ").append(CompatibilityReport.hex(p.cmd));
            row(reads,"Reports from the scooter",seen.length()==0?"None seen (they may only arrive while riding)":seen.toString(),ui.muted);
        }
        LinearLayout fields=group("Fields");
        if(fields!=null){
            StatusFields s=ble.discoveryStatus();ModelRegistry.Layer layer=ModelRegistry.resolve(ble.descriptor()).layer;
            if(!s.accepted())row(fields,"Status","Not received",ui.muted);
            else for(int o=0;o<=CompatibilityReport.LAST_OFFSET;o++){
                if(!CompatibilityReport.valueOffset(o)&&!CompatibilityReport.presenceOffset(o))continue;
                StatusFields.Field f=s.field(o);
                String value=!f.present?"Absent":CompatibilityReport.valueOffset(o)?Integer.toString(f.value):"Present";
                row(fields,CompatibilityReport.fieldLabel(o,layer),value,f.present?ui.text:ui.muted);
            }
            if(s.accepted())row(fields,"Read",age(s),ui.muted);
        }
        LinearLayout controls=group("Controls");
        if(controls!=null){
            List<CapabilityResolver.Decision> ds=ble.discoveryDecisions();
            for(CapabilityResolver.Decision d:ds){
                String label=d.feature.equals("clock_sync")?"Clock":ScooterCommands.find(d.feature).label;
                String value=(d.available()?"Available":d.reasonText())+" · "+evidence(d.evidence);
                row(controls,label,value,d.available()?ui.ok:ui.muted);
            }
        }
    }
    private String age(StatusFields s){
        long ms=android.os.SystemClock.elapsedRealtime()-s.observedMs();
        if(ms<0)return "Just now";long minutes=ms/60000;
        return minutes<1?"Under a minute ago":minutes==1?"1 minute ago":minutes+" minutes ago";
    }
    static String readName(int cmd){
        switch(cmd){
            case 0x74: return "Serial check"; case 0x70: return "Status"; case 0x73: return "Firmware"; case 0x72: return "Battery";
            case 0x76: return "Ride history"; case 0x7b: return "Fault history"; case 0x71: return "Driving (probe)";
            case 0x75: return "Battery ID (probe)"; case 0x79: return "Battery signs (probe)"; default: return "Read";
        }
    }
    static String readValue(DiscoveryCoordinator.Read r){
        String late=r.late?" (late)":"";
        switch(r.outcome){
            case READ_SUPPORTED: return "✓ "+r.length+" bytes"+late;
            case REJECTED: return "Rejected "+r.status;
            case MALFORMED: return "Too short ("+r.length+" bytes)";
            case EMPTY_REPLY: return "Replied with no data";
            case NO_RESPONSE: return "No response";
            case INTERRUPTED: return "Interrupted";
            default: return "Waiting";
        }
    }
    static String evidence(CapabilityResolver.Evidence e){
        switch(e){
            case VOLT_VERIFIED: return "Volt-verified";
            case VOLT_LAB: return "Lab";
            case OEM_MAPPED: return "OEM-mapped";
            case SHARED_CONTRACT: return "Shared";
            case LOCAL_TEST: return "Tested here";
            default: return "No evidence";
        }
    }

    // ---- Preview --------------------------------------------------------------------------

    private void preview(BleController ble){
        if(report==null){preview=false;return;}
        TextView heading=ui.sectionLabel("Report preview");heading.setText("Report preview");heading.setAllCaps(true);root.addView(heading);
        LinearLayout card=ui.card();root.addView(card);
        card.addView(muted("Only what is shown here is shared. No serial, MAC, owner key, PIN, names or routes."));
        TextView summary=ui.text(showJson?report.json():report.summary(),12,false);summary.setTypeface(Typeface.MONOSPACE);summary.setTextIsSelectable(true);
        summary.setPadding(0,ui.dp(10),0,0);card.addView(summary);
        TextView toggle=ui.text(showJson?"Show summary":"Show JSON",13.5f,false);toggle.setTypeface(ui.medium());toggle.setTextColor(ui.accent);
        toggle.setGravity(Gravity.CENTER);toggle.setMinHeight(ui.dp(48));toggle.setFocusable(true);toggle.setContentDescription(showJson?"Show report summary":"Show report JSON");
        toggle.setOnClickListener(v->{showJson=!showJson;refresh();});root.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
        final CompatibilityReport shared=report;
        root.addView(ui.button("Share",()->share(shared)));
        // Android confirms neither a share nor an email, so those mark the tries at the handoff; a saved file only once written.
        root.addView(ui.secondaryButton("Save file",()->host.saveReport(shared)));
        root.addView(ui.secondaryButton("Close preview",()->{preview=false;report=null;refresh();}));
    }
    private void emailNow(BleController ble){
        CompatibilityReport r=ble.compatibilityReport(AppVersion.name(host.activity()));
        if(r==null){host.toast("No finished check yet");return;}
        host.emailReport(r);
    }
    /** Android share sheet, only from the owner's tap in the preview. */
    private void share(CompatibilityReport r){
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"Volt compatibility report");
        send.putExtra(Intent.EXTRA_TEXT,r.summary()+"\n"+r.json());
        try{host.activity().startActivity(Intent.createChooser(send,"Share compatibility report"));BleController ble=ble();if(ble!=null)ble.markReported(r);}
        catch(Exception e){host.toast("No app can share this report");}
    }

    // ---- Small views ----------------------------------------------------------------------

    /** Collapsible group: a heading that toggles it; null when collapsed. */
    private LinearLayout group(String name){
        boolean open=expanded.contains(name);
        LinearLayout header=new LinearLayout(host.activity());header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(48));
        TextView label=ui.sectionLabel(name);label.setText(name);label.setAllCaps(true);
        header.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView state=ui.text(open?"Hide":"Show",12.5f,false);state.setTextColor(ui.accent);state.setPadding(ui.dp(8),ui.dp(14),ui.dp(6),ui.dp(6));
        state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);header.addView(state);
        header.setFocusable(true);header.setClickable(true);
        header.setContentDescription(name+(open?", expanded":", collapsed"));
        header.setOnClickListener(v->{if(!expanded.remove(name))expanded.add(name);refresh();});
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        if(!open)return null;
        LinearLayout g=ui.groupCard();root.addView(g);return g;
    }
    private void row(LinearLayout group,String title,String value,int valueColor){
        if(group.getChildCount()>0)ui.divider(group);
        LinearLayout line=new LinearLayout(host.activity());line.setOrientation(LinearLayout.HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(44));line.setPadding(ui.dp(2),ui.dp(8),0,ui.dp(8));
        TextView t=ui.text(title,14,false);line.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView v=ui.text(value,13,false);v.setTextColor(valueColor);v.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);v.setMaxWidth(ui.dp(170));
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=ui.dp(10);line.addView(v,vp);
        line.setFocusable(true);line.setContentDescription(title+": "+value);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void line(LinearLayout card,String title,String value){
        TextView t=ui.text(title+" · "+value,13,false);t.setTextColor(ui.muted);t.setPadding(0,ui.dp(6),0,0);card.addView(t);
    }
    private TextView muted(String text){TextView t=ui.text(text,12.5f,false);t.setTextColor(ui.muted);t.setPadding(0,ui.dp(6),0,ui.dp(4));return t;}
    private TextView note(String text){TextView t=ui.caption(text);t.setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(6));return t;}
}
