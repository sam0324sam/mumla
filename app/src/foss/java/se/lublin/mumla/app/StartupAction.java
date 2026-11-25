package se.lublin.mumla.app;

import android.app.Activity;

import androidx.annotation.NonNull;

public class StartupAction implements IStartupAction {
    @Override
    public void execute(@NonNull Activity activity) {
        // No startup news popup
    }
}
