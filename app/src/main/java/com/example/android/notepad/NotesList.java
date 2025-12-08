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

import com.example.android.notepad.NotePad;

import android.app.ListActivity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.SearchView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Displays a list of notes. Will display notes from the {@link Uri}
 * provided in the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 */
public class NotesList extends ListActivity {

    // For logging and debugging
    private static final String TAG = "NotesList";

    /**
     * The columns needed by the cursor adapter
     */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID, // 0
            NotePad.Notes.COLUMN_NAME_TITLE, // 1
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, // 2 (时间戳)
            NotePad.Notes.COLUMN_NAME_CATEGORY // 3
    };

    /** The index of the title column */
    private static final int COLUMN_INDEX_TITLE = 1;
    private static final int COLUMN_INDEX_MODIFICATION_DATE = 2; // 时间戳索引
    private static final int COLUMN_INDEX_CATEGORY = 3; // 分类索引

    private SimpleCursorAdapter mAdapter;
    private String mCurrentFilter = null; // 用于存储当前的搜索关键词

    /**
     * 将分类 ID 转换为对应的字符串名称
     */
    private String getCategoryName(int categoryId) {
        // 从 strings.xml 中获取分类名称
        String[] categoryNames = getResources().getStringArray(R.array.category_names);

        // 映射 NotePad.java 中的 CATEGORY_常量 到 strings.xml 数组的索引
        // CATEGORY_NONE=0 -> categoryNames[0]
        // CATEGORY_WORK=1 -> categoryNames[1]
        // ...

        int index;
        switch (categoryId) {
            case NotePad.Notes.CATEGORY_WORK:
                index = 1;
                break;
            case NotePad.Notes.CATEGORY_PERSONAL:
                index = 2;
                break;
            case NotePad.Notes.CATEGORY_STUDY:
                index = 3;
                break;
            case NotePad.Notes.CATEGORY_NONE:
            default:
                index = 0; // 默认是 "无"
                break;
        }

        if (index >= 0 && index < categoryNames.length) {
            return categoryNames[index];
        }
        return "未知";
    }

    /**
     * onCreate is called when Android starts this Activity from scratch.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        Intent intent = getIntent();

        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }

        getListView().setOnCreateContextMenuListener(this);

        String[] dataColumns = {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                NotePad.Notes.COLUMN_NAME_CATEGORY
        };

        // 对应 noteslist_item.xml 中的 TextView ID
        int[] viewIDs = {
                android.R.id.text1,
                R.id.note_timestamp,
                R.id.category_text
        };

        Cursor cursor = managedQuery(
                getIntent().getData(),
                PROJECTION,
                null,
                null,
                NotePad.Notes.DEFAULT_SORT_ORDER
        );

        mAdapter = new SimpleCursorAdapter(
                this,
                R.layout.noteslist_item, // 使用包含时间戳和分类的自定义布局
                cursor,
                dataColumns,
                viewIDs,
                0
        );

        // 使用 ViewBinder 来格式化时间戳和分类 ID
        mAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {

                // 1. 处理时间戳 (修改时间列)
                if (columnIndex == COLUMN_INDEX_MODIFICATION_DATE) {
                    TextView textView = (TextView) view;
                    long timestamp = cursor.getLong(columnIndex);

                    String dateString = new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            Locale.getDefault()
                    ).format(new Date(timestamp));

                    textView.setText(dateString);
                    return true;
                }

                // 2. 处理分类
                if (columnIndex == COLUMN_INDEX_CATEGORY) {
                    TextView textView = (TextView) view;
                    int categoryId = cursor.getInt(columnIndex);
                    String categoryName = getCategoryName(categoryId);
                    textView.setText("[" + categoryName + "]");
                    return true;
                }

                // 3. 其他列由默认处理
                return false;
            }
        });

        setListAdapter(mAdapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        // 配置搜索功能
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        if (searchItem != null) {
            SearchView searchView = (SearchView) searchItem.getActionView();

            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    doQueryAndChangeCursor(query);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    doQueryAndChangeCursor(newText);
                    return true;
                }
            });

            // 展开搜索框时，隐藏其他菜单项
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    // 搜索展开时，列表不会立刻变化，除非输入文本
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    // 搜索收起时，恢复原始列表 (filter = null)
                    doQueryAndChangeCursor(null);
                    return true;
                }
            });
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);


        MenuItem mPasteItem = menu.findItem(R.id.menu_paste);

        if (clipboard.hasPrimaryClip()) {
            mPasteItem.setEnabled(true);
        } else {
            mPasteItem.setEnabled(false);
        }

        final boolean haveItems = getListAdapter().getCount() > 0;

        if (haveItems) {
            Uri uri = ContentUris.withAppendedId(getIntent().getData(), getSelectedItemId());
            Intent[] specifics = new Intent[1];
            specifics[0] = new Intent(Intent.ACTION_EDIT, uri);
            MenuItem[] items = new MenuItem[1];
            Intent intent = new Intent(null, uri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

            menu.addIntentOptions(
                    Menu.CATEGORY_ALTERNATIVE,
                    Menu.NONE,
                    Menu.NONE,
                    null,
                    specifics,
                    intent,
                    Menu.NONE,
                    items
            );
            if (items[0] != null) {
                items[0].setShortcut('1', 'e');
            }
        } else {
            menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_add) {
            startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            return true;
        } else if (item.getItemId() == R.id.menu_paste) {
            startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {

        AdapterView.AdapterContextMenuInfo info;

        try {
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        Cursor cursor = (Cursor) getListAdapter().getItem(info.position);

        if (cursor == null) {
            return;
        }

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_context_menu, menu);

        menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

        Intent intent = new Intent(null, Uri.withAppendedPath(getIntent().getData(),
                Integer.toString((int) info.id) ));
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;

        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return false;
        }
        Uri noteUri = ContentUris.withAppendedId(getIntent().getData(), info.id);

        int id = item.getItemId();
        if (id == R.id.context_open) {
            startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
            return true;
        } else if (id == R.id.context_copy) {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);

            clipboard.setPrimaryClip(ClipData.newUri(
                    getContentResolver(),
                    "Note",
                    noteUri));

            return true;
        } else if (id == R.id.context_delete) {
            getContentResolver().delete(
                    noteUri,
                    null,
                    null
            );

            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {

        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);
        String action = getIntent().getAction();

        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    /**
     * 执行笔记查询并更新列表显示
     * @param queryText 搜索关键词
     */
    private void doQueryAndChangeCursor(String queryText) {

        mCurrentFilter = queryText;

        // 1. 准备查询参数
        String selection = null;

        if (mCurrentFilter != null && mCurrentFilter.length() > 0) {
            // 如果有关键词，将关键词作为 selection 传入 ContentProvider
            selection = mCurrentFilter;
        }

        // 2. 执行查询，获取新的 Cursor
        // 注意：selectionArgs 设为 null，让 NotePadProvider.query() 根据 selection 执行 LIKE 模糊查询
        Cursor newCursor = managedQuery(
                getIntent().getData(),
                PROJECTION,
                selection,
                null,
                NotePad.Notes.DEFAULT_SORT_ORDER
        );

        // 3. 通知适配器使用新的 Cursor
        if (mAdapter != null) {
            mAdapter.changeCursor(newCursor);
        }
    }
}