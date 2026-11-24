package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.ApiConfig;
import com.fongmi.android.tv.api.LiveConfig;
import com.fongmi.android.tv.api.WallConfig;
import com.fongmi.android.tv.auth.DeviceAuthManager;
import com.fongmi.android.tv.auth.FusionApi;
import com.fongmi.android.tv.ui.custom.dialog.NoticeDialog;
import com.github.catvod.utils.Prefers;
import android.text.TextUtils;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.custom.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Utils;
import com.github.catvod.utils.Util;
import com.google.common.collect.Lists;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private HistoryPresenter mPresenter;
    private ArrayObjectAdapter mAdapter;
    private SiteViewModel mViewModel;
    private boolean confirm;
    private DeviceAuthManager mDeviceAuth;
    private boolean mDeviceAuthorized;
    private boolean mAppInitialized;
    private boolean mLoading;
    private boolean mReloadPending;
    private Result mResult;
    private Clock mClock;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mDeviceAuthorized) checkAction(intent);
    }

    @Override
    protected void initView() {
        mClock = Clock.create(mBinding.time).format("MM/dd HH:mm:ss");
        mBinding.progressLayout.showProgress();
        setRecyclerView();
        setViewModel();
        setAdapter();
        mDeviceAuth = DeviceAuthManager.create(this);
        mDeviceAuth.start(new DeviceAuthManager.Callback() {
            @Override
            public void onApproved() {
                if (isFinishing()) return;
                mDeviceAuthorized = true;
                if (!mAppInitialized) {
                    Server.get().start();
                    mAppInitialized = true;
                }
                initConfig();
            }

            @Override
            public void onRevoked() {
                mDeviceAuthorized = false;
                stopMarquee();
            }

            @Override
            public void onChanged(FusionApi.Info info, boolean configChanged) {
                if (isFinishing()) return;
                showMarquee(info);
                showPopup(info.popup);
                if (configChanged) initConfig();
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                mBinding.toolbar.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, Uri.parse(intent.getStringExtra(Intent.EXTRA_TEXT)));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            if ("text/plain".equals(intent.getType()) || Util.path(intent.getData()).endsWith(".m3u")) {
                loadLive("file:/" + FileChooser.getPathFromUri(this, intent.getData()));
            } else {
                VideoActivity.push(this, intent.getData());
            }
        }
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.result.observe(this, result -> {
            mAdapter.remove("progress");
            addVideo(mResult = result);
        });
    }

    private void setAdapter() {
        mAdapter.add(getFuncRow());
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
    }

    /** 配置只从 FusionTV 后台取：地址由设备令牌决定，后台按分组过滤后返回。 */
    private void initConfig() {
        if (mLoading) {
            mReloadPending = true;
            return;
        }
        String url = FusionApi.configUrl();
        if (url.isEmpty()) return;
        mLoading = true;
        WallConfig.get().init();
        LiveConfig.get().init().config(Config.find(url, "FusionTV", 1));
        ApiConfig.get().init();
        ApiConfig.load(Config.find(url, "FusionTV", 0), getCallback());
    }

    private void finishLoad() {
        mLoading = false;
        if (mReloadPending) {
            mReloadPending = false;
            initConfig();
        }
    }

    private final java.util.Set<String> mShownKeys = new java.util.HashSet<>();
    private android.animation.ValueAnimator mMarqueeAnim;
    private String mMarqueeKey = "";

    /** 本次打开应用内每条内容只处理一次；"每天几次"再按当天已显示次数限制。 */
    private boolean shouldShow(String key, String mode, int daily, String prefKey) {
        if (!mShownKeys.add(key)) return false;
        if (!"daily".equals(mode)) return true;
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        String day = Prefers.getString(prefKey + "_day");
        int count = today.equals(day) ? Prefers.getInt(prefKey + "_cnt", 0) : 0;
        if (count >= daily) return false;
        Prefers.put(prefKey + "_day", today);
        Prefers.put(prefKey + "_cnt", count + 1);
        return true;
    }

    private void showPopup(FusionApi.Popup popup) {
        if (popup == null) return;
        if ("once".equals(popup.mode)) {
            NoticeDialog.maybeShow(this, popup);
        } else if (shouldShow("pp|" + popup.version + "|" + popup.mode + "|" + popup.daily, popup.mode, popup.daily, "fusiontv_pp")) {
            NoticeDialog.showNow(this, popup);
        }
    }

    private void stopMarquee() {
        if (mMarqueeAnim != null) {
            android.animation.ValueAnimator anim = mMarqueeAnim;
            mMarqueeAnim = null;
            anim.cancel();
        }
        mMarqueeKey = "";
        mBinding.marqueeBox.setVisibility(View.GONE);
    }

    private void showMarquee(FusionApi.Info info) {
        String text = info.marquee;
        if (TextUtils.isEmpty(text)) {
            stopMarquee();
            return;
        }
        String key = text + "|" + info.marqueeMode + "|" + info.marqueeDaily + "|" + info.marqueeVersion;
        if (key.equals(mMarqueeKey)) return;
        stopMarquee();
        mMarqueeKey = key;
        android.view.ViewGroup.LayoutParams mlp = mBinding.marqueeBox.getLayoutParams();
        mlp.width = ResUtil.getScreenWidth() / 2;
        mBinding.marqueeBox.setLayoutParams(mlp);
        boolean always = "always".equals(info.marqueeMode);
        if (!always && !shouldShow("mq|" + key, info.marqueeMode, info.marqueeDaily, "fusiontv_mq")) return;
        mBinding.marquee.setEllipsize(null);
        mBinding.marquee.setText(text);
        mBinding.marqueeBox.setVisibility(View.VISIBLE);
        mBinding.marqueeBox.post(() -> startMarquee(always, 0));
    }

    private void startMarquee(boolean always, int retry) {
        if (isFinishing() || mBinding.marqueeBox.getVisibility() != View.VISIBLE) return;
        int boxWidth = mBinding.marqueeBox.getWidth();
        if (boxWidth <= 0) {
            if (retry < 20) mBinding.marqueeBox.postDelayed(() -> startMarquee(always, retry + 1), 100);
            return;
        }
        mBinding.marquee.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int textWidth = mBinding.marquee.getMeasuredWidth();
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) mBinding.marquee.getLayoutParams();
        lp.width = textWidth;
        mBinding.marquee.setLayoutParams(lp);
        float from = boxWidth;
        float to = -textWidth;
        long duration = (long) ((from - to) / (ResUtil.dp2px(90) / 1000f));
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(Math.max(duration, 3000));
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        if (always) anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.addUpdateListener(a -> mBinding.marquee.setTranslationX((float) a.getAnimatedValue()));
        if (!always) {
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    // 滚完一遍就收起（被取消时由 stopMarquee 处理）
                    if (mMarqueeAnim == animation) mBinding.marqueeBox.setVisibility(View.GONE);
                }
            });
        }
        mMarqueeAnim = anim;
        anim.start();
    }


    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                mBinding.progressLayout.showContent();
                if (mDeviceAuthorized) checkAction(getIntent());
                getHistory();
                getVideo();
                setFocus();
                finishLoad();
            }

            @Override
            public void error(String msg) {
                mBinding.progressLayout.showContent();
                mResult = Result.empty();
                Notify.show(msg);
                setFocus();
                finishLoad();
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.recycler.requestFocus();
        App.post(() -> mBinding.title.setFocusable(true), 500);
    }

    private void getVideo() {
        mResult = Result.empty();
        int index = getRecommendIndex();
        String home = ApiConfig.get().getHome().getName();
        mBinding.title.setText(home.isEmpty() ? ResUtil.getString(R.string.app_name) : home);
        if (mAdapter.size() > index) mAdapter.removeItems(index, mAdapter.size() - index);
        if (ApiConfig.get().getHome().getKey().isEmpty()) return;
        mViewModel.homeContent();
        mAdapter.add("progress");
    }

    private void addVideo(Result result) {
        for (List<Vod> items : Lists.partition(result.getList(), Product.getColumn())) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(new VodPresenter(this));
            adapter.setItems(items, null);
            mAdapter.add(new ListRow(adapter));
        }
    }

    private ListRow getFuncRow() {
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(new FuncPresenter(this));
        adapter.add(Func.create(R.string.home_vod));
        adapter.add(Func.create(R.string.home_live));
        adapter.add(Func.create(R.string.home_search));
        adapter.add(Func.create(R.string.home_keep));
        adapter.add(Func.create(R.string.home_setting));
        return new ListRow(adapter);
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        List<History> items = History.get();
        int historyIndex = getHistoryIndex();
        int recommendIndex = getRecommendIndex();
        boolean exist = recommendIndex - historyIndex == 2;
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if ((items.isEmpty() && exist) || (renew && exist)) mAdapter.removeItems(historyIndex, 1);
        if ((items.size() > 0 && !exist) || (renew && exist)) mAdapter.add(historyIndex, new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, null);
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        mAdapter.removeItems(getHistoryIndex(), 1);
        History.delete(ApiConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    private int getHistoryIndex() {
        for (int i = 0; i < mAdapter.size(); i++) if (mAdapter.get(i).equals(R.string.home_history)) return i + 1;
        return -1;
    }

    private int getRecommendIndex() {
        for (int i = 0; i < mAdapter.size(); i++) if (mAdapter.get(i).equals(R.string.home_recommend)) return i + 1;
        return -1;
    }

    private void setConfirm() {
        confirm = true;
        Notify.show(R.string.app_exit);
        App.post(() -> confirm = false, 5000);
    }

    @Override
    public void onItemClick(Func item) {
        switch (item.getResId()) {
            case R.string.home_vod:
                VodActivity.start(this, mResult.clear());
                break;
            case R.string.home_live:
                LiveActivity.start(this);
                break;
            case R.string.home_search:
                SearchActivity.start(this);
                break;
            case R.string.home_keep:
                KeepActivity.start(this);
                break;
            case R.string.home_push:
                PushActivity.start(this);
                break;
            case R.string.home_setting:
                SettingActivity.start(this);
                break;
        }
    }

    @Override
    public void onItemClick(Vod item) {
        VideoActivity.start(this, item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        CollectActivity.start(this, item.getVodName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create(this).show();
    }

    @Override
    public void setSite(Site item) {
        if (item.isLocked()) {
            com.fongmi.android.tv.auth.PurchaseDialog.showLocked(this, null);
            return;
        }
        ApiConfig.get().setHome(item);
        getVideo();
    }

    @Override
    public void onChanged() {
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        super.onRefreshEvent(event);
        switch (event.getType()) {
            case VIDEO:
                getVideo();
                break;
            case IMAGE:
                int index = getRecommendIndex();
                mAdapter.notifyArrayItemRangeChanged(index, mAdapter.size() - index);
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.getType()) {
            case SEARCH:
                CollectActivity.start(this, event.getText(), true);
                break;
            case PUSH:
                VideoActivity.push(this, event.getText(), true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (!mDeviceAuthorized) return;
        // 数据源只来自 FusionTV 后台：投屏过来的配置一律忽略，直接用本机当前配置播放
        VideoActivity.cast(this, event.getHistory().update(ApiConfig.getCid()));
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                RefreshEvent.history();
                RefreshEvent.video();
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (Utils.isMenuKey(event)) showDialog();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    @Override
    public void onBackPressed() {
        if (mBinding.progressLayout.isProgress()) {
            mBinding.progressLayout.showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else if (!confirm) {
            setConfirm();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDeviceAuth != null) mDeviceAuth.cancel();
        stopMarquee();
        WallConfig.get().clear();
        LiveConfig.get().clear();
        ApiConfig.get().clear();
        Server.get().stop();
        Source.get().exit();
    }
}