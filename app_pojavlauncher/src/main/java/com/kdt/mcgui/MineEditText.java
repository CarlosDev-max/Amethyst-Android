package com.kdt.mcgui;

import android.content.*;
import android.util.*;
import android.graphics.*;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

public class MineEditText extends androidx.appcompat.widget.AppCompatEditText {
	public MineEditText(Context ctx) {
		super(ctx);
		init();
	}

	public MineEditText(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		setBackgroundColor(ContextCompat.getColor(getContext(), R.color.field_background));
		setPadding(5, 5, 5, 5);
	}
}
