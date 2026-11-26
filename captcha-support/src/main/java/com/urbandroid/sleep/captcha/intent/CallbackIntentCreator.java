package com.urbandroid.sleep.captcha.intent;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;

import com.urbandroid.sleep.captcha.annotation.SleepOperation;

public interface CallbackIntentCreator {

    Intent createSolvedIntent(@NonNull Context context, @SleepOperation String operation);
    Intent createUnsolvedIntent(@NonNull Context context, @SleepOperation String operation);

}
