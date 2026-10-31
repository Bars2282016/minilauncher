package io.github.bars2282016.minilauncher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** Одно приложение в списке лаунчера. */
final class AppEntry {

    final String label;
    final String packageName;
    final ComponentName componentName;
    final Drawable icon;

    AppEntry(String label, String packageName, ComponentName componentName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.componentName = componentName;
        this.icon = icon;
    }
}
