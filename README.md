# Push Notification SDK for Android

A push-based multi-factor authentication SDK for Android. This SDK enables secure authentication flows by allowing enrolled devices to receive and respond to push-based challenges.

## What This SDK Does

The SDK handles two core flows:

- **Enrollment**: Register a device with a backend issuer using a one-time enrollment grant (delivered via QR code or deep link).
- **Challenge Resolution**: Receive push notifications containing authentication challenges and resolve them

The SDK does **not**:
- Scan QR codes or manage deep links — your app owns the camera and intent handling
- Register for push tokens, or detect when one rotates — your app owns FCM registration and token management, and calls `updateDeviceToken` to report a new one (see [Handling Enrollments](#handling-enrollments))
- Display notifications — your app decides what appears on screen
- Manage challenge UI — your app renders the challenge and collects the user's decision

## Integration Guide

### 1. Add the SDK to Your Project

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.strivacity.android:pushnotification:<version>")
}
```

> **Note:** Replace `<version>` with the latest release version. The artifact will be published to Maven Central at `https://mvncentral.com/artifact/com.strivacity.android/pushnotification`.

### 2. Instantiate the SDK

Create a single SDK instance at app startup, typically in your Application class or as a dependency-injected singleton:

```kotlin
import com.strivacity.android.pushnotification.PushNotification
import com.strivacity.android.pushnotification.storage.DefaultPushNotificationStorage

val preferences = context.getSharedPreferences(
    "com.strivacity.pushnotification.enrollments",
    Context.MODE_PRIVATE
)

val sdk = PushNotification(DefaultPushNotificationStorage(preferences))
```

The SDK depends only on the plain `android.content.SharedPreferences` interface. Enrollment details (issuer, account id, device name) are stored unencrypted in the `SharedPreferences` you provide; the keys themselves live in the Android Keystore. If you need encryption, supply an `EncryptedSharedPreferences` instance instead (from `androidx.security:security-crypto`), or implement a custom `PushNotificationStorage` backed by a database with its own encryption.

`PushNotificationStorage` is a plain interface — a custom implementation can store keys and enrollments however it likes. See [Storage Extension Point](#storage-extension-point).

<details>
<summary>Java</summary>

```java
import android.content.Context;
import android.content.SharedPreferences;
import com.strivacity.android.pushnotification.PushNotification;
import com.strivacity.android.pushnotification.PushNotificationCompat;
import com.strivacity.android.pushnotification.storage.DefaultPushNotificationStorage;

SharedPreferences preferences = context.getSharedPreferences(
    "com.strivacity.pushnotification.enrollments",
    Context.MODE_PRIVATE
);

// PushNotificationCompat wraps the suspending API in the *Async callbacks used below
PushNotificationCompat sdk = new PushNotificationCompat(
    new PushNotification(new DefaultPushNotificationStorage(preferences))
);
```

</details>

The SDK instance is app-scoped. One instance serves all issuers your device is enrolled against. You do not need to close it — it lives for the process lifetime.

#### Configuration

**`storage`** — A `PushNotificationStorage`, which stores each enrollment's keypair together with the record of the enrollment itself, and signs with the keypair. The SDK ships `DefaultPushNotificationStorage`, which uses NIST P-256 keys in the Android Keystore, backed by secure hardware (TEE or StrongBox on physical devices), and records each enrollment's details in the `SharedPreferences` instance you supply.

**Hardware-backed keys are required by default.** Emulators and the Android Simulator use software-backed keys and will fail enrollment with `SoftwareBackedKeyException`. For development against an emulator, opt into software keys explicitly:

```kotlin
val storage = DefaultPushNotificationStorage(preferences, allowSoftwareBackedKeys = true)
```

<details>
<summary>Java</summary>

```java
DefaultPushNotificationStorage storage = new DefaultPushNotificationStorage(
    preferences,
    true // allowSoftwareBackedKeys
);
```

</details>

Keep `allowSoftwareBackedKeys = true` only in debug builds or CI, never in production-facing builds. A build that never explicitly allows this cannot silently end up signing with a software key.

### 3. Handle Enrollment

Enrollment requires:
1. An enrollment grant (parsed from a scanned QR code or deep link)
2. The current FCM push token (to which the SDK refers to as "Device Token"), and which kind of token it is (`DeviceTokenType.FCM_TOKEN` — the SDK doesn't infer this, since Android could in principle also be told about an FCM Installation ID via `DeviceTokenType.FCM_FID`)
3. A user-friendly device name (required). It must be 1-64 characters (after trimming), or `enrollDevice` throws `InvalidDeviceNameException`. 

**💡 Tip:** A good default to the device name can be queried using `Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)`

#### Parse the enrollment grant

Your app handles QR scanning and deep linking. Once you have the scanned string or deep-link URI, parse it into an enrollment grant:

```kotlin
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import android.net.Uri

val scannedUri = Uri.parse(qrCodeContent) // or intent.data from deep link
try {
    val grant = EnrollmentGrant.parse(scannedUri)
} catch (e: InvalidGrantException) {
    // Malformed grant — show an error to the user
} catch (e: InvalidIssuerException) {
    // The issuer parameter isn't an absolute https URL
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.model.EnrollmentGrant;
import com.strivacity.android.pushnotification.model.InvalidGrantException;
import com.strivacity.android.pushnotification.model.InvalidIssuerException;
import android.net.Uri;

Uri scannedUri = Uri.parse(qrCodeContent); // or intent.getData() from deep link
try {
    EnrollmentGrant grant = EnrollmentGrant.parse(scannedUri);
} catch (InvalidGrantException e) {
    // Malformed grant — show an error to the user
} catch (InvalidIssuerException e) {
    // The issuer parameter isn't an absolute https URL
}
```

</details>

#### Call enrollDevice

Call enrollment inside a coroutine context:

```kotlin
viewModelScope.launch {
    try {
        val enrollment = sdk.enrollDevice(
            grant = grant,
            deviceToken = fcmToken,  // Get from FirebaseMessaging.getInstance().token
            deviceTokenType = DeviceTokenType.FCM_TOKEN,
            deviceName = userChosenName    // Must be 1-64 characters (after trimming), or throws InvalidDeviceNameException
        )
        // Success — the device is now enrolled
    } catch (e: AlreadyEnrolledException) {
        // This issuer + account is already enrolled on this device
        // Un-enroll first if you want to replace it
    } catch (e: EnrollmentException) {
        // Backend refused the enrollment — not retryable
        // The user needs a fresh enrollment grant
    } catch (e: SoftwareBackedKeyException) {
        // Running on an emulator without allowSoftwareBackedKeys = true
    }
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;

CancellationSignal signal = new CancellationSignal();
Executor executor = Executors.newSingleThreadExecutor();

sdk.enrollDeviceAsync(
    grant,
    fcmToken,
    DeviceTokenType.FCM_TOKEN,
    userChosenName,
    signal,
    executor,
    new PushNotificationCallback<Enrollment, PushNotificationException>() {
        @Override
        public void onResult(Enrollment enrollment) {
            // Success — the device is now enrolled
        }

        @Override
        public void onError(PushNotificationException e) {
            if (e instanceof AlreadyEnrolledException) {
                // This issuer + account is already enrolled on this device
            } else if (e instanceof EnrollmentException) {
                // Backend refused the enrollment — not retryable
            } else if (e instanceof SoftwareBackedKeyException) {
                // Running on an emulator without allowSoftwareBackedKeys = true
            }
        }
    }
);
```

</details>

### 4. Handle Incoming Challenge Pushes

Your app receives FCM data messages. Delegate to the SDK to check if it can handle them:

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyMessagingService : FirebaseMessagingService() {
    private val sdk = // ... get your SDK instance ...

    override fun onMessageReceived(message: RemoteMessage) {
        lifecycleScope.launch {
            // Check if this is a challenge the SDK can handle
            if (sdk.canHandleMessage(message.data)) {
                handleChallenge(message.data)
            } else {
                // Not a challenge, or you're not enrolled for it — handle as app message
            }
        }
    }

    private suspend fun handleChallenge(messageData: Map<String, String>) {
        try {
            val challenge = sdk.handleMessage(messageData)
            // Show your challenge UI — see the Challenge section below
            showChallengeUI(challenge)
        } catch (e: PushNotificationException) {
            // Something went wrong fetching the challenge detail
            // Log and show an error
        }
    }
}
```

<details>
<summary>Java</summary>

```java
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.strivacity.android.pushnotification.PushNotificationCallback;

public class MyMessagingService extends FirebaseMessagingService {
    private PushNotificationCompat sdk; // ... get your SDK instance ...

    @Override
    public void onMessageReceived(RemoteMessage message) {
        // Check if this is a challenge the SDK can handle
        CancellationSignal signal = new CancellationSignal();
        Executor executor = Executors.newSingleThreadExecutor();

        sdk.canHandleMessageAsync(message.getData(), signal, executor,
            new PushNotificationCallback<Boolean, PushNotificationException>() {
                @Override
                public void onResult(Boolean canHandle) {
                    if (canHandle) {
                        handleChallenge(message.getData());
                    } else {
                        // Not a challenge, or you're not enrolled for it
                    }
                }

                @Override
                public void onError(PushNotificationException e) {
                    // Error checking message
                }
            });
    }

    private void handleChallenge(Map<String, String> messageData) {
        CancellationSignal signal = new CancellationSignal();
        // Use main thread executor for UI operations
        Executor executor = ContextCompat.getMainExecutor(context);

        sdk.handleMessageAsync(messageData, signal, executor,
            new PushNotificationCallback<Challenge, PushNotificationException>() {
                @Override
                public void onResult(Challenge challenge) {
                    showChallengeUI(challenge);  // UI operation
                }

                @Override
                public void onError(PushNotificationException e) {
                    // Error fetching challenge detail
                }
            });
    }
}
```

</details>

#### Handling a Message Delivered as Launch Intent Extras

If the push also carries a notification payload, the platform posts it
to the system tray while your app is backgrounded or not running, and
`onMessageReceived` is never called for it. Tapping that notification
launches your app through its launcher activity, with the message's
data as extras on the launch `Intent`. With the default (`standard`)
launch mode this always reaches `onCreate` — regardless of whether your
app process was already running elsewhere. Use `MessageData.from(intent)`
there to extract the SDK's data:

```kotlin
import com.strivacity.android.pushnotification.helper.MessageData

class MyLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageData.from(intent)?.let { messageData ->
            lifecycleScope.launch {
                if (sdk.canHandleMessage(messageData)) {
                    handleChallenge(messageData)
                }
            }
        }
    }
}
```

If your launcher activity uses `singleTop` or `singleTask` launch mode,
an instance already on top of the back stack receives the tap via
`onNewIntent` instead — handle it there too if that applies to your app:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    MessageData.from(intent)?.let { messageData ->
        lifecycleScope.launch {
            if (sdk.canHandleMessage(messageData)) {
                handleChallenge(messageData)
            }
        }
    }
}
```

`MessageData.from(intent)` returns `null` if the intent's extras carry
nothing for the SDK — safe to call unconditionally on any intent.

<details>
<summary>Java</summary>

`MessageData.from` is a plain static method from Java — no `Executor` or
callback needed for that part, since it does no signing or network
work:

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;
import com.strivacity.android.pushnotification.helper.MessageData;

public class MyLaunchActivity extends ComponentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Map<String, String> messageData = MessageData.from(getIntent());
        if (messageData == null) {
            return;
        }

        CancellationSignal signal = new CancellationSignal();
        // Use main thread executor since handleChallenge will perform UI operations
        Executor executor = ContextCompat.getMainExecutor(context);

        sdk.canHandleMessageAsync(messageData, signal, executor,
            new PushNotificationCallback<Boolean, PushNotificationException>() {
                @Override
                public void onResult(Boolean canHandle) {
                    if (canHandle) {
                        handleChallenge(messageData);  // Leads to UI operations
                    }
                }

                @Override
                public void onError(PushNotificationException e) {
                    // Error checking message
                }
            });
    }
}
```

</details>

### 5. Render the Challenge and Collect a Response

The SDK returns a sealed `Challenge` with two variants. Your app renders appropriate UI based on the type:

```kotlin
when (challenge) {
    is Challenge.Simple -> {
        // Show a simple approve/deny screen
        showApproveDenyUI(challenge) { userApproved ->
            val resolution = if (userApproved) {
                challenge.approve()
            } else {
                challenge.deny()
            }
            submitResolution(resolution)
        }
    }
    is Challenge.ContextMatching -> {
        // Show the context-matching screen with the offered options
        showContextMatchingUI(challenge.options) { selectedOption ->
            val resolution = challenge.select(selectedOption)
            submitResolution(resolution)
        }
    }
}
```

<details>
<summary>Java</summary>

```java
if (challenge instanceof Challenge.Simple) {
    Challenge.Simple approveDeny = (Challenge.Simple) challenge;
    showApproveDenyUI(approveDeny, userApproved -> {
        ChallengeResolution resolution = userApproved 
            ? approveDeny.approve() 
            : approveDeny.deny();
        submitResolution(resolution);
    });
} else if (challenge instanceof Challenge.ContextMatching) {
    Challenge.ContextMatching contextMatching = (Challenge.ContextMatching) challenge;
    showContextMatchingUI(contextMatching.getOptions(), selectedOption -> {
        ChallengeResolution resolution = contextMatching.select(selectedOption);
        submitResolution(resolution);
    });
}
```

</details>

### 6. Submit the Resolution

Once the user has made a decision, sign and submit it:

```kotlin
private suspend fun submitResolution(resolution: ChallengeResolution) {
    try {
        val result = sdk.resolveChallenge(resolution)
        when (result) {
            ResolutionResult.OK -> {
                // Resolution accepted by the backend
                showSuccess()
            }
            ResolutionResult.NOT_OK -> {
                // Resolution rejected by the backend (including explicit denials)
                showFailure()
            }
        }
    } catch (e: PushNotificationException) {
        // Network error, signing error, etc.
        showError(e)
    }
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;

private void submitResolution(ChallengeResolution resolution) {
    CancellationSignal signal = new CancellationSignal();
    // Use main thread executor for UI operations
    Executor executor = ContextCompat.getMainExecutor(context);

    sdk.resolveChallengeAsync(resolution, signal, executor,
        new PushNotificationCallback<ResolutionResult, PushNotificationException>() {
            @Override
            public void onResult(ResolutionResult result) {
                if (result == ResolutionResult.OK) {
                    // Resolution accepted by the backend
                    showSuccess();  // UI operation
                } else {
                    // Resolution rejected by the backend (including explicit denials)
                    showFailure();  // UI operation
                }
            }

            @Override
            public void onError(PushNotificationException e) {
                // Network error, signing error, etc.
                showError(e);  // UI operation
            }
        });
}
```

</details>

## Concepts and Flows

### Enrollment Flow

**Enrollment** establishes a trusted relationship between a device, a user account, and a backend issuer.

1. **Parse the grant** — Your app scans a QR code or handles a deep link, extracting:
   - The issuer URL (which backend cluster this enrollment targets)
   - An account ID (the user's account at that issuer)
   - The device name (how the user will recognize this device in their enrollment list)

2. **Generate and register** — Call `enrollDevice` with the grant, the device's push token, and the device name. The SDK:
   - Generates a keypair for this (issuer, account) pair and stores it locally, together with the enrollment's details, for future lookups and token updates (the private key never leaves the device)
   - Sends the public key to the backend along with the one-time token from the grant
   - Removes the keypair and the enrollment again if the backend refuses or can't be reached

3. **Constraints:**
   - One device can hold at most one enrollment per (issuer, account) pair. Attempting a second enrollment for the same pair fails with `AlreadyEnrolledException` — un-enroll first to replace it.
   - Whether an enrollment exists is decided by the enrollment records, not by the presence of a key. A key stored without an enrollment record is not an enrollment, and is replaced when you enroll again. Likewise, an enrollment record whose key is gone is not listed.
   - If your app is terminated while `enrollDevice` is waiting for the backend, the enrollment stays recorded on the device even though the backend may never have accepted it. It then appears in `listEnrollments`, and `updateDeviceToken` for it is refused by the backend. Call `unenrollDevice` to remove it — the local removal completes even though the backend call fails.
   - The one-time token in the grant is single-use. A failed enrollment cannot be retried with the same token; a fresh grant is required.

### Challenge Flow

**Challenges** are MFA requests pushed to an enrolled device. The user reviews the request and makes a decision.

1. **Push notification arrives** — A push reaches the enrolled device's push token. Your app receives it in its messaging service and calls `canHandleMessage` to check if this is an enrollment the SDK can handle.

2. **Fetch challenge details** — Call `handleMessage` to retrieve the full challenge from the backend. The SDK returns a challenge object containing:
   - The challenge type: **Simple** (approve/deny) or **ContextMatching** (pick from options)
   - For simple: nothing more
   - For context matching: a list of options the user will see
   - Metadata (device, location, time — context for the user's decision)
   - Expiration time (an ISO 8601 / RFC 3339 instant string, e.g. `2026-10-01T14:30:00Z`)

3. **Render UI and collect decision** — Your app renders a screen appropriate to the challenge type and captures the user's choice:
   - **Simple**: Approve or Deny buttons
   - **ContextMatching**: Select one of the offered options (or deny)

4. **Submit the resolution** — Call `resolveChallenge` with the user's decision. The SDK signs the response with the enrolled keypair and sends it to the backend.

5. **Get the result** — The backend returns `ResolutionResult.OK` or `ResolutionResult.NOT_OK`.

**User denial is not an error.** An explicit refusal from the user returns `ResolutionResult.NOT_OK` normally — it's a legitimate outcome. The SDK reserves exceptions for actual failures (network errors, missing key, etc.).

**Expiry and timestamps.** The SDK provides the challenge's expiration time (`challenge.expiresAt`, an ISO 8601 / RFC 3339 instant string, e.g. `2026-10-01T14:30:00Z`) for your app to display or warn about, but does not enforce it — the backend is the authority.

## Storage Extension Point

`PushNotificationStorage` stores each enrollment's keypair together with the record of the enrollment itself, and signs with the keypair. The SDK ships `DefaultPushNotificationStorage`; you may replace it to:

- Integrate with an existing key management system
- Use a different secure storage backend, or a different local database (e.g. SQLite/Room) for the enrollment records

```kotlin
public interface PushNotificationStorage {
    /**
     * Retrieve the public key for this issuer and account, or null if none exists.
     * Only a single key exists for an issuer and account combination.
     */
    public suspend fun getPublicKey(
        issuer: String,
        accountId: String,
    ): Jwk?

    /**
     * Sign the given data and return the signature as a base64url string.
     * The data is a raw string; the implementation is responsible only for signing,
     * not for JWT structure.
     *
     * @throws NotEnrolledException if no enrollment is recorded for this issuer and account —
     *   checked first.
     * @throws StorageException if an enrollment is recorded but no key of that id is stored.
     */
    public suspend fun createSignature(
        issuer: String,
        accountId: String,
        keyId: String,
        data: String,
    ): String

    /**
     * Generates a new keypair for this issuer and account, records the enrollment it belongs to
     * (its keyId is the returned key's kid), and returns the key's public half.
     *
     * @throws AlreadyEnrolledException if a key for this issuer and account already exists.
     */
    public suspend fun createEnrollment(
        issuer: String,
        accountId: String,
        userName: String?,
        deviceName: String?,
    ): Jwk

    /** Every enrollment currently recorded. A read failure degrades to an empty list. */
    public suspend fun listEnrollments(): List<Enrollment>

    /**
     * Removes every key and enrollment record held for this issuer and account. Silently does
     * nothing if none is stored.
     */
    public suspend fun removeEnrollment(
        issuer: String,
        accountId: String,
    )
}
```

<details>
<summary>Java</summary>

```java
public interface PushNotificationStorage {
    /**
     * Retrieve the public key for this issuer and account, or null if none exists.
     * Only a single key exists for an issuer and account combination.
     */
    Jwk getPublicKey(String issuer, String accountId) throws Exception;

    /**
     * Sign the given data and return the signature as a base64url string.
     * The data is a raw string; the implementation is responsible only for signing,
     * not for JWT structure.
     *
     * @throws NotEnrolledException if no enrollment is recorded for this issuer and account —
     *   checked first.
     * @throws StorageException if an enrollment is recorded but no key of that id is stored.
     */
    String createSignature(String issuer, String accountId, String keyId, String data) throws Exception;

    /**
     * Generates a new keypair for this issuer and account, records the enrollment it belongs to
     * (its keyId is the returned key's kid), and returns the key's public half.
     *
     * @throws AlreadyEnrolledException if a key for this issuer and account already exists.
     */
    Jwk createEnrollment(String issuer, String accountId, String userName, String deviceName) throws Exception;

    /**
     * Every enrollment currently recorded. A read failure degrades to an empty list.
     */
    List<Enrollment> listEnrollments() throws Exception;

    /**
     * Removes every key and enrollment record held for this issuer and account. Silently does
     * nothing if none is stored.
     */
    void removeEnrollment(String issuer, String accountId) throws Exception;
}
```

</details>

How the SDK uses an implementation:

- `enrollDevice` and `hasEnrollment` decide whether an enrollment exists from `listEnrollments`, not from the presence of a key. The other methods look the key up with `getPublicKey`, and `createSignature` rejects a key that has no enrollment record with `NotEnrolledException`.
- Before creating an enrollment, the SDK calls `removeEnrollment` for that issuer and account, so `createEnrollment` only has to handle the case where nothing is stored. If the backend then refuses the enrollment, the SDK calls `removeEnrollment` again.
- If `removeEnrollment` throws during `unenrollDevice`, the SDK rethrows your exception unchanged, in preference to any backend failure from the same call. Throw a `StorageException` so callers can handle it like the SDK's other storage errors.

Store each enrollment's key and record together, so that one is never left without the other.

### JWK Format

Public keys are returned as `Jwk` objects containing:
- `kid` — A unique identifier for the key (client-generated; the SDK uses it to retrieve the key for signing)
- `alg` — The signing algorithm used (e.g. `ES256` for NIST P-256)
- `members` — A map of JWK fields (e.g. `x`, `y`, `crv` for elliptic curve keys)

The JWK must contain only the public half. The SDK validates that the private-key member `d` is not present; if it is, enrollment fails.

### Supported Algorithms

The SDK enforces a closed allowlist of signing algorithms. Currently, only ES256 (NIST P-256) is accepted. A `PushNotificationStorage` implementation that returns a key with an unsupported algorithm will fail at enrollment with `UnsupportedAlgorithmException`.

## Handling Enrollments

### Check Whether You're Enrolled

Before calling `enrollDevice`, check if an enrollment already exists. This answers from the enrollments recorded in storage — the same check `enrollDevice` makes:

```kotlin
val isEnrolled = sdk.hasEnrollment(issuer, accountId)
if (isEnrolled) {
    // Enrollment exists; un-enroll first if you want to replace it
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;

CancellationSignal signal = new CancellationSignal();
Executor executor = Executors.newSingleThreadExecutor();

sdk.hasEnrollmentAsync(issuer, accountId, signal, executor,
    new PushNotificationCallback<Boolean, PushNotificationException>() {
        @Override
        public void onResult(Boolean isEnrolled) {
            if (isEnrolled) {
                // Enrollment exists; un-enroll first if you want to replace it
            }
        }

        @Override
        public void onError(PushNotificationException e) {
            // Error checking enrollment
        }
    });
```

</details>

### Un-Enroll a Device

```kotlin
viewModelScope.launch {
    try {
        sdk.unenrollDevice(issuer, accountId)
        // Device is un-enrolled
    } catch (e: NotEnrolledException) {
        // Nothing to un-enroll
    } catch (e: UnenrollmentException) {
        // The backend refused, but the local enrollment was removed anyway.
        // The backend may still believe the device is enrolled.
    } catch (e: NetworkException) {
        // Same: the backend couldn't be reached, but the local enrollment was removed.
    } catch (e: Exception) {
        // Looking up the key, or removing the enrollment locally, failed
    }
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;
import kotlin.Unit;

CancellationSignal signal = new CancellationSignal();
Executor executor = Executors.newSingleThreadExecutor();

sdk.unenrollDeviceAsync(issuer, accountId, signal, executor,
    new PushNotificationCallback<Unit, PushNotificationException>() {
        @Override
        public void onResult(Unit result) {
            // Device is un-enrolled
        }

        @Override
        public void onError(PushNotificationException e) {
            // NotEnrolledException: nothing to un-enroll.
            // UnenrollmentException or NetworkException: the backend call failed, but the local
            // enrollment was removed anyway, and the backend may still believe the device is enrolled.
            // Otherwise looking up the key, or removing the enrollment locally, failed.
        }
    });
```

</details>

**Important:** The local keypair and enrollment record are always deleted, even if the backend call fails. This prevents a device from becoming stuck with an un-recoverable enrollment if the backend is temporarily unreachable. The trade-off is that the backend may retain a stale enrollment record. If a local removal itself fails, `unenrollDevice` throws the storage's error (a `StorageException` with the shipped storage), even when the backend call failed too, and the backend's failure is lost. The shipped storage always runs both of its removals — the enrollment record and the key — so a failure removing one doesn't keep the other.

### List Enrollments

Every enrollment this device holds — across every issuer and account, including ones your app never itself initiated (e.g. restored from a backup):

```kotlin
val enrollments = sdk.listEnrollments()
// Enrollment(issuer, accountId, keyId, userName, deviceName)
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;

CancellationSignal signal = new CancellationSignal();
Executor executor = Executors.newSingleThreadExecutor();

sdk.listEnrollmentsAsync(signal, executor,
    new PushNotificationCallback<List<Enrollment>, PushNotificationException>() {
        @Override
        public void onResult(List<Enrollment> enrollments) {
            // ...
        }

        @Override
        public void onError(PushNotificationException e) {
            // Error reading the local enrollments
        }
    });
```

</details>

This is what makes reporting a refreshed push token possible without your app keeping its own record of enrollments.

### Report a Refreshed Push Token

Your app owns FCM registration, including detecting when the token rotates (`FirebaseMessagingService.onNewToken`). When that happens, list what's enrolled and decide for yourself what to do with each — update all of them silently, warn the user first, or skip ones you don't recognise:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    private val sdk = // ... get your SDK instance ...

    override fun onNewToken(token: String) {
        viewModelScope.launch {
            sdk.listEnrollments().forEach { enrollment ->
                try {
                    sdk.updateDeviceToken(enrollment.issuer, enrollment.accountId, token, DeviceTokenType.FCM_TOKEN)
                } catch (e: PushNotificationException) {
                    // UpdateTokenException: the backend refused the update for this one enrollment.
                    // Anything else, e.g. NetworkException — retry later, or skip.
                }
            }
        }
    }
}
```

<details>
<summary>Java</summary>

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;
import kotlin.Unit;

@Override
public void onNewToken(String token) {
    CancellationSignal signal = new CancellationSignal();
    Executor executor = Executors.newSingleThreadExecutor();

    sdk.listEnrollmentsAsync(signal, executor,
        new PushNotificationCallback<List<Enrollment>, PushNotificationException>() {
            @Override
            public void onResult(List<Enrollment> enrollments) {
                for (Enrollment enrollment : enrollments) {
                    sdk.updateDeviceTokenAsync(enrollment.getIssuer(), enrollment.getAccountId(), token,
                        DeviceTokenType.FCM_TOKEN, signal, executor,
                        new PushNotificationCallback<Unit, PushNotificationException>() {
                            @Override
                            public void onResult(Unit result) {
                                // Token reported to the backend
                            }

                            @Override
                            public void onError(PushNotificationException e) {
                                // Backend refused the update for this one enrollment
                            }
                        });
                }
            }

            @Override
            public void onError(PushNotificationException e) {
                // Error reading the local enrollments
            }
        });
}
```

</details>

## Java Interoperability

If you're writing in Java rather than Kotlin, use `PushNotificationCompat`, which wraps the suspending API with callbacks:

```java
import com.strivacity.android.pushnotification.PushNotificationCallback;
import com.strivacity.android.pushnotification.PushNotificationCompat;

CancellationSignal signal = new CancellationSignal();
Executor executor = Executors.newSingleThreadExecutor();

sdk.enrollDeviceAsync(grant, fcmToken, DeviceTokenType.FCM_TOKEN, deviceName, signal, executor,
    new PushNotificationCallback<Enrollment, PushNotificationException>() {
        @Override
        public void onResult(Enrollment enrollment) { }

        @Override
        public void onError(PushNotificationException e) { }
    });
```

## Error Handling

The SDK uses typed exceptions. All SDK exceptions inherit from `PushNotificationException`:

- **`AlreadyEnrolledException`** — An enrollment already exists for this issuer + account. Un-enroll first.
- **`EnrollmentException`** — The backend refused enrollment (e.g. invalid one-time token). Not retryable.
- **`UnenrollmentException`** — The backend refused un-enrollment. The local enrollment is removed regardless.
- **`InvalidGrantException`** — The enrollment grant URI is malformed or missing required parameters.
- **`InvalidDeviceNameException`** — The device name (after trimming) isn't 1-64 characters.
- **`NotEnrolledException`** — No enrollment exists for this issuer + account.
- **`UpdateTokenException`** — The backend refused a device token update.
- **`SoftwareBackedKeyException`** — The device's Keystore produced a software-backed key, but you did not opt into allowing them.
- **`ChallengeException`** — The push payload is not a challenge this SDK can handle, or the backend refused it or returned one this SDK can't read, or failed its resolution.
- **`UnsupportedChallengeTypeException`** — The backend sent a challenge type not supported by this SDK version.
- **`StorageException`** — The `PushNotificationStorage` implementation returned invalid data, or key generation/signing failed, or an enrollment could not be recorded or removed locally.
- **`NetworkException`** — The backend could not be reached, or the response was unreadable.
- **`UnsupportedAlgorithmException`** — The key algorithm is not in the allowlist.
- **`InvalidIssuerException`** — The issuer URL is malformed.
- **`UnexpectedException`** — An unexpected error (for Java callers via `PushNotificationCompat`).
- **`InvalidChallengeOptionException`** — `Challenge.ContextMatching.select` was called with an option not among those the challenge offered.

All exceptions carry an error code suitable for logging and error reporting. They never contain sensitive material (tokens, keys, JWTs).

Exceptions thrown by a custom `PushNotificationStorage` are rethrown unchanged. So are exceptions the shipped storage doesn't wrap, such as a `KeyStoreException`, so `catch (e: PushNotificationException)` isn't exhaustive. The `*Async` variants report them as `UnexpectedException`.

## Running on an Emulator

Emulators and the Android Simulator use software-backed keys by default — they have no secure element. The SDK requires hardware-backed keys by default, so enrollment will fail with `SoftwareBackedKeyException`.

To develop against an emulator, explicitly opt into software keys:

```kotlin
val storage = DefaultPushNotificationStorage(preferences, allowSoftwareBackedKeys = true)
```

<details>
<summary>Java</summary>

```java
DefaultPushNotificationStorage storage = new DefaultPushNotificationStorage(
    preferences,
    true // allowSoftwareBackedKeys
);
```

</details>

**Keep this flag enabled only in debug builds.** A production build should never allow software-backed keys.

## Development

Before committing, verify your changes pass formatting and static analysis checks locally:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v21) && ./gradlew spotlessCheck detekt lint
```

This runs:

- **Spotless** (`spotlessCheck`) — verifies Kotlin/Java code formatting via ktlint / google-java-format
- **Detekt** (`detekt`) — static analysis for Kotlin code smells and best practices
- **Android Lint** (`lint`) — Android-specific static analysis

If `spotlessCheck` fails, auto-fix formatting issues with:

```bash
./gradlew spotlessApply
```

These same checks run in CI on every pull request targeting `main`.

Instrumented tests (`pushnotification/src/androidTest`) run against a real Android Keystore and real `SharedPreferences`, so they need a device or emulator — CI runs them on a Gradle Managed Device, which you can run locally the same way, no emulator setup required:

```bash
./gradlew :pushnotification:ciDeviceDebugAndroidTest
```

To try a local change from a consuming app before publishing a real release, publish the SDK to your local Maven repository:

```bash
./gradlew clean :pushnotification:publishToMavenLocal --no-configuration-cache --rerun-tasks
```

## Security Considerations

- **Keys are hardware-backed by default.** The shipped `DefaultPushNotificationStorage` uses the Android Keystore, which provides hardware backing (TEE or StrongBox) on physical devices.
- **The shipped `DefaultPushNotificationStorage` never encrypts enrollment details itself.** Each enrollment's details (issuer, account id, user name, device name) are stored unencrypted in the `SharedPreferences` you provide. If you need encryption, supply an `EncryptedSharedPreferences` instance (from `androidx.security:security-crypto`) instead, or implement a custom `PushNotificationStorage` backed by an encrypted database. This is specific to the shipped implementation: `PushNotificationStorage` is a plain interface, and a custom implementation may use a different storage mechanism with its own protection model.
- **The one-time token is single-use.** A failed enrollment cannot be retried with the same token.
- **The challenge value prevents replay.** It is a single-use value the backend invalidates on first use, and it is bound into the resolution JWT.
- **Private keys never leave the device.** Signing happens in the Keystore; only signatures cross the network.
- **The default is the secure option.** Software-backed keys must be opted into explicitly.
