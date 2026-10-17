package local.navee.offlinelock;

/** Presence is separate from value: a short 0x70 never reads as zero. */
public final class StatusFieldsTest {
    private static int assertions;
    public static void main(String[] args) {
        presence(); acceptance(); values(); freshness();
        System.out.println("StatusFields tests passed: "+assertions+" assertions");
    }

    private static void presence() {
        StatusFields s=StatusFields.of(notification(0x70,0,new byte[13]),1000);
        check(s.accepted()&&s.length()==13,"13 OEM data bytes");
        check(s.field(12).present&&!s.field(13).present&&s.u8(12)==0&&s.u8(13)==null,"Offset 12 present, 13 absent");
        check(s.has(9,4)&&!s.has(10,4)&&!s.has(-1,1)&&!s.has(0,0),"Range presence");
        check(!s.field(-1).present&&s.field(13).value==0,"Absent field value is meaningless, not data");
    }

    private static void acceptance() {
        StatusFields rejected=StatusFields.of(notification(0x70,1,new byte[44]),1000);
        check(!rejected.accepted()&&rejected.length()==0&&rejected.u8(2)==null&&!rejected.field(0).present&&rejected.frame()==null,"Status 1 is not accepted");
        check(!StatusFields.of(notification(0x72,0,new byte[44]),1000).accepted(),"Wrong command is not accepted");
        check(!StatusFields.of(null,1000).accepted()&&!StatusFields.NONE.accepted()&&StatusFields.NONE.u8(0)==null,"No frame");
    }

    private static void values() {
        byte[] data=new byte[44];
        StatusFields shortFrame=StatusFields.of(notification(0x70,0,java.util.Arrays.copyOf(data,25)),5);
        check(shortFrame.u8(25)==null&&!shortFrame.field(25).present,"Offset 25 absent is not 0");
        StatusFields zero=StatusFields.of(notification(0x70,0,java.util.Arrays.copyOf(data,26)),5);
        check(Integer.valueOf(0).equals(zero.u8(25))&&zero.field(25).present,"Present 0 is 0");
        data[27]=0x01;data[28]=0x02;data[29]=0x03;data[30]=0x04;data[2]=(byte)0xff;
        Protocol.Frame frame=notification(0x70,0,data);StatusFields s=StatusFields.of(frame,5);
        check(Long.valueOf(0x01020304L).equals(s.be32(27))&&s.be32(27).equals(ScooterCommands.timeReadback(frame)),"BE32 matches ScooterCommands.timeReadback");
        check(s.u8(2)==255&&s.be32(41)==null&&s.be32(40)!=null,"Unsigned bytes and BE32 bounds");
        check(s.frame()==frame,"UT5 variants read the same frame");
    }

    private static void freshness() {
        StatusFields s=StatusFields.of(notification(0x70,0,new byte[44]),10000);
        check(s.observedMs()==10000&&s.ageMs(17000)==7000,"Age");
        check(s.fresh(17000,7000)&&!s.fresh(17001,7000),"7000 ms is fresh, 7001 is stale");
        check(!s.fresh(9999,7000),"A status from the future is not fresh");
        check(!StatusFields.of(notification(0x70,0,new byte[44]),0).fresh(0,7000),"No observation time is never fresh");
        check(!StatusFields.of(notification(0x70,1,new byte[44]),10000).fresh(10000,7000),"Rejected status is never fresh");
    }

    static Protocol.Frame notification(int cmd,int status,byte[] payload) {
        byte[] d=new byte[payload.length+1];d[0]=(byte)status;System.arraycopy(payload,0,d,1,payload.length);
        byte[] p=new byte[d.length+8];p[0]=0x55;p[1]=(byte)0xaa;p[3]=(byte)cmd;p[4]=(byte)d.length;
        System.arraycopy(d,0,p,5,d.length);int sum=0;for(int i=0;i<p.length-3;i++)sum+=p[i]&255;
        p[p.length-3]=(byte)sum;p[p.length-2]=(byte)0xfe;p[p.length-1]=(byte)0xfd;return Protocol.parse(p);
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
