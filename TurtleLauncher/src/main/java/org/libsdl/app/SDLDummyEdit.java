/*
 * This file is part of SDL3 android-project java code.
 * Licensed under the zlib license: https://www.libsdl.org/license.php
 */

package org.libsdl.app;

import android.content.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/* This is a fake invisible editor view that receives the input and defines the
 * pan&scan region
 */
public class SDLDummyEdit extends View implements View.OnKeyListener
{
    InputConnection ic;
    int input_type;

    public SDLDummyEdit(Context context) {
        super(context);
        setFocusableInTouchMode(true);
        setFocusable(true);
        setOnKeyListener(this);
    }

    public void setInputType(int input_type) {
        this.input_type = input_type;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return SDLActivity.handleKeyEvent(v, keyCode, event, ic);
    }

    //
    @Override
    public boolean onKeyPreIme (int keyCode, KeyEvent event) {
        if (event.getAction()==KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
            if (SDLActivity.mTextEdit != null && SDLActivity.mTextEdit.getVisibility() == View.VISIBLE) {
                SDLActivity.onNativeKeyboardFocusLost();
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        ic = new SDLInputConnection(this, true);

        outAttrs.inputType = input_type;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                              EditorInfo.IME_FLAG_NO_FULLSCREEN /* API 11 */;

        return ic;
    }
}

