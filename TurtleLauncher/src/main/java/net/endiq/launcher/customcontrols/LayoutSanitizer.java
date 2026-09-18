package net.endiq.launcher.customcontrols;

import java.util.Iterator;
import java.util.List;

public class LayoutSanitizer {

    // TurtleLauncher CRASH FIX: this whole class runs on hand-edited/converted user JSON
    // during game startup. Every check is null-tolerant now and unknown entry types are
    // dropped instead of throwing - a corrupt control file must cost the user a button,
    // never the game process.

    // Maybe add more conditions here later?
    private static boolean isInvalidFormula(String formula) {
        return formula == null || formula.contains("Infinity");
    }

    private static boolean isSaneData(ControlData controlData) {
        if(controlData == null) return false;
        if(controlData.getWidth() == 0 || controlData.getHeight() == 0) return false;
        if(isInvalidFormula(controlData.dynamicX) || isInvalidFormula(controlData.dynamicY)) return false;
        return true;
    }

    private static ControlData getControlData(Object dataEntry) {
        if(dataEntry instanceof ControlData) {
            return (ControlData) dataEntry;
        }else if(dataEntry instanceof ControlDrawerData) {
            return ((ControlDrawerData) dataEntry).properties;
        }
        // Previously threw RuntimeException("Encountered wrong type...") which escaped
        // loadLayout() on several call sites that only catch IOException -> crash.
        // Returning null makes isSaneData() reject the entry so it gets removed instead.
        return null;
    }

    private static boolean sanitizeList(List<?> controlDataList) {
        if(controlDataList == null) return false;
        boolean madeChanges = false;
        Iterator<?> iterator = controlDataList.iterator();
        while(iterator.hasNext()) {
            Object entry = iterator.next();
            ControlData controlData;
            try {
                controlData = getControlData(entry);
            } catch (Throwable t) {
                controlData = null;
            }
            if(!isSaneData(controlData)) {
                madeChanges = true;
                iterator.remove();
            }
        }
        return madeChanges;
    }

    /**
     * Check all buttons in a control layout and ensure they're sane (contain values valid enough
     * to be displayed properly). Removes any buttons deemed not sane.
     * @param controls the original control layout.
     * @return whether the sanitization process made any changes to the layout
     */
    public static boolean sanitizeLayout(CustomControls controls) {
        if(controls == null) return false;
        boolean madeChanges = sanitizeList(controls.mControlDataList);
        if(sanitizeList(controls.mDrawerDataList)) madeChanges = true;
        if(sanitizeList(controls.mJoystickDataList)) madeChanges = true;
        return madeChanges;
    }
}
