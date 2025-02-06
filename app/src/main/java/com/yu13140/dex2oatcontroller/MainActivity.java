package com.yu13140.dex2oatcontroller;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {
	// 日志标签
	private static final String TAG = "DexController";
	private static final int PERMISSION_REQUEST_CODE = 1001;

	// UI组件
	private ListView appListView;
	private AppListAdapter adapter;
	private Switch shamikoSwitch;
	private RelativeLayout mainLayout;
	private ImageButton searchBtn, menuBtn;

	// 数据与线程管理
	private boolean showSystemApps = false;
	private List<AppInfo> originalAppList = new ArrayList<>();
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	// 内部类定义（唯一）
	private static class AppInfo {
		String name;
		String packageName;
		Drawable icon;
	}

	private class AppListAdapter extends BaseAdapter {
		private List<AppInfo> apps;

		public AppListAdapter(List<AppInfo> apps) {
			this.apps = apps;
		}

		public void filter(String query) {
			List<AppInfo> filtered = new ArrayList<>();
			for (AppInfo app : originalAppList) {
				if (app.name.toLowerCase().contains(query.toLowerCase())) {
					filtered.add(app);
				}
			}
			apps = filtered;
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return apps.size();
		}

		@Override
		public AppInfo getItem(int position) {
			return apps.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			ViewHolder holder;
			if (convertView == null) {
				convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_app, parent, false);
				holder = new ViewHolder(convertView);
				convertView.setTag(holder);
			} else {
				holder = (ViewHolder) convertView.getTag();
			}

			AppInfo app = apps.get(position);
			holder.name.setText(app.name);

			WeakReference<Drawable> weakIcon = new WeakReference<>(app.icon);
			holder.icon.setImageDrawable(weakIcon.get());

			holder.switchBtn.setOnCheckedChangeListener((v, checked) -> setDex2oat(app.packageName, checked));

			return convertView;
		}

		private class ViewHolder {
			ImageView icon;
			TextView name;
			Switch switchBtn;

			public ViewHolder(View view) {
				icon = view.findViewById(R.id.app_icon);
				name = view.findViewById(R.id.app_name);
				switchBtn = view.findViewById(R.id.app_switch);
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Switch shamikoSwitch = findViewById(R.id.shamiko_switch);
		checkShamikoWhitelist(isFileExists -> {
			shamikoSwitch.setChecked(isFileExists);
		});
		initUIComponents();
		requestStoragePermission();
		setupGlobalExceptionHandler();

	}

	// ================= 初始化模块 =================
	private void initUIComponents() {
		try {
			appListView = findViewById(R.id.app_list);
			shamikoSwitch = findViewById(R.id.shamiko_switch);
			mainLayout = findViewById(R.id.main_layout);
			searchBtn = findViewById(R.id.search_btn);
			menuBtn = findViewById(R.id.menu_btn);

			showSystemApps = false;

			shamikoSwitch.setOnCheckedChangeListener((v, checked) -> toggleShamikoMode(checked));
			searchBtn.setOnClickListener(v -> showSearchBar());
			menuBtn.setOnClickListener(v -> showSystemAppMenu());

		} catch (Exception e) {
			Log.e(TAG, "UI初始化失败: " + e.getMessage());
			showToast("初始化失败，请重启应用");
			finish();
		}
	}

	// ================= 权限管理 =================
	private static final int STORAGE_PERMISSION_CODE = 1002;

	private void requestStoragePermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
						STORAGE_PERMISSION_CODE);
			}
		}
		loadApps();
	}

	@Override
	public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
		super.onRequestPermissionsResult(code, perms, results);
		if (code == PERMISSION_REQUEST_CODE && results.length > 0) {
			if (results[0] == PackageManager.PERMISSION_GRANTED) {
				checkRootStatus();
			} else {
				showToast("需要权限才能显示应用列表");
			}
		}
	}

	private void checkRootStatus() {
		ProgressDialog dialog = ProgressDialog.show(this, "", "检测Root权限...");
		executor.execute(() -> {
			boolean hasRoot = checkRoot();
			mainHandler.post(() -> {
				dialog.dismiss();
				if (hasRoot) {
					loadApps();
				} else {
					showNoRootView();
				}
			});
		});
	}

	private boolean checkRoot() {
		final CountDownLatch latch = new CountDownLatch(1);
		final boolean[] result = { false };

		java.lang.Process process = null;
		BufferedReader inReader = null;
		try {
			process = Runtime.getRuntime().exec("id -u");
			final java.lang.Process finalProcess = process;
			new Thread(() -> {
				try (BufferedReader errReader = new BufferedReader(
						new InputStreamReader(finalProcess.getErrorStream()))) {
					String line;
					while ((line = errReader.readLine()) != null) {
						Log.e(TAG, "[Root检测错误流] " + line);
					}
				} catch (IOException e) {
					Log.e(TAG, "错误流读取失败: " + e.getMessage());
				}
			}).start();

			inReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = inReader.readLine()) != null) {
				if (line.contains("0"))
					result[0] = true;
			}

			if (process.waitFor(2, TimeUnit.SECONDS)) {
				result[0] = (process.exitValue() == 0);
			}
		} catch (Exception e) {
			Log.e(TAG, "Root检测异常: " + e.getMessage());
		} finally {
			try {
				if (inReader != null)
					inReader.close();
				if (process != null)
					process.destroy();
			} catch (IOException e) {
				Log.e(TAG, "资源关闭失败: " + e.getMessage());
			}
			latch.countDown();
		}

		try {
			latch.await(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return result[0];
	}

	// ================= 核心功能模块 =================
	private void loadApps() {
		ProgressDialog dialog = ProgressDialog.show(this, "加载中", "获取应用列表...");
		executor.execute(() -> {
			try {
				List<AppInfo> apps = loadApplicationData();
				mainHandler.post(() -> {
					dialog.dismiss();
					if (apps.isEmpty()) {
						showToast("未找到应用");
						return;
					}
					adapter = new AppListAdapter(apps);
					appListView.setAdapter(adapter);
				});
			} catch (Exception e) {
				Log.e(TAG, "应用加载失败: " + e.getMessage());
				mainHandler.post(() -> {
					dialog.dismiss();
					showToast("加载失败，请重试");
				});
			}
		});
	}

	private List<AppInfo> loadApplicationData() {
		List<AppInfo> apps = new ArrayList<>();
		PackageManager pm = getPackageManager();
		List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

		for (ApplicationInfo pkg : packages) {
			if (pkg == null)
				continue;

			if (!showSystemApps && isSystemApp(pkg))
				continue;

			AppInfo info = new AppInfo();
			info.packageName = Optional.ofNullable(pkg.packageName).orElse("unknown");

			try {
				info.name = pm.getApplicationLabel(pkg).toString();
			} catch (Exception e) {
				info.name = "未知应用";
			}

			try {
				info.icon = pm.getApplicationIcon(pkg);
			} catch (Exception e) {
				info.icon = getDrawable(android.R.drawable.sym_def_app_icon);
			}

			apps.add(info);
		}

		originalAppList = new ArrayList<>(apps);
		return apps;
	}

	// ================= 功能操作模块 =================
	public void checkShamikoWhitelist(Consumer<Boolean> callback) {
		executor.execute(() -> {
			try {
				String cmd = "ls /data/adb/shamiko/whitelist";
				int exitCode = executeRootCommandForExitCode(cmd);
				boolean isFileExists = (exitCode == 0);
				runOnUiThread(() -> callback.accept(isFileExists));

			} catch (Exception e) {
				Log.e(TAG, "检查文件失败: " + e.getMessage());
				runOnUiThread(() -> showToast("无法检查文件，请检查Root权限"));
			}
		});
	}

	private int executeRootCommandForExitCode(String command) throws Exception {
		java.lang.Process process = Runtime.getRuntime().exec("su");
		DataOutputStream os = new DataOutputStream(process.getOutputStream());
		os.writeBytes(command + "\n");
		os.writeBytes("exit\n");
		os.flush();
		os.close();
		return process.waitFor();
	}

	private void toggleShamikoMode(boolean isWhiteList) {
		final boolean finalIsWhiteList = isWhiteList;
		executor.execute(() -> {
			try {
				String cmd = finalIsWhiteList ? "touch /data/adb/shamiko/whitelist"
						: "rm -f /data/adb/shamiko/whitelist";

				executeRootCommand(cmd);

				runOnUiThread(() -> {
					showToast(finalIsWhiteList ? "已启用白名单模式" : "已启用黑名单模式");
					shamikoSwitch.setChecked(finalIsWhiteList);
				});

			} catch (Exception e) {
				Log.e(TAG, "模式切换失败: " + e.getMessage());
				showToast("操作失败，请检查Root权限");
			}
		});
	}

	private void setDex2oat(String packageName, boolean enable) {
		if (packageName == null || packageName.isEmpty()) {
			showToast("无效包名");
			return;
		}

		executor.execute(() -> {
			try {
				String mode = enable ? "everything" : "speed-profile";
				executeRootCommand("cmd package compile -f -m " + mode + " " + packageName);
				showToast("优化已" + (enable ? "启用" : "恢复为speed-profile"));
			} catch (Exception e) {
				Log.e(TAG, "Dex2oat设置失败: " + e.getMessage());
				showToast("优化可能已经启用");
			}
		});
	}

	// ================= 工具方法模块 =================
	private void executeRootCommand(String command) throws Exception {
		java.lang.Process process = null;
		try {
			process = Runtime.getRuntime().exec("su");
			final java.lang.Process finalProcess = process;
			try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
				os.writeBytes(command + "\nexit\n");
				os.flush();

				new Thread(() -> {
					try (BufferedReader errReader = new BufferedReader(
							new InputStreamReader(finalProcess.getErrorStream()))) {
						String line;
						while ((line = errReader.readLine()) != null) {
							Log.e(TAG, "[命令错误流] " + line);
						}
					} catch (IOException e) {
						Log.e(TAG, "错误流读取失败: " + e.getMessage());
					}
				}).start();

				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					throw new TimeoutException("命令执行超时");
				}
				if (process.exitValue() != 0) {
					throw new IOException("命令返回码: " + process.exitValue());
				}
			}
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	private boolean isSystemApp(ApplicationInfo info) {
		return (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
	}

	// ================= UI模块 =================
	private void showNoRootView() {
		mainHandler.post(() -> {
			mainLayout.removeAllViews();
			TextView tv = new TextView(this);
			tv.setTextSize(20);
			tv.setText("需要Root权限");
			mainLayout.addView(tv);
		});
	}

	private void showSearchBar() {
		mainHandler.post(() -> {
			EditText searchBox = new EditText(this);
			searchBox.setHint("搜索应用...");
			mainLayout.addView(searchBox, 1);

			searchBox.addTextChangedListener(new TextWatcher() {
				@Override
				public void afterTextChanged(Editable s) {
				}

				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					if (adapter != null)
						adapter.filter(s.toString());
				}
			});
		});
	}

	private void showSystemAppMenu() {
		mainHandler.post(() -> {
			PopupMenu popup = new PopupMenu(this, menuBtn);
			popup.getMenu().add("显示系统应用").setCheckable(true).setChecked(showSystemApps);
			popup.setOnMenuItemClickListener(item -> {
				showSystemApps = !showSystemApps;
				loadApps();
				return true;
			});
			popup.show();
		});
	}

	private void showToast(String message) {
		mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
	}

	// ================= 系统管理模块 =================
	private void enableStrictMode() {
		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
				.detectNetwork().penaltyLog().build());
	}

	private void setupGlobalExceptionHandler() {
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
			Log.e(TAG, "全局异常: " + e.getMessage());
			mainHandler.post(() -> {
				showToast("程序异常，即将退出");
				finishAffinity();
			});
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		executor.shutdownNow();
	}
}