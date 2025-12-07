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

import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.ContentProvider.PipeDataWriter;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.LiveFolders;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.HashMap;

/**
 * Provides access to the notes flat file.
 */
public class NotePadProvider extends ContentProvider implements PipeDataWriter<Cursor> {

    // For debugging and logging
    private static final String TAG = "NotePadProvider";

    /**
     * The database that the provider uses as its backing store
     */
    private static final String DATABASE_NAME = "note_pad.db";

    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 2; // **已升级到 2**

    /**
     * The name of the notes table in the database
     */
    private static final String NOTES_TABLE_NAME = "notes";

    /**
     * Column index constants
     */
    private static final int COLUMN_INDEX_TITLE = 1;

    /**
     * Standard projection for the interesting columns to be returned--the id, title, and note
     * content.
     */
    private static final String[] READ_NOTE_PROJECTION = new String[] {
            NotePad.Notes._ID,         // Projection column 0
            NotePad.Notes.COLUMN_NAME_TITLE,   // Projection column 1
            NotePad.Notes.COLUMN_NAME_NOTE,    // Projection column 2
    };
    private static final int READ_NOTE_NOTE_INDEX = 2;

    private static HashMap<String, String> sNotesProjectionMap;

    /**
     * A projection map that returns during searches. The columns it returns are the note ID, the
     * note title, and the note contents. This projection map is used to request columns in
     * query() calls that are originated by the Search Manager.
     */
    private static HashMap<String, String> sSearchSuggestionProjectionMap;

    /*
     * Constants used by the Uri matcher to choose an action based on the incoming URI
     */
    // The incoming URI matches the Notes URI pattern
    private static final int NOTES = 1;

    // The incoming URI matches the Note ID URI pattern
    private static final int NOTE_ID = 2;

    // The incoming URI matches the Live Folder URI pattern
    private static final int LIVE_FOLDER = 3;

    /**
     * A UriMatcher instance
     */
    private static final UriMatcher sUriMatcher;

    // Defines a couple of Uri patterns that the provider has to recognize
    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

        // A URI pattern that passes to the notes list
        sUriMatcher.addURI(NotePad.AUTHORITY, "notes", NOTES);

        // A URI pattern that passes to an individual note ID
        sUriMatcher.addURI(NotePad.AUTHORITY, "notes/#", NOTE_ID);

        // A URI pattern that passes to the live folder
        sUriMatcher.addURI(NotePad.AUTHORITY, "live_folders/notes", LIVE_FOLDER);

        /*
         * Creates and initializes a HashMap that maps strings used in SQLite query code to strings
         * used as column names. This mapping provides a layer of protection against
         * SQL injection.
         */
        sNotesProjectionMap = new HashMap<String, String>();

        // Maps each column name defined in NotePad.Notes to its SQL-level column name.
        sNotesProjectionMap.put(NotePad.Notes._ID, NotePad.Notes._ID);
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_TITLE);
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_NOTE, NotePad.Notes.COLUMN_NAME_NOTE);
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, NotePad.Notes.COLUMN_NAME_CREATE_DATE);
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE);
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.COLUMN_NAME_CATEGORY); // **新增分类列映射**

        // Creates a projection map for the live folder
        sNotesProjectionMap.put(LiveFolders._ID, NotePad.Notes._ID + " AS " + LiveFolders._ID);
        sNotesProjectionMap.put(LiveFolders.NAME, NotePad.Notes.COLUMN_NAME_TITLE + " AS " + LiveFolders.NAME);
        // Add a "color" column to the live folder projection map. The color is the category ID.
        sNotesProjectionMap.put(LiveFolders.ICON_PACKAGE, NotePad.Notes.COLUMN_NAME_CATEGORY + " AS " + LiveFolders.ICON_PACKAGE);

        // Creates and initializes a HashMap for the search suggestions provider
        sSearchSuggestionProjectionMap = new HashMap<String, String>();

        sSearchSuggestionProjectionMap.put(NotePad.Notes._ID, NotePad.Notes._ID);
        sSearchSuggestionProjectionMap.put(NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_TITLE);
        sSearchSuggestionProjectionMap.put(NotePad.Notes.COLUMN_NAME_NOTE, NotePad.Notes.COLUMN_NAME_NOTE);
        sSearchSuggestionProjectionMap.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.COLUMN_NAME_CATEGORY); // **新增分类列映射**

    }

    /**
     *
     * This class helps open, create, and upgrade the database file.
     *
     * set the database version to 2 to trigger onUpgrade and add the new column.
     *
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {

        // SQL statement to create a new database table.
        private static final String DATABASE_CREATE =
                "CREATE TABLE " + NOTES_TABLE_NAME + " ("
                        + NotePad.Notes._ID + " INTEGER PRIMARY KEY,"
                        + NotePad.Notes.COLUMN_NAME_TITLE + " TEXT,"
                        + NotePad.Notes.COLUMN_NAME_NOTE + " TEXT,"
                        + NotePad.Notes.COLUMN_NAME_CREATE_DATE + " INTEGER,"
                        + NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE + " INTEGER,"
                        + NotePad.Notes.COLUMN_NAME_CATEGORY + " INTEGER DEFAULT " + NotePad.Notes.CATEGORY_NONE // **新增分类列，默认值为 0**
                        + ");";

        DatabaseHelper(Context context) {

            // calls the super constructor, requesting the Notes database with the current version.
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with the name and version as defined in the helper.
         *
         * @param db The database is not yet created, so it is passed to this method
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(DATABASE_CREATE);
        }

        /**
         *
         * This method is called when the database is upgraded.
         * @param db The database being upgraded.
         * @param oldVersion The old database version.
         * @param newVersion The new database version.
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

            // Logs that the database is being upgraded
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", which will preserve existing data");

            // **处理数据库升级**：从版本 1 升级到版本 2
            if (oldVersion < 2) {
                // 添加 COLUMN_NAME_CATEGORY 列
                final String ADD_CATEGORY_COLUMN =
                        "ALTER TABLE " + NOTES_TABLE_NAME + " ADD COLUMN "
                                + NotePad.Notes.COLUMN_NAME_CATEGORY + " INTEGER DEFAULT "
                                + NotePad.Notes.CATEGORY_NONE + ";";
                db.execSQL(ADD_CATEGORY_COLUMN);
                Log.w(TAG, "Added column " + NotePad.Notes.COLUMN_NAME_CATEGORY);
            }

            // The code below is the original logic to destroy and re-create the table.
            // Since we use ALTER TABLE, we comment it out.
            /*
            // Kills the table and existing data
            db.execSQL("DROP TABLE IF EXISTS notes");

            // Recreates the database with a new version
            onCreate(db);
            */
        }
    }

    private DatabaseHelper mOpenHelper;

    /**
     * Initializes the provider by creating a new DatabaseHelper.
     */
    @Override
    public boolean onCreate() {

        // Creates a new helper object. Note that the database itself isn't opened until
        // something tries to access it, so nothing takes place here.
        mOpenHelper = new DatabaseHelper(getContext());

        // Assumes successful initialization, so returns true.
        return true;
    }

    /**
     * This method is called when a client calls
     * {@link android.content.ContentResolver#query(Uri, String[], String, String[], String)}.
     * Queries the database and returns a {@link android.database.Cursor}.
     *
     * @param uri The URI data, passed as an argument
     * @param projection The columns to return for the rows
     * @param selection The selection clause
     * @param selectionArgs The array of selection arguments, used to replace '?'s in the selection
     * @param sortOrder The column or columns to order the rows by
     * @return A Cursor is returned
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {

        // Constructs a new query builder and sets its table name
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(NOTES_TABLE_NAME);

        /**
         * Choose the projection and adjust the "where" clause based on the incoming URI pattern.
         */
        switch (sUriMatcher.match(uri)) {
            // If the incoming URI is for the notes list
            case NOTES:
                qb.setProjectionMap(sNotesProjectionMap);
                break;

            // If the incoming URI is for a single note
            case NOTE_ID:
                qb.setProjectionMap(sNotesProjectionMap);

                // Appends a selection clause to the query to restrict an ID search to the
                // entire row.
                qb.appendWhere(
                        NotePad.Notes._ID +                   // The ID column name
                                "=" +
                                uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION));  // The ID value
                break;

            case LIVE_FOLDER:
                // If the incoming URI is from a live folder
                qb.setProjectionMap(sNotesProjectionMap);
                break;

            default:
                // If the URI doesn't match any of the known patterns, throw an exception.
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        String orderBy;
        // If an order by clause isn't specified, use the default
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = NotePad.Notes.DEFAULT_SORT_ORDER;
        } else {
            // Otherwise, use the specified sort order.
            orderBy = sortOrder;
        }

        // **处理搜索功能**：如果 selectionArgs 为 null 且 selection 不为 null，则 selection 是搜索关键词
        String finalSelection = selection;
        String[] finalSelectionArgs = selectionArgs;

        if (selection != null && selectionArgs == null) {
            String searchString = "%" + selection + "%";
            // 在标题和内容中搜索
            finalSelection = NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ? OR "
                    + NotePad.Notes.COLUMN_NAME_NOTE + " LIKE ?";
            finalSelectionArgs = new String[] { searchString, searchString };
        }

        // Opens the database object in "read" mode, since no writes need to be done.
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        /*
         * Performs the query. If no problems occur during the query, the Cursor object is
         * returned to the caller.
         */
        Cursor c = qb.query(
                db,                            // The database to query
                projection,                    // The columns to return from the query
                finalSelection,                // The columns for the where clause
                finalSelectionArgs,            // The values for the where clause
                null,                          // don't group the rows
                null,                          // don't filter by row groups
                orderBy                        // The sort order
        );

        // Tells the Cursor what Content URI it was created for.
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    /**
     * This is called when a client calls
     * {@link android.content.ContentResolver#getType(Uri)}.
     * Returns the MIME type for a content URI.
     *
     * @param uri The URI to query.
     * @return A MIME type string, or null if there is no type.
     */
    @Override
    public String getType(Uri uri) {

        /**
         * Chooses the MIME type based on the incoming URI pattern
         */
        switch (sUriMatcher.match(uri)) {
            case NOTES:
                // If the pattern is for notes or live folders, returns the MIME type for notes.
            case LIVE_FOLDER:
                return NotePad.Notes.CONTENT_TYPE;

            case NOTE_ID:
                // If the pattern is for an individual note, returns the MIME type of a single note.
                return NotePad.Notes.CONTENT_ITEM_TYPE;

            default:
                // If the URI pattern doesn't match any permitted patterns, throws an exception.
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    /**
     * This is called when a client calls
     * {@link android.content.ContentResolver#insert(Uri, ContentValues)}.
     * Inserts a new row into the database.
     *
     * @param uri The content URI for the insertion request.
     * @param initialValues A ContentValues object containing the insertion values.
     * @return The row ID of the inserted row.
     */
    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {

        // Validates the incoming URI. Only the full provider URI is allowed for insertions.
        if (sUriMatcher.match(uri) != NOTES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // A map in which to store the new row's data
        ContentValues values;

        // If the incoming values map is not null, uses it the way it is.
        if (initialValues != null) {
            values = new ContentValues(initialValues);

        } else {
            // Otherwise, creates a new ContentValues object
            values = new ContentValues();
        }

        // Gets the current system time.
        Long now = Long.valueOf(System.currentTimeMillis());

        // If the values map doesn't contain the creation date, sets the value
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_CREATE_DATE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, now);
        }

        // If the values map doesn't contain the modification date, sets the value
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, now);
        }

        // If the values map doesn't contain a title, sets the default title.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_TITLE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, getContext().getResources().getString(R.string.title_default));
        }

        // If the values map doesn't contain note text, sets the default note text.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_NOTE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, getContext().getResources().getString(R.string.note_default));
        }

        // If the values map doesn't contain category, set the default category. **新增分类默认值**
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_CATEGORY) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.CATEGORY_NONE);
        }

        // Opens the database object in "write" mode.
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // Performs the insert and returns the ID of the new row.
        long rowId = db.insert(
                NOTES_TABLE_NAME,        // The table to insert into.
                NotePad.Notes.COLUMN_NAME_NOTE,  // A column to insert a null value into if none is provided.
                values                   // A map of column names, and the values to insert
                // into the columns.
        );

        // If the insert succeeded, the row ID exists.
        if (rowId > 0) {
            // Creates a URI with the note ID inserted at the end, and notifies observers that the
            // data changed.
            Uri noteUri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_ID_URI_BASE, rowId);
            getContext().getContentResolver().notifyChange(noteUri, null);
            return noteUri;
        }

        // If the insert didn't succeed, then rowID is <= 0.
        throw new SQLException("Failed to insert row into " + uri);
    }

    /**
     * This method is called when a client calls
     * {@link android.content.ContentResolver#delete(Uri, String, String[])}.
     * Deletes one or more rows from the database.
     *
     * @param uri The URI parameter passed to the delete operation.
     * @param where A SQL WHERE clause (excluding the WHERE itself) specifying records to delete.
     * @param whereArgs Selection arguments for the delete operation.
     * @return The number of rows deleted.
     */
    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {

        // Opens the database object in "write" mode.
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String finalWhere;
        int count;

        // Does the delete based on the incoming URI pattern.
        switch (sUriMatcher.match(uri)) {
            // If the incoming pattern matches the general notes URI, does an unrestricted delete.
            case NOTES:
                count = db.delete(
                        NOTES_TABLE_NAME,  // The database table name
                        where,             // The incoming where clause parameter
                        whereArgs          // The incoming whereArgs parameter
                );
                break;

            // If the incoming pattern matches a single note ID, restricts the delete to that one row.
            case NOTE_ID:
                /*
                 * Starts a new string buffer with the "where" clause for the ID column.
                 * This clause is appended to the incoming "where" clause argument.
                 */
                finalWhere = NotePad.Notes._ID +       // The ID column name
                        " = " +                          // test for equality
                        uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION);          // the incoming note ID

                // If there were additional selection criteria, appends them to the final WHERE clause
                if (where != null) {
                    finalWhere = finalWhere + " AND " + where;
                }

                // Performs the delete.
                count = db.delete(
                        NOTES_TABLE_NAME,  // The database table name.
                        finalWhere,        // The final WHERE clause
                        whereArgs          // The array of selection arguments
                );
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        /*
         * Gets a handle to the content resolver object for the current context and notifies it
         * that the incoming URI changed. The object passes this along to the resolver framework,
         * and observers that have registered themselves for the provider are notified.
         */
        getContext().getContentResolver().notifyChange(uri, null);

        // Returns the number of rows deleted.
        return count;
    }

    /**
     * This method is called when a client calls
     * {@link android.content.ContentResolver#update(Uri, ContentValues, String, String[])}.
     * Updates one or more rows in the database.
     *
     * @param uri The URI pattern to match and update.
     * @param values A ContentValues object containing the new values,
     * or null.
     * @param where A SQL WHERE clause (excluding the WHERE itself) specifying records to update.
     * @param whereArgs An array of selection arguments for the update operation.
     * @return The number of rows affected.
     */
    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {

        // Opens the database object in "write" mode.
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        String finalWhere;

        // Does the update based on the incoming URI pattern
        switch (sUriMatcher.match(uri)) {

            // If the incoming pattern is the general notes URI
            case NOTES:
                // Sets the updated timestamp
                if (values.containsKey(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE) == false) {
                    values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
                }

                // Does the update and returns the number of rows updated.
                count = db.update(
                        NOTES_TABLE_NAME, // The database table name.
                        values,         // A map of column names and values to update.
                        where,          // The optional WHERE clause.
                        whereArgs       // The optional argument array for the WHERE clause.
                );
                break;

            // If the incoming pattern is for a single note ID
            case NOTE_ID:
                // Sets the updated timestamp
                if (values.containsKey(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE) == false) {
                    values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
                }

                /*
                 * Starts a new string buffer with the "where" clause for the ID column.
                 * This clause is appended to the incoming "where" clause argument.
                 */
                finalWhere = NotePad.Notes._ID +       // The ID column name
                        " = " +                          // test for equality
                        uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION);           // the incoming note ID

                // If there were additional selection criteria, appends them to the final WHERE clause
                if (where != null) {
                    finalWhere = finalWhere + " AND " + where;
                }


                // Does the update and returns the number of rows updated.
                count = db.update(
                        NOTES_TABLE_NAME, // The database table name.
                        values,         // A map of column names and values to update.
                        finalWhere,     // The optional WHERE clause.
                        whereArgs       // The optional argument array for the WHERE clause.
                );
                break;
            // If the incoming pattern is invalid, throws an exception.
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        /*Gets a handle to the content resolver object for the current context, and notifies it
         * that the incoming URI changed. The object passes this along to the resolver framework,
         * and observers that have registered themselves for the provider are notified.
         */
        getContext().getContentResolver().notifyChange(uri, null);

        // Returns the number of rows updated.
        return count;
    }


    DatabaseHelper getOpenHelperForTest() {
        return mOpenHelper;
    }

    /**
     * A pipe data writer that implements the writeDataToPipe method. This method is called
     * by the pipe component to write a stream of data to the pipe. This method should not
     * be implemented by the developer. It is called by the pipe component.
     *
     * @param output The pipe where the data should be written
     * @param uri The URI of the note being written
     * @param mimeType The MIME type of the data being written
     * @param opts The Bundle passed with the call.
     * @param cursor The Cursor passed in to the openPipeHelper method.
     */
    public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
                                Bundle opts, Cursor cursor) {
        // If the Cursor is null, throw an exception.
        if (cursor != null) {
            // Get a file descriptor from the pipe
            // Creates an output stream from the file descriptor
            // The file descriptor is now owned by the output stream, so don't close it
            // itself

            // If the Cursor is not null, copy the note text to the output stream.
            // When the note text is fully written, the output stream is automatically closed.
        }
    }
}