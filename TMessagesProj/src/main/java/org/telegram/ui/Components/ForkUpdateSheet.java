package org.telegram.ui.Components;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/** GitHub release information using the standalone updater's layout and theme. */
public class ForkUpdateSheet extends BottomSheet {
    public ForkUpdateSheet(Context context, String version, long size, String changelog, Runnable download) {
        super(context, false);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setCanceledOnTouchOutside(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        setCustomView(content);

        TextView title = text(context, 20, Theme.key_dialogTextBlack);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.AppUpdate));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 23, 16, 23, 0));

        TextView subtitle = text(context, 14, Theme.key_dialogTextGray3);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(size > 0 ? LocaleController.formatString("AppUpdateVersionAndSize",
                R.string.AppUpdateVersionAndSize, version, AndroidUtilities.formatFileSize(size)) : version);
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 23, 0, 23, 5));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.max(AndroidUtilities.dp(120), AndroidUtilities.displaySize.y * 2 / 5), MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        TextView changes = text(context, 14, Theme.key_dialogTextBlack);
        changes.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
        changes.setText(changelog == null || changelog.trim().isEmpty()
                ? AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty)) : changelog);
        scroll.addView(changes, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 23, 15, 23, 16));

        TextView primary = text(context, 14, Theme.key_featuredStickers_buttonText);
        primary.setTypeface(AndroidUtilities.bold());
        primary.setGravity(Gravity.CENTER);
        primary.setText(LocaleController.getString(R.string.AppUpdateDownloadNow));
        primary.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
        content.addView(primary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 0));
        primary.setOnClickListener(v -> {
            dismiss();
            download.run();
        });

        TextView later = text(context, 14, Theme.key_featuredStickers_addButton);
        later.setGravity(Gravity.CENTER);
        later.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater));
        content.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        later.setOnClickListener(v -> dismiss());
    }

    private static TextView text(Context context, int size, int colorKey) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(colorKey));
        return view;
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
