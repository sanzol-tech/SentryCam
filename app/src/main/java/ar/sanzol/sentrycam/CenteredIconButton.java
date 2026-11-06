package ar.sanzol.sentrycam;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.widget.Button;

/**
 * TimerPlus' CenteredIconButton on the framework Button (SentryCam has no AndroidX): icon and
 * text sit together, centered. A plain Button draws drawableStart at the left padding and
 * centers the text in what is left. The padding is recomputed before every measure from the
 * width the parent imposes, with the XML padding as a minimum.
 */
public class CenteredIconButton extends Button {

    private int minPaddingStart = -1;
    private int minPaddingEnd = -1;

    public CenteredIconButton(Context context) {
        super(context);
    }

    public CenteredIconButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CenteredIconButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY;
        applyCenteringPadding(exact ? MeasureSpec.getSize(widthMeasureSpec) : 0);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        // START <-> STOP changes the text width; fixed-width buttons don't re-layout on their own.
        requestLayout();
    }

    // The width the parent imposes, or 0 when the button sizes itself.
    private void applyCenteringPadding(int width) {
        if (minPaddingStart < 0) {
            minPaddingStart = getPaddingStart();
            minPaddingEnd = getPaddingEnd();
        }
        int start = minPaddingStart;
        int end = minPaddingEnd;
        Drawable icon = getCompoundDrawablesRelative()[0];
        if (icon != null && width > 0) {
            CharSequence text = getText();
            TransformationMethod caps = getTransformationMethod();
            if (caps != null) {
                text = caps.getTransformation(text, this);
            }
            float textWidth = getPaint().measureText(text.toString());
            int content = (int) Math.ceil(textWidth) + icon.getIntrinsicWidth() + getCompoundDrawablePadding();
            int side = (width - content) / 2;
            start = Math.max(minPaddingStart, side);
            end = Math.max(minPaddingEnd, side);
        }
        // Only a real change asks for another layout pass, so this settles at once.
        if (start != getPaddingStart() || end != getPaddingEnd()) {
            setPaddingRelative(start, getPaddingTop(), end, getPaddingBottom());
        }
    }
}
