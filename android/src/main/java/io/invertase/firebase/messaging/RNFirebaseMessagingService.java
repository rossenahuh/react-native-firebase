package io.invertase.firebase.messaging;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;

import androidx.core.app.JobIntentService;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.os.Bundle;
import android.util.Log;

import com.facebook.react.HeadlessJsTaskService;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.quantumgraph.sdk.NotificationJobIntentService;
import com.quantumgraph.sdk.QG;


import java.util.Map;

import io.invertase.firebase.Utils;

public class RNFirebaseMessagingService extends FirebaseMessagingService {
  private static final String TAG = "RNFMessagingService";

  public static final String MESSAGE_EVENT = "messaging-message";
  public static final String NEW_TOKEN_EVENT = "messaging-token-refresh";
  public static final String REMOTE_NOTIFICATION_EVENT = "notifications-remote-notification";

  @Override
  public void onNewToken(String token) {
    Log.d(TAG, "onNewToken event received");
    QG.logFcmId(getApplicationContext());
    Intent newTokenEvent = new Intent(NEW_TOKEN_EVENT);
    LocalBroadcastManager
      .getInstance(this)
      .sendBroadcast(newTokenEvent);
  }

  @Override
  public void onMessageReceived(RemoteMessage message) {
    Log.d(TAG, "onMessageReceived event received");

    // Solving Android uninstall push notification not being silent
    if(message.getData().containsKey("af-uinstall-tracking")){
      return;
    }

    // AIQUA message handle
    String from = message.getFrom();
    Map data = message.getData();

  	// only send notifications from AIQUA to AIQUA Sdk
    if (data.containsKey("message") && QG.isQGMessage(data.get("message").toString())) {
        Bundle qgData = new Bundle();
        qgData.putString("message", data.get("message").toString());
        Context context = getApplicationContext();
        if (from == null || context == null) {
            return;
        }
        Intent intent = new Intent(context, NotificationJobIntentService.class);
        intent.setAction("QG");
        intent.putExtras(qgData);
        JobIntentService.enqueueWork(context, NotificationJobIntentService.class, 1000, intent);

        // aiqua notification data가 js handler로 들어오게끔 처리
        Intent notificationEvent = new Intent(REMOTE_NOTIFICATION_EVENT);
        notificationEvent.putExtra("notification", message);
        LocalBroadcastManager
          .getInstance(this)
          .sendBroadcast(notificationEvent);

        return;
    } else {
    	// handle fcm message from other services
      if (message.getNotification() != null) {
        // It's a notification, pass to the Notifications module
        Intent notificationEvent = new Intent(REMOTE_NOTIFICATION_EVENT);
        notificationEvent.putExtra("notification", message);

        // Broadcast it to the (foreground) RN Application
        LocalBroadcastManager
          .getInstance(this)
          .sendBroadcast(notificationEvent);
      } else {
        // It's a data message
        // If the app is in the foreground we send it to the Messaging module
        if (Utils.isAppInForeground(this.getApplicationContext())) {
          Intent messagingEvent = new Intent(MESSAGE_EVENT);
          messagingEvent.putExtra("message", message);
          // Broadcast it so it is only available to the RN Application
          LocalBroadcastManager
            .getInstance(this)
            .sendBroadcast(messagingEvent);
        } else {
          try {
            // If the app is in the background we send it to the Headless JS Service
            Intent headlessIntent = new Intent(
              this.getApplicationContext(),
              RNFirebaseBackgroundMessagingService.class
            );
            headlessIntent.putExtra("message", message);
            ComponentName name = this.getApplicationContext().startService(headlessIntent);
            if (name != null) {
              HeadlessJsTaskService.acquireWakeLockNow(this.getApplicationContext());
            }
          } catch (IllegalStateException ex) {
            Log.e(
              TAG,
              "Background messages will only work if the message priority is set to 'high'",
              ex
            );
          }
        }
      }
    }
  }
}