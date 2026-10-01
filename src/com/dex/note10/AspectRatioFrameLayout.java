package com.dex.note10;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * AspectRatio-preserving container that fits video precisely without stretching.
 * When the phone rotates between portrait and landscape, it adapts immediately.
 */
public class AspectRatioFrameLayout extends FrameLayout {
    private int videoWidth = 1080;
    private int videoHeight = 2280;
    private ControlSender controlSender;

    public AspectRatioFrameLayout(Context context) {
        super(context);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setVideoSize(int w, int h) {
        if (w > 0 && h > 0 && (this.videoWidth != w || this.videoHeight != h)) {
            this.videoWidth = w;
            this.videoHeight = h;
            post(this::requestLayout);
        }
    }

    public void setControlSender(ControlSender sender) {
        this.controlSender = sender;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true; // Capture all touches
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxW = MeasureSpec.getSize(widthMeasureSpec);
        int maxH = MeasureSpec.getSize(heightMeasureSpec);

        if (videoWidth <= 0 || videoHeight <= 0 || maxW <= 0 || maxH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Compute aspect-ratio preserved dimensions (never stretches the video)
        int w = maxW;
        int h = Math.round((float) w * videoHeight / videoWidth);
        if (h > maxH) {
            h = maxH;
            w = Math.round((float) h * videoWidth / videoHeight);
        }

        int wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int hSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        super.onMeasure(wSpec, hSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (controlSender != null && getWidth() > 0 && getHeight() > 0) {
            controlSender.sendTouchEvent(event, getWidth(), getHeight());
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (controlSender == null) return super.onGenericMotionEvent(event);

        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW <= 0 || viewH <= 0) return true;

            int remoteW = controlSender.getRemoteWidth();
            int remoteH = controlSender.getRemoteHeight();

            int remoteX = Math.round(event.getX() * remoteW / viewW);
            int remoteY = Math.round(event.getY() * remoteH / viewH);

            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);

            controlSender.sendScroll(remoteX, remoteY, Math.round(hScroll), Math.round(-vScroll));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
