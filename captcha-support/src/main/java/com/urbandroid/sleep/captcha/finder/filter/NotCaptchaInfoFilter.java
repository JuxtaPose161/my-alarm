package com.urbandroid.sleep.captcha.finder.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.urbandroid.sleep.captcha.domain.CaptchaGroup;
import com.urbandroid.sleep.captcha.domain.CaptchaInfo;
import com.urbandroid.sleep.captcha.finder.CaptchaInfoFilter;

public class NotCaptchaInfoFilter implements CaptchaInfoFilter {

    private final CaptchaInfoFilter filter;

    public NotCaptchaInfoFilter(final @NonNull CaptchaInfoFilter filter) {
        this.filter = filter;
    }

    @Override
    public boolean apply(@Nullable CaptchaGroup group, @Nullable CaptchaInfo captcha) {
        return !filter.apply(group, captcha);
    }

}
