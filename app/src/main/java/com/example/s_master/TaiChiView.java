package com.example.s_master;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class TaiChiView extends View {

    private Paint paint;
    private int lightGreen;
    private int lightBlue;
    private int softWhite;
    private float centerX, centerY;
    private float radius;

    public TaiChiView(Context context) {
        super(context);
        init();
    }

    public TaiChiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TaiChiView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        
        lightGreen = Color.argb(255, 147, 230, 193);
        lightBlue = Color.argb(255, 155, 209, 247);
        softWhite = Color.argb(245, 255, 255, 255);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f - 4;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        drawTaiChi(canvas);
    }

    private void drawTaiChi(Canvas canvas) {
        float halfRadius = radius / 2f;
        float smallRadius = radius / 6f;
        
        paint.setShader(null);
        
        Path path = new Path();
        path.addCircle(centerX, centerY, radius, Path.Direction.CCW);
        
        Path clipPath = new Path();
        clipPath.moveTo(centerX, centerY - radius);
        clipPath.quadTo(centerX + radius, centerY, centerX, centerY + radius);
        clipPath.quadTo(centerX - radius, centerY, centerX, centerY - radius);
        path.op(clipPath, Path.Op.INTERSECT);
        
        RadialGradient greenGradient = new RadialGradient(
                centerX - halfRadius, centerY, radius,
                Color.argb(255, 167, 235, 205),
                Color.argb(255, 127, 220, 180),
                Shader.TileMode.CLAMP);
        paint.setShader(greenGradient);
        canvas.drawPath(path, paint);
        
        Path path2 = new Path();
        path2.addCircle(centerX, centerY, radius, Path.Direction.CCW);
        path.op(path2, Path.Op.DIFFERENCE);
        
        RadialGradient blueGradient = new RadialGradient(
                centerX + halfRadius, centerY, radius,
                Color.argb(255, 175, 225, 252),
                Color.argb(255, 135, 195, 235),
                Shader.TileMode.CLAMP);
        paint.setShader(blueGradient);
        canvas.drawPath(path, paint);
        paint.setShader(blueGradient);
        canvas.drawPath(path2, paint);
        
        paint.setShader(null);
        
        paint.setColor(softWhite);
        canvas.drawCircle(centerX, centerY - halfRadius, smallRadius, paint);
        
        paint.setColor(Color.argb(255, 147, 230, 193));
        canvas.drawCircle(centerX, centerY + halfRadius, smallRadius, paint);
        
        paint.setColor(Color.argb(255, 155, 209, 247));
        canvas.drawCircle(centerX, centerY - halfRadius, smallRadius / 2f, paint);
        
        paint.setColor(softWhite);
        canvas.drawCircle(centerX, centerY + halfRadius, smallRadius / 2f, paint);
        
        paint.setColor(Color.argb(180, 255, 255, 255));
        paint.setStrokeWidth(1.5f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}