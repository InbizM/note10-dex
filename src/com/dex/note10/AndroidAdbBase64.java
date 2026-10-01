package com.dex.note10;

import android.util.Base64;
import com.tananaev.adblib.AdbBase64;

public class AndroidAdbBase64 implements AdbBase64 {
    @Override
    public String encodeToString(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }
}
