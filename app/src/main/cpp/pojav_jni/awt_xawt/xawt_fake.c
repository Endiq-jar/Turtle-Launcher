#include <jni.h>

// java.awt.*
JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Button_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Component_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Container_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Checkbox_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Cursor_initIDs
  (JNIEnv *env, jclass cls)
{
    
}

JNIEXPORT void JNICALL
Java_java_awt_Cursor_finalizeImpl(JNIEnv *env, jclass clazz, jlong pData)
{
    
}

JNIEXPORT void JNICALL
Java_java_awt_Dialog_initIDs
  (JNIEnv *env, jclass cls)
{
    
}

JNIEXPORT void JNICALL
Java_java_awt_Event_initIDs(JNIEnv *env, jclass cls)
{
    
}

JNIEXPORT void JNICALL
Java_java_awt_FileDialog_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL
Java_java_awt_Frame_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Insets_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_KeyboardFocusManager_initIDs
  (JNIEnv *env, jclass cls)
{
    
}

JNIEXPORT void JNICALL Java_java_awt_Menu_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_MenuComponent_initIDs(JNIEnv *env, jclass cls)
{
    
}

JNIEXPORT void JNICALL Java_java_awt_MenuItem_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Scrollbar_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL Java_java_awt_ScrollPane_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextArea_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextField_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL Java_java_awt_TrayIcon_initIDs(JNIEnv *env , jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Window_initIDs
  (JNIEnv *env, jclass cls)
{
}

// java.awt.event.*
JNIEXPORT void JNICALL
Java_java_awt_event_InputEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_event_KeyEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_nativeSetSource(JNIEnv *env, jobject self,
                                       jobject newSource)
{
    // Maybe implement this?
}

// sun.awt.SunToolkit
JNIEXPORT void JNICALL
Java_sun_awt_SunToolkit_closeSplashScreen
  (JNIEnv *env, jclass cls)
{
    
}
// sun.awt.UNIXToolkit
JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_check_1gtk(JNIEnv *env, jclass klass, jint version) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_sun_awt_UNIXToolkit_get_1gtk_1version(JNIEnv *env, jclass klass)
{
    // return GTK_ANY;
    return (jint) 1;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_gtkCheckVersionImpl(JNIEnv *env, jobject this,
        jint major, jint minor, jint micro)
{
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_load_1gtk(JNIEnv *env, jclass klass, jint version,
                                                             jboolean verbose) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_load_1gtk_1icon(JNIEnv *env, jobject this,
        jstring filename)
{
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_load_1stock_1icon(JNIEnv *env, jobject this,
        jint widget_type, jstring stock_id, jint icon_size,
        jint text_direction, jstring detail)
{
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_sun_awt_UNIXToolkit_nativeSync(JNIEnv *env, jobject this)
{

}

JNIEXPORT jboolean JNICALL
Java_sun_awt_UNIXToolkit_unload_1gtk(JNIEnv *env, jclass klass)
{
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_java_awt_Font_initIDs(JNIEnv *env, jclass cls) {}


// ============================================================
// X11FontScaler stubs
// libfontmanager.so가 X11FontScaler.c와 함께 빌드되어 17개 AWT*
// 심볼을 undefined로 export한다. Caciocavallo + FreeType 경로에서는
// 실제로 호출되지 않지만, dlopen(RTLD_NOW) 시점에 심볼 resolution을
// 통과시키려면 존재해야 한다. 호출되면 안 되니까 노옵이 안전.
// ============================================================

JNIEXPORT int      JNICALL AWTCountFonts(void* xlfd)                                { return 0; }
JNIEXPORT void     JNICALL AWTLoadFont(void* name, void* pReturn)                   {}
JNIEXPORT void     JNICALL AWTFreeFont(void* font)                                  {}
JNIEXPORT unsigned JNICALL AWTFontMinByte1(void* font)                              { return 0; }
JNIEXPORT unsigned JNICALL AWTFontMaxByte1(void* font)                              { return 0; }
JNIEXPORT unsigned JNICALL AWTFontMinCharOrByte2(void* font)                        { return 0; }
JNIEXPORT unsigned JNICALL AWTFontMaxCharOrByte2(void* font)                        { return 0; }
JNIEXPORT unsigned JNICALL AWTFontDefaultChar(void* font)                           { return 0; }
JNIEXPORT void*    JNICALL AWTFontPerChar(void* font, int index)                    { return 0; }
JNIEXPORT void*    JNICALL AWTFontMaxBounds(void* font)                             { return 0; }
JNIEXPORT int      JNICALL AWTFontAscent(void* font)                                { return 0; }
JNIEXPORT int      JNICALL AWTFontDescent(void* font)                               { return 0; }
JNIEXPORT void     JNICALL AWTFontTextExtents16(void* a, void* b, int c,
                                                void* d, void* e, void* f, void* g) {}
JNIEXPORT void     JNICALL AWTDrawGlyphList(void* a, void* b, void* c,
                                            int d, void* e)                          {}
JNIEXPORT void     JNICALL AWTFreeChar(void* character)                             {}
JNIEXPORT void*    JNICALL AWTCharAdvance(void* font, int c)                        { return 0; }
JNIEXPORT void     JNICALL AWTFontGenerateImage(void* a, void* b)                   {}



