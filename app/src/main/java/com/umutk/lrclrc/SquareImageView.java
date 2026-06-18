package com.umutk.lrclrc;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Forces height to equal the measured width, producing a square tile regardless of layout. */
public class SquareImageView extends ImageView {
    public SquareImageView(Context context) { super(context); }
    public SquareImageView(Context context, AttributeSet attrs) { super(context, attrs); }
    public SquareImageView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
    }
}
