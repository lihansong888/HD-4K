package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.databinding.ActivitySettingCustomBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.base.BaseActivity;

public class SettingCustomActivity extends BaseActivity {

    private ActivitySettingCustomBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingCustomActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingCustomBinding.inflate(getLayoutInflater());
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected void initView() {
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        mBinding.incognito.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.homeHistory.setOnClickListener(this::setHomeHistory);
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private void setHomeHistory(View view) {
        Setting.putHomeHistory(!Setting.isHomeHistory());
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        RefreshEvent.history();
    }
}
