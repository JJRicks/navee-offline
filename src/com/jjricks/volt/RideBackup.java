package com.jjricks.volt;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Android storage locations for ride-only transfers, separate from the full-app backup format. */
final class RideBackup {
    static RideArchive.Storage storage(Context context) {
        Context app=context.getApplicationContext();
        return new RideArchive.Storage() {
            public File files(){return app.getFilesDir();}
            public File work(){return new File(app.getNoBackupFilesDir(),"ride-transfer");}
        };
    }
    static boolean needsRecovery(Context context){return RideArchive.needsRecovery(storage(context));}
    static void recover(Context context)throws IOException{RideArchive.recover(storage(context));}
}
