package com.langqi.fakegps;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DashboardLayoutTest {
    @Test public void smallPhoneAndLargeFontsKeepControlsAndMetricsReadable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int widthDp : new int[]{320, 375, 640}) {
                for (float scale : new float[]{1f, 2f}) {
                    Configuration config = new Configuration(base.getResources().getConfiguration());
                    config.fontScale = scale;
                    config.screenWidthDp = widthDp;
                    Context themed = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_FakeGPS);
                    View dashboard = LayoutInflater.from(themed).inflate(R.layout.activity_fake_gps, null);
                    FakeGPSActivity.adaptMetricRows(dashboard);
                    float density = themed.getResources().getDisplayMetrics().density;
                    int width = Math.round(widthDp * density);
                    int height = Math.round(480 * density);
                    dashboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    dashboard.layout(0, 0, width, height);
                    for (int id : new int[]{R.id.btn_start, R.id.btn_pause, R.id.btn_resume, R.id.btn_stop,
                            R.id.btn_browse, R.id.btn_update_route, R.id.btn_delete_route,
                            R.id.btn_pace_down, R.id.btn_pace_up, R.id.btn_laps_down, R.id.btn_laps_up,
                            R.id.input_pace, R.id.input_repetitions, R.id.input_interval,
                            R.id.current_pace, R.id.target_pace, R.id.distance_done, R.id.elapsed_time}) {
                        TextView view = dashboard.findViewById(id);
                        String detail = view.getResources().getResourceEntryName(id) + " at " + widthDp + "dp / " + scale;
                        assertTrue(detail, view.getWidth() > view.getCompoundPaddingLeft() + view.getCompoundPaddingRight());
                        assertTrue(detail, view.getHeight() >= view.getLayout().getHeight()
                                + view.getCompoundPaddingTop() + view.getCompoundPaddingBottom());
                        for (int line = 0; line < view.getLineCount(); line++) {
                            assertEquals(detail, 0, view.getLayout().getEllipsisCount(line));
                            assertTrue(detail, view.getLayout().getLineWidth(line) <= view.getWidth()
                                    - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() + 1);
                        }
                        if (view.isClickable()) assertTrue(detail, view.getHeight() >= 48 * density - 1);
                    }
                }
            }
        });
    }

    @Test public void normalTextContrastMeetsFourPointFiveToOne() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int background : new int[]{R.color.surface, R.color.surface_elevated, R.color.app_background}) {
            assertTrue(ColorUtils.calculateContrast(context.getColor(R.color.text_muted),
                    context.getColor(background)) >= 4.5);
        }
        assertTrue(ColorUtils.calculateContrast(context.getColor(R.color.white),
                context.getColor(R.color.danger_red)) >= 4.5);
    }
}
