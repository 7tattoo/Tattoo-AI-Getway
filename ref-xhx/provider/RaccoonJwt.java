package dev.raccoon2api.provider;

import java.util.Base64;
import java.util.List;
import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.CharsKt;
import kotlin.text.Charsets;
import kotlin.text.StringsKt;
import org.json.JSONObject;

/* compiled from: RaccoonJwt.kt */
@Metadata(d1 = {"\u0000$\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\t\n\u0002\b\u000b\bÆ\u0002\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003J\u000e\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u0005J\u0010\u0010\u0007\u001a\u0004\u0018\u00010\b2\u0006\u0010\t\u001a\u00020\u0005J\u0015\u0010\n\u001a\u0004\u0018\u00010\u000b2\u0006\u0010\t\u001a\u00020\u0005¢\u0006\u0002\u0010\fJ\u000e\u0010\r\u001a\u00020\u00052\u0006\u0010\u000e\u001a\u00020\bJ\u000e\u0010\u000f\u001a\u00020\u00052\u0006\u0010\u000e\u001a\u00020\bJ\u000e\u0010\u0010\u001a\u00020\u00052\u0006\u0010\u0011\u001a\u00020\bJ\u0016\u0010\u0012\u001a\u00020\u00052\u0006\u0010\u0011\u001a\u00020\b2\u0006\u0010\u0013\u001a\u00020\u0005J\u000e\u0010\u0014\u001a\u00020\u00052\u0006\u0010\u0015\u001a\u00020\u0005¨\u0006\u0016"}, d2 = {"Ldev/raccoon2api/provider/RaccoonJwt;", "", "<init>", "()V", "stripBearer", "", "value", "decodeClaims", "Lorg/json/JSONObject;", "token", "expiryMs", "", "(Ljava/lang/String;)Ljava/lang/Long;", "extractUserId", "claims", "displayName", "displayNameOf", "obj", "asText", "key", "hexToDecimal", "raw", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final class RaccoonJwt {
    public static final RaccoonJwt INSTANCE = new RaccoonJwt();

    private RaccoonJwt() {
    }

    public final String stripBearer(String value) {
        Intrinsics.checkNotNullParameter(value, "value");
        String string = StringsKt.trim((CharSequence) value).toString();
        if (string.length() > "bearer".length()) {
            String strSubstring = string.substring(0, "bearer".length());
            Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
            if (StringsKt.equals(strSubstring, "bearer", true)) {
                String strSubstring2 = string.substring("bearer".length());
                Intrinsics.checkNotNullExpressionValue(strSubstring2, "substring(...)");
                String str = strSubstring2;
                if (str.length() <= 0 || CharsKt.isWhitespace(strSubstring2.charAt(0))) {
                    return StringsKt.trimStart((CharSequence) str).toString();
                }
            }
        }
        return string;
    }

    public final JSONObject decodeClaims(String token) {
        Intrinsics.checkNotNullParameter(token, "token");
        List listSplit$default = StringsKt.split$default((CharSequence) stripBearer(token), new char[]{'.'}, false, 0, 6, (Object) null);
        if (listSplit$default.size() >= 2 && ((CharSequence) listSplit$default.get(1)).length() != 0) {
            try {
                byte[] bArrDecode = Base64.getUrlDecoder().decode((String) listSplit$default.get(1));
                Intrinsics.checkNotNull(bArrDecode);
                String str = new String(bArrDecode, Charsets.UTF_8);
                JSONObject jSONObject = new JSONObject(str);
                if (jSONObject.length() != 0 || StringsKt.startsWith$default(StringsKt.trim((CharSequence) str).toString(), "{", false, 2, (Object) null)) {
                    return jSONObject;
                }
                return null;
            } catch (IllegalArgumentException | Exception unused) {
            }
        }
        return null;
    }

    public final Long expiryMs(String token) {
        Double doubleOrNull;
        double dDoubleValue;
        Intrinsics.checkNotNullParameter(token, "token");
        JSONObject jSONObjectDecodeClaims = decodeClaims(token);
        if (jSONObjectDecodeClaims == null || !jSONObjectDecodeClaims.has("exp")) {
            return null;
        }
        Object objOpt = jSONObjectDecodeClaims.opt("exp");
        if (!(objOpt instanceof Number)) {
            if ((objOpt instanceof String) && (doubleOrNull = StringsKt.toDoubleOrNull((String) objOpt)) != null) {
                dDoubleValue = doubleOrNull.doubleValue();
            }
            return null;
        }
        dDoubleValue = ((Number) objOpt).doubleValue();
        if (!Double.isInfinite(dDoubleValue) && !Double.isNaN(dDoubleValue) && dDoubleValue > 0.0d) {
            return Long.valueOf((long) (dDoubleValue * 1000));
        }
        return null;
    }

    public final String extractUserId(JSONObject claims) {
        Intrinsics.checkNotNullParameter(claims, "claims");
        String[] strArr = {"sid", "iss"};
        for (int i = 0; i < 2; i++) {
            String strAsText = asText(claims, strArr[i]);
            if (strAsText.length() > 0) {
                String strHexToDecimal = hexToDecimal(strAsText);
                if (strHexToDecimal.length() > 0) {
                    return strHexToDecimal;
                }
            }
        }
        String[] strArr2 = {"sub", "id", "user_id"};
        for (int i2 = 0; i2 < 3; i2++) {
            String string = StringsKt.trim((CharSequence) asText(claims, strArr2[i2])).toString();
            if (string.length() > 0) {
                return string;
            }
        }
        return "";
    }

    public final String displayName(JSONObject claims) {
        Intrinsics.checkNotNullParameter(claims, "claims");
        return StringsKt.trim((CharSequence) asText(claims, "name")).toString();
    }

    public final String displayNameOf(JSONObject obj) {
        Intrinsics.checkNotNullParameter(obj, "obj");
        String[] strArr = {"nickname", "nick_name", "user_name", "name", "username", "phone", "mobile"};
        for (int i = 0; i < 7; i++) {
            String string = StringsKt.trim((CharSequence) asText(obj, strArr[i])).toString();
            if (string.length() > 0) {
                return string;
            }
        }
        return "";
    }

    public final String asText(JSONObject obj, String key) {
        Intrinsics.checkNotNullParameter(obj, "obj");
        Intrinsics.checkNotNullParameter(key, "key");
        if (!obj.has(key) || obj.isNull(key)) {
            return "";
        }
        Object objOpt = obj.opt(key);
        return objOpt instanceof String ? (String) objOpt : ((objOpt instanceof Number) || (objOpt instanceof Boolean)) ? objOpt.toString() : "";
    }

    public final String hexToDecimal(String raw) {
        Intrinsics.checkNotNullParameter(raw, "raw");
        String string = StringsKt.trim((CharSequence) raw).toString();
        if (StringsKt.startsWith$default(string, "0x", false, 2, (Object) null) || StringsKt.startsWith$default(string, "0X", false, 2, (Object) null)) {
            string = string.substring(2);
            Intrinsics.checkNotNullExpressionValue(string, "substring(...)");
        }
        String str = string;
        if (str.length() == 0) {
            return "";
        }
        for (int i = 0; i < str.length(); i++) {
            char cCharAt = str.charAt(i);
            if (('0' > cCharAt || cCharAt >= ':') && (('a' > cCharAt || cCharAt >= 'g') && ('A' > cCharAt || cCharAt >= 'G'))) {
                return "";
            }
        }
        int[] iArr = new int[256];
        int length = string.length();
        int i2 = 1;
        for (int i3 = 0; i3 < length; i3++) {
            int iDigit = Character.digit(string.charAt(i3), 16);
            int i4 = 0;
            while (true) {
                if (i4 >= i2 && iDigit <= 0) {
                    break;
                }
                int i5 = (iArr[i4] * 16) + iDigit;
                iArr[i4] = i5 % 10;
                iDigit = i5 / 10;
                i4++;
            }
            if (i4 > i2) {
                i2 = i4;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i6 = i2 - 1; -1 < i6; i6--) {
            sb.append(iArr[i6]);
        }
        String string2 = sb.toString();
        Intrinsics.checkNotNullExpressionValue(string2, "toString(...)");
        String strTrimStart = StringsKt.trimStart(string2, '0');
        return strTrimStart.length() == 0 ? "0" : strTrimStart;
    }
}
