package com.winlator.cmod;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Html;
import android.text.format.Formatter;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.winlator.cmod.lsfg.LsfgManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.FileManagerFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import com.winlator.cmod.services.NotificationService;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final String EXTRA_BROWSE_CONTAINER = "browse_container_id";
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final int PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 500;
    public static final int PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE = 501;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    public static final byte OPEN_IMAGE_REQUEST_CODE = 5;
    public static final String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static final int NOTIFICATION_ID = 100;
    private static final String ORIENTATION_MODE_AUTO = "auto";
    private static final String ORIENTATION_MODE_VERTICAL = "vertical";
    private static final String ORIENTATION_MODE_HORIZONTAL = "horizontal";
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private BottomNavigationView bottomNavigation;
    private View mainToolbar;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Intent notificationService;
    private SharedPreferences sharedPreferences;
    private ContainerManager containerManager;
    private boolean isDarkMode;
    private boolean syncingBottomNavigation;
    private boolean orientationLocked;
    private String orientationMode = ORIENTATION_MODE_AUTO;

    private void createNotificationChannel() {
        String name = "Winlator";
        String description = "Winlator XServer Messages";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Coming back to the app asks the registry again (files downloaded in the last 30 s are not downloaded twice).
        com.winlator.cmod.contents.RegistryCache.refreshInBackground(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        ContentsManager.migrateRemoteProfilesUrl(sharedPreferences);

        // Persist the default value on first run so all other components
        // (dialogs, fragments) read the correct value instead of their own default
        if (!sharedPreferences.contains("dark_mode")) {
            sharedPreferences.edit().putBoolean("dark_mode", true).apply();
        }

        isDarkMode = sharedPreferences.getBoolean("dark_mode", true);

        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            setTheme(R.style.AppTheme_Dark);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            setTheme(R.style.AppTheme);
        }

        super.onCreate(savedInstanceState);
        applyImmersiveMode();

        if (!sharedPreferences.getBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        // Installs set up before telemetry existed are asked once, after the screen is up.
        getWindow().getDecorView().post(() -> com.winlator.cmod.telemetry.TelemetryScreen.askOnce(this));

        notificationService = new Intent(this, NotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
            createNotificationChannel();

        boolean isBigPictureModeEnabled = sharedPreferences.getBoolean("enable_big_picture_mode", false);

        if (isBigPictureModeEnabled && !getIntent().hasExtra("selected_menu_item_id")) {
            Intent intent = new Intent(MainActivity.this, BigPictureActivity.class);
            startActivity(intent);
        }

        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setBackgroundColor(Color.parseColor("#0B0D12"));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        bottomNavigation = findViewById(R.id.BottomNavigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            if (syncingBottomNavigation) return true;
            int target;
            if (item.getItemId() == R.id.bottom_nav_library) target = R.id.main_menu_shortcuts;
            else if (item.getItemId() == R.id.bottom_nav_controls) target = R.id.main_menu_input_controls;
            else if (item.getItemId() == R.id.bottom_nav_settings) target = R.id.main_menu_settings;
            else return false;
            MenuItem destination = navigationView.getMenu().findItem(target);
            navigationView.setCheckedItem(target);
            return onNavigationItemSelected(destination);
        });
        updateStorageFooter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        mainToolbar = findViewById(R.id.Toolbar);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                refreshMainNavigation();
            }
        }, false);
        setSupportActionBar((androidx.appcompat.widget.Toolbar) mainToolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        setNavigationViewItemTextColor(navigationView, Color.WHITE);

        File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
        if (!winlatorDir.exists())
            winlatorDir.mkdirs();

        containerManager = new ContainerManager(this);
        new Thread(() -> LsfgManager.migrateLegacyDlls(getApplicationContext()), "lsfg-migrate").start();

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeAsUpIndicator(R.drawable.ui_ic_back);
            }
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.main_menu_input_controls));
            navigationView.setCheckedItem(R.id.main_menu_input_controls);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId;
            if (selectedMenuItemId > 0) {
                menuItemId = selectedMenuItemId;
            } else {
                List<Shortcut> shortcuts = containerManager.loadShortcuts();
                menuItemId = (shortcuts != null && !shortcuts.isEmpty())
                        ? R.id.main_menu_shortcuts
                        : R.id.main_menu_containers;
            }

            if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(false);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            selectBottomDestination(menuItemId);
            com.winlator.cmod.container.Container browseContainer = containerManager.getContainerById(intent.getIntExtra(EXTRA_BROWSE_CONTAINER, -1));
            if (browseContainer != null) {
                show(FileManagerFragment.forContainerFiles(browseContainer), false);
                navigationView.setCheckedItem(R.id.main_menu_file_manager);
                selectBottomDestination(R.id.main_menu_file_manager);
            }

            if (!ImageFsInstaller.installIfNeeded(this, () -> requestAppPermissions())) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    startForegroundService(notificationService);
            }
        }
    }

    private void updateStorageFooter() {
        TextView storageView = findViewById(R.id.TVStorageUsage);
        ProgressBar storageProgress = findViewById(R.id.PBStorageUsage);
        if (storageView == null || storageProgress == null) return;

        File storageRoot = Environment.getExternalStorageDirectory();
        StatFs statFs = new StatFs(storageRoot.getPath());
        long totalBytes = statFs.getTotalBytes();
        long freeBytes = statFs.getAvailableBytes();
        long usedBytes = Math.max(0, totalBytes - freeBytes);
        int usedPercent = totalBytes > 0 ? Math.min(100, Math.round((usedBytes * 100f) / totalBytes)) : 0;

        storageView.setText(Formatter.formatShortFileSize(this, usedBytes) + " / " + Formatter.formatShortFileSize(this, totalBytes));
        storageProgress.setProgress(usedPercent);
    }

    private void showAllFilesAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("All Files Access Required")
                .setMessage("In order to grant access to additional storage devices such as USB storage device, the All Files Access permission must be granted. Press Okay to grant All Files Access in your Android Settings.")
                .setPositiveButton("Okay", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startForegroundService(notificationService);
        } else if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                requestAppPermissions();
            else
                finish();
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
            return;
        }
        if (editInputControls) {
            super.onBackPressed();
            return;
        }
        finish();
    }

    private void requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasManageStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        boolean hasPostNotificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
            return;
        }

        if (!hasPostNotificationPermission) {
            createNotificationChannel();
            String[] permissions = new String[]{Manifest.permission.POST_NOTIFICATIONS};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_POST_NOTIFICATIONS_REQUEST_CODE);
        }

        if (!hasManageStoragePermission) {
            showAllFilesAccessDialog();
        }
    }

    public boolean isOrientationLocked() {
        return orientationLocked;
    }

    public boolean isVerticalModeEnabled() {
        return ORIENTATION_MODE_VERTICAL.equals(orientationMode);
    }

    public boolean isHorizontalModeEnabled() {
        return ORIENTATION_MODE_HORIZONTAL.equals(orientationMode);
    }

    public boolean toggleOrientationLock() {
        orientationLocked = !orientationLocked;
        applyOrientationMode();
        return orientationLocked;
    }

    public boolean toggleVerticalMode() {
        orientationMode = isVerticalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_VERTICAL;
        applyOrientationMode();
        return isVerticalModeEnabled();
    }

    public boolean toggleHorizontalMode() {
        orientationMode = isHorizontalModeEnabled() ? ORIENTATION_MODE_AUTO : ORIENTATION_MODE_HORIZONTAL;
        applyOrientationMode();
        return isHorizontalModeEnabled();
    }

    private void applyOrientationMode() {
        switch (orientationMode) {
            case ORIENTATION_MODE_VERTICAL:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case ORIENTATION_MODE_HORIZONTAL:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(orientationLocked
                        ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
            if (current instanceof GameDetailFragment
                    || ((current instanceof SettingsFragment || current instanceof ContainersFragment
                        || current instanceof InputControlsFragment)
                        && getSupportFragmentManager().getBackStackEntryCount() > 0)
                    || current instanceof ContainerOverviewFragment
                    || current instanceof ContainerDetailFragment
                    || current instanceof ContainerSectionFragment) {
                onBackPressed();
                return true;
            }
            if (editInputControls) {
                onBackPressed();
                return true;
            }

            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    public void toggleDrawer() {
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (item.getItemId() == R.id.main_menu_shortcuts) {
            for (int index = 0; index < fragmentManager.getBackStackEntryCount(); index++) {
                if ("library_destination".equals(fragmentManager.getBackStackEntryAt(index).getName())) {
                    fragmentManager.popBackStack("library_destination", FragmentManager.POP_BACK_STACK_INCLUSIVE);
                    selectBottomDestination(item.getItemId());
                    return true;
                }
            }
        }
        setDetailMode(false);
        selectBottomDestination(item.getItemId());
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.main_menu_shortcuts:
                show(new ShortcutsFragment(), false);
                break;
            case R.id.main_menu_containers:
                show(new ContainersFragment(), false);
                break;
            case R.id.main_menu_input_controls:
                show(InputControlsFragment.newInstance(selectedProfileId), false);
                break;
            case R.id.main_menu_file_manager:
                show(new FileManagerFragment(), false);
                break;
            case R.id.main_menu_settings:
                show(new SettingsFragment(), false);
                break;
            case R.id.main_menu_about:
                showAboutDialog();
                break;
        }
        return true;
    }


    private void selectBottomDestination(int menuItemId) {
        if (bottomNavigation == null) return;
        int bottomId = 0;
        if (menuItemId == R.id.main_menu_shortcuts) bottomId = R.id.bottom_nav_library;
        else if (menuItemId == R.id.main_menu_containers || menuItemId == R.id.main_menu_file_manager) bottomId = R.id.bottom_nav_settings;
        else if (menuItemId == R.id.main_menu_input_controls) bottomId = R.id.bottom_nav_controls;
        else if (menuItemId == R.id.main_menu_settings) bottomId = R.id.bottom_nav_settings;
        if (bottomId != 0 && bottomNavigation.getSelectedItemId() != bottomId) {
            syncingBottomNavigation = true;
            bottomNavigation.setSelectedItemId(bottomId);
            syncingBottomNavigation = false;
        }
    }

    private boolean isLandscapeOrientation() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public void setBottomNavigationVisible(boolean visible) {
        if (bottomNavigation == null) return;
        bottomNavigation.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void refreshMainNavigation() {
        setBottomNavigationVisible(true);
        setMainToolbarVisible(true);
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
        if (current instanceof ShortcutsFragment || current instanceof GameDetailFragment
                || current instanceof com.winlator.cmod.ui.addgame.AddGameFragment) {
            selectBottomDestination(R.id.main_menu_shortcuts);
        } else if (current instanceof InputControlsFragment) {
            selectBottomDestination(R.id.main_menu_input_controls);
        } else if (current instanceof SettingsFragment || current instanceof ContainersFragment
                || current instanceof ContainerOverviewFragment || current instanceof ContainerDetailFragment
                || current instanceof ContainerSectionFragment) {
            selectBottomDestination(R.id.main_menu_settings);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int destination = intent.getIntExtra("selected_menu_item_id", 0);
        if (destination != 0) navigateToMainDestination(destination);
    }

    public void setMainToolbarVisible(boolean visible) {
        if (mainToolbar == null) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
        mainToolbar.setVisibility((visible && !isLandscapeOrientation()
                && !(current instanceof ShortcutsFragment)) ? View.VISIBLE : View.GONE);
    }

    public void navigateToMainDestination(int menuItemId) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
        if (current instanceof ShortcutsFragment) {
            Fragment destination = null;
            if (menuItemId == R.id.main_menu_settings) destination = new SettingsFragment();
            else if (menuItemId == R.id.main_menu_containers) destination = new ContainersFragment();
            else if (menuItemId == R.id.main_menu_input_controls) destination = InputControlsFragment.newInstance(selectedProfileId);
            if (destination != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.FLFragmentContainer, destination)
                        .addToBackStack("library_destination")
                        .commit();
                selectBottomDestination(menuItemId);
                return;
            }
        }
        if (navigationView == null) return;
        MenuItem destination = navigationView.getMenu().findItem(menuItemId);
        if (destination == null) return;
        navigationView.setCheckedItem(menuItemId);
        onNavigationItemSelected(destination);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    public void setDetailMode(boolean detail) {
        setBottomNavigationVisible(true);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(detail);
            if (detail) actionBar.setHomeAsUpIndicator(R.drawable.ui_ic_back);
        }
    }

    /** The container comes first: its type decides what can be added (a .exe for Wine, a Linux program otherwise). A single container is used straight away. */
    public void chooseContainerForNewShortcut() {
        java.util.ArrayList<com.winlator.cmod.container.Container> containers = new ContainerManager(this).getContainers();
        if (containers == null || containers.isEmpty()) {
            android.widget.Toast.makeText(this, "Create a container first!", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (containers.size() == 1) {
            openAddShortcutFor(containers.get(0));
            return;
        }
        boolean mixed = false;
        for (com.winlator.cmod.container.Container container : containers) {
            if (container.isLinux() != containers.get(0).isLinux()) mixed = true;
        }
        String[] names = new String[containers.size()];
        for (int i = 0; i < names.length; i++) {
            com.winlator.cmod.container.Container container = containers.get(i);
            names[i] = container.getName() + (mixed ? (container.isLinux() ? "  (Linux)" : "  (Windows)") : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Add shortcut to")
                .setItems(names, (dialog, which) -> openAddShortcutFor(containers.get(which)))
                .show();
    }

    private void openAddShortcutFor(com.winlator.cmod.container.Container container) {
        Fragment target = container.isLinux()
                ? com.winlator.cmod.ui.container.LinuxProgramsFragment.forContainer(container.id, true)
                : FileManagerFragment.forContainer(container.id);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, target)
                .commit();
    }

    /** "Add game": the user picks the exe first; the container is made for the game afterwards. */
    public void addGame() {
        com.winlator.cmod.telemetry.Telemetry.event("skynet.funnel", "add_game_started");
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, FileManagerFragment.forNewGame())
                .commit();
    }

    /**
     * The file manager of {@link #addGame} was given a game's folder: the registry game that has a program in it is found and
     * its program is the one added. A folder that holds the program of a game for both Windows and Linux asks which one. A folder
     * no game is recognised in opens the file manager in it, to pick the program.
     */
    public void onGameFolderPicked(java.io.File folder) {
        final android.content.Context appContext = getApplicationContext();
        new Thread(() -> {
            com.winlator.cmod.gameprofile.GameMatcher.Result windows = com.winlator.cmod.gameprofile.GameMatcher.matchFolder(
                    appContext, folder, com.winlator.cmod.gameprofile.GameProfile.Platform.WINDOWS, true);
            com.winlator.cmod.gameprofile.GameMatcher.Result linux = com.winlator.cmod.gameprofile.GameMatcher.matchFolder(
                    appContext, folder, com.winlator.cmod.gameprofile.GameProfile.Platform.LINUX, true);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (windows == null && linux == null) {
                    android.widget.Toast.makeText(this, "No known game in this folder. Pick the game's program.", android.widget.Toast.LENGTH_LONG).show();
                    getSupportFragmentManager().popBackStack();
                    getSupportFragmentManager().beginTransaction()
                            .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                            .addToBackStack(null)
                            .replace(R.id.FLFragmentContainer, FileManagerFragment.forNewGame(folder))
                            .commit();
                }
                else if (windows != null && linux != null) {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(windows.game.name)
                            .setMessage("The folder has the Windows and the Linux version of the game. Which one is it added as?")
                            .setPositiveButton("Linux", (d, w) -> onGamePicked(linux.executable))
                            .setNegativeButton("Windows", (d, w) -> onGamePicked(windows.executable))
                            .show();
                }
                else onGamePicked((windows != null ? windows : linux).executable);
            });
        }, "GameFolderMatch").start();
    }

    /** The file manager of {@link #addGame} found the exe: the file manager is left and the screen for choosing how to run it opens. */
    public void onGamePicked(java.io.File exe) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.popBackStack();
        fragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, com.winlator.cmod.ui.addgame.AddGameFragment.Companion.newInstance(exe.getAbsolutePath()))
                .commit();
    }

    /** The shortcut is written: the add-game screen is left and the game's own screen opens. */
    public void gameAdded(String shortcutPath) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.popBackStack();
        fragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new GameDetailFragment(shortcutPath))
                .commit();
    }

    private void show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (reverse) {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_down, R.anim.slide_out_up)
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    public void showAboutDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.about_dialog);
        dialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        if (isDarkMode) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background_dark);
        } else {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background);
        }

        try {
            final PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            TextView tvWebpage = dialog.findViewById(R.id.TVWebpage);
            tvWebpage.setText(Html.fromHtml("<a href=\"https://www.winlator.org\">winlator.org</a>", Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            ((TextView) dialog.findViewById(R.id.TVAppVersion)).setText(getString(R.string.version) + " " + pInfo.versionName);

            String creditsAndThirdPartyAppsHTML = String.join("<br />",
                    "Winlator Ludashi by StevenMX, pipetto-crypto (<a href=\"https://github.com/StevenMXZ/Winlator-Ludashi\">Fork</a>, <a href=\"https://github.com/Pipetto-crypto/winlator\">Fork</a>)",
                    "Big Picture Mode Music by",
                    "Dale Melvin Blevens III (Fumer)",
                    "---",
                    "Termux Package(<a href=\"https://github.com/termux/termux-packages\">github.com/termux/termux-package</a>)",
                    "Wine (<a href=\"https://www.winehq.org\">winehq.org</a>)",
                    "Box64 (<a href=\"https://github.com/ptitSeb/box64\">github.com/ptitSeb/box64</a>)",
                    "Mesa (Turnip/Zink/Wrapper) (<a href=\"https://github.com/xMeM/mesa/tree/wrapper\">github.com/xMeM/mesa</a>)",
                    "DXVK (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>)",
                    "VKD3D (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">gitlab.winehq.org/wine/vkd3d</a>)",
                    "D8VK (<a href=\"https://github.com/AlpyneDreams/d8vk\">github.com/AlpyneDreams/d8vk</a>)",
                    "CNC DDraw (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>)",
                    "dxwrapper (<a href=\"https://github.com/elishacloud/dxwrapper\">github.com/elishacloud/dxwrapper</a>)",
                    "FEX-Emu (<a href=\"https://github.com/FEX-Emu/FEX\">github.com/FEX-Emu/FEX</a>)",
                    "libadrenotools (<a href=\"https://github.com/bylaws/libadrenotools\">github.com/bylaws/libadrenotools</a>)"
            );

            TextView tvCreditsAndThirdPartyApps = dialog.findViewById(R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());

            String glibcExpVersionForkHTML = String.join("<br />",
                    "longjunyu2's <a href=\"https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot\">(GLIBC Fork)</a>");
            TextView tvGlibcExpVersionFork = dialog.findViewById(R.id.TVGlibcExpVersionFork);
            tvGlibcExpVersionFork.setText(Html.fromHtml(glibcExpVersionForkHTML, Html.FROM_HTML_MODE_LEGACY));
            tvGlibcExpVersionFork.setMovementMethod(LinkMovementMethod.getInstance());
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        dialog.show();
    }

    private void setNavigationViewItemTextColor(NavigationView navigationView, int color) {
        for (int i = 0; i < navigationView.getMenu().size(); i++) {
            MenuItem menuItem = navigationView.getMenu().getItem(i);
            setMenuItemTextColor(menuItem, color);

            if (menuItem.hasSubMenu()) {
                for (int j = 0; j < menuItem.getSubMenu().size(); j++) {
                    MenuItem subMenuItem = menuItem.getSubMenu().getItem(j);
                    setMenuItemTextColor(subMenuItem, color);
                }
            }
        }
    }

    private void setMenuItemTextColor(MenuItem menuItem, int color) {
        SpannableString spanString = new SpannableString(menuItem.getTitle());
        spanString.setSpan(new ForegroundColorSpan(color), 0, spanString.length(), 0);
        menuItem.setTitle(spanString);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 1280);
            if (bitmap == null) return;
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(this);
            ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
        }
    }
}
