package com.reactnativegeetestmodule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import com.facebook.react.bridge.ReactApplicationContext;
import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;

/**
 * Covers the null guard in {@link GeetestModuleModule#tearDown()}.
 *
 * Before the guard was added, tearDown() called gt3GeetestUtils.destory()
 * unconditionally, so tearing down without a preceding setUp() — or tearing
 * down twice — crashed with a NullPointerException.
 */
@RunWith(RobolectricTestRunner.class)
public class GeetestModuleModuleTest {

    private ActivityController<Activity> activityController;
    private GeetestModuleModule module;

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class).setup();

        ReactApplicationContext reactContext =
                new ReactApplicationContext(RuntimeEnvironment.getApplication());
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

    @Test
    public void tearDownWithoutSetUpNeverTouchesSdk() {
        GT3GeetestUtils utils = mock(GT3GeetestUtils.class);

        module.tearDown();
        drainMainLooper();

        verify(utils, never()).destory();
    }

    @Test
    public void getNameIsStableAcrossTheBridge() {
        // The JS side resolves NativeModules.GeetestModule by this exact string.
        assertEquals("GeetestModule", module.getName());
    }

    /**
     * tearDown() hands its work to runOnUiThread. Robolectric runs the test on the
     * main thread so the body executes inline, but draining keeps the test correct
     * if it is ever posted instead — a swallowed post would otherwise let a
     * regression pass silently.
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
