package tw.nekomimi.nekogram.helpers;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.HashMap;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;
import io.sentry.protocol.User;
import tw.nekomimi.nekogram.Extra;

public class AnalyticsHelper {
    private static SharedPreferences preferences;

    private static FirebaseAnalytics firebaseAnalytics;

    public static boolean sendBugReport = true;
    public static boolean analyticsDisabled = false;
    public static String userId = null;

    public static void start(Application application) {
        try {
            preferences = application.getSharedPreferences("nekoanalytics", Application.MODE_PRIVATE);
        } catch (Exception ignored) {}
        analyticsDisabled = true;
        sendBugReport = false;
        FileLog.d("Analytics: disabled for performance and privacy");
    }

    private static String generateUserID() {
        return Utilities.generateRandomString(32);
    }

    public static void trackFragmentLifecycle(String lifecycle, BaseFragment fragment) {
        if (analyticsDisabled || fragment == null) return;
    }

    private static String getFragmentName(BaseFragment fragment) {
        var canonicalName = fragment.getClass().getCanonicalName();
        return canonicalName != null ? canonicalName : fragment.getClass().getSimpleName();
    }

    public static void trackEvent(String event, HashMap<String, String> map) {
        if (analyticsDisabled) return;
    }

    public static boolean isSettingsAvailable() {
        return !Extra.FORCE_ANALYTICS;
    }

    public static void setAnalyticsDisabled() {
        AnalyticsHelper.analyticsDisabled = true;
        if (preferences != null) {
            preferences.edit().putBoolean("analyticsDisabled", true).apply();
        }
    }

    public static void toggleSendBugReport() {
        AnalyticsHelper.sendBugReport = !AnalyticsHelper.sendBugReport;
        if (preferences != null) {
            preferences.edit().putBoolean("sendBugReport", sendBugReport).apply();
        }
    }
}
