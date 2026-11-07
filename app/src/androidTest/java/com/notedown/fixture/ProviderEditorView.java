package com.notedown.fixture;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

/** Java keeps the standalone fixture independent of the target APK's Kotlin runtime. */
public final class ProviderEditorView extends View {
    private final String mode;

    public ProviderEditorView(Context context, String mode) {
        super(context);
        this.mode = mode;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return new AccessibilityNodeProvider() {
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                AccessibilityNodeInfo node = new AccessibilityNodeInfo();
                node.setPackageName(getContext().getPackageName());
                node.setClassName(id == HOST_VIEW_ID ? "android.view.View" : "android.widget.EditText");
                node.setSource(ProviderEditorView.this, id);
                node.setVisibleToUser(true);
                node.setEnabled(!"disabled".equals(mode));
                node.setBoundsInScreen(new Rect(0, 100, 500, 300));
                if (id == HOST_VIEW_ID) {
                    node.setContentDescription("provider-root");
                    node.addChild(ProviderEditorView.this, 1);
                } else {
                    node.setParent(ProviderEditorView.this);
                    node.setContentDescription("provider-editor");
                    node.setEditable(true);
                    node.setFocused(!"unfocused".equals(mode));
                    node.setAccessibilityFocused("unfocused".equals(mode));
                    node.setPassword("password".equals(mode));
                    node.setText("Fixture text");
                }
                return node;
            }

            @Override public AccessibilityNodeInfo findFocus(int focus) {
                return createAccessibilityNodeInfo(HOST_VIEW_ID);
            }
        };
    }
}
