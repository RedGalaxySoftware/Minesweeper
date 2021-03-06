package net.rgsw.minesweeper.game;

import android.animation.ArgbEvaluator;
import android.animation.FloatEvaluator;
import android.animation.PointFEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PointF;
import android.os.*;
import android.support.annotation.StringRes;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.view.animation.LinearOutSlowInInterpolator;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.CardView;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import net.rgsw.ctable.io.CTableDecoder;
import net.rgsw.ctable.io.CTableEncoder;
import net.rgsw.ctable.io.CTableReader;
import net.rgsw.ctable.io.CTableWriter;
import net.rgsw.ctable.tag.TagStringCompound;
import net.rgsw.minesweeper.R;
import net.rgsw.minesweeper.game.hint.*;
import net.rgsw.minesweeper.main.Mode;
import net.rgsw.minesweeper.settings.Configuration;
import net.rgsw.minesweeper.settings.ELongPressBehavior;
import net.rgsw.minesweeper.settings.EShowDialogOnEndBehavior;
import net.rgsw.minesweeper.util.TwoDScrollView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

public class GameActivity extends AppCompatActivity implements ICellInvalidator, IEffects {

    private Mode mode = null;
    private MinesweeperGame game;
    private TextView timeView;
    private TextView minesView;
    private Toolbar toolbar;
    private Handler handler;
    private FloatingActionButton modeButton;
    private TwoDScrollView gameScrollView;
    private CardView hintCard;
    private boolean hintCardShown = false;
    private TextView hintText;
    private FrameLayout gameLayout;
    private MenuItem pauseIcon;
    private MenuItem faceIcon;
    private boolean flagMode;
    private boolean finalUpdate;
    private int canvasOffsetX;
    private int canvasOffsetY;

    private Vibrator vibrator;

    private int chunkSize;

    private MinesweeperCanvas[] chunks;
    private int width, height;

    @SuppressLint( "RtlHardcoded" )
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setTheme( Configuration.useDarkTheme.getValue() ? R.style.AppTheme_Dark_NoActionBar : R.style.AppTheme_NoActionBar );
        setContentView( R.layout.activity_game );

        if( Configuration.keepScreenOn.getValue() ) {
            getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
        }

        // Create action bar
        setSupportActionBar( toolbar = findViewById( R.id.toolbar2 ) );
        ActionBar actionBar = getSupportActionBar();
        if( actionBar != null ) {
            // Show the back arrow in the toolbar
            getSupportActionBar().setDisplayHomeAsUpEnabled( true );
            getSupportActionBar().setDisplayShowHomeEnabled( true );
        }

        // Start or resume game based on current activity state
        if( savedInstanceState != null && savedInstanceState.getBoolean( "playing", false ) ) {
            // Activity was killed, resume...
            resume();
        } else if( getIntent().getBooleanExtra( "resuming", false ) ) {
            // Resume button was pressed in main menu...
            resume();
        } else {
            // Nothing indicates that a game should be resumed: start a new one...
            init();
        }

        // Handler for handling delayed events
        handler = new Handler();

        // Find some views
        timeView = findViewById( R.id.game_time );              // The time in the action bar
        minesView = findViewById( R.id.game_remaining_mines );  // The amount of flags left in the action bar
        modeButton = findViewById( R.id.mode_button );          // The mode switch FAB
        gameScrollView = findViewById( R.id.game_sv );          // The game scroll view
        hintCard = findViewById( R.id.hintCard );               // The hint card view
        hintText = findViewById( R.id.hintText );               // The hint text view
        gameLayout = findViewById( R.id.game_fl );           // Game layout

        hintCard.measure( View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED );
        hintCard.setTranslationY( -hintCard.getMeasuredHeight() * 1.5F );
        hintCard.invalidate();

        canvasOffsetX = gameLayout.getPaddingLeft();
        canvasOffsetY = gameLayout.getPaddingTop();

        // Make the mode switch do the actual switching
        modeButton.setOnClickListener( this::modeButtonPress );

        // Set mode switch position based on settings
        LinearLayout layout = findViewById( R.id.mode_button_layout );
        switch( Configuration.modeButtonPos.getValue() ) {
            case LEFT:
                layout.setGravity( Gravity.LEFT ); break;
            case MIDDLE:
                layout.setGravity( Gravity.CENTER_HORIZONTAL ); break;
            case RIGHT:
                layout.setGravity( Gravity.RIGHT ); break;
        }


        // Place a time update in the queue
        while( !handler.postDelayed( this::updateTime, 200 ) ) ; // Try until placed



        chunkSize = Configuration.gameChunkSize.getValue();

        // Create chunks
        LinearLayout gameChunkLayout = findViewById( R.id.gameChunks );

        width = game.width() / chunkSize + 1;
        height = game.height() / chunkSize + 1;

        chunks = new MinesweeperCanvas[ width * height ];

        for( int y = 0; y < height; y++ ) {
            LinearLayout row = new LinearLayout( this );
            row.setOrientation( LinearLayout.HORIZONTAL );
            LinearLayout.LayoutParams params1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            for( int x = 0; x < width; x++ ) {
                MinesweeperCanvas canvas = create( x, y );
                LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                row.addView( canvas, params2 );

                canvas.setClickable( true );
                canvas.setFocusable( true );
                canvas.setLongClickable( true );

                canvas.setGame( game );

                chunks[ x * height + y ] = canvas;
            }
            gameChunkLayout.addView( row, params1 );
        }

        gameChunkLayout.invalidate();

        game.setInvalidator( this );
        game.setEffects( this );

        vibrator = ( Vibrator ) getSystemService( VIBRATOR_SERVICE );


        // Update corner radius of game board shadow
        CardView gameCard = findViewById( R.id.game_cardview );
        gameCard.setRadius( toPx( Configuration.gameCornerRadius.getValue() ) );
        gameCard.invalidate(); // Mark for re-render


        registerHints();

        // Start updating game state
        updateGameState();
    }

    private MinesweeperCanvas create( int cx, int cy ) {
        MinesweeperCanvas gameCanvas = new MinesweeperCanvas( this );
        // Dark/Light theme applies automatically, since colors are defined in 'values/styles.xml'

        // Update some values based on settings
        gameCanvas.setGridEnabled( Configuration.checkerboardGrid.getValue() );
        gameCanvas.setCellSize( toPx( Configuration.gameCellSize.getValue() ) );
        gameCanvas.setIconSize( toPx( Configuration.gameIconSize.getValue() ) );
        gameCanvas.setCornerRounding( toPx( Configuration.gameCornerRadius.getValue() ) );

        gameCanvas.setGame( game );
        gameCanvas.setChunk( new BasicGameChunk( cx, cy, chunkSize ) );
        initCanvas( gameCanvas );
        return gameCanvas;
    }

    private int toPx( int dp ) {
        return Math.round( dp * ( getResources().getDisplayMetrics().xdpi / DisplayMetrics.DENSITY_DEFAULT ) );
    }

    // Animates the FAB color
    public void setFABColor( int old, int now ) {
        ValueAnimator animator = ValueAnimator.ofObject( new ArgbEvaluator(), old, now );
        animator.addUpdateListener( animation -> {
            int val = ( int ) animation.getAnimatedValue();
            modeButton.setSupportBackgroundTintList( ColorStateList.valueOf( val ) );
        } );
        animator.setDuration( 300 );
        animator.start();
    }

    // Called on FAB click
    public void modeButtonPress( View v ) {
        if( !game.isInitialized() ) return;
        if( flagMode ) {
            flagMode = false; // Switch to reveal mode
            setFABColor( 0xff00e676, 0xffff5252 );
            modeButton.setImageResource( R.drawable.ic_mode_reveal );
        } else {
            flagMode = true; // Switch to flag mode
            setFABColor( 0xffff5252, 0xff00e676 );
            modeButton.setImageResource( R.drawable.ic_mode_flag );
        }
    }

    @SuppressLint( "DefaultLocale" )
    public void updateTime() {
        long timeMS = game.getTimeMS();

        String formatted = String.format(
                "%d:%02d:%02d",
                ( ( timeMS / ( 1000 * 60 * 60 ) ) % 24 ),
                ( ( timeMS / ( 1000 * 60 ) ) % 60 ),
                ( ( timeMS / ( 1000 ) ) % 60 )
        );

        timeView.setText( formatted );
        timeView.invalidate(); // Mark for re-render

        // Place a time update in the queue
        while( !handler.postDelayed( this::updateTime, 200 ) ) ; // Try until placed
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        getMenuInflater().inflate( R.menu.menu_game, menu );
        pauseIcon = menu.findItem( R.id.menu_pause );
        faceIcon = menu.findItem( R.id.menu_face );
        menu.findItem( R.id.menu_hint ).setVisible( Configuration.showHintOption.getValue() );

        // Update game state again so that icons are updated too
        updateGameState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected( MenuItem item ) {
        int id = item.getItemId();

        if( id == R.id.menu_pause ) { // Pause/play
            if( !game.done() ) {
                if( game.isPaused() ) {
                    game.resume(); // Resume button
                    pauseIcon.setIcon( R.drawable.ic_game_pause );
                } else {
                    game.pause(); // Pause button
                    pauseIcon.setIcon( R.drawable.ic_game_play );
                }
            }

            // Re-render
            toolbar.invalidate();
            return true;
        }

        if( id == R.id.menu_face ) { // Face: only play new game when done
            if( game.done() ) {
                newGame();
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder( this );
                builder.setMessage( R.string.confirm_new_game );
                builder.setPositiveButton( R.string.confirm_yes, ( d, w ) -> newGame() );
                builder.setNegativeButton( R.string.confirm_no, null );
                builder.show();
            }
            return true;
        }

        if( id == R.id.menu_new_game ) { // New game, even if we are still playing
            newGame(); // No need for dialog
            return true;
        }

        if( id == R.id.menu_main_menu ) { // Main menu, just finish the activity
            finish();
            return true;
        }

        if( id == R.id.menu_discard ) { // Main menu, and do not save game
            AlertDialog.Builder builder = new AlertDialog.Builder( this );
            builder.setMessage( R.string.confirm_discard );
            builder.setPositiveButton( R.string.confirm_yes, ( d, w ) -> {
                Intent intent = new Intent();
                TagStringCompound cpd = new TagStringCompound();
                mode.save( cpd ); // Serialize mode in ctable format
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                CTableEncoder encoder = new CTableEncoder( stream );
                try {
                    encoder.write( cpd );
                } catch( IOException e ) {
                    e.printStackTrace();
                    setResult( RESULT_CANCELED );
                    finish();
                    return;
                }
                intent.putExtra( "mode", stream.toByteArray() );
                intent.putExtra( "undone", false ); // Set as done, we don't need to restore game state again
                setResult( RESULT_OK, intent );
                finish();
            } );
            builder.setNegativeButton( R.string.confirm_no, null );
            builder.show();
            return true;
        }

        if( id == R.id.menu_hint ) { // Main menu, just finish the activity
            hint();
            return true;
        }

        return super.onOptionsItemSelected( item );
    }

    // Initializes a new game
    public void init() {
        byte[] bytes = getIntent().getByteArrayExtra( "mode" );
        if( bytes == null ) {
            finish();
            return;
        }
        ByteArrayInputStream stream = new ByteArrayInputStream( bytes );
        CTableDecoder decoder = new CTableDecoder( stream );
        try {
            TagStringCompound cpd = decoder.readTagStringCompound();
            mode = new Mode( cpd );
        } catch( IOException e ) {
            e.printStackTrace();
            finish();
            return;
        }

        this.game = new MinesweeperGame( mode );

        // Updates the played games counter
        mode.started();
    }

    // Loads from savedState.dat
    public void resume() {
        File filesDir = getFilesDir();
        try( CTableReader reader = new CTableReader( new File( filesDir, "savedState.dat" ), false, false ) ) {
            reader.init();
            TagStringCompound cpd = reader.readTagStringCompound();
            game = new MinesweeperGame( null );
            game.load( cpd );
            mode = game.getMode();
        } catch( Exception e ) {
            e.printStackTrace();
            init();
            return;
        }
    }

    // Saves the game state
    public void save() {
        File filesDir = getFilesDir();
        try( CTableWriter writer = new CTableWriter( new File( filesDir, "savedState.dat" ), false, false ) ) {
            TagStringCompound cpd = new TagStringCompound();
            game.save( cpd );
            writer.write( cpd );
        } catch( Exception exc ) {
            exc.printStackTrace();
            // Finish... Something went wrong...
            finish();
        }
    }

    // Store game states on stop
    @Override
    protected void onStop() {
        super.onStop();
        save();
    }

    // Store game states on pause
    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    public void initCanvas( MinesweeperCanvas canvas ) {
        // Init cell click listeners
        canvas.setOnCellClickListener( this::onClick );
        canvas.setOnCellLongClickListener( this::onLongClick );
    }

    public void updateGameState() {
        if( finalUpdate ) return;
        int remaining = game.mines() - game.totalFlags();
        minesView.setText( getString( R.string.simple_decimal_format, remaining ) );


        if( faceIcon != null ) faceIcon.setIcon( R.drawable.ic_face_alive );
        if( game.isPaused() ) {
            if( pauseIcon != null ) pauseIcon.setIcon( R.drawable.ic_game_play );
        } else {
            if( pauseIcon != null ) pauseIcon.setIcon( R.drawable.ic_game_pause );
        }

        if( game.done() ) {
            EShowDialogOnEndBehavior behavior = Configuration.endDialogBehavior.getValue();
            if( pauseIcon != null ) pauseIcon.setIcon( R.drawable.ic_game_stopped );
            if( game.won() ) {
                // Won!
                if( faceIcon != null ) faceIcon.setIcon( R.drawable.ic_face_happy );
                if( minesView != null ) minesView.setText( getString( R.string.simple_decimal_format, 0 ) );

                if( behavior == EShowDialogOnEndBehavior.ALWAYS || behavior == EShowDialogOnEndBehavior.ON_WIN ) {
                    // Show a dialog
                    WinDialog dialog = new WinDialog( this );
                    long bestTimeMS = mode.getBestTime();
                    long timeMS = game.getTimeMS();
                    dialog.show( game.mines(), game.getTimeMS(), bestTimeMS < 0 || timeMS < bestTimeMS );
                }
            } else {
                // Lost!
                if( faceIcon != null ) faceIcon.setIcon( R.drawable.ic_face_dead );

                if( behavior == EShowDialogOnEndBehavior.ALWAYS || behavior == EShowDialogOnEndBehavior.ON_LOSE ) {
                    // Show a dialog
                    LoseDialog dialog = new LoseDialog( this );
                    dialog.show( game.getFlaggedMines(), game.mines(), game.getRevealedRelative() );
                }
            }

            finalUpdate = true;

            // Update stats
            mode.played( game.won(), game.getTimeMS() );
        }

        // Re-render
        toolbar.invalidate();


        // Save mode and stats to activity result, so that stats get updated when exiting the activity
        Intent intent = new Intent();
        TagStringCompound cpd = new TagStringCompound();
        mode.save( cpd ); // Serialize mode in ctable format
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CTableEncoder encoder = new CTableEncoder( stream );
        try {
            encoder.write( cpd );
        } catch( IOException e ) {
            e.printStackTrace();
            setResult( RESULT_CANCELED );
            return;
        }
        intent.putExtra( "mode", stream.toByteArray() );
        intent.putExtra( "undone", !game.done() );
        setResult( RESULT_OK, intent );
    }

    // Called when a cell is pressed
    public void onClick( MinesweeperCanvas canvas, int x, int y ) {
        game.doInput( x, y, flagMode ? MinesweeperGame.Flag.FLAG : null );
        canvas.invalidate();
        updateGameState();
        hideHint( false );
    }

    // Called when a cell is long-pressed
    public void onLongClick( MinesweeperCanvas canvas, int x, int y ) {
        ELongPressBehavior behavior = Configuration.longPressBehavior.getValue();
        if( behavior == ELongPressBehavior.OFF ) return;

        MinesweeperGame.Flag flag = null;
        if( behavior == ELongPressBehavior.FLAG_DIG ) flag = flagMode ? null : MinesweeperGame.Flag.FLAG;
        if( behavior == ELongPressBehavior.FLAG_SOFTMARK ) {
            flag = flagMode ? MinesweeperGame.Flag.SOFT_MARK : MinesweeperGame.Flag.FLAG;
        }
        if( behavior == ELongPressBehavior.SOFTMARK_ONLY ) flag = MinesweeperGame.Flag.SOFT_MARK;
        game.doInput( x, y, flag );
        canvas.invalidate();
        updateGameState();
        hideHint( false );
    }

    @Override
    protected void onSaveInstanceState( Bundle outState ) {
        super.onSaveInstanceState( outState );

        // Save the game, so that we can load it again on activity start
        save();

        // Store that we were playing the game, and that it should be reloaded when the activity opens again...
        outState.putBoolean( "playing", true );
    }

    public void doneAndFinish() {
        Intent intent = new Intent();
        TagStringCompound cpd = new TagStringCompound();
        mode.save( cpd ); // Serialize mode in ctable format
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CTableEncoder encoder = new CTableEncoder( stream );
        try {
            encoder.write( cpd );
        } catch( IOException e ) {
            e.printStackTrace();
            setResult( RESULT_CANCELED );
            return;
        }
        intent.putExtra( "mode", stream.toByteArray() );
        intent.putExtra( "undone", false );
        setResult( RESULT_OK, intent );
        finish();
    }

    @Override
    public void onMultiWindowModeChanged( boolean isInMultiWindowMode ) {
        super.onMultiWindowModeChanged( isInMultiWindowMode );
    }

    @Override
    public void onPictureInPictureModeChanged( boolean isInPictureInPictureMode ) {
        super.onPictureInPictureModeChanged( isInPictureInPictureMode );
    }

    // Do a new game
    public void newGame() {
        faceIcon.setIcon( R.drawable.ic_face_alive );
        pauseIcon.setIcon( R.drawable.ic_game_pause );
        finalUpdate = false;
        game.reset();
        if( flagMode ) {
            setFABColor( 0xff00e676, 0xffff5252 );
            modeButton.setImageResource( R.drawable.ic_mode_reveal );
            flagMode = false;
        }
        mode.started();
        updateGameState();
        toolbar.invalidate();
    }

    private boolean outOfBounds( int x, int y ) {
        return x < 0 || y < 0 || x >= game.width() || y >= game.height();
    }

    private void internalInvalidate( int x, int y ) {
        if( outOfBounds( x, y ) ) return;
        int chunkx = x / chunkSize;
        int chunky = y / chunkSize;

        chunks[ chunkx * height + chunky ].invalidate();
    }

    @Override
    public void invalidateCell( int x, int y ) {
        // Invalidate surroundings to update connections and inferred flags
        internalInvalidate( x, y );
        internalInvalidate( x, y - 1 );
        internalInvalidate( x, y + 1 );
        internalInvalidate( x - 1, y );
        internalInvalidate( x + 1, y );

        if( Configuration.showInferredFlags.getValue() ) {
            // These are only for inferred flags, save some performance when disalbed
            internalInvalidate( x + 1, y + 1 );
            internalInvalidate( x - 1, y + 1 );
            internalInvalidate( x - 1, y - 1 );
            internalInvalidate( x + 1, y - 1 );
        }
    }

    @Override
    public void invalidateAll() {
        for( MinesweeperCanvas canvas : chunks ) {
            canvas.invalidate();
        }
    }

    /**
     * Returns the value of the game cell size setting in pixels (setting is in dp)
     * @return The game cell size in pixels
     */
    public int getGameCellSizeInPX() {
        return toPx( Configuration.gameCellSize.getValue() );
    }

    /**
     * Scrolls a specific cell into the visible area, with animation.
     * @param loc The location to show
     */
    public void scrollCellIntoView( Location loc ) {
        int gcsipx = getGameCellSizeInPX();
        int x = loc.x * gcsipx + gcsipx / 2 + canvasOffsetX - gameScrollView.getWidth() / 2;
        int y = loc.y * gcsipx + gcsipx / 2 + canvasOffsetY - gameScrollView.getHeight() / 2;

        int sx = gameScrollView.getScrollX();
        int sy = gameScrollView.getScrollY();

        int dx = sx - x;
        int dy = sy - y;

        double dist = Math.sqrt( dx * dx + dy * dy );

        ValueAnimator animator = ValueAnimator.ofObject( new PointFEvaluator(), new PointF( sx, sy ), new PointF( x, y ) );
        animator.setDuration( ( int ) ( dist * 0.3 ) );
        animator.setInterpolator( new AccelerateDecelerateInterpolator() );
        animator.addUpdateListener( animation -> {
            PointF value = ( PointF ) animation.getAnimatedValue();
            gameScrollView.scrollTo( ( int ) value.x, ( int ) value.y );
        } );
        animator.start();
    }

    /**
     * Updates the hint card text
     * @param txt The string resource
     */
    public void setHintText( @StringRes int txt ) {
        hintText.setText( txt );

        hintCard.measure( View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED );

        if( !hintCardShown ) {
            hintCard.setTranslationY( -hintCard.getMeasuredHeight() * 1.5F );
        }
    }

    /**
     * Shows the hint card with animation
     */
    public void showHintCard() {
        if( !hintCardShown ) {
            hintCard.measure( View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED );
            ValueAnimator animator = ValueAnimator.ofObject( new FloatEvaluator(), hintCard.getTranslationY(), 0 );
            animator.addUpdateListener( animation -> hintCard.setTranslationY( ( float ) animation.getAnimatedValue() ) );
            animator.setDuration( 200 );
            animator.setInterpolator( new LinearOutSlowInInterpolator() );
            animator.start();
            hintCardShown = true;
        }
    }

    /**
     * Hides the hint card with animation.
     */
    public void hideHintCard() {
        if( hintCardShown ) {
            hintCard.measure( View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED );
            ValueAnimator animator = ValueAnimator.ofObject( new FloatEvaluator(), hintCard.getTranslationY(), -hintCard.getMeasuredHeight() * 1.5F );
            animator.addUpdateListener( animation -> hintCard.setTranslationY( ( float ) animation.getAnimatedValue() ) );
            animator.setDuration( 200 );
            animator.setInterpolator( new AccelerateInterpolator() );
            animator.start();
            hintCardShown = false;
        }
    }

    /**
     * Hides the currently shown hint if not done, or when forced
     * @param brute If the hint should be forced to hide.
     */
    public void hideHint( boolean brute ) {
        if( brute || game.isHintDone() ) {
            hideHintCard();
            game.hideHint();
        }
    }

    /**
     * Infers a hint and shows that. When the hint needs to show a specific location, it will automatically scroll that
     * location into view.
     */
    public void hint() {
        game.inferHint();
        Hint hint = game.getShownHint();

        Location loc = hint.getViewLocation();
        if( loc != null ) {
            scrollCellIntoView( loc );
        }

        setHintText( hint.getMessageResource() );
        showHintCard();
    }

    /**
     * Registers the hints in the game. This is called in the {@link #onCreate} method.
     */
    public void registerHints() {
        game.addHint( -1, new NotJetStartedHint() );        // Most important hint
        game.addHint( -1, new EndedGameHint() );
        game.addHint( -1, new TooManyFlagsHint() );
        game.addHint( -1, new RandomFlagHint() );
        game.addHint( -1, new FlagNextToZeroHint() );
        game.addHint( -1, new StraightforwardNumberHint() );
        game.addHint( -1, new CompletedNumberHint() );
        game.addHint( -1, new TankSolverHint( game, 8 ) );  // Least important hint
        // Game will fall back on GuessHint automatically: No need for adding that to the game...
    }

    @Override
    public void fxNumber( boolean dig ) {
        if( dig ) {
            vibrate( 20 );
        }
    }

    private void vibrate( int ms ) {
        if( !Configuration.vibration.getValue() ) return;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            vibrator.vibrate( VibrationEffect.createOneShot( ms, VibrationEffect.DEFAULT_AMPLITUDE ) );
        } else {
            vibrator.vibrate( ms );
        }
    }

    private void vibrate( long... ms ) {
        if( !Configuration.vibration.getValue() ) return;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            vibrator.vibrate( VibrationEffect.createWaveform( ms, -1 ) );
        } else {
            vibrator.vibrate( ms, -1 );
        }
    }

    @Override
    public void fxExplode() {
        vibrate( 50 );
    }

    @Override
    public void fxFlag() {

    }

    @Override
    public void fxSoftMark() {

    }

    @Override
    public void fxWin() {
        vibrate( 0, 30, 20, 30 );
    }

    @Override
    public void fxDig() {
        vibrate( 20 );
    }
}
