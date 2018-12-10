/*
Copyright 2018 Square Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package sqip.flutter.internal;

import android.app.Activity;
import android.content.Intent;
import sqip.Callback;
import sqip.CardDetails;
import sqip.CardEntry;
import sqip.CardEntryActivityCommand;
import sqip.CardEntryActivityResult;
import sqip.CardNonceBackgroundHandler;
import sqip.flutter.internal.converter.CardConverter;
import sqip.flutter.internal.converter.CardDetailsConverter;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;

final public class CardEntryModule {

  private final Activity currentActivity;
  private final CardDetailsConverter cardDetailsConverter;
  private final AtomicReference<CardEntryActivityCommand> reference;
  private volatile CountDownLatch countDownLatch;

  public CardEntryModule(PluginRegistry.Registrar registrar, final MethodChannel channel) {
    currentActivity = registrar.activity();
    cardDetailsConverter = new CardDetailsConverter(new CardConverter());
    reference = new AtomicReference<>();

    registrar.addActivityResultListener(new PluginRegistry.ActivityResultListener() {
      @Override public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        CardEntry.handleActivityResult(data, new Callback<CardEntryActivityResult>() {
          @Override public void onResult(CardEntryActivityResult cardEntryActivityResult) {
            if (cardEntryActivityResult.isCanceled()) {
              channel.invokeMethod("cardEntryDidCancel", null);
            } else if (cardEntryActivityResult.isSuccess()) {
              channel.invokeMethod("cardEntryComplete", null);
            }
          }
        });
        return false;
      }
    });

    CardEntry.setCardNonceBackgroundHandler(new CardNonceBackgroundHandler() {
      @NotNull @Override
      public CardEntryActivityCommand handleEnteredCardInBackground(CardDetails cardDetails) {
        Map<String, Object> mapToReturn = cardDetailsConverter.toMapObject(cardDetails);
        countDownLatch = new CountDownLatch(1);
        channel.invokeMethod("cardEntryDidObtainCardDetails", mapToReturn);
        try {
          // completeCardEntry or showCardNonceProcessingError must be called,
          // otherwise the thread will be leaked.
          countDownLatch.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }

        return reference.get();
      }
    });
  }

  public void startCardEntryFlow(MethodChannel.Result result) {
    CardEntry.startCardEntryActivity(currentActivity);
    result.success(null);
  }

  public void completeCardEntry(MethodChannel.Result result) {
    reference.set(new CardEntryActivityCommand.Finish());
    countDownLatch.countDown();
    result.success(null);
  }

  public void showCardNonceProcessingError(MethodChannel.Result result, String errorMessage) {
    reference.set(new CardEntryActivityCommand.ShowError(errorMessage));
    countDownLatch.countDown();
    result.success(null);
  }
}