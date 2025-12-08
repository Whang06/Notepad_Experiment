/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import static com.example.android.notepad.R.*;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

/**
 * This Activity handles "editing" a note, where editing is responding to
 * {@link Intent#ACTION_VIEW} (request to view data), edit a note
 * {@link Intent#ACTION_EDIT}, create a note {@link Intent#ACTION_INSERT}, or
 * create a new note from the current contents of the clipboard {@link Intent#ACTION_PASTE}.
 */
public class NoteEditor extends Activity {
    // For logging and debugging purposes
    private static final String TAG = "NoteEditor";

    /*
     * Creates a projection that returns the note ID and the note contents.
     */
    private static final String[] PROJECTION =
            new String[] {
                    NotePad.Notes._ID,
                    NotePad.Notes.COLUMN_NAME_TITLE,
                    NotePad.Notes.COLUMN_NAME_NOTE,
                    NotePad.Notes.COLUMN_NAME_CATEGORY
            };

    // 分类 ID 列表 (对应 NotePad.java 中的常量)
    private static final int[] CATEGORY_IDS = new int[] {
            NotePad.Notes.CATEGORY_NONE,
            NotePad.Notes.CATEGORY_WORK,
            NotePad.Notes.CATEGORY_PERSONAL,
            NotePad.Notes.CATEGORY_STUDY
    };

    // A label for the saved state of the activity
    private static final String ORIGINAL_CONTENT = "origContent";
    private static final String ORIGINAL_CATEGORY = "origCategory"; // 新增，用于保存原始分类

    // This Activity can be started by more than one action. Each action is represented
    // as a "state" constant
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    // Global mutable variables
    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private EditText mText;
    private Spinner mCategorySpinner;
    private int mCurrentCategoryId = NotePad.Notes.CATEGORY_NONE;
    private int mOriginalCategoryId = NotePad.Notes.CATEGORY_NONE; // 新增，用于保存原始分类
    private String mOriginalContent;

    /**
     * Defines a custom EditText View that draws lines between each line of text that is displayed.
     */
    public static class LinedEditText extends EditText {
        private Rect mRect;
        private Paint mPaint;

        // This constructor is used by LayoutInflater
        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);

            // Creates a Rect and a Paint object, and sets the style and color of the Paint object.
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0x800000FF);
        }

        /**
         * This is called to draw the LinedEditText object
         * @param canvas The canvas on which the background is drawn.
         */
        @Override
        protected void onDraw(Canvas canvas) {

            // Gets the number of lines of text in the View.
            int count = getLineCount();

            // Gets the global Rect and Paint objects
            Rect r = mRect;
            Paint paint = mPaint;

            /*
             * Draws one line in the rectangle for every line of text in the EditText
             */
            for (int i = 0; i < count; i++) {

                // Gets the baseline coordinates for the current line of text
                int baseline = getLineBounds(i, r);

                /*
                 * Draws a line in the background from the left of the rectangle to the right,
                 * at a vertical position one dip below the baseline, using the "paint" object
                 * for details.
                 */
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }

            // Finishes up by calling the parent method
            super.onDraw(canvas);
        }
    }

    /**
     * This method is called by Android when the Activity is first started. From the incoming
     * Intent, it determines what kind of editing is desired, and then does it.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_EDIT.equals(action)) {
            mState = STATE_EDIT;
            mUri = intent.getData();

        } else if (Intent.ACTION_INSERT.equals(action)
                || Intent.ACTION_PASTE.equals(action)) {

            mState = STATE_INSERT;
            // 在插入模式下，默认分类为 NONE (0)
            ContentValues initialValues = new ContentValues();
            initialValues.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.CATEGORY_NONE);
            mUri = getContentResolver().insert(intent.getData(), initialValues);

            if (mUri == null) {
                Log.e(TAG, "Failed to insert new note into " + getIntent().getData());
                finish();
                return;
            }

            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));

        } else {
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        mCursor = managedQuery(
                mUri,         // The URI that gets multiple notes from the provider.
                PROJECTION,   // 使用包含 CATEGORY 的 PROJECTION
                null,
                null,
                null
        );

        // For a paste, initializes the data from clipboard.
        if (Intent.ACTION_PASTE.equals(action)) {
            performPaste();
            mState = STATE_EDIT;
        }

        // Sets the layout for this Activity. See res/layout/note_editor.xml
        setContentView(R.layout.note_editor);

        // Gets a handle to the EditText in the the layout.
        mText = (EditText) findViewById(R.id.note);

        // 初始化 Spinner
        mCategorySpinner = (Spinner) findViewById(R.id.category_spinner);

        // 创建 ArrayAdapter，从 strings.xml 中获取分类名称数组
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.category_names,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(adapter);

        // 设置 Spinner 监听器
        mCategorySpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 将选中的位置 (position) 映射到 CATEGORY_IDS 数组中对应的分类常量
                mCurrentCategoryId = CATEGORY_IDS[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Not implemented
            }
        });


        /*
         * If this Activity had stopped previously, its state was written the ORIGINAL_CONTENT
         * location in the saved Instance state. This gets the state.
         */
        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
            mOriginalCategoryId = savedInstanceState.getInt(ORIGINAL_CATEGORY, NotePad.Notes.CATEGORY_NONE);
        }
    }

    /**
     * This method is called when the Activity is about to come to the foreground.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (mCursor != null) {
            // Requery in case something changed while paused (such as the title)
            mCursor.requery();

            mCursor.moveToFirst();

            if (mState == STATE_EDIT) {
                // Set the title of the Activity to include the note title
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                String title = mCursor.getString(colTitleIndex);
                Resources res = getResources();
                String text = String.format(res.getString(R.string.title_edit), title);
                setTitle(text);

                // 获取并设置当前笔记的分类
                int colCategoryIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);
                int savedCategory = mCursor.getInt(colCategoryIndex);

                // 查找 savedCategory 对应的索引位置
                int categoryIndex = 0;
                for (int i = 0; i < CATEGORY_IDS.length; i++) {
                    if (CATEGORY_IDS[i] == savedCategory) {
                        categoryIndex = i;
                        break;
                    }
                }
                mCategorySpinner.setSelection(categoryIndex);
                mCurrentCategoryId = savedCategory;


                // Sets the title to "create" for inserts
            } else if (mState == STATE_INSERT) {
                setTitle(getText(R.string.title_create));
                // 新插入的笔记默认选择第一个分类 (CATEGORY_NONE)
                mCategorySpinner.setSelection(0);
                mCurrentCategoryId = CATEGORY_IDS[0];
            }

            // Gets the note text from the Cursor and puts it in the TextView, but doesn't change
            // the text cursor's position.
            int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
            String note = mCursor.getString(colNoteIndex);
            mText.setTextKeepState(note);

            // Stores the original note text and category ID, to allow the user to revert changes.
            if (mOriginalContent == null) {
                mOriginalContent = note;

                int colCategoryIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);
                mOriginalCategoryId = mCursor.getInt(colCategoryIndex);
            }

        } else {
            setTitle(getText(R.string.error_title));
            mText.setText(getText(R.string.error_message));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Save away the original text and category, so we still have it if the activity
        // needs to be killed while paused.
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
        outState.putInt(ORIGINAL_CATEGORY, mOriginalCategoryId);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mCursor != null) {

            // Get the current note text.
            String text = mText.getText().toString();
            int length = text.length();

            if (isFinishing() && (length == 0)) {
                setResult(RESULT_CANCELED);
                deleteNote();

            } else if (mState == STATE_EDIT) {
                // Creates a map to contain the new values for the columns
                updateNote(text, null);
            } else if (mState == STATE_INSERT) {
                // 只有当笔记内容不为空时才保存
                if (length > 0) {
                    updateNote(text, text);
                    mState = STATE_EDIT;
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);

        // Only add extra menu items for a saved note
        if (mState == STATE_EDIT) {
            // Append to the
            // menu items for any other activities that can do stuff with it
            // as well.  This does a query on the system for any activities that
            // implement the ALTERNATIVE_ACTION for our data, adding a menu item
            // for each one that is found.
            Intent intent = new Intent(null, mUri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                    new ComponentName(this, NoteEditor.class), null, intent, 0, null);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Check if note has changed and enable/disable the revert option
        // Note: mCursor is updated in onResume, but we use mOriginalContent/Id for comparison

        String currentNote = mText.getText().toString();

        // 检查文本或分类是否更改
        boolean contentChanged = !mOriginalContent.equals(currentNote)
                || (mOriginalCategoryId != mCurrentCategoryId);

        if (!contentChanged) {
            menu.findItem(R.id.menu_revert).setVisible(false);
        } else {
            menu.findItem(R.id.menu_revert).setVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        int id = item.getItemId();
        if(id== R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null);
            finish();
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
        } else if (id == R.id.menu_revert) {
            cancelNote();
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * A helper method that replaces the note's data with the contents of the clipboard.
     */
    private final void performPaste() {

        // Gets a handle to the Clipboard Manager
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        // Gets a content resolver instance
        ContentResolver cr = getContentResolver();

        // Gets the clipboard data from the clipboard
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null) {

            String text=null;
            String title=null;
            int category = NotePad.Notes.CATEGORY_NONE;

            // Gets the first item from the clipboard data
            ClipData.Item item = clip.getItemAt(0);

            // Tries to get the item's contents as a URI pointing to a note
            Uri uri = item.getUri();

            // Tests to see that the item actually is an URI, and that the URI
            // is a content URI pointing to a provider whose MIME type is the same
            // as the MIME type supported by the Note pad provider.
            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {

                // The clipboard holds a reference to data with a note MIME type. This copies it.
                Cursor orig = cr.query(
                        uri,            // URI for the content provider
                        PROJECTION,     // 使用包含 CATEGORY 的 PROJECTION
                        null,           // No selection variables
                        null,           // No selection variables, so no criteria are needed
                        null            // Use the default sort order
                );

                // If the Cursor is not null, and it contains at least one record
                // (moveToFirst() returns true), then this gets the note data from it.
                if (orig != null) {
                    if (orig.moveToFirst()) {
                        int colNoteIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                        int colTitleIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                        int colCategoryIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);

                        text = orig.getString(colNoteIndex);
                        title = orig.getString(colTitleIndex);
                        category = orig.getInt(colCategoryIndex);
                    }

                    // Closes the cursor.
                    orig.close();
                }
            }

            // If the contents of the clipboard wasn't a reference to a note, then
            // this converts whatever it is to text.
            if (text == null) {
                text = item.coerceToText(this).toString();
            }

            // Updates the current note with the retrieved title and text.
            updateNote(text, title, category);
        }
    }

    /**
     * Replaces the current note contents with the text and title provided as arguments.
     * @param text The new note contents to use.
     * @param title The new note title to use
     */
    private final void updateNote(String text, String title) {
        // 重载方法，调用带 category 的方法，使用当前选中的 mCurrentCategoryId
        updateNote(text, title, mCurrentCategoryId);
    }

    /**
     * Replaces the current note contents with the text and title provided as arguments.
     * @param text The new note contents to use.
     * @param title The new note title to use
     * @param category The new note category ID
     */
    private final void updateNote(String text, String title, int category) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        // 添加分类到 ContentValues
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, category);

        // If the action is to insert a new note, this creates an initial title for it.
        if (mState == STATE_INSERT) {

            // If no title was provided as an argument, create one from the note text.
            if (title == null) {

                // Get the note's length
                int length = text.length();

                // Sets the title by getting a substring of the text that is 31 characters long
                // or the number of characters in the note plus one, whichever is smaller.
                title = text.substring(0, Math.min(30, length));

                // If the resulting length is more than 30 characters, chops off any
                // trailing spaces
                if (length > 30) {
                    int lastSpace = title.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        title = title.substring(0, lastSpace);
                    }
                }
            }
            // In the values map, sets the value of the title
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            // In the values map, sets the value of the title
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }

        // This puts the desired notes text into the map.
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);

        /*
         * Updates the provider with the new values in the map.
         */
        getContentResolver().update(
                mUri,    // The URI for the record to update.
                values,  // The map of column names and new values to apply to them.
                null,    // No selection criteria are used, so no where columns are necessary.
                null     // No where columns are used, so no where arguments are necessary.
        );

        // 更新原始数据以匹配保存后的状态
        mOriginalContent = text;
        mOriginalCategoryId = category;


    }

    /**
     * This helper method cancels the work done on a note.
     */
    private final void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                // Put the original note text and original category back into the database
                mCursor.close();
                mCursor = null;

                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_NOTE, mOriginalContent);
                values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mOriginalCategoryId); // 恢复原始分类

                getContentResolver().update(mUri, values, null, null);

            } else if (mState == STATE_INSERT) {
                // We inserted an empty note, make sure to delete it
                deleteNote();
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Take care of deleting a note.  Simply deletes the entry.
     */
    private final void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
        }
    }
}