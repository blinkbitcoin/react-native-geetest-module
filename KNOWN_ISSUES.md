# Known issues

Open defects in the Android module. Each entry has a reproduction, the reason it
happens, and a fix that was written and validated but not merged — the fixes are
reproduced in full here so they survive independently of any branch or commit.

All three are in
[`GeetestModuleModule.java`](android/src/main/java/com/reactnativegeetestmodule/GeetestModuleModule.java)
and share a shape: a reference is dereferenced on a path where it can legitimately
be null. The `tearDown()` guard added in `220ea7f` fixed one instance of it.

---

## 1. `tearDown()` crashes when no activity is attached

**Symptom**

```
java.lang.NullPointerException: Cannot invoke "android.app.Activity.runOnUiThread(java.lang.Runnable)"
because the return value of "GeetestModuleModule.getCurrentActivity()" is null
```

**Reproduction** — tear down while no activity is current: background the app
mid-captcha, or let a teardown race a screen unmount.

**Why** — `tearDown()` reaches the UI thread through `getCurrentActivity()`. The
existing null guard sits *inside* the runnable, one line too late to help; the
activity reference is dereferenced before the runnable is ever posted.

**Fix** — hop through the bridge's own helper, which posts to the main looper and
needs no activity. Preferred over null-guarding the activity: with a guard,
teardown after a detach would skip `destory()` entirely and leak the captcha
dialog.

```java
import com.facebook.react.bridge.UiThreadUtil;

@ReactMethod
public void tearDown() {
    UiThreadUtil.runOnUiThread(() -> {
        if (gt3GeetestUtils != null) {
            gt3GeetestUtils.destory();
        }
        gt3GeetestUtils = null;
        gt3ConfigBean = null;
    });
}
```

---

## 2. `handleRegisteredGeeTestCaptcha()` crashes on a null SDK handle

**Symptom**

```
java.lang.NullPointerException: Cannot invoke "com.geetest.sdk.GT3GeetestUtils.startCustomFlow()"
because "this.gt3GeetestUtils" is null
```

**Reproduction** — trigger the captcha before `setUp()`, or after `tearDown()` has
cleared the handles.

**Why** — this is the subtle one, and the reason it looks safe. The call sits
inside a `try`/`catch (Exception)`, but that `catch` cannot see it. `@ReactMethod`
runs on the NativeModules thread, never the UI thread, so `runOnUiThread` *posts*
the runnable rather than running it inline. The runnable executes on the UI thread
long after the `try` frame has returned, and the NPE goes uncaught.

Do not "simplify" this back by re-wrapping the hop in a `try`/`catch` — that is
the broken version.

**Fix** — parse before the hop, where the `catch` is meaningful, and null-check the
handles inside the runnable, where they are actually used.

```java
@ReactMethod
public void handleRegisteredGeeTestCaptcha(String params) {
    if (TextUtils.isEmpty(params)) {
        return;
    }

    final JSONObject jsonObject;
    try {
        jsonObject = new JSONObject(params);
    } catch (JSONException e) {
        e.printStackTrace();
        return;
    }

    UiThreadUtil.runOnUiThread(() -> {
        if (gt3GeetestUtils == null || gt3ConfigBean == null) {
            return;
        }
        gt3GeetestUtils.startCustomFlow();
        gt3ConfigBean.setApi1Json(jsonObject);
        gt3GeetestUtils.getGeetest();
    });
}
```

---

## 3. `setUp()` dereferences a possibly-null activity

`setUp()` calls `new GT3GeetestUtils(getCurrentActivity())` unguarded.

Unlike the two above, this has **no mechanical fix**. The SDK genuinely needs a
real activity to build the captcha dialog, so a null check would produce a module
that reports success but can never show a captcha — trading a loud crash for a
silent dead end. Deciding between failing loudly, deferring initialisation until
an activity attaches, or surfacing an error to JS is a product decision, not a
null check.

---

## Testing these

The existing suite
([`GeetestModuleModuleTest.java`](android/src/test/java/com/reactnativegeetestmodule/GeetestModuleModuleTest.java))
covers only the `tearDown()` null guard. Run it with `./gradlew test` from
`android/`, using JDK 17.

Two things to know before adding tests for issues 1 and 2.

**Robolectric runs tests on the main thread.** Calling a `@ReactMethod` directly
therefore makes `runOnUiThread` execute its body *inline*, inside the caller's
frame — where the `try`/`catch` in issue 2 swallows the very crash under test. A
test written that way passes against unfixed code and proves nothing. Drive the
module from a separate thread instead, then drain the looper:

```java
Thread t = new Thread(() -> module.handleRegisteredGeeTestCaptcha("{\"success\":1}"));
t.start();
t.join();
shadowOf(Looper.getMainLooper()).idle();   // the NPE surfaces here
```

**Always confirm a new test fails without its fix.** Every guard here is one that
a test can accidentally pass around.
