package com.cliqz.jsengine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.cliqz.browser.main.Messages;
import com.cliqz.browser.webview.CliqzMessages;
import com.cliqz.nove.Bus;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * @author Sam Macbeth
 * @author Moaz Rashad
 */
public class JSBridge extends ReactContextBaseJavaModule {

    private final ReactApplicationContext context;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    /**
     * Action names registered from the JS side of the bridge
     */
    private final Set<String> registeredActions = new HashSet<>();
    private final Map<Integer, FutureResult> waitingForResults = new ConcurrentHashMap<>();
    private final Map<Integer, Callback> eventCallbacks = new ConcurrentHashMap<>();

    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(1);

    // counter for unique action ids
    private final AtomicInteger actionCounter = new AtomicInteger(1);

    private final Map<String, List<WaitingAction>> awaitingRegistration =
            new HashMap<>();

    private static final long ACTION_TIMEOUT = 500;

    private final Handler handler;
    private final Bus bus;

    /**
     * CliqzEvents in javascript which should be broadcast to Bus
     */
    private static Map<String, EventTranslator> forwardedEvents = new HashMap<>();
    static {
        forwardedEvents.put("mobile-search:openUrl", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return CliqzMessages.OpenLink.open(args.getString(0));
            }
        });
        forwardedEvents.put("mobile-search:copyValue", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.CopyData(args.getString(0));
            }
        });
        forwardedEvents.put("mobile-search:call", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.CallNumber(args.getString(0));
            }
        });
        forwardedEvents.put("mobile-search:map", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return CliqzMessages.OpenLink.open(args.getString(0));
            }
        });
        forwardedEvents.put("mobile-pairing:openTab", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.OpenTab(args.getMap(0));
            }
        });

        forwardedEvents.put("mobile-pairing:pushPairingData", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.PushPairingData(args.getMap(0));
            }
        });
        forwardedEvents.put("mobile-pairing:notifyPairingSuccess", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.NotifyPairingSuccess(args.getMap(0));
            }
        });
        forwardedEvents.put("mobile-pairing:notifyPairingError", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.NotifyPairingError(args.getMap(0));
            }
        });
        forwardedEvents.put("mobile-pairing:notifyTabSuccess", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.NotifyTabSuccess(args.getMap(0));
            }
        });
        forwardedEvents.put("mobile-pairing:notifyTabError", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.NotifyTabError(args.getMap(0));
            }
        });
        forwardedEvents.put("mobile-pairing:downloadVideo", new EventTranslator() {
            @Override
            public Object messageForEvent(ReadableArray args) {
                return new CliqzMessages.DownloadVideo(args.getMap(0));
            }
        });
    }


    public JSBridge(ReactApplicationContext reactContext, Engine engine, Bus bus) {
        super(reactContext);
        this.context = reactContext;
        engine.setBridgeIsReady(this);
        this.handler = new Handler(Looper.getMainLooper());
        this.bus = bus;
    }

    @Override
    public String getName() {
        return "JSBridge";
    }

    @Nullable
    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("events", forwardedEvents.keySet().toArray());
        return constants;
    }

    private DeviceEventManagerModule.RCTDeviceEventEmitter getEventEmitter() {
        if (this.eventEmitter == null) {
            this.eventEmitter = this.context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
        }
        return this.eventEmitter;
    }

    public interface Callback {
        void callback(ReadableMap result);
    }

    public static class NoopCallback implements Callback {
        @Override
        public void callback(ReadableMap result) {
        }
    }

    public interface EventTranslator {
        Object messageForEvent(ReadableArray args);
    }

    class FutureResult {

        int actionId;
        ReadableMap result = null;

        FutureResult(int actionId) {
            this.actionId = actionId;
        }

        ReadableMap getResult() {
            return result;
        }

        void setResult(ReadableMap result) {
            this.result = result;
            this.notifyAll();
        }
    }

    private class WaitingAction {

        final String actionName;
        final Object[] args;
        final Callback callback;

        WaitingAction(String actionName, Object[] args, Callback callback) {
            this.actionName = actionName;
            this.args = args;
            this.callback = callback;
        }
    }

    public ReadableMap callAction(String functionName, Object... args) throws ActionNotAvailable, EmptyResponseException {
        if (!this.registeredActions.contains(functionName)) {
            throw new ActionNotAvailable(functionName);
        }

        // create action id
        final int actionId = actionCounter.getAndIncrement();
        // create result object
        final FutureResult result = new FutureResult(actionId);
        this.waitingForResults.put(actionId, result);

        this.generateActionEvent(actionId, functionName, args);

        // check/wait for results
        synchronized (result) {
            if (result.getResult() == null) {
                try {
                    result.wait(ACTION_TIMEOUT);
                } catch (InterruptedException e) {
                    Log.e("JSBridge", "Interrupted waiting for bridge response", e);
                }
            }
        }

        final ReadableMap response = result.getResult();
        this.waitingForResults.remove(actionId);

        // no response value likely means there was a timeout
        if (response == null) {
            throw new EmptyResponseException(functionName);
        }

        return response;
    }

    // Please use Engine.callAction instead of this function
    // for action queuing until the bridge is ready
    void callAction(String functionName, Callback callback, Object... args) {
        if (!this.registeredActions.contains(functionName)) {
            synchronized (this.awaitingRegistration) {
                List<WaitingAction> waitingList = this.awaitingRegistration.get(functionName);
                if (waitingList == null) {
                    waitingList = new LinkedList<>();
                    this.awaitingRegistration.put(functionName, waitingList);
                }
                waitingList.add(new WaitingAction(functionName, args, callback));
            }
            return;
        }

        final int actionId = actionCounter.getAndIncrement();
        this.eventCallbacks.put(actionId, callback);

        this.generateActionEvent(actionId, functionName, args);
    }

    public void publishEvent(String eventName, Object... args) {
        WritableMap eventBody = Arguments.createMap();
        eventBody.putString("event", eventName);
        eventBody.putArray("args", Arguments.fromJavaArgs(args));
        this.getEventEmitter().emit("publishEvent", eventBody);
    }

    @ReactMethod
    public void pushEvent(final String event, final ReadableArray args) {
        if (forwardedEvents.containsKey(event)) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    bus.post(forwardedEvents.get(event).messageForEvent(args));
                }
            });
        } else {
            Log.w("JSBridge", "Unknown event received " + event);
        }
    }

    @ReactMethod
    public void registerAction(String actionName) {
        this.registeredActions.add(actionName);

        synchronized (this.awaitingRegistration) {
            if (this.awaitingRegistration.containsKey(actionName)) {
                final List<WaitingAction> waitingList = this.awaitingRegistration.get(actionName);
                this.callbackExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (WaitingAction action : waitingList) {
                            JSBridge.this.callAction(action.actionName, action.callback, action.args);
                        }
                    }
                });
                this.awaitingRegistration.remove(actionName);
            }
        }
    }

    @ReactMethod
    public void replyToAction(final int actionId, final ReadableMap result) {
        final FutureResult res = waitingForResults.get(actionId);
        if (res != null) {
            synchronized (res) {
                res.setResult(result);
                res.notifyAll();
            }
        } else if (this.eventCallbacks.containsKey(actionId)) {
            final Callback cb = this.eventCallbacks.get(actionId);
            // if cb is a noop we can skip the callback entirely
            if (!(cb instanceof NoopCallback)) {
                this.callbackExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        cb.callback(result);
                    }
                });
            }
            this.eventCallbacks.remove(actionId);
        }
    }

    private WritableMap createCallActionMessageBody(int actionId, String functionName, Object... args) {
        final WritableMap eventBody = Arguments.createMap();
        eventBody.putInt("id", actionId);
        eventBody.putString("action", functionName);
        eventBody.putArray("args", Arguments.fromJavaArgs(args));
        return eventBody;
    }

    private void generateActionEvent(int actionId, String functionName, Object... args) {
        this.getEventEmitter().emit("callAction", this.createCallActionMessageBody(actionId, functionName, args));
    }

}
