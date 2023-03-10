package me.leoletto.caller
import  androidx.core.content.ContextCompat.getSystemService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.time.Duration
import java.time.ZonedDateTime
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
enum class CallType {
    INCOMING, OUTGOING,INCOMING_ENDED,MISSED_CALL,OUTGOING_ENDED;
}

class CallerPhoneStateListener internal constructor(
    private val context: Context,
    private val intent: Intent,
    private val flutterLoader: FlutterLoader
) : PhoneStateListener() {

    private var sBackgroundFlutterEngine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var callbackHandler: Long? = null
    private var callbackHandlerUser: Long? = null

    private var time: ZonedDateTime? = null
    private var callType: CallType? = null
    private var previousState: Int? = null

    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                val duration = Duration.between(time ?: ZonedDateTime.now(), ZonedDateTime.now())

                if (previousState == TelephonyManager.CALL_STATE_OFFHOOK && callType == CallType.INCOMING) {
                    // Incoming call ended
                    Log.d(CallerPlugin.PLUGIN_NAME, "Phone State event IDLE (INCOMING ENDED) with number - $incomingNumber")
                    notifyFlutterEngine(CallType.INCOMING_ENDED, duration.toMillis() / 1000, incomingNumber!!)
                }
                if (previousState == TelephonyManager.CALL_STATE_RINGING && callType == CallType.INCOMING) {
                    // Missed call
                    Log.d(CallerPlugin.PLUGIN_NAME, "Phone State event IDLE (MISSED CALL) with number - $incomingNumber")
                    notifyFlutterEngine(CallType.MISSED_CALL, 0, incomingNumber!!)
                }
                else if(callType == CallType.OUTGOING) {
                    // Outgoing call ended
                    Log.d(CallerPlugin.PLUGIN_NAME, "Phone State event IDLE (OUTGOING ENDED) with number - $incomingNumber")
                    notifyFlutterEngine(CallType.OUTGOING_ENDED, duration.toMillis() / 1000, incomingNumber!!)
                }

                callType = null
                previousState = TelephonyManager.CALL_STATE_IDLE
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(CallerPlugin.PLUGIN_NAME, "Phone State event STATE_OFFHOOK")
                // Phone didn't ring, so this is an outgoing call
                if (callType == null)
                    callType = CallType.OUTGOING

                // Get current time to use later to calculate the duration of the call
                time = ZonedDateTime.now()
                previousState = TelephonyManager.CALL_STATE_OFFHOOK
                notifyFlutterEngine(CallType.OUTGOING, 0, incomingNumber!!)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // INCOMING_CALL
                Log.d(CallerPlugin.PLUGIN_NAME, "Phone State event PHONE_RINGING number: $incomingNumber")
                callType = CallType.INCOMING
                previousState = TelephonyManager.CALL_STATE_RINGING
                notifyFlutterEngine(CallType.INCOMING, 0, incomingNumber!!)
            }
        }
    }

    private fun notifyFlutterEngine(type: CallType, duration: Long, number: String){
        val arguments = ArrayList<Any?>()


            callbackHandler = context.getSharedPreferences(
                CallerPlugin.PLUGIN_NAME,
                Context.MODE_PRIVATE
            ).getLong(CallerPlugin.CALLBACK_SHAREDPREFERENCES_KEY, 0)
            callbackHandlerUser = context.getSharedPreferences(
                CallerPlugin.PLUGIN_NAME,
                Context.MODE_PRIVATE
            ).getLong(CallerPlugin.CALLBACK_USER_SHAREDPREFERENCES_KEY, 0)
            if (callbackHandler == 0L || callbackHandlerUser == 0L) {
                Log.e(CallerPlugin.PLUGIN_NAME, "Fatal: No callback registered")
                return
            }
            Log.d(CallerPlugin.PLUGIN_NAME, "Found callback handler $callbackHandler")
            Log.d(CallerPlugin.PLUGIN_NAME, "Found user callback handler $callbackHandlerUser")
try {
    // Retrieve the actual callback information needed to invoke it.
    val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandler!!)
    if (callbackInfo == null) {
        Log.e(CallerPlugin.PLUGIN_NAME, "Fatal: failed to find callback")
        return
    }
    sBackgroundFlutterEngine = FlutterEngine(context)
    val args = DartCallback(
        context.assets,
        flutterLoader.findAppBundlePath(),
        callbackInfo
    )

    // Start running callback dispatcher code in our background FlutterEngine instance.
    sBackgroundFlutterEngine!!.dartExecutor.executeDartCallback(args)

    // Create the MethodChannel used to communicate between the callback
    // dispatcher and this instance.
    channel = MethodChannel(
        sBackgroundFlutterEngine!!.dartExecutor.binaryMessenger,
        CallerPlugin.PLUGIN_NAME + "_background"
    )
     arguments.add(callbackHandler)
     arguments.add(callbackHandlerUser)
     arguments.add(type.toString())
     arguments.add(duration)
     arguments.add(number)
    Log.e(CallerPlugin.PLUGIN_NAME, arguments.toString())
    buildNotification(number)

    channel!!.invokeMethod("call", arguments)
     } catch(e: Exception){
      Log.e(CallerPlugin.PLUGIN_NAME, e.toString())
      }
    }

    private fun buildNotification( phone: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "my_channel_id",
                "My Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager: NotificationManager? =
                getSystemService(context, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        Log.i(TAG, "building notification")
        // Next, create a notification
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(
            context,
            "my_channel_id"
        )
            .setSmallIcon(R.drawable.notification_icon_background)
            .setContentTitle("Call Notification")
            .setContentText(phone)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

// Finally, show the notification
        Log.i(TAG, "show notification")
        val notificationManager: NotificationManagerCompat =
            NotificationManagerCompat.from(context)
        notificationManager.notify(0, builder.build())
        Log.i(TAG, "show notification")
    }
}