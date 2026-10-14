package local.navee.offlinelock;

public final class RangeFollowPolicyTest {
    private static int count;
    private static final int[] DS = {0,2,3}, EDS = {4,2,3};
    public static void main(String[] args) {
        eq(3, RangeFollowPolicy.target(2538, 3d, DS));
        eq(2, RangeFollowPolicy.target(2538, 2d, DS));
        eq(4, RangeFollowPolicy.target(2538, 2d, EDS));
        eq(2, RangeFollowPolicy.target(2538, 11d, DS));
        eq(4, RangeFollowPolicy.target(2538, 11d, EDS));
        eq(null, RangeFollowPolicy.target(2538, 3d, new int[]{0}));
        eq(null, RangeFollowPolicy.target(0, 3d, DS));
        eq(null, RangeFollowPolicy.target(2538, 1d, DS));
        eq(null, RangeFollowPolicy.target(2538, Double.NaN, DS));
        eq(null, RangeFollowPolicy.target(2538, Double.POSITIVE_INFINITY, DS));
        eq(null, RangeFollowPolicy.target(2538, null, DS));
        eq(null, RangeFollowPolicy.target(2538, 3d, null));
        RangeFollowPolicy p = new RangeFollowPolicy();
        eq(null, p.next("owner",2538,3d,DS,2,true,true,true,true,true)); // busy
        eq(null, p.next("owner",2538,3d,DS,2,true,true,false,false,true)); // stale
        eq(null, p.next("owner",2538,3d,DS,2,true,false,false,true,true)); // not ready
        eq(3, next(p, "owner", 3, 2));
        eq(null, next(p, "owner", 3, 2)); // no retry loop after rejected/unconfirmed command
        eq(null, next(p, "owner", 3, 3)); // matched readback
        eq(2, next(p, "owner", 2, 3)); // switch to Eco
        eq(3, next(p, "owner", 3, 2)); // back to Sport
        eq(3, next(p, "another scooter", 3, 2));
        for (int reconnect = 0; reconnect < 5; reconnect++) {
            eq(null, p.next("owner",2538,3d,DS,2,false,false,false,true,true));
            eq(null, next(p, "owner", 3, 2)); // unconfirmed command must not repeat on reconnect
        }
        eq(null, p.next("owner",2538,null,DS,2,true,true,false,true,true));
        eq(null, next(p, "owner", 3, 2)); // transient missing mode must not erase attempt
        eq(null, next(p, "another scooter", 3, 2)); // returning to a profile retains its guard
        eq(null, p.next("owner",2538,3d,DS,2,true,true,false,true,false)); // fixed choice
        eq(3, next(p, "owner", 3, 2)); // explicitly enable follow again
        eq(null, p.next("",2538,3d,DS,2,true,true,false,true,true));
        eq(null, p.next("owner",2538,3d,DS,null,true,true,false,true,true));
        p.reset(); eq(3, next(p,"owner",3,2));
        System.out.println("RangeFollowPolicy tests passed: " + count + " assertions");
    }
    private static Integer next(RangeFollowPolicy p, String owner, double mode, int current) {
        return p.next(owner,2538,mode,DS,current,true,true,false,true,true);
    }
    private static void eq(Integer a, Integer b) { count++; if (a==null ? b!=null : !a.equals(b)) throw new AssertionError(a+" != "+b); }
}
