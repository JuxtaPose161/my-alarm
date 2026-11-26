package com.urbandroid.sleep.captcha.domain;

import androidx.annotation.NonNull;

import java.util.List;

public interface CaptchaGroup {

    @NonNull
    String getId();

    @NonNull
    String getLabel();

    @NonNull
    List<CaptchaInfo> getCaptchaInfos();

    CaptchaGroup add(@NonNull CaptchaInfo captchaInfo);

    boolean isExternalStorage();

}
