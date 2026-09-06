Fix: add save/cancel buttons to settings-with-tabs UI.
Also fix UTF-8 mojibake in button labels (Show for Save, Back for Back).

Files to check:
- MainController.java (save/cancel buttons only exist in showLauncherSettingsPage, not in showSettingsWithTabs)
- SettingsManager.java (save() is no-op — already correct since set() is write-through)

В коде созданной Kiro вкладки настроек (showSettingsWithTabs → createDesignSettingsTab) кнопок сохранения НЕТ — только в старой showLauncherSettingsPage.
Нужно добавить saveBtn + cancelBtn в showSettingsWithTabs() или в контент вкладок.

Сейчас:
- showSettingsWithTabs() кладёт в mainContent только TabPane в contentWrapper — без кнопок
- createDesignSettingsTab() возвращает VBox без кнопок сохранения
- createLauncherSettingsPage() (старый, англоязычный) — есть buttonRow с saveBtn и cancelBtn

План:
1. Добавить save/cancel кнопки в showSettingsWithTabs() после tabPane (в contentWrapper или к mainContent).
2. Убедиться, что saveBtn вызывает settings.save() и showMainPage().
3. Проверить UTF-8: кнопки были с mojibake ("рџ’ѕ Save" вместо "💾 Save").
