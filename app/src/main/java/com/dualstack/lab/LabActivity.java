package com.dualstack.lab;

import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

public class LabActivity extends ComponentActivity {
    private boolean appliedDark;
    private int[] palette;
    @Override public void onCreate(Bundle saved) {
        setTheme(Ui.isDark(this) ? R.style.Theme_DualStack_Dark : R.style.Theme_DualStack);
        Ui.configure(this);
        appliedDark = Ui.dark; palette = Ui.palette();
        super.onCreate(saved);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    protected void handleBack() { finish(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (appliedDark != Ui.isDark(this)) {
            setTheme(Ui.isDark(this) ? R.style.Theme_DualStack_Dark : R.style.Theme_DualStack);
            Ui.updateTheme(this, palette); appliedDark = Ui.dark; palette = Ui.palette(); onPaletteChanged();
        }
    }
    protected void onPaletteChanged() { }
}
