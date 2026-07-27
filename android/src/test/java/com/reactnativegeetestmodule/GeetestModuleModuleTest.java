package com.reactnativegeetestmodule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import com.facebook.react.bridge.ReactApplicationContext;
import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers the null dereferences in {@link GeetestModuleModule}: the guard in
 * tearDown(), the activity it used to reach the UI thread through, and the SDK
 * handles handleRegisteredGeeTestCaptcha() touches.
 *
 * All three share a shape — a reference dereferenced on a path where it can
 * legitimately be null — and all three crashed in ordinary use before the
 * guards were added.
 */
@RunWith(RobolectricTestRunner.class)
public class GeetestModuleModuleTest {

    private ActivityController<Activity> activityController;
    private ReactApplicationContext reactContext;
    private GeetestModuleModule module;

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class).setup();

        reactContext = new ReactApplicationContext(RuntimeEnvironment.getApplication());
        // getCurrentActivity() is final, so the activity is injected through the
        // lifecycle API the bridge itself uses rather than by stubbing.
        reactContext.onHostResume(activityController.get());

        module = new GeetestModuleModule(reactContext);
    }

    /**
     * The regression test: this is the exact call that used to crash.
     */
    @Test
    public void tearDownWithoutSetUpDoesNotThrow() {
        module.tearDown();
        drainMainLooper();

        assertNull(getField("gt3GeetestUtils"));
        assertNull(getField("gt3ConfigBean"));
    }

    @Test
    public void tearDownTwiceDoesNotThrow() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        module.tearDown();
        drainMainLooper();
        // The second call is the one that used to crash: the first nulled the field.
        module.tearDown();
        drainMainLooper();

        verify(utils, times(1)).destory();
        assertNull(getField("gt3GeetestUtils"));
    }

    /**
     * The guard must not have turned tearDown() into a no-op: when the SDK handle
     * is present it still has to be destroyed, or the captcha dialog leaks.
     */
    @Test
    public void tearDownDestroysSdkWhenPresent() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        module.tearDown();
        drainMainLooper();

        verify(utils).destory();
        assertNull(getField("gt3GeetestUtils"));
        assertNull(getField("gt3ConfigBean"));
    }

    /**
     * Teardown has to survive the activity going away — backgrounding the app
     * mid-captcha, or a teardown racing a screen unmount. tearDown() used to reach
     * the UI thread through getCurrentActivity(), which is null on exactly that
     * path, so the crash happened before the null guard inside the runnable could
     * help.
     *
     * The SDK still has to be destroyed here: skipping it leaks the captcha dialog,
     * which is why the fix hops through UiThreadUtil instead of guarding the
     * activity.
     */
    @Test
    public void tearDownAfterActivityDetachedStillDestroysSdk() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        // The bridge's own way of saying "no activity is current" — same lifecycle
        // API used to attach one above.
        reactContext.onHostDestroy();
        assertNull("precondition: no activity attached", reactContext.getCurrentActivity());

        module.tearDown();
        drainMainLooper();

        verify(utils).destory();
        assertNull(getField("gt3GeetestUtils"));
        assertNull(getField("gt3ConfigBean"));
    }

    /**
     * The captcha can be triggered before setUp() has run, leaving both SDK handles
     * null when the runnable reaches the UI thread.
     */
    @Test
    public void captchaBeforeSetUpDoesNotCrashTheUiThread() {
        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("{\"success\":1}"));

        drainMainLooper();
    }

    /**
     * The same crash from the other direction: tearDown() clears the handles, and a
     * captcha already in flight lands afterwards.
     */
    @Test
    public void captchaAfterTearDownDoesNotCrashTheUiThread() {
        setField("gt3GeetestUtils", mock(GT3GeetestUtils.class));
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        module.tearDown();
        drainMainLooper();

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("{\"success\":1}"));

        drainMainLooper();
    }

    /**
     * The guard must not have turned the captcha into a no-op: with the SDK set up,
     * the flow still has to start and receive the parsed payload.
     */
    @Test
    public void captchaStartsTheFlowWhenSetUp() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        GT3ConfigBean configBean = mock(GT3ConfigBean.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", configBean);

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("{\"success\":1}"));

        drainMainLooper();

        ArgumentCaptor<JSONObject> json = ArgumentCaptor.forClass(JSONObject.class);
        InOrder order = inOrder(utils, configBean);
        order.verify(utils).startCustomFlow();
        order.verify(configBean).setApi1Json(json.capture());
        order.verify(utils).getGeetest();
        assertEquals(1, json.getValue().optInt("success"));
    }

    /**
     * Parsing moved ahead of the thread hop so the catch around it is meaningful.
     * Malformed input must still be swallowed there, without reaching the SDK.
     */
    @Test
    public void captchaWithMalformedJsonDoesNotTouchTheSdk() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        GT3ConfigBean configBean = mock(GT3ConfigBean.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", configBean);

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("not json"));

        drainMainLooper();

        verifyNoInteractions(utils, configBean);
    }

    @Test
    public void captchaWithEmptyParamsDoesNotTouchTheSdk() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        GT3ConfigBean configBean = mock(GT3ConfigBean.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", configBean);

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha(""));

        drainMainLooper();

        verifyNoInteractions(utils, configBean);
    }

    @Test
    public void getNameIsStableAcrossTheBridge() {
        // The JS side resolves NativeModules.GeetestModule by this exact string.
        assertEquals("GeetestModule", module.getName());
    }

    /**
     * Drives a @ReactMethod the way the bridge does: off the main thread.
     *
     * This is load bearing, not ceremony. Robolectric runs tests on the main
     * thread, so calling handleRegisteredGeeTestCaptcha() directly would make the
     * hop to the UI thread execute its body inline, inside the caller's frame —
     * where the old try/catch swallowed the very NullPointerException under test.
     * A test written that way passes against unfixed code. Posting from another
     * thread keeps the body on the looper, so {@link #drainMainLooper()} is what
     * surfaces the crash.
     */
    private void callFromBridgeThread(Runnable body) {
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread bridgeThread = new Thread(body, "NativeModulesThread");
        bridgeThread.setUncaughtExceptionHandler((thread, throwable) -> escaped.set(throwable));
        bridgeThread.start();
        try {
            bridgeThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for the bridge thread", e);
        }
        if (escaped.get() != null) {
            throw new AssertionError("Threw on the bridge thread", escaped.get());
        }
    }

    /**
     * Both @ReactMethods hand their work to UiThreadUtil.runOnUiThread, which always
     * posts to the main looper — even when the caller is already on it. Nothing in
     * either runnable has run until this drains the queue, so every assertion about
     * their effects, and every crash inside them, depends on this call.
     */
    private void drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void setField(String name, Object value) {
        try {
            Field field = GeetestModuleModule.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(module, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not set " + name, e);
        }
    }

    private Object getField(String name) {
        try {
            Field field = GeetestModuleModule.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(module);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read " + name, e);
        }
    }
}
