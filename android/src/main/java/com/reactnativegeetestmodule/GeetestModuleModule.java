package com.reactnativegeetestmodule;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;

import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.GT3Listener;

import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;

@ReactModule(name = GeetestModuleModule.NAME)
public class GeetestModuleModule extends ReactContextBaseJavaModule {
    public static final String NAME = "GeetestModule";

    private GT3GeetestUtils gt3GeetestUtils;
    private GT3ConfigBean gt3ConfigBean;

    public GeetestModuleModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void setUp() {
        // TODO: getCurrentActivity() may be null here, and unlike the null paths in
        // tearDown() and handleRegisteredGeeTestCaptcha() this one has no mechanical
        // fix. The SDK needs a real activity to build the captcha dialog, so a null
        // check would produce a module that reports success but can never show a
        // captcha — a silent dead end traded for a loud crash. Choosing between
        // failing loudly, deferring initialisation until an activity attaches, and
        // surfacing an error to JS is a product decision, not a null check.
        gt3GeetestUtils = new GT3GeetestUtils(getCurrentActivity());

        // Configure the bean file
        gt3ConfigBean = new GT3ConfigBean();
        // Set how captcha is presented，1：bind，2：unbind
        gt3ConfigBean.setPattern(1);
        // The default is false
        gt3ConfigBean.setCanceledOnTouchOutside(false);
        // Set language. Use system default language if null
        gt3ConfigBean.setLang(null);
        // Set the timeout for loading webview static files
        gt3ConfigBean.setTimeout(10000);
        // Set the timeout for webview request after user finishing the CAPTCHA verification. The default is 10,000
        gt3ConfigBean.setWebviewTimeout(10000);
        // Set callback listener
        gt3ConfigBean.setListener(new GT3Listener() {
            /**
             * CAPTCHA loading is completed
             * @param duration Loading duration and version info，in JSON format
             */
            @Override
            public void onDialogReady(String duration) {}

            /**
             * Verification result callback
             * @param code 1:success, 0:fail
             */
            @Override
            public void onReceiveCaptchaCode(int code) {}

            /**
             * api2 custom call
             * @param result
             */
            @Override
            public void onDialogResult(String result) {
                gt3GeetestUtils.dismissGeetestDialog();
                WritableMap params = Arguments.createMap();
                params.putString("result", result);
                sendEvent(getReactApplicationContext(), "GT3-->onDialogResult-->", params);
            }

            /**
             * Statistic info.
             * @param result
             */
            @Override
            public void onStatistics(String result) {}

            /**
             * Close the CAPTCHA
             * @param num 1 Click the close button to close the CAPTCHA, 2 Click anyplace on screen to close the CAPTCHA, 3 Click return button the close
             */
            @Override
            public void onClosed(int num) {
                WritableMap params = Arguments.createMap();
                params.putInt("closed", num);
                sendEvent(getReactApplicationContext(), "GT3-->onClosed-->", params);
            }

            /**
             * Verification succeeds
             * @param result
             */
            @Override
            public void onSuccess(String result) {}

            /**
             * Verification fails
             * @param errorBean Version info, error code & description, etc.
             */
            @Override
            public void onFailed(GT3ErrorBean errorBean) {
                WritableMap params = Arguments.createMap();
                params.putString("error", errorBean.toString());
                sendEvent(getReactApplicationContext(), "GT3-->onFailed-->", params);
            }

            /**
             * api1 custom call
             */
            @Override
            public void onButtonClick() {}
        });
        gt3GeetestUtils.init(gt3ConfigBean);
    }

    @ReactMethod
    public void tearDown() {
        // Hop through the bridge helper rather than getCurrentActivity(): teardown
        // can legitimately run with no activity attached (backgrounded mid-captcha,
        // or racing a screen unmount), and the SDK still has to be destroyed then or
        // the captcha dialog leaks.
        UiThreadUtil.runOnUiThread(() -> {
            if (gt3GeetestUtils != null) {
                gt3GeetestUtils.destory();
            }
            gt3GeetestUtils = null;
            gt3ConfigBean = null;
        });
    }

    @ReactMethod
    public void handleRegisteredGeeTestCaptcha(String params) {
        if (TextUtils.isEmpty(params)) {
            return;
        }

        // Parse before the hop. @ReactMethod runs on the NativeModules thread, so
        // runOnUiThread posts the runnable rather than running it inline: a catch
        // wrapped around the hop returns long before the body executes and cannot
        // see anything it throws. Do not re-wrap this — that is the broken version.
        final JSONObject jsonObject;
        try {
            jsonObject = new JSONObject(params);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        UiThreadUtil.runOnUiThread(() -> {
            // Reachable before setUp() and after tearDown() cleared the handles.
            if (gt3GeetestUtils == null || gt3ConfigBean == null) {
                return;
            }
            gt3GeetestUtils.startCustomFlow();
            gt3ConfigBean.setApi1Json(jsonObject);
            gt3GeetestUtils.getGeetest();
        });
    }


    private void sendEvent(ReactContext reactContext,
                           String eventName,
                           @Nullable WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Remove upstream listeners, stop unnecessary background tasks
    }
}
