package dev.tilesmile;

import android.app.Application;
import android.os.Build;
import android.util.Log;
import com.onyx.android.sdk.rx.RxBaseAction;
import com.onyx.android.sdk.utils.ResManager;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class PenApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // BOOX's current official sample requires this for its firmware API bridge.
        // Limit exemptions to the vendor namespaces used by that bridge.
        if (Build.VERSION.SDK_INT >= 30) {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/onyx/", "Lcom/onyx/",
                "Landroid/view/View;->postInvalidateWithUpdateMode",
                "Landroid/view/View;->invalidateWithUpdateMode");
        }
        try {
            ResManager.init(this);
            RxBaseAction.init(this);
        } catch (RuntimeException | LinkageError e) {
            Log.e("TileSmile", "BOOX initialization failed; Android control remains available", e);
        }
    }
}
