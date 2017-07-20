package fr.neamar.kiss;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Pattern;

import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.api.provider.Result;
import fr.neamar.kiss.broadcast.IncomingCallHandler;
import fr.neamar.kiss.broadcast.IncomingSmsHandler;
import fr.neamar.kiss.dataprovider.AppProvider;
import fr.neamar.kiss.ui.ResultStateManager;
import fr.neamar.kiss.ui.ResultView;
import fr.neamar.kiss.searcher.ApplicationsSearcher;
import fr.neamar.kiss.searcher.HistorySearcher;
import fr.neamar.kiss.searcher.NullSearcher;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.searcher.QuerySearcher;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.BlockableListView;
import fr.neamar.kiss.ui.BottomPullEffectView;
import fr.neamar.kiss.ui.KeyboardScrollHider;
import fr.neamar.kiss.utils.PackageManagerUtils;

public class MainActivity extends Activity implements QueryInterface, KeyboardScrollHider.KeyboardHandler {

    public static final String START_LOAD = "fr.neamar.summon.START_LOAD";
    public static final String LOAD_OVER = "fr.neamar.summon.LOAD_OVER";
    public static final String FULL_LOAD_OVER = "fr.neamar.summon.FULL_LOAD_OVER";
    /**
     * InputType that behaves as if the consuming IME is a standard-obeying
     * soft-keyboard
     * <p>
     * *Auto Complete* means "we're handling auto-completion ourselves". Then
     * we ignore whatever the IME thinks we should display.
     */
    private final static int INPUT_TYPE_STANDARD = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    /**
     * InputType that behaves as if the consuming IME is SwiftKey
     * <p>
     * *Visible Password* fields will break many non-Latin IMEs and may show
     * unexpected behaviour in numerous ways. (#454, #517)
     */
    private final static int INPUT_TYPE_WORKAROUND = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
    private static final String TAG = "MainActivity";
    /**
     * IDs for the favorites buttons
     */
    public static final int[] FAVS_IDS = new int[]{R.id.favorite0, R.id.favorite1, R.id.favorite2, R.id.favorite3, R.id.favorite4, R.id.favorite5};
    /**
     * IDs for the favorites buttons on the quickbar
     */

    public static final int[] FAV_BAR_IDS = new int[]{R.id.favoriteBar0, R.id.favoriteBar1, R.id.favoriteBar2, R.id.favoriteBar3, R.id.favoriteBar4, R.id.favoriteBar5};

    /**
     * Number of favorites to retrieve.
     * We need to pad this number to account for removed items still in history
     */
    public final int tryToRetrieve = FAVS_IDS.length + 2;
    /**
     * Adapter to display records
     */
    public RecordAdapter adapter;
    /**
     * Store user preferences
     */
    private SharedPreferences prefs;
    private BroadcastReceiver mReceiver;
    /**
     * View for the Search text
     */
    private EditText searchEditText;
    private final Runnable displayKeyboardRunnable = new Runnable() {
        @Override
        public void run() {
            showKeyboard();
        }
    };
    /**
     * Whether or not Search text should be spell checked (affects inputType)
     */
    private boolean searchEditTextWorkaround;
    /**
     * Main list view
     */
    private ListView list;
    private View listContainer;
    /**
     * View to display when list is empty
     */
    private View listEmpty;
    /**
     * Utility for automatically hiding the keyboard when scrolling down
     */
    private KeyboardScrollHider hider;
    /**
     * Menu button
     */
    private View menuButton;
    /**
     * Kiss bar
     */
    private View kissBar;
    /**
     * Favorites bar, in the KISS bar (not the quick favorites bar from minimal UI)
     */
    private View favoritesKissBar;

    /**
     * Task launched on text change
     */
    private Searcher searcher;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        KissApplication.setMainActivity(this);
        
        // Initialize UI
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String theme = prefs.getString("theme", "light");
        switch (theme) {
            case "dark":
                setTheme(R.style.AppThemeDark);
                break;
            case "transparent":
                setTheme(R.style.AppThemeTransparent);
                break;
            case "semi-transparent":
                setTheme(R.style.AppThemeSemiTransparent);
                break;
            case "semi-transparent-dark":
                setTheme(R.style.AppThemeSemiTransparentDark);
                break;
            case "transparent-dark":
                setTheme(R.style.AppThemeTransparentDark);
                break;
        }


        super.onCreate(savedInstanceState);

        IntentFilter intentFilter = new IntentFilter(START_LOAD);
        IntentFilter intentFilterBis = new IntentFilter(LOAD_OVER);
        IntentFilter intentFilterTer = new IntentFilter(FULL_LOAD_OVER);
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equalsIgnoreCase(LOAD_OVER)) {
                    updateRecords(searchEditText.getText().toString());
                } else if (intent.getAction().equalsIgnoreCase(FULL_LOAD_OVER)) {
                    // Run GC once to free all the garbage accumulated during provider initialization
                    System.gc();

                    displayQuickFavoritesBar(true, false);
                    displayLoader(false);

                } else if (intent.getAction().equalsIgnoreCase(START_LOAD)) {
                    displayLoader(true);
                }
            }
        };

        this.registerReceiver(mReceiver, intentFilter);
        this.registerReceiver(mReceiver, intentFilterBis);
        this.registerReceiver(mReceiver, intentFilterTer);
        KissApplication.initDataHandler(this);

        // Initialize preferences
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Lock launcher into portrait mode
        // Do it here (before initializing the view) to make the transition as smooth as possible
        if (prefs.getBoolean("force-portrait", true)) {
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        }

        setContentView(R.layout.main);

        this.list = (ListView) this.findViewById(android.R.id.list);
        this.listContainer = (View) this.list.getParent();
        this.listEmpty = this.findViewById(android.R.id.empty);

        // Create adapter for records
        this.adapter = new RecordAdapter(this, this, R.layout.result, new ArrayList<Result>());
        this.list.setAdapter(this.adapter);

        this.list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                adapter.onClick((ResultView) v, v);
            }
        });
        this.adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                super.onChanged();

                if (adapter.isEmpty()) {
                    listContainer.setVisibility(View.GONE);
                    listEmpty.setVisibility(View.VISIBLE);
                } else {
                    listContainer.setVisibility(View.VISIBLE);
                    listEmpty.setVisibility(View.GONE);
                }
            }
        });

        registerLongClickOnFavorites();
        searchEditText = (EditText) findViewById(R.id.searchEditText);

        // Listen to changes
        searchEditText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                // Auto left-trim text.
                if (s.length() > 0 && s.charAt(0) == ' ')
                    s.delete(0, 1);
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s.toString();
                adjustInputType(text);
                updateRecords(text);
                displayClearOnInput();
            }
        });

        // On validate, launch first record
        searchEditText.setOnEditorActionListener(new OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                RecordAdapter adapter = ((RecordAdapter) list.getAdapter());

                adapter.onClick((ResultView) list.getChildAt(list.getChildCount() - 1), v);

                return true;
            }
        });

        kissBar = findViewById(R.id.main_kissbar);
        favoritesKissBar = findViewById(R.id.favoritesKissBar);

        menuButton = findViewById(R.id.menuButton);
        registerForContextMenu(menuButton);

        this.list.setLongClickable(true);
        this.list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                ((RecordAdapter) parent.getAdapter()).onLongClick((ResultView) v, v);
                return true;
            }
        });

        this.hider = new KeyboardScrollHider(this,
                (BlockableListView) this.list,
                (BottomPullEffectView) this.findViewById(R.id.listEdgeEffect)
        );
        this.hider.start();

        // Check whether user enabled spell check and adjust input type accordingly
        searchEditTextWorkaround = prefs.getBoolean("enable-keyboard-workaround", false);
        adjustInputType(null);

        //enable/disable phone/sms broadcast receiver
        PackageManagerUtils.enableComponent(this, IncomingSmsHandler.class, prefs.getBoolean("enable-sms-history", false));
        PackageManagerUtils.enableComponent(this, IncomingCallHandler.class, prefs.getBoolean("enable-phone-history", false));

        // Hide the "X" after the text field, instead displaying the menu button
        displayClearOnInput();

        UiTweaks.updateThemePrimaryColor(this);
        UiTweaks.tintResources(this);
    }

    private void registerLongClickOnFavorites() {
        View.OnLongClickListener listener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {

                int favNumber = Integer.parseInt((String) view.getTag());
                ArrayList<Result> favorites = KissApplication.getDataHandler(MainActivity.this).getFavorites(tryToRetrieve);
                if (favNumber >= favorites.size()) {
                    // Clicking on a favorite before everything is loaded.
                    Log.i(TAG, "Long clicking on an uninitialized favorite.");
                    return false;
                }
                // Favorites handling
                Result result = favorites.get(favNumber);
                final ResultView resultView = ResultView.create(MainActivity.this, new ResultStateManager(result, MainActivity.this));
                resultView.getPopupMenu(adapter, view).show();
                return true;
            }
        };
        for (int id : FAV_BAR_IDS) {
            findViewById(id).setOnLongClickListener(listener);
        }
        for (int id : FAVS_IDS) {
            findViewById(id).setOnLongClickListener(listener);
        }
    }

    private void adjustInputType(String currentText) {
        int currentInputType = searchEditText.getInputType();
        int requiredInputType;

        if (currentText != null && Pattern.matches("[+]\\d+", currentText)) {
            requiredInputType = InputType.TYPE_CLASS_PHONE;
        } else if (searchEditTextWorkaround) {
            requiredInputType = INPUT_TYPE_WORKAROUND;
        } else {
            requiredInputType = INPUT_TYPE_STANDARD;
        }
        if (currentInputType != requiredInputType) {
            searchEditText.setInputType(requiredInputType);
        }
    }

    private void displayQuickFavoritesBar(boolean initialize, boolean touched) {
        View quickFavoritesBar = findViewById(R.id.favoritesBar);
        if (searchEditText.getText().toString().length() == 0
                && prefs.getBoolean("enable-favorites-bar", false)) {
            if((!prefs.getBoolean("favorites-hide", false) || touched)) {
                quickFavoritesBar.setVisibility(View.VISIBLE);
            }

            if (initialize) {
                Log.i(TAG, "Using quick favorites bar, filling content.");
                favoritesKissBar.setVisibility(View.INVISIBLE);
                updateFavourites();
            }
        } else {
            quickFavoritesBar.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_settings, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return onOptionsItemSelected(item);
    }

    /**
     * Restart if required,
     * Hide the kissbar by default
     */
    @SuppressLint("CommitPrefEdits")
    protected void onResume() {
        Log.i(TAG, "Resuming KISS");

        if (prefs.getBoolean("require-layout-update", false)) {
            super.onResume();
            Log.i(TAG, "Restarting app after setting changes");
            // Restart current activity to refresh view, since some preferences
            // may require using a new UI
            prefs.edit().putBoolean("require-layout-update", false).apply();
            this.recreate();
            return;
        }

        if (kissBar.getVisibility() != View.VISIBLE) {
            updateRecords(searchEditText.getText().toString());
            displayClearOnInput();
        } else {
            displayKissBar(false);
        }

        //Show favorites above search field ONLY if AppProvider is already loaded
        //Otherwise this will get triggered by the broadcastreceiver in the onCreate
        AppProvider appProvider = KissApplication.getDataHandler(this).getAppProvider();
        if (appProvider != null && appProvider.isLoaded())
            // Favorites needs to be displayed again if the quickfavorite bar is active,
            // Not sure why exactly, but without the "true" the favorites drawable will disappear
            // (not their intent) after moving to another activity and switching back to KISS.
            displayQuickFavoritesBar(true, searchEditText.getText().toString().length() > 0);

        // Activity manifest specifies stateAlwaysHidden as windowSoftInputMode
        // so the keyboard will be hidden by default
        // we may want to display it if the setting is set
        if (prefs.getBoolean("display-keyboard", false)) {
            // Display keyboard
            showKeyboard();

            new Handler().postDelayed(displayKeyboardRunnable, 10);
            // For some weird reasons, keyboard may be hidden by the system
            // So we have to run this multiple time at different time
            // See https://github.com/Neamar/KISS/issues/119
            new Handler().postDelayed(displayKeyboardRunnable, 100);
            new Handler().postDelayed(displayKeyboardRunnable, 500);
        } else {
            // Not used (thanks windowSoftInputMode)
            // unless coming back from KISS settings
            hideKeyboard();
        }

        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        KissApplication.unsetMainActivity();
        
        // unregister our receiver
        this.unregisterReceiver(this.mReceiver);
        KissApplication.getCameraHandler().releaseCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        KissApplication.getCameraHandler().releaseCamera();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // This is called when the user press Home again while already browsing MainActivity
        // onResume() will be called right after, hiding the kissbar if any.
        // http://developer.android.com/reference/android/app/Activity.html#onNewIntent(android.content.Intent)
        // Animation can't happen in this method, since the activity is not resumed yet, so they'll happen in the onResume()
        // https://github.com/Neamar/KISS/issues/569
        if (!searchEditText.getText().toString().isEmpty()) {
            Log.i(TAG, "Clearing search field");
            searchEditText.setText("");
        }
    }

    @Override
    public void onBackPressed() {
        // Is the kiss bar visible?
        if (kissBar.getVisibility() == View.VISIBLE) {
            displayKissBar(false);
        } else if (!searchEditText.getText().toString().isEmpty()) {
            // If no kissmenu, empty the search bar
            searchEditText.setText("");
        }
        // No call to super.onBackPressed, since this would quit the launcher.
    }

    @Override
    public boolean onKeyDown(int keycode, @NonNull KeyEvent e) {
        switch (keycode) {
            case KeyEvent.KEYCODE_MENU:
                // For user with a physical menu button, we still want to display *our* contextual menu
                menuButton.showContextMenu();
                return true;
        }

        return super.onKeyDown(keycode, e);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                return true;
            case R.id.wallpaper:
                hideKeyboard();
                Intent intent = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(Intent.createChooser(intent, getString(R.string.menu_wallpaper)));
                return true;
            case R.id.preferences:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_settings, menu);

        return true;
    }

    /**
     * Display menu, on short or long press.
     *
     * @param menuButton "kebab" menu (3 dots)
     */
    public void onMenuButtonClicked(View menuButton) {
        // When the kiss bar is displayed, the button can still be clicked in a few areas (due to favorite margin)
        // To fix this, we discard any click event occurring when the kissbar is displayed
        if (kissBar.getVisibility() != View.VISIBLE)
            menuButton.showContextMenu();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        //if motion movement ends
        if ((event.getAction() == MotionEvent.ACTION_CANCEL) || (event.getAction() == MotionEvent.ACTION_UP)) {
            //if history is hidden
            if (prefs.getBoolean("history-hide", false) && prefs.getBoolean("history-onclick", false)) {
                //if not on the application list and not searching for something
                if ((kissBar.getVisibility() != View.VISIBLE) && (searchEditText.getText().toString().isEmpty())) {
                    //if list is empty
                    if ((this.list.getAdapter() == null) || (this.list.getAdapter().getCount() == 0)) {
                        searcher = new HistorySearcher(MainActivity.this);
                        searcher.execute();
                    }
                }
            }
            if (prefs.getBoolean("history-hide", false) && prefs.getBoolean("favorites-hide", false)) {
                displayQuickFavoritesBar(false, true);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Clear text content when touching the cross button
     */
    @SuppressWarnings("UnusedParameters")
    public void onClearButtonClicked(View clearButton) {
        searchEditText.setText("");
    }

    /**
     * Display KISS menu
     */
    public void onLauncherButtonClicked(View launcherButton) {
        // Display or hide the kiss bar, according to current view tag (showMenu / hideMenu).

        displayKissBar(launcherButton.getTag().equals("showMenu"));
    }

    public void onFavoriteButtonClicked(View favorite) {
        // The bar is shown due to dispatchTouchEvent, hide it again to stop the bad ux.
        displayKissBar(false);

        int favNumber = Integer.parseInt((String) favorite.getTag());
        ArrayList<Result> favorites = KissApplication.getDataHandler(MainActivity.this).getFavorites(tryToRetrieve);
        if (favNumber >= favorites.size()) {
            // Clicking on a favorite before everything is loaded.
            Log.i(TAG, "Clicking on an uninitialized favorite.");
            return;
        }
        // Favorites handling
        Result result = favorites.get(favNumber);
        final ResultView resultView = ResultView.create(MainActivity.this, new ResultStateManager(result, MainActivity.this));

        resultView.fastLaunch(favorite);
    }

    private void displayClearOnInput() {
        final View clearButton = findViewById(R.id.clearButton);
        if (searchEditText.getText().length() > 0) {
            clearButton.setVisibility(View.VISIBLE);
            menuButton.setVisibility(View.INVISIBLE);
        } else {
            clearButton.setVisibility(View.INVISIBLE);
            menuButton.setVisibility(View.VISIBLE);
        }
    }

    private void displayLoader(Boolean display) {
        final View loaderBar = findViewById(R.id.loaderBar);
        final View launcherButton = findViewById(R.id.launcherButton);

        int animationDuration = getResources().getInteger(
                android.R.integer.config_longAnimTime);

        if (!display) {
            launcherButton.setVisibility(View.VISIBLE);

            // Animate transition from loader to launch button
            launcherButton.setAlpha(0);
            launcherButton.animate()
                    .alpha(1f)
                    .setDuration(animationDuration)
                    .setListener(null);
            loaderBar.animate()
                    .alpha(0f)
                    .setDuration(animationDuration)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            loaderBar.setVisibility(View.GONE);
                            loaderBar.setAlpha(1);
                        }
                    });
        } else {
            launcherButton.setVisibility(View.INVISIBLE);
            loaderBar.setVisibility(View.VISIBLE);
        }
    }

    private void displayKissBar(Boolean display) {
        final ImageView launcherButton = (ImageView) findViewById(R.id.launcherButton);

        // get the center for the clipping circle
        int cx = (launcherButton.getLeft() + launcherButton.getRight()) / 2;
        int cy = (launcherButton.getTop() + launcherButton.getBottom()) / 2;

        // get the final radius for the clipping circle
        int finalRadius = Math.max(kissBar.getWidth(), kissBar.getHeight());

        if (display) {
            // Display the app list
            if (searcher != null) {
                searcher.cancel(true);
            }
            searcher = new ApplicationsSearcher(MainActivity.this);
            searcher.execute();

            // Reveal the bar
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Animator anim = ViewAnimationUtils.createCircularReveal(kissBar, cx, cy, 0, finalRadius);
                kissBar.setVisibility(View.VISIBLE);
                anim.start();
            } else {
                // No animation before Lollipop
                kissBar.setVisibility(View.VISIBLE);
            }

            // Only display favorites if we're not using the quick bar
            if (favoritesKissBar.getVisibility() == View.VISIBLE) {
                // Retrieve favorites. Try to retrieve more, since some favorites can't be displayed (e.g. search queries)
                updateFavourites();
            }
        } else {
            // Hide the bar
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Animator anim = ViewAnimationUtils.createCircularReveal(kissBar, cx, cy, finalRadius, 0);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        kissBar.setVisibility(View.GONE);
                        super.onAnimationEnd(animation);
                    }
                });
                anim.start();
            } else {
                // No animation before Lollipop
                kissBar.setVisibility(View.GONE);
            }
            searchEditText.setText("");

            if (prefs.getBoolean("display-keyboard", false)) {
                // Display keyboard
                showKeyboard();
            }
        }

        // Hide the favorite bar in the kiss bar if the quick bar is enabled
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("enable-favorites-bar", false)) {
            favoritesKissBar.setVisibility(View.INVISIBLE);
        } else {
            favoritesKissBar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void updateFavourites() {
        int[] favoritesIds = favoritesKissBar.getVisibility() == View.VISIBLE ? FAVS_IDS : FAV_BAR_IDS;

        ArrayList<Result> favoriteResults = KissApplication.getDataHandler(MainActivity.this)
                .getFavorites(tryToRetrieve);

        if (favoriteResults.size() == 0) {
            int noFavCnt = prefs.getInt("no-favorites-tip", 0);
            if (noFavCnt < 3 && !prefs.getBoolean("enable-favorites-bar", false)) {
                Toast toast = Toast.makeText(MainActivity.this, getString(R.string.no_favorites), Toast.LENGTH_SHORT);
                toast.show();
                prefs.edit().putInt("no-favorites-tip", ++noFavCnt).apply();

            }
        }

        // Don't look for items after favIds length, we won't be able to display them
        for (int i = 0; i < Math.min(favoritesIds.length, favoriteResults.size()); i++) {
            Result result = favoriteResults.get(i);

            final ImageView image = (ImageView) findViewById(favoritesIds[i]);
            
            if(result.userInterface.staticIcon != null) {
                image.setImageBitmap(result.userInterface.staticIcon);
            } else {
                image.setImageResource(R.drawable.ic_contact);
            }
    
            ResultStateManager stateManager = new ResultStateManager(result, MainActivity.this);
            stateManager.attachToRenderer(new ResultStateManager.IRenderer() {
                @Override
                public void onStateManagerAttached(ResultStateManager stateManager) {}
                
                @Override
                public void onStateManagerDetached(ResultStateManager stateManager) {}
                
                @Override
                public void displayIcon(Bitmap icon, boolean tintIcon) {
                    image.setImageBitmap(icon);
                }
                
                @Override
                public void displaySubicon(Bitmap icon, boolean tintIcon) {
                    // Not supported when displaying favourites
                }
    
                @Override
                public void updateButtonState(int action, boolean enabled, boolean sensitive) {
                    // Not supported when displaying favourites
                }
            });

            image.setVisibility(View.VISIBLE);
            image.setContentDescription(result.name);
        }

        // Hide empty favorites (not enough favorites yet)
        for (int i = favoriteResults.size(); i < favoritesIds.length; i++) {
            findViewById(favoritesIds[i]).setVisibility(View.GONE);
        }
    }

    @Override
    public void updateRecords() {
        updateRecords(searchEditText.getText().toString());
    }

    /**
     * This function gets called on changes. It will ask all the providers for
     * data
     *
     * @param query the query on which to search
     */
    private void updateRecords(String query) {
        if (searcher != null) {
            searcher.cancel(true);
        }

        if (query.length() == 0) {
            if (prefs.getBoolean("history-hide", false)) {
                list.setVerticalScrollBarEnabled(false);
                searchEditText.setHint("");
                searcher = new NullSearcher(this);
                //Hide default scrollview
                findViewById(R.id.main_empty).setVisibility(View.INVISIBLE);

            } else {
                list.setVerticalScrollBarEnabled(true);
                searchEditText.setHint(R.string.ui_search_hint);
                searcher = new HistorySearcher(this);
                //Show default scrollview
                findViewById(R.id.main_empty).setVisibility(View.VISIBLE);
            }
        } else {
            searcher = new QuerySearcher(this, query);
        }
        searcher.execute();
        displayQuickFavoritesBar(true, false);
    }

    public void resetTask() {
        searcher = null;
    }

    /**
     * Call this function when we're leaving the activity We can't use
     * onPause(), since it may be called for a configuration change
     */
    @Override
    public void launchOccurred(ResultView resultView, View reason) {
        // We selected an item on the list,
        // now we can cleanup the filter:
        if (!searchEditText.getText().toString().equals("")) {
            searchEditText.setText("");
            hideKeyboard();
        }
    }

    @Override
    public void showKeyboard() {
        searchEditText.requestFocus();
        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        mgr.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public void hideKeyboard() {

        // Check if no view has focus:
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager inputManager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    /**
     * Check if history / search or app list is visible
     * @return true of history, false on app list
     */
    public boolean isOnSearchView() {
        return kissBar.getVisibility() != View.VISIBLE;
    }

    public static int getFavIconsSize() {
        return FAVS_IDS.length;
    }
}
