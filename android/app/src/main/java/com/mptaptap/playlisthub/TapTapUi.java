package com.mptaptap.playlisthub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Set;
import java.util.function.Consumer;

/** Shared styling for the app's forms, actions and custom popup content. */
final class TapTapUi {
    private TapTapUi() {}

    static int dp(Context context,int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }

    static GradientDrawable rounded(Context context,int color,int radius,boolean border) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(context.getColor(color));shape.setCornerRadius(dp(context,radius));
        if(border)shape.setStroke(dp(context,1),context.getColor(R.color.line));return shape;
    }

    static void font(TextView view,boolean bold) {
        view.setTypeface(ResourcesCompat.getFont(view.getContext(),bold?R.font.pretendard_semibold:R.font.pretendard));
        view.setIncludeFontPadding(false);
    }

    static void input(EditText view) {
        Context context=view.getContext();font(view,false);view.setTextSize(15);view.setTextColor(context.getColor(R.color.ink));view.setHintTextColor(context.getColor(R.color.muted));
        view.setBackgroundTintList(null);
        StateListDrawable states=new StateListDrawable();GradientDrawable focused=rounded(context,R.color.input_surface,14,true);
        focused.setStroke(dp(context,1),context.getColor(R.color.deck_accent));
        states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},rounded(context,R.color.input_surface,14,true));view.setBackground(states);
        view.setPadding(dp(context,16),dp(context,15),dp(context,16),dp(context,15));view.setMinimumHeight(dp(context,56));
        view.setGravity((view.getInputType()&android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0?Gravity.TOP|Gravity.START:Gravity.CENTER_VERTICAL|Gravity.START);
        view.setLineSpacing(dp(context,3),1);
    }

    static void button(MaterialButton view) {
        Context context=view.getContext();font(view,true);view.setTextSize(14);view.setAllCaps(false);view.setMinHeight(dp(context,56));view.setMinWidth(0);
        view.setCornerRadius(dp(context,16));view.setStrokeWidth(dp(context,1));view.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.line)));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{context.getColor(R.color.muted),context.getColor(R.color.ink)}));
        view.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.surface)));view.setRippleColor(ColorStateList.valueOf(0x26D9FF00));
        view.setPadding(dp(context,14),dp(context,12),dp(context,14),dp(context,12));view.setInsetTop(dp(context,4));view.setInsetBottom(dp(context,4));
        view.setIconTint(ColorStateList.valueOf(context.getColor(R.color.deck_accent)));view.setIconPadding(dp(context,10));view.setLetterSpacing(0);view.setMaxLines(4);
    }

    static void primary(MaterialButton view) {
        Context context=view.getContext();view.setStrokeWidth(0);view.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{context.getColor(R.color.soft_accent),context.getColor(R.color.deck_accent)}));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{context.getColor(R.color.muted),context.getColor(R.color.deck_surface)}));
        view.setIconTint(ColorStateList.valueOf(context.getColor(R.color.deck_surface)));
    }

    static void checkbox(CheckBox view) {
        Context context=view.getContext();font(view,false);view.setTextColor(context.getColor(R.color.ink));view.setTextSize(14);view.setMinimumHeight(dp(context,48));
        view.setButtonTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{context.getColor(R.color.line),context.getColor(R.color.deck_accent),context.getColor(R.color.muted)}));
    }

    private static void popupContent(View view) {
        if(view instanceof EditText)input((EditText)view);
        if(view instanceof CheckBox)checkbox((CheckBox)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)popupContent(((ViewGroup)view).getChildAt(i));
    }

    static MaterialAlertDialogBuilder dialog(Context context,Set<AlertDialog> tracked) {
        return new MaterialAlertDialogBuilder(context) {
            @Override public MaterialAlertDialogBuilder setView(View view) { popupContent(view);return super.setView(view); }
            @Override public AlertDialog create() { AlertDialog dialog=super.create();tracked.add(dialog);return dialog; }
        };
    }
    static void consent(Context context,Set<AlertDialog> tracked,String base,Runnable accept,Runnable decline,Consumer<String> open){
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(context,20),dp(context,8),dp(context,20),dp(context,8));
        TextView explanation=new TextView(context);font(explanation,false);explanation.setTextColor(context.getColor(R.color.ink));explanation.setTextSize(14);explanation.setText("서버 기능은 직접 입력한 요청과 앱의 반응을 처리해요. 공개 목록을 이용하면 공개 동의, 신고 내용과 작성자 차단 목록도 보관해요. YouTube 기능을 이용하려면 아래 약관과 개인정보 안내를 확인하고 동의해 주세요. 동의하지 않아도 이 기기에 저장한 음악 목록은 사용할 수 있어요.");content.addView(explanation);
        String[] titles={"TapTap 개인정보 처리 안내","계정·데이터 삭제 안내","YouTube 서비스 약관","Google 개인정보처리방침"};
        String[] urls={base+"/privacy",base+"/account-deletion","https://www.youtube.com/t/terms","https://policies.google.com/privacy"};
        for(int i=0;i<titles.length;i++){MaterialButton link=new MaterialButton(context);button(link);link.setText(titles[i]);String url=urls[i];link.setOnClickListener(v->open.accept(url));content.addView(link,new LinearLayout.LayoutParams(-1,-2));}
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.addView(content);
        AlertDialog consent=dialog(context,tracked).setTitle("서비스 이용 안내").setView(scroll).setPositiveButton("동의하고 계속",(d,w)->accept.run()).setNegativeButton("동의하지 않음",(d,w)->decline.run()).create();
        consent.setOnCancelListener(d->decline.run());consent.show();
    }
}
