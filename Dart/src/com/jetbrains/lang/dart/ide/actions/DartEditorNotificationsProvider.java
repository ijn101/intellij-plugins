package com.jetbrains.lang.dart.ide.actions;

import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotifications;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkGlobalLibUtil;
import com.jetbrains.lang.dart.util.PubspecYamlUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class DartEditorNotificationsProvider extends EditorNotifications.Provider<EditorNotificationPanel> {
  private static final Key<EditorNotificationPanel> KEY = Key.create("DartEditorNotificationsProvider");

  private final @NotNull Project myProject;

  public DartEditorNotificationsProvider(final @NotNull Project project) {
    myProject = project;
  }

  public Key<EditorNotificationPanel> getKey() {
    return KEY;
  }

  @Nullable
  public EditorNotificationPanel createNotificationPanel(final @NotNull VirtualFile file, final @NotNull FileEditor fileEditor) {
    if (file.isInLocalFileSystem() && PubspecYamlUtil.PUBSPEC_YAML.equalsIgnoreCase(file.getName())) {
      final DartSdk sdk = DartSdk.getGlobalDartSdk();
      final Module module = ModuleUtilCore.findModuleForFile(file, myProject);
      if (module != null && sdk != null && DartSdkGlobalLibUtil.isDartSdkGlobalLibAttached(module, sdk.getGlobalLibName())) {
        return new PubActionsPanel();
      }
    }

    return null;
  }

  private static class PubActionsPanel extends EditorNotificationPanel {
    private PubActionsPanel() {
      myLinksPanel.add(new JLabel("Pub actions:"));
      createActionLabel("Get Dependencies", "Dart.pub.get");
      createActionLabel("Upgrade Dependencies", "Dart.pub.upgrade");
      createActionLabel("Build", "Dart.pub.build");
    }

    public Color getBackground() {
      final Color color = EditorColorsManager.getInstance().getGlobalScheme().getColor(EditorColors.GUTTER_BACKGROUND);
      return color != null ? color : super.getBackground();
    }
  }
}
