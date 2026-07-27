# Known issues

Open defects in the Android module. Each entry has a reproduction and the reason
it happens.

All are in
[`GeetestModuleModule.java`](android/src/main/java/com/reactnativegeetestmodule/GeetestModuleModule.java)
and share a shape: a reference is dereferenced on a path where it can legitimately
be null. The `tearDown()` guard added in `220ea7f` fixed one instance of it.

Two of the three recorded here are now fixed and covered by tests; issue 3 remains
open because it needs a product decision rather than a null check.

---

## 1. `tearDown()` crashes when no activity is attached — FIXED

**Symptom**

```
java.lang.NullPointerException: Cannot invoke "android.app.Activity.runOnUiThread(java.lang.Runnable)"
because the return value of "GeetestModuleModule.getCurrentActivity()" is null
```

**Reproduction** — tear down while no activity is current: background the app
mid-captcha, or let a teardown race a screen unmount.

**Why** — `tearDown()` reached the UI thread through `getCurrentActivity()`. The
null guard sat *inside* the runnable, one line too late to help; the activity
reference was dereferenced before the runnable was ever posted.

**Fix** — hop through `UiThreadUtil`, the bridge's own helper, which posts to the
main looper and needs no activity. Preferred over null-guarding the activity: with
a guard, teardown after a detach would skip `destory()` entirely and leak the
captcha dialog.

Pinned by `tearDownAfterActivityDetachedStillDestroysSdk`, which asserts the SDK is
still destroyed with no activity attached — the leak a null guard would have
introduced.

---

## 2. `handleRegisteredGeeTestCaptcha()` crashes on a null SDK handle — FIXED

**Symptom**

```
java.lang.NullPointerException: Cannot invoke "com.geetest.sdk.GT3GeetestUtils.startCustomFlow()"
because "this.gt3GeetestUtils" is null
```

**Reproduction** — trigger the captcha before `setUp()`, or after `tearDown()` has
cleared the handles.

**Why** — this is the subtle one, and the reason it looked safe. The call sat
inside a `try`/`catch (Exception)`, but that `catch` could not see it.
`@ReactMethod` runs on the NativeModules thread, never the UI thread, so
`runOnUiThread` *posts* the runnable rather than running it inline. The runnable
executed on the UI thread long after the `try` frame had returned, and the NPE went
uncaught.

Do not "simplify" this back by re-wrapping the hop in a `try`/`catch` — that is
the broken version.

**Fix** — parse before the hop, where the `catch` is meaningful, and null-check the
handles inside the runnable, where they are actually used.

Pinned by `captchaBeforeSetUpDoesNotCrashTheUiThread` and
`captchaAfterTearDownDoesNotCrashTheUiThread` for the crash,
`captchaStartsTheFlowWhenSetUp` for the happy path the guard must not swallow, and
`captchaWithMalformedJsonDoesNotTouchTheSdk` for the parse the `catch` still covers.

---

## 3. `setUp()` dereferences a possibly-null activity — OPEN

`setUp()` calls `new GT3GeetestUtils(getCurrentActivity())` unguarded.

Unlike the two above, this has **no mechanical fix**. The SDK genuinely needs a
real activity to build the captcha dialog, so a null check would produce a module
that reports success but can never show a captcha — trading a loud crash for a
silent dead end. Deciding between failing loudly, deferring initialisation until
an activity attaches, or surfacing an error to JS is a product decision, not a
null check.

---

## Testing these

The suite
([`GeetestModuleModuleTest.java`](android/src/test/java/com/reactnativegeetestmodule/GeetestModuleModuleTest.java))
covers issues 1 and 2 alongside the original `tearDown()` null guard. Run it with
`./gradlew test` from `android/`, using JDK 17.

Two things to know before touching those tests.

**Robolectric runs tests on the main thread.** Calling a `@ReactMethod` directly
therefore makes an `Activity.runOnUiThread` hop execute its body *inline*, inside
the caller's frame — where the old `try`/`catch` in issue 2 swallowed the very
crash under test. A test written that way passes against unfixed code and proves
nothing; this was confirmed, not assumed. That is what `callFromBridgeThread()` in
the suite exists for: it posts from a separate thread so the body stays on the
looper, and the drain is what surfaces the crash.

Note that `UiThreadUtil.runOnUiThread` always posts, even when the caller is
already on the UI thread — unlike `Activity.runOnUiThread`, which runs inline. So
nothing in either runnable has happened until the looper is drained, on any thread.

**Always confirm a new test fails without its fix.** Every guard here is one that
a test can accidentally pass around. Reverting the module to its unfixed state
fails exactly `tearDownAfterActivityDetachedStillDestroysSdk`,
`captchaBeforeSetUpDoesNotCrashTheUiThread` and
`captchaAfterTearDownDoesNotCrashTheUiThread`, each with the NullPointerException
quoted above.
