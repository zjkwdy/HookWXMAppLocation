package cc.hakurei.wxhooklocation;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import androidx.core.app.ActivityCompat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.HashSet;
import java.util.Set;

public class HookMain implements IXposedHookLoadPackage {
    private static final Set<Class<?>> hookedClasses = new HashSet<>();

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.tencent.mm")) return;

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                ClassLoader classLoader = context.getClassLoader();

                try {
                    Class<?> clazz = classLoader.loadClass("com.tencent.map.geolocation.sapp.TencentLocationManager");
                    XposedBridge.log("[WxLocationHook] TencentLocationManager: " + clazz.toString());
                    hookTencentLocationManager(clazz,context);
                } catch (Exception e) {
                    XposedBridge.log("[WxLocationHook] Error in attach: " + e.getMessage());
                }
            }
        });
    }

    private void hookTencentLocationManager(Class<?> clazz,Context context){
        if (hookedClasses.contains(clazz)) return;
        hookedClasses.add(clazz);
        XposedHelpers.findAndHookMethod(clazz, "getInstance", Context.class, new XC_MethodHook() {

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedBridge.log("[WxLocationHook] TencentLocationManager.getInstance called");
                Object tlmi = param.getResult();
                Class<?> proxyClazz = (Class<?>) XposedHelpers.getObjectField(tlmi, "mProxyClass");
                Object proxyObj = (Object) XposedHelpers.getObjectField(tlmi, "mProxyObj");

                hookRequestLocation(proxyClazz, context);
                hookRequestLocation(proxyObj.getClass(), context);
                hookRequestLocation(tlmi.getClass(), context);
            }
        });
    }
    private void hookRequestLocation(Class<?> proxyClazz, Context context) {
        if (hookedClasses.contains(proxyClazz)) return;
        hookedClasses.add(proxyClazz);
        XposedBridge.log("[WxLocationHook] Hooking proxy class: " + proxyClazz.toString());

        XposedHelpers.findAndHookMethod(proxyClazz, "requestSingleFreshLocation",
                "com.tencent.map.geolocation.sapp.TencentLocationRequest",
                "com.tencent.map.geolocation.sapp.TencentLocationListener",
                "android.os.Looper", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[1];
                        if (listener == null) return;
                        hookLocationListener(listener.getClass(), context);
                    }
                });
        XposedHelpers.findAndHookMethod(proxyClazz, "requestLocationUpdates",
                "com.tencent.map.geolocation.sapp.TencentLocationRequest",
                "com.tencent.map.geolocation.sapp.TencentLocationListener",
                "android.os.Looper", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[1];
                        if (listener == null) return;
                        hookLocationListener(listener.getClass(), context);
                    }
                });
    }

    private void hookLocationListener(Class<?> listenerClass, Context context) {
        if (hookedClasses.contains(listenerClass)) return;
        hookedClasses.add(listenerClass);
        XposedBridge.log("[WxLocationHook] Hooking listener: " + listenerClass.toString());

        XposedHelpers.findAndHookMethod(listenerClass, "onLocationChanged",
                "com.tencent.map.geolocation.sapp.TencentLocation",
                int.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object locationObj = param.args[0];
                        if (locationObj == null) return;
                        hookTencentLocation(locationObj.getClass(), context);
                    }
                });
    }

    private void hookTencentLocation(Class<?> locationClass, Context context) {
        if (hookedClasses.contains(locationClass)) return;
        hookedClasses.add(locationClass);
        XposedBridge.log("[WxLocationHook] Hooking location: " + locationClass.toString());

        XposedHelpers.findAndHookMethod(locationClass, "getLatitude", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Location systemLocation = getSystemLocation(context);
                if (systemLocation != null) {
                    param.setResult(systemLocation.getLatitude());
                }
            }
        });

        XposedHelpers.findAndHookMethod(locationClass, "getLongitude", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Location systemLocation = getSystemLocation(context);
                if (systemLocation != null) {
                    param.setResult(systemLocation.getLongitude());
                }
            }
        });
    }

    private static Location getSystemLocation(Context context) {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return null;

            Location location = null;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return null;
                }
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            return location;
        } catch (Exception e) {
            return null;
        }
    }
}