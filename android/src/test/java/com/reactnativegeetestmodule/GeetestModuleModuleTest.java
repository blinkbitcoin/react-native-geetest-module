package com.reactnativegeetestmodule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import com.facebook.react.bridge.JavaOnlyMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.GT3Listener;

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
 * tearDown(), the activity it used to reach the UI thread through, the SDK handles
 * handleRegisteredGeeTestCaptcha() touches, and the one onDialogResult() touches from
 * the SDK callback side. Where a guard aborts the flow, the tests also assert the
 * onFailed event that has to reach JS in its place.
 *
 * They share a shape — a reference dereferenced on a path where it can legitimately
 * be null — and each crashed in ordinary use before the guards were added.
 *
 * Two traps to know about before adding tests here.
 *
 * Robolectric runs tests on the main thread, so calling a {@code @ReactMethod}
 * directly from a test makes an {@code Activity.runOnUiThread} hop execute its body
 * inline, inside the caller's frame. That is not how the bridge invokes it, and it
 * hides crashes: the try/catch that handleRegisteredGeeTestCaptcha() used to wrap
 * around its hop swallowed the exact NullPointerException under test, so a direct
 * call passed against the unfixed module. That was confirmed by trying it, not
 * assumed. {@link #callFromBridgeThread(Runnable)} exists for this reason — drive
 * the module from another thread, then drain.
 *
 * {@code UiThreadUtil.runOnUiThread} always posts, even when the caller is already
 * on the UI thread, unlike {@code Activity.runOnUiThread}. Nothing inside either
 * runnable has happened until {@link #drainMainLooper()}, on any thread, in any
 * test here.
 *
 * And always confirm a new test fails without its fix. Every guard in this module
 * is one a test can accidentally pass around — 21974ae removed one that could not
 * fail.
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

        // Spied to stub the two native seams off-device and verify which event reaches JS.
        module = spy(new GeetestModuleModule(reactContext));
        doAnswer(invocation -> new JavaOnlyMap()).when(module).createMap();
        doNothing().when(module).sendEvent(any(), anyString(), any());
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
     * captcha already in flight lands afterwards. It must emit onFailed, not return silently.
     */
    @Test
    public void captchaAfterTearDownEmitsFailureWithoutCrashing() {
        setField("gt3GeetestUtils", mock(GT3GeetestUtils.class));
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        module.tearDown();
        drainMainLooper();

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("{\"success\":1}"));

        drainMainLooper();

        verify(module).sendEvent(any(), eq("GT3-->onFailed-->"), any());
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
     * Malformed input must not reach the SDK, and must emit onFailed, not return silently.
     */
    @Test
    public void captchaWithMalformedJsonEmitsFailureWithoutTouchingTheSdk() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        GT3ConfigBean configBean = mock(GT3ConfigBean.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", configBean);

        callFromBridgeThread(() -> module.handleRegisteredGeeTestCaptcha("not json"));

        drainMainLooper();

        verifyNoInteractions(utils, configBean);
        verify(module).sendEvent(any(), eq("GT3-->onFailed-->"), any());
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

    /**
     * The PR-title crash from the callback side: onDialogResult() lands after tearDown()
     * nulled the handle. Reverting the guard throws here; the result must still reach JS.
     */
    @Test
    public void onDialogResultAfterTearDownForwardsResultWithoutCrashing() {
        setField("gt3GeetestUtils", mock(GT3GeetestUtils.class));
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        GT3Listener listener = module.createListener();

        module.tearDown();
        drainMainLooper();

        listener.onDialogResult("{\"result\":\"ok\"}");

        verify(module).sendEvent(any(), eq("GT3-->onDialogResult-->"), any());
    }

    /**
     * The guard must not have turned onDialogResult() into a no-op: with the SDK
     * present the dialog is still dismissed and the result still forwarded.
     */
    @Test
    public void onDialogResultDismissesDialogAndForwardsResultWhenSdkPresent() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);
        setField("gt3GeetestUtils", utils);
        setField("gt3ConfigBean", mock(GT3ConfigBean.class));

        module.createListener().onDialogResult("{\"result\":\"ok\"}");

        verify(utils).dismissGeetestDialog();
        verify(module).sendEvent(any(), eq("GT3-->onDialogResult-->"), any());
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
