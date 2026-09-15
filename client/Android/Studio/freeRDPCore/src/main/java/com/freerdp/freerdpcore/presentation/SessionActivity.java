/*
   Android Session Activity

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.presentation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.RoundedCorner;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ConnectionReference;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.ClipboardManagerProxy;

public class SessionActivity extends AppCompatActivity
    implements LibFreeRDP.UIEventListener, ClipboardManagerProxy.OnClipboardChangedListener
{
	public static final String PARAM_CONNECTION_REFERENCE = "conRef";
	public static final String PARAM_INSTANCE = "instance";
	private static final String TAG = "FreeRDP.SessionActivity";
	static volatile SessionActivity activeSession;
	private Bitmap bitmap;
	private SessionState session;
	private SessionView sessionView;
	private TouchPointerView touchPointerView;

	private static final int REFRESH_SESSIONVIEW = 1;
	private static final int DISPLAY_TOAST = 2;
	private static final int GRAPHICS_CHANGED = 6;
	private static final int POINTER_SET = 7;
	private static final int REQUEST_MEDIA_PERMISSIONS = 100;

	private RailWindowManager railManager;

	private final Handler uiHandler = new Handler(Looper.getMainLooper()) {
		@Override public void handleMessage(Message msg)
		{
			switch (msg.what)
			{
				case GRAPHICS_CHANGED:
				{
					sessionView.onSurfaceChange(session);
					scrollView.requestLayout();
					break;
				}
				case REFRESH_SESSIONVIEW:
				{
					sessionView.invalidateRegion();
					break;
				}
				case DISPLAY_TOAST:
				{
					Toast errorToast = Toast.makeText(getApplicationContext(), msg.obj.toString(),
					                                  Toast.LENGTH_LONG);
					errorToast.show();
					break;
				}
				case POINTER_SET:
				{
					Bundle data = msg.getData();
					if (data != null && data.containsKey("pixels"))
					{
						int[] pixels = data.getIntArray("pixels");
						int width = data.getInt("width");
						int height = data.getInt("height");
						int hotX = data.getInt("hotX");
						int hotY = data.getInt("hotY");
						sessionView.setRemoteCursor(pixels, width, height, hotX, hotY);
						if (touchPointerView != null)
							touchPointerView.setRemoteCursor(pixels, width, height, hotX, hotY);
					}
					else
					{
						sessionView.setRemoteCursor(null, 0, 0, 0, 0);
						if (touchPointerView != null)
							touchPointerView.setRemoteCursor(null, 0, 0, 0, 0);
					}
					break;
				}
			}
		}
	};

	private int screen_width;
	private int screen_height;

	private BookmarkBase pendingConnectBookmark = null;
	private boolean connectCancelledByUser = false;
	private boolean sessionRunning = false;
	private long backPressedTime = 0;
	// handle shown right above the keyboard, used to pan the session view
	private View panHandle;
	// live network indicators (measured client side, shown in a corner of the session view)
	private android.widget.TextView netStatsView;
	private static final int NET_STATS_INTERVAL_MS = 2000;
	private long netStatsLastBytes = -1;
	private long netStatsLastTime = 0;
	private volatile int netStatsRttMs = -1;
	private String netStatsPingHost = null;
	private int netStatsPingTick = 0;
	private volatile boolean netStatsPingInFlight = false;
	private java.util.concurrent.ExecutorService netStatsExecutor = null;
	// frame counter of the remote session, incremented on every graphics update the server sends
	private final java.util.concurrent.atomic.AtomicLong netStatsFrames =
	    new java.util.concurrent.atomic.AtomicLong();
	private long netStatsLastFrames = -1;
	private long netStatsLastFrameTime = 0;
	// viewport height of the session scroll view, used to keep the visible centre
	private int lastViewportHeight = 0;

	private SessionViewModel sessionViewModel;
	private ScrollView2D scrollView;
	private ClipboardManagerProxy mClipboardManager;
	private SessionInputManager inputManager;
	private SessionDialogs dialogs;

	private FloatingToolbar floatingToolbar;

	void hideSystemBars()
	{
		boolean hideStatusBar = ApplicationSettingsActivity.getHideStatusBar(this);
		boolean hideNavBar = ApplicationSettingsActivity.getHideNavigationBar(this);

		if (inputManager != null && inputManager.isSoftInputActive())
		{
			// out of immersive mode while the IME is up, so the back gesture works right away
			hideNavBar = false;
		}

		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

		if (getSupportActionBar() != null)
			getSupportActionBar().hide();

		WindowInsetsControllerCompat controller =
		    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
		controller.setAppearanceLightStatusBars(false);
		controller.setAppearanceLightNavigationBars(false);

		getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarContrastEnforced(false);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			int toHide = 0;
			int toShow = 0;
			if (hideStatusBar)
				toHide |= WindowInsetsCompat.Type.statusBars();
			else
				toShow |= WindowInsetsCompat.Type.statusBars();

			if (hideNavBar)
				toHide |= WindowInsetsCompat.Type.navigationBars();
			else
				toShow |= WindowInsetsCompat.Type.navigationBars();

			if (toHide != 0)
			{
				// whatever stays hidden keeps immersive behaviour; BEHAVIOR_DEFAULT would let any
				// tap on the session pull the bar back in
				controller.setSystemBarsBehavior(
				    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
				controller.hide(toHide);
			}

			if (toShow != 0)
			{
				controller.show(toShow);
			}
		}
		else
		{
			// API 29: layout flags must be set explicitly to keep drawing behind system bars.
			int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
			            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
			if (hideStatusBar)
				flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
			if (hideNavBar)
				flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
			if ((flags & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) !=
			    0)
				flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

			getWindow().getDecorView().setSystemUiVisibility(flags);
		}

		WindowManager.LayoutParams lp = getWindow().getAttributes();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			lp.layoutInDisplayCutoutMode =
			    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
		else
			lp.layoutInDisplayCutoutMode =
			    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		getWindow().setAttributes(lp);
	}

	@Override public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		hideSystemBars();

		this.setContentView(R.layout.session);

		Log.v(TAG, "Session.onCreate");

		// ATTENTION: We use the onGlobalLayout notification to start our
		// session.
		// This is because only then we can know the exact size of our session
		// when using fit screen
		// accounting for any status bars etc. that Android might throws on us.
		// A bit weird looking
		// but this is the only way ...
		final View activityRootView = findViewById(R.id.session_root_view);
		activityRootView.setFitsSystemWindows(false);
		ViewCompat.setOnApplyWindowInsetsListener(activityRootView,
		                                          (v, insets) -> onWindowInsetsChanged(v, insets));
		activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(
		    new OnGlobalLayoutListener() {
			    @Override public void onGlobalLayout()
			    {
				    screen_width = scrollView.getWidth() - scrollView.getPaddingLeft() -
				                   scrollView.getPaddingRight();
				    screen_height = scrollView.getHeight() - scrollView.getPaddingTop() -
				                    scrollView.getPaddingBottom();

				    // start session
				    if (!sessionRunning && getIntent() != null)
				    {
					    processIntent(getIntent());
					    sessionRunning = true;
				    }
			    }
		    });

		sessionView = findViewById(R.id.sessionView);
		sessionView.requestFocus();

		touchPointerView = findViewById(R.id.touchPointerView);

		floatingToolbar = new FloatingToolbar(this, new FloatingToolbar.Listener() {
			@Override public void onToggleTouchPointer()
			{
				if (inputManager != null)
					inputManager.toggleTouchPointer();
			}
			@Override public void onToggleKeyboard()
			{
				if (inputManager != null)
					inputManager.toggleKeyboard();
			}
			@Override public void onDisconnect()
			{
				disconnectSession();
			}
			@Override public void onQuality()
			{
				showQualityDialog();
			}
		});

		ExtendedKeyboardView keyboard = findViewById(R.id.extended_keyboard);

		scrollView = findViewById(R.id.sessionScrollView);
		scrollView.setScrollViewListener(null);
		panHandle = findViewById(R.id.session_pan_handle);
		setupPanHandle();
		railManager = new RailWindowManager(this, findViewById(R.id.railContainer), sessionView);
		sessionViewModel = new ViewModelProvider(this).get(SessionViewModel.class);
		sessionViewModel.getState().observe(this, this::onConnectionStateChanged);

		dialogs = new SessionDialogs(this, new SessionDialogs.OnUserCancelListener() {
			@Override public void onUserCancel()
			{
				connectCancelledByUser = true;
			}
		});

		// Wire up the input manager (instance is attached later in bindSession()).
		inputManager =
		    new SessionInputManager(this, scrollView, sessionView, touchPointerView, keyboard);
		sessionView.setSessionViewListener(inputManager);
		touchPointerView.setTouchPointerListener(inputManager);
		sessionView.setScaleGestureDetector(
		    new ScaleGestureDetector(this, inputManager.getPinchZoomListener()));

		mClipboardManager = ClipboardManagerProxy.getClipboardManager(this);
		mClipboardManager.addClipboardChangedListener(this);

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed()
			{
				handleBackPressed();
			}
		});

		hideSystemBars();
	}

	@Override public void onWindowFocusChanged(boolean hasFocus)
	{
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus)
		{
			hideSystemBars();
			mClipboardManager.getPrimaryClipManually();
		}
	}

	@Override protected void onStart()
	{
		super.onStart();
		Log.v(TAG, "Session.onStart");
	}

	@Override protected void onRestart()
	{
		super.onRestart();
		Log.v(TAG, "Session.onRestart");
	}

	@Override protected void onResume()
	{
		super.onResume();
		Log.v(TAG, "Session.onResume");
		activeSession = this;
	}

	@Override protected void onPause()
	{
		super.onPause();
		Log.v(TAG, "Session.onPause");
		if (activeSession == this)
			activeSession = null;
		// hide any visible keyboards
		inputManager.hideKeyboards();
	}

	@Override protected void onStop()
	{
		super.onStop();
		Log.v(TAG, "Session.onStop");
	}

	@Override protected void onDestroy()
	{
		if (connectThread != null)
		{
			connectThread.interrupt();
		}
		if (netStatsExecutor != null)
		{
			netStatsExecutor.shutdownNow();
			netStatsExecutor = null;
		}
		super.onDestroy();
		Log.v(TAG, "Session.onDestroy");

		// Cancel running disconnect timers.
		GlobalApp.cancelDisconnectTimer();

		// Disconnect only this activity's session.
		if (session != null)
			LibFreeRDP.disconnect(session.getInstance());

		// unregister freerdp session listener
		sessionViewModel.unregister();

		// remove clipboard listener
		mClipboardManager.removeClipboardboardChangedListener(this);

		// free session
		GlobalApp.freeSession(session.getInstance());

		session = null;
	}

	@Override public void onConfigurationChanged(Configuration newConfig)
	{
		super.onConfigurationChanged(newConfig);

		hideSystemBars();

		// screen_width/screen_height will be updated by the next onGlobalLayout callback;
		if (session != null && session.getBookmark() != null &&
		    session.getBookmark().getActiveScreenSettings().isFitScreen())
		{
			scrollView.post(() -> {
				if (screen_width > 0 && screen_height > 0)
					LibFreeRDP.sendMonitorLayout(session.getInstance(), screen_width,
					                             screen_height);
			});
		}
	}

	private WindowInsetsCompat onWindowInsetsChanged(View rootView, WindowInsetsCompat windowInsets)
	{
		boolean fitSafeArea = ApplicationSettingsActivity.getFitRoundedCorners(this);
		boolean hideStatusBar = ApplicationSettingsActivity.getHideStatusBar(this);
		boolean hideNavBar = ApplicationSettingsActivity.getHideNavigationBar(this);

		int insetsTop = windowInsets
		                    .getInsets(WindowInsetsCompat.Type.statusBars() |
		                               WindowInsetsCompat.Type.displayCutout())
		                    .top;
		rootView.setPadding(0, hideStatusBar ? 0 : insetsTop, 0, 0);
		Insets navInsets = hideNavBar
		                       ? Insets.NONE
		                       : windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
		if (floatingToolbar != null)
			floatingToolbar.setInsets(navInsets.left, hideStatusBar ? 0 : insetsTop,
			                          navInsets.right, navInsets.bottom);

		int safeLeft = 0, safeTop = 0, safeRight = 0, safeBottom = 0;
		if (fitSafeArea && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			WindowInsets platformInsets = windowInsets.toWindowInsets();
			if (platformInsets != null)
			{
				boolean landscape = getResources().getConfiguration().orientation ==
				                    Configuration.ORIENTATION_LANDSCAPE;

				int radTL = cornerRadius(platformInsets, RoundedCorner.POSITION_TOP_LEFT);
				int radBL = cornerRadius(platformInsets, RoundedCorner.POSITION_BOTTOM_LEFT);
				int radTR = cornerRadius(platformInsets, RoundedCorner.POSITION_TOP_RIGHT);
				int radBR = cornerRadius(platformInsets, RoundedCorner.POSITION_BOTTOM_RIGHT);

				if (landscape)
				{
					safeLeft = Math.max(0, Math.max(radTL, radBL) - rootView.getPaddingLeft());
					safeRight = Math.max(0, Math.max(radTR, radBR) - rootView.getPaddingRight());
				}
				else
				{
					safeTop = Math.max(0, Math.max(radTL, radTR) - rootView.getPaddingTop());
					safeBottom = Math.max(0, Math.max(radBL, radBR) - rootView.getPaddingBottom());
				}
			}
		}

		int imeBottom = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;

		// the only reliable account of whether the IME is really on screen: it lets the input
		// manager tell an external dismissal from a show request that has not animated in yet
		if (inputManager != null)
			inputManager.onImeVisibilityChanged(imeBottom > 0);

		View extKeyboard = findViewById(R.id.extended_keyboard);
		if (extKeyboard instanceof ExtendedKeyboardView)
		{
			// imeBottom already covers the nav bar, so don't pad for it twice
			int kbdBottom = imeBottom > 0 ? 0 : navInsets.bottom;
			((ExtendedKeyboardView)extKeyboard)
			    .setInsets(Math.max(navInsets.left, safeLeft), Math.max(navInsets.right, safeRight),
			               kbdBottom);
		}

		// the keyboard reserves the bottom inset through its own margin, so the scroll view
		// above it must not pad for the nav bar again
		boolean kbdVisible = extKeyboard != null && extKeyboard.getVisibility() == View.VISIBLE;
		int scrollBottom = kbdVisible ? 0 : Math.max(safeBottom, navInsets.bottom);
		scrollView.setPadding(Math.max(safeLeft, navInsets.left), safeTop,
		                      Math.max(safeRight, navInsets.right), scrollBottom);

		// insets are consumed here, so lift the extended keyboard above the IME
		if (extKeyboard != null)
		{
			ViewGroup.MarginLayoutParams lp =
			    (ViewGroup.MarginLayoutParams)extKeyboard.getLayoutParams();
			if (lp.bottomMargin != imeBottom)
			{
				lp.bottomMargin = imeBottom;
				extKeyboard.setLayoutParams(lp);
			}
		}

		// the pan handle floats right above the keyboard (extended bar and/or IME) and is
		// only visible while a keyboard is up
		if (panHandle != null)
		{
			final boolean keyboardUp = kbdVisible || imeBottom > 0;
			final View kbd = extKeyboard;
			scrollView.post(() -> {
				int kbdHeight = 0;
				if (kbd != null && kbd.getVisibility() == View.VISIBLE)
					kbdHeight = kbd.getHeight();

				final int margin = imeBottom + Math.max(0, kbdHeight);
				ViewGroup.MarginLayoutParams lp =
				    (ViewGroup.MarginLayoutParams)panHandle.getLayoutParams();
				if (lp.bottomMargin != margin)
				{
					lp.bottomMargin = margin;
					panHandle.setLayoutParams(lp);
				}
				// only offer the handle when there is actually something to pan to
				final View content = scrollView.getChildAt(0);
				final boolean canPan =
				    content != null && (content.getWidth() > scrollView.getWidth() ||
				                        content.getHeight() > scrollView.getHeight());
				panHandle.setVisibility(keyboardUp && canPan ? View.VISIBLE : View.GONE);

				// keep the visible centre of the session while the keyboard shrinks it
				final int height = scrollView.getHeight();
				if (keyboardUp && lastViewportHeight > 0 && height > 0 &&
				    height != lastViewportHeight)
				{
					final int delta = lastViewportHeight - height;
					if (delta > 0)
						scrollView.scrollBy(0, delta / 2);
				}
				if (height > 0)
					lastViewportHeight = height;
			});
		}

		return WindowInsetsCompat.CONSUMED;
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private static int cornerRadius(WindowInsets insets, int position)
	{
		RoundedCorner corner = insets.getRoundedCorner(position);
		return (corner != null) ? corner.getRadius() : 0;
	}

	private void processIntent(Intent intent)
	{
		// get either session instance or create one from a bookmark/uri
		Bundle bundle = intent.getExtras();
		Uri openUri = intent.getData();
		if (openUri != null)
		{
			// Launched from URI, e.g:
			// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-
			connect(openUri);
		}
		else if (bundle.containsKey(PARAM_INSTANCE))
		{
			int inst = bundle.getInt(PARAM_INSTANCE);
			session = GlobalApp.getSession(inst);
			bitmap = session.getSurface().getBitmap();
			bindSession();
		}
		else if (bundle.containsKey(PARAM_CONNECTION_REFERENCE))
		{
			String refStr = bundle.getString(PARAM_CONNECTION_REFERENCE);
			if (ConnectionReference.isHostnameReference(refStr))
			{
				BookmarkBase bookmark = new BookmarkBase();
				bookmark.setHostname(ConnectionReference.getHostname(refStr));
				connect(bookmark);
			}
			else if (ConnectionReference.isBookmarkReference(refStr))
			{
				sessionViewModel.loadBookmarkById(ConnectionReference.getBookmarkId(refStr),
				                                  bookmark -> {
					                                  if (bookmark != null)
						                                  connect(bookmark);
					                                  else
						                                  closeSessionActivity(RESULT_CANCELED);
				                                  });
			}
			else
			{
				closeSessionActivity(RESULT_CANCELED);
			}
		}
		else
		{
			// no session found - exit
			closeSessionActivity(RESULT_CANCELED);
		}
	}

	private void connect(BookmarkBase bookmark)
	{
		session = GlobalApp.createSession(bookmark, getApplicationContext());

		BookmarkBase.ScreenSettings screenSettings =
		    session.getBookmark().getActiveScreenSettings();
		Log.v(TAG, "Screen Resolution: " + screenSettings.getResolutionString());
		if (screenSettings.isAutomatic())
		{
			// Instead of enforcing obsolete ratios based on screen categories,
			// directly map to actual device metrics without arbitrary multi-scaling.
			screenSettings.setHeight(screen_height);
			screenSettings.setWidth(screen_width);
		}
		if (screenSettings.isFitScreen())
		{
			screenSettings.setHeight(screen_height);
			screenSettings.setWidth(screen_width);
		}

		// RECORD_AUDIO / CAMERA: only if the matching redirect is enabled.
		java.util.ArrayList<String> needed = new java.util.ArrayList<>();
		if (bookmark.getAdvancedSettings().getRedirectMicrophone() &&
		    checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
		        PackageManager.PERMISSION_GRANTED)
			needed.add(Manifest.permission.RECORD_AUDIO);
		if (bookmark.getAdvancedSettings().getRedirectCamera() &&
		    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
			needed.add(Manifest.permission.CAMERA);

		if (!needed.isEmpty())
		{
			pendingConnectBookmark = bookmark;
			requestPermissions(needed.toArray(new String[0]), REQUEST_MEDIA_PERMISSIONS);
			return;
		}

		connectWithTitle(bookmark.getLabel());
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	                                       @NonNull int[] grantResults)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_MEDIA_PERMISSIONS && pendingConnectBookmark != null)
		{
			BookmarkBase bookmark = pendingConnectBookmark;
			pendingConnectBookmark = null;
			connectWithTitle(bookmark.getLabel());
		}
	}

	private void connect(Uri openUri)
	{
		session = GlobalApp.createSession(openUri, getApplicationContext());

		connectWithTitle(openUri.getAuthority());
	}

	static class ConnectThread extends Thread
	{
		private final SessionState runnableSession;
		private final Context context;

		public ConnectThread(@NonNull Context context, @NonNull SessionState session)
		{
			this.context = context;
			runnableSession = session;
		}

		public void run()
		{
			runnableSession.connect(context.getApplicationContext());
		}
	}

	private ConnectThread connectThread = null;

	private void connectWithTitle(String title)
	{
		session.setUIEventListener(this);

		sessionViewModel.register(session.getInstance());

		dialogs.showProgress(title, () -> {
			connectCancelledByUser = true;
			LibFreeRDP.cancelConnection(session.getInstance());
		});

		connectThread = new ConnectThread(getApplicationContext(), session);
		connectThread.start();
	}

	// ---------------------------------------------------------------- network stats
	// Live indicators shown in a corner of the session while the connection is up. Both values are
	// measured on the client so that they do not depend on the server answering the RDP NETCHAR
	// (RTT / bandwidth) measurement - Windows 11 simply ignores those requests:
	//   * throughput: delta of the process traffic counters (android.net.TrafficStats)
	//   * latency:    a locally issued ICMP echo request to the session host
	private final Runnable netStatsUpdater = new Runnable() {
		@Override public void run()
		{
			updateNetworkStats();
			uiHandler.postDelayed(this, NET_STATS_INTERVAL_MS);
		}
	};

	private void startNetworkStats()
	{
		if (session == null)
			return;
		if (netStatsView == null)
			netStatsView = findViewById(R.id.session_net_stats);

		final BookmarkBase bookmark = session.getBookmark();
		netStatsPingHost = (bookmark != null) ? bookmark.getHostname() : null;
		netStatsLastBytes = -1;
		netStatsLastTime = 0;
		netStatsRttMs = -1;
		netStatsPingTick = 0;
		netStatsFrames.set(0);
		netStatsLastFrames = -1;
		netStatsLastFrameTime = 0;
		if (netStatsExecutor == null)
			netStatsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

		uiHandler.removeCallbacks(netStatsUpdater);
		uiHandler.post(netStatsUpdater);
	}

	private void stopNetworkStats()
	{
		uiHandler.removeCallbacks(netStatsUpdater);
		if (netStatsView != null)
			netStatsView.setVisibility(View.GONE);
	}

	// MiB/s (displayed as MB/s, 1 MB = 1024 * 1024 bytes) over the last sampling interval,
	// or -1 while no valid delta exists yet.
	private double sampleBandwidthMBps()
	{
		final long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
		final long tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
		if ((rx < 0) || (tx < 0))
			return -1;

		final long total = rx + tx;
		final long now = android.os.SystemClock.elapsedRealtime();
		if ((netStatsLastBytes < 0) || (netStatsLastTime == 0) || (now <= netStatsLastTime))
		{
			netStatsLastBytes = total;
			netStatsLastTime = now;
			return -1;
		}

		final long deltaBytes = total - netStatsLastBytes;
		final long deltaMs = now - netStatsLastTime;
		netStatsLastBytes = total;
		netStatsLastTime = now;
		if (deltaBytes < 0)
			return -1;
		// bytes per millisecond * 1000 == bytes/s, divided by 1024 * 1024 == MB/s
		return (deltaBytes * 1000.0) / deltaMs / (1024.0 * 1024.0);
	}

	// Frames per second the remote session pushed to this client over the last sampling
	// interval, or -1 while no valid sample exists. OnGraphicsUpdate() counts the updates,
	// so the value reflects what the server actually rendered (0 while the desktop is idle).
	private double sampleFps()
	{
		final long frames = netStatsFrames.get();
		final long now = android.os.SystemClock.elapsedRealtime();
		if ((netStatsLastFrames < 0) || (netStatsLastFrameTime == 0) ||
		    (now <= netStatsLastFrameTime))
		{
			netStatsLastFrames = frames;
			netStatsLastFrameTime = now;
			return -1;
		}

		final long deltaFrames = frames - netStatsLastFrames;
		final long deltaMs = now - netStatsLastFrameTime;
		netStatsLastFrames = frames;
		netStatsLastFrameTime = now;
		if ((deltaFrames < 0) || (deltaMs <= 0))
			return -1;
		return (deltaFrames * 1000.0) / deltaMs;
	}

	// One ICMP echo request, returning the round trip time in ms or -1 when unavailable.
	private int measureRttMs(String host)
	{
		if ((host == null) || host.isEmpty())
			return -1;
		try
		{
			final java.lang.Process process =
			    new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
			        .redirectErrorStream(true)
			        .start();
			final java.io.BufferedReader reader =
			    new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
			try
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					final int index = line.indexOf("time=");
					if (index >= 0)
					{
						final String value = line.substring(index + 5).trim().split("\\s+")[0];
						return Math.round(Float.parseFloat(value));
					}
				}
			}
			finally
			{
				reader.close();
				process.destroy();
			}
		}
		catch (Exception e)
		{
			Log.w(TAG, "ping failed: " + e);
		}
		return -1;
	}

	private void updateNetworkStats()
	{
		if ((netStatsView == null) || (session == null))
			return;

		// the overlay can be switched off in the application settings at any time
		if (!ApplicationSettingsActivity.getNetworkStatsEnabled(this))
		{
			netStatsView.setVisibility(View.GONE);
			return;
		}

		final double mbps = sampleBandwidthMBps();
		final double fps = sampleFps();
		final int rtt = netStatsRttMs;

		// Refresh the latency every third sample in the background; ping blocks.
		netStatsPingTick++;
		if ((netStatsPingHost != null) && ((netStatsPingTick % 3) == 1) && !netStatsPingInFlight &&
		    (netStatsExecutor != null))
		{
			netStatsPingInFlight = true;
			final String host = netStatsPingHost;
			netStatsExecutor.execute(() -> {
				netStatsRttMs = measureRttMs(host);
				netStatsPingInFlight = false;
			});
		}

		if ((mbps < 0.0) && (rtt <= 0) && (fps < 0.0))
		{
			netStatsView.setVisibility(View.GONE);
			return;
		}

		final double mbs = (mbps > 0.0) ? mbps : 0.0;
		final double rate = (fps > 0.0) ? fps : 0.0;
		if (rtt > 0)
			netStatsView.setText(getString(R.string.session_net_stats, rtt, mbs, rate));
		else
			netStatsView.setText(
				getString(R.string.session_net_stats_unknown_rtt, mbs, rate));
		netStatsView.setVisibility(View.VISIBLE);
	}

	// Lets the user pick a quality/bandwidth profile for the running session. The choice is
	// written into the bookmark and takes effect on the next connect.
	private void showQualityDialog()
	{
		final BookmarkBase bookmark = (session != null) ? session.getBookmark() : null;
		if (bookmark == null)
			return;

		final String[] labels = { getString(R.string.quality_low), getString(R.string.quality_balanced),
			                      getString(R.string.quality_high) };

		new android.app.AlertDialog.Builder(this)
		    .setTitle(R.string.quality_title)
		    .setItems(labels,
		              (dialog, which) -> applyQualityProfile(bookmark, which, labels[which]))
		    .show();
	}

	private void applyQualityProfile(BookmarkBase bookmark, int level, String label)
	{
		final BookmarkBase.PerformanceFlags flags = bookmark.getActivePerformanceFlags();

		// 0 = bandwidth saver, 1 = balanced, 2 = best quality
		flags.setNetworkType((level == 0) ? "broadband-low" : ((level == 2) ? "lan" : "auto"));
		flags.setRemoteFX(level == 2);
		flags.setGfx(true);
		flags.setH264(level != 0);
		flags.setGfxProgressive(level != 1);
		flags.setVideoOptimized(level != 1);
		flags.setWallpaper(level == 2);
		flags.setTheming(level == 2);
		flags.setFullWindowDrag(level == 2);
		flags.setMenuAnimations(level == 2);
		flags.setFontSmoothing(level == 2);
		flags.setDesktopComposition(level == 2);

		// Room refuses main thread access, so persist on a worker thread
		final BookmarkBase target = bookmark;
		new Thread(() -> {
			try
			{
				new com.freerdp.freerdpcore.services.ManualBookmarkGateway(
				    com.freerdp.freerdpcore.data.AppDatabase.getInstance(getApplicationContext())
				        .bookmarkDao())
				    .update(target);
			}
			catch (Exception e)
			{
				Log.e(TAG, "failed to store the quality profile", e);
			}
		}, "quality-profile-store").start();

		Toast.makeText(this, getString(R.string.quality_applied, label), Toast.LENGTH_LONG).show();
	}

	// binds the current session to the activity by wiring it up with the
	// sessionView and updating all internal objects accordingly
	private void bindSession()
	{
		Log.v(TAG, "bindSession called");
		session.setUIEventListener(this);
		sessionView.onSurfaceChange(session);
		scrollView.requestLayout();

		Bitmap surface = session.getSurface() != null ? session.getSurface().getBitmap() : null;
		inputManager.attachSession(session.getInstance(), surface);
		inputManager.setScreenSize(screen_width, screen_height);
		hideSystemBars();
		View rootView = findViewById(R.id.session_root_view);
		if (rootView != null)
			ViewCompat.requestApplyInsets(rootView);
	}

	private void closeSessionActivity(int resultCode)
	{
		// Go back to home activity (and send intent data back to home)
		setResult(resultCode, getIntent());
		finish();
	}

	// Leaves the running session: disconnect and return to the bookmark list. Used by the
	// "disconnect" button of the floating toolbar.
	private void disconnectSession()
	{
		if (session == null)
		{
			closeSessionActivity(RESULT_CANCELED);
			return;
		}

		// mark as user initiated so a pending/failed connection does not report an error
		connectCancelledByUser = true;
		if (inputManager != null)
			inputManager.cancelPendingEvents();
		LibFreeRDP.disconnect(session.getInstance());
	}

	// Drag handle that sits above the keyboard: it pans the session view so the parts of
	// the remote desktop hidden by the keyboard stay reachable.
	private void setupPanHandle()
	{
		if (panHandle == null)
			return;

		panHandle.setOnTouchListener(new View.OnTouchListener() {
			private float lastX;
			private float lastY;

			@Override public boolean onTouch(View v, MotionEvent event)
			{
				switch (event.getActionMasked())
				{
					case MotionEvent.ACTION_DOWN:
						lastX = event.getRawX();
						lastY = event.getRawY();
						return true;

					case MotionEvent.ACTION_MOVE:
					{
						final float x = event.getRawX();
						final float y = event.getRawY();
						// the picture follows the finger
						scrollView.scrollBy((int)(lastX - x), (int)(lastY - y));
						lastX = x;
						lastY = y;
						return true;
					}

					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL:
						return true;

					default:
						return false;
				}
			}
		});
	}

	public void handleBackPressed()
	{
		// hide keyboards (if any visible) or send alt+f4 to the session
		if (inputManager != null)
		{
			if (inputManager.handleKeyboardBack())
				return;
			if (inputManager.handleBackAsAltF4())
				return;
		}
		if (System.currentTimeMillis() - backPressedTime < 2000)
		{
			connectCancelledByUser = true;
			LibFreeRDP.disconnect(session.getInstance());
		}
		else
		{
			backPressedTime = System.currentTimeMillis();
			Toast.makeText(this, R.string.session_double_back_to_exit, Toast.LENGTH_SHORT).show();
		}
	}

	@Override public boolean onKeyLongPress(int keyCode, KeyEvent event)
	{
		if (inputManager.onAndroidKeyLongPress(keyCode))
			return true;
		return super.onKeyLongPress(keyCode, event);
	}

	boolean handleKeyEvent(KeyEvent event)
	{
		return inputManager != null && inputManager.onAndroidKeyEvent(event);
	}

	// android keyboard input handling
	// We always use the unicode value to process input from the android
	// keyboard except if key modifiers
	// (like Win, Alt, Ctrl) are activated. In this case we will send the
	// virtual key code to allow key
	// combinations (like Win + E to open the explorer).
	@Override public boolean onKeyDown(int keycode, KeyEvent event)
	{
		if (keycode == KeyEvent.KEYCODE_BACK)
			return super.onKeyDown(keycode, event);
		return inputManager.onAndroidKeyEvent(event);
	}

	@Override public boolean onKeyUp(int keycode, KeyEvent event)
	{
		if (keycode == KeyEvent.KEYCODE_BACK)
			return super.onKeyUp(keycode, event);
		return inputManager.onAndroidKeyEvent(event);
	}

	// onKeyMultiple is called for input of some special characters like umlauts
	// and some symbol characters
	@Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event)
	{
		return inputManager.onAndroidKeyEvent(event);
	}

	// ****************************************************************************
	// KeyboardMapper.KeyProcessingListener — delegated to SessionInputManager

	// ****************************************************************************
	// LibFreeRDP UI event listener implementation
	@Override public void OnSettingsChanged(int width, int height, int bpp)
	{

		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);

		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		if (inputManager != null)
			inputManager.setBitmap(bitmap);

		if (session.getBookmark() == null)
		{
			// Return immediately if we launch from URI
			return;
		}
		// check this settings and initial settings - if they are not equal the
		// server doesn't support our settings
		// FIXME: the additional check (settings.getWidth() != width + 1) is for
		// the RDVH bug fix to avoid accidental notifications
		// (refer to android_freerdp.c for more info on this problem)
		BookmarkBase.ScreenSettings settings = session.getBookmark().getActiveScreenSettings();
		if ((settings.getWidth() != width && settings.getWidth() != width + 1) ||
		    settings.getHeight() != height || settings.getColors() != bpp)
			uiHandler.sendMessage(Message.obtain(
			    null, DISPLAY_TOAST, getResources().getText(R.string.info_capabilities_changed)));
	}

	@Override public void OnGraphicsUpdate(int x, int y, int width, int height)
	{
		// frame counter for the performance overlay (see sampleFps())
		netStatsFrames.incrementAndGet();

		LibFreeRDP.updateGraphics(session.getInstance(), bitmap, x, y, width, height);

		sessionView.addInvalidRegion(new Rect(x, y, x + width, y + height));

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */

		uiHandler.sendEmptyMessage(REFRESH_SESSIONVIEW);
	}

	@Override public void OnGraphicsResize(int width, int height, int bpp)
	{
		// replace bitmap
		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);
		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		if (inputManager != null)
			inputManager.setBitmap(bitmap);

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */
		uiHandler.sendEmptyMessage(GRAPHICS_CHANGED);
	}

	@Override
	public boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
	                              StringBuilder password)
	{
		return dialogs.promptCredentials(username, domain, password);
	}

	@Override
	public boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
	                                     StringBuilder password)
	{
		return dialogs.promptCredentials(username, domain, password);
	}

	@Override
	public int OnVerifiyCertificateEx(String host, long port, String commonName, String subject,
	                                  String issuer, String fingerprint, long flags)
	{
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;
		return dialogs.verifyCertificate(host, port, subject, issuer, fingerprint, flags);
	}

	@Override
	public int OnVerifyChangedCertificateEx(String host, long port, String commonName,
	                                        String subject, String issuer, String fingerprint,
	                                        String oldSubject, String oldIssuer,
	                                        String oldFingerprint, long flags)
	{
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;
		return dialogs.verifyChangedCertificate(host, port, subject, issuer, fingerprint, flags);
	}

	@Override public boolean OnExperimentalFeature(int feature)
	{
		final String featureKey;
		final String displayName;
		switch (feature)
		{
			case LibFreeRDP.EXPERIMENTAL_REMOTEAPP:
				featureKey = "remoteapp";
				displayName = getString(R.string.experimental_feature_remoteapp);
				break;
			case LibFreeRDP.EXPERIMENTAL_CAMERA:
				featureKey = "camera";
				displayName = getString(R.string.experimental_feature_camera);
				break;
			default:
				return true;
		}
		if (ApplicationSettingsActivity.isExperimentalEnabled(this, featureKey))
			return true;
		// suppress the generic failure toast; the dialog explains the abort
		connectCancelledByUser = true;
		dialogs.showExperimentalBlocked(displayName);
		return false;
	}

	@Override public void OnRemoteClipboardChanged(String data)
	{
		Log.v(TAG, "OnRemoteClipboardChanged: " + data);
		mClipboardManager.setClipboardData(data);
	}

	@Override public void OnRemoteClipboardImageChanged(byte[] data)
	{
		Log.v(TAG, "OnRemoteClipboardImageChanged: " + data.length + " bytes");
		mClipboardManager.setClipboardImage(data);
	}

	@Override public void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY)
	{
		Bundle data = new Bundle();
		data.putIntArray("pixels", pixels);
		data.putInt("width", width);
		data.putInt("height", height);
		data.putInt("hotX", hotX);
		data.putInt("hotY", hotY);
		Message msg = uiHandler.obtainMessage(POINTER_SET);
		msg.setData(data);
		uiHandler.sendMessage(msg);
	}

	@Override public void OnPointerSetNull()
	{
		uiHandler.sendEmptyMessage(POINTER_SET);
	}

	@Override public void OnPointerSetDefault()
	{
		sessionView.setDefaultCursor();
	}

	@Override public void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels)
	{
		railManager.onWindowUpdate(windowId, width, height, pixels);
	}

	@Override public void OnRailWindowMove(long windowId, int x, int y, int w, int h)
	{
		railManager.onWindowMove(windowId, x, y, w, h);
	}

	@Override public void OnRailWindowHide(long windowId)
	{
		railManager.onWindowHide(windowId);
	}

	@Override public void OnRailWindowDestroy(long windowId)
	{
		railManager.onWindowDestroy(windowId);
	}

	@Override public void OnRailSessionEnd()
	{
		railManager.onSessionEnd();
	}

	@Override public void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId)
	{
		railManager.onMonitoredDesktop(windowIds, activeWindowId);
	}

	// ****************************************************************************
	// SessionView.SessionViewListener and TouchPointerView.TouchPointerListener
	// — delegated to SessionInputManager

	@Override public boolean onGenericMotionEvent(MotionEvent e)
	{
		super.onGenericMotionEvent(e);
		return inputManager != null && inputManager.onGenericMotionEvent(e);
	}

	// ****************************************************************************
	// ClipboardManagerProxy.OnClipboardChangedListener
	@Override public void onClipboardChanged(String data)
	{
		Log.v(TAG, "onClipboardChanged: " + data);
		if (session != null)
			LibFreeRDP.sendClipboardData(session.getInstance(), data);
	}

	@Override public void onClipboardImageChanged(byte[] data, String mimeType)
	{
		if (session != null && data != null)
			LibFreeRDP.sendClipboardImageData(session.getInstance(), data, mimeType);
	}

	private void onConnectionStateChanged(SessionViewModel.ConnectionState state)
	{
		if (session == null)
			return;
		switch (state)
		{
			case CONNECTED:
				onSessionConnected();
				break;
			case FAILED:
				onSessionFailed();
				break;
			case DISCONNECTED:
				onSessionDisconnected();
				break;
			default:
				break;
		}
	}

	private void onSessionConnected()
	{
		Log.v(TAG, "onSessionConnected");

		if (connectCancelledByUser)
		{
			LibFreeRDP.disconnect(session.getInstance());
			closeSessionActivity(RESULT_CANCELED);
			return;
		}

		// bind session
		bindSession();
		startNetworkStats();

		if (ApplicationSettingsActivity.getKeepScreenOnWhenConnected(this))
		{
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		dialogs.dismissProgress();

		if (session.getBookmark() == null)
		{
			// Return immediately if we launch from URI
			return;
		}

		// add hostname to history if quick connect was used
		Bundle bundle = getIntent().getExtras();
		if (bundle != null && bundle.containsKey(PARAM_CONNECTION_REFERENCE))
		{
			if (ConnectionReference.isHostnameReference(
			        bundle.getString(PARAM_CONNECTION_REFERENCE)))
			{
				assert session.getBookmark().getType() == BookmarkBase.TYPE_MANUAL;
				sessionViewModel.recordQuickConnectHistory(session.getBookmark().getHostname());
			}
		}
	}

	private void onSessionFailed()
	{
		Log.v(TAG, "onSessionFailed");

		// cancel any pending input events
		if (inputManager != null)
			inputManager.cancelPendingEvents();

		dialogs.dismissProgress();

		// post error message on UI thread
		if (!connectCancelledByUser)
			uiHandler.sendMessage(Message.obtain(
			    null, DISPLAY_TOAST, getResources().getText(R.string.error_connection_failure)));

		closeSessionActivity(RESULT_CANCELED);
	}

	private void onSessionDisconnected()
	{
		Log.v(TAG, "onSessionDisconnected");

		// cancel any pending input events
		if (inputManager != null)
			inputManager.cancelPendingEvents();

		if (ApplicationSettingsActivity.getKeepScreenOnWhenConnected(this))
		{
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		dialogs.dismissProgress();

		stopNetworkStats();

		railManager.clear();

		session.setUIEventListener(null);
		closeSessionActivity(RESULT_OK);
	}
}
