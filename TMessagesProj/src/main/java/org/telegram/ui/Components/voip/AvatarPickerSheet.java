package org.telegram.ui.Components.voip;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.voip.AvatarController;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

public class AvatarPickerSheet {

    public static void show(Context context) {
        if (context == null) return;

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false);
        AvatarController controller = AvatarController.getInstance();

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));

        // Header Title
        TextView titleView = new TextView(context);
        titleView.setText("🎭 3D Аватар в звонке");
        titleView.setTextSize(19);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(4));
        contentLayout.addView(titleView);

        // Subtitle explanation
        TextView subtitleView = new TextView(context);
        subtitleView.setText("Камера отслеживает ваше лицо и мимику, но собеседник видит только выбранного 3D персонажа. Ваше лицо никогда не транслируется.");
        subtitleView.setTextSize(13);
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        subtitleView.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        contentLayout.addView(subtitleView);

        // Toggle Cell
        TextCheckCell toggleCell = new TextCheckCell(context);
        toggleCell.setTextAndCheck("Заменять камеру на 3D аватар", controller.isAvatarEnabled(), true);
        contentLayout.addView(toggleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Models Section Header
        HeaderCell headerCell = new HeaderCell(context);
        headerCell.setText("Выберите 3D персонажа:");
        contentLayout.addView(headerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Models Radio List
        List<AvatarController.AvatarModel> models = controller.getModels();
        List<RadioCell> radioCells = new ArrayList<>();
        int selectedIndex = controller.getSelectedModelIndex();

        for (int i = 0; i < models.size(); i++) {
            final int index = i;
            AvatarController.AvatarModel model = models.get(i);
            RadioCell cell = new RadioCell(context);
            cell.setText(model.name, index == selectedIndex, i != models.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> {
                controller.setSelectedModelIndex(index);
                for (int j = 0; j < radioCells.size(); j++) {
                    radioCells.get(j).setChecked(j == index, true);
                }
                Toast.makeText(context, "Выбран аватар: " + model.name, Toast.LENGTH_SHORT).show();
            });
            radioCells.add(cell);
            contentLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        // Toggle click listener
        toggleCell.setOnClickListener(v -> {
            boolean newState = !toggleCell.isChecked();
            toggleCell.setChecked(newState);
            controller.setAvatarEnabled(newState);
            for (RadioCell rc : radioCells) {
                rc.setEnabled(newState, null);
            }
            Toast.makeText(context, newState ? "3D Аватар включен" : "3D Аватар выключен (обычная камера)", Toast.LENGTH_SHORT).show();
        });

        for (RadioCell rc : radioCells) {
            rc.setEnabled(controller.isAvatarEnabled(), null);
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(contentLayout);

        builder.setCustomView(scrollView);
        BottomSheet sheet = builder.create();
        sheet.show();
    }
}
