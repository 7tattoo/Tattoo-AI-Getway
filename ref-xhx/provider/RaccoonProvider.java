package dev.raccoon2api.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.Metadata;
import kotlin.Pair;
import kotlin.Triple;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.io.CloseableKt;
import kotlin.io.ConstantsKt;
import kotlin.io.TextStreamsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.Charsets;
import kotlin.text.StringsKt;
import kotlin.text.Typography;
import org.json.JSONObject;

/* compiled from: RaccoonProvider.kt */
@Metadata(d1 = {"\u0000\u0090\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\f\n\u0002\u0010\u0002\n\u0002\b\u0005\n\u0002\u0010\t\n\u0000\n\u0002\u0010\b\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010$\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0012\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\r\bÆ\u0002\u0018\u00002\u00020\u0001:\u0001YB\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003J\u0006\u0010\u0015\u001a\u00020\u0016J\u000e\u0010$\u001a\u00020\u00052\u0006\u0010%\u001a\u00020\u0005J\u001c\u0010&\u001a\u0010\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u0005\u0018\u00010'2\u0006\u0010(\u001a\u00020\u0005J\u001c\u0010)\u001a\u000e\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u00050*2\u0006\u0010+\u001a\u00020\u0005H\u0002J>\u0010,\u001a\u00020\u00162\"\u0010-\u001a\u001e\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u00050.j\u000e\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u0005`/2\u0006\u00100\u001a\u00020\u00052\b\b\u0002\u00101\u001a\u00020\u0005H\u0002J\u0010\u00102\u001a\u00020\u00052\u0006\u00101\u001a\u00020\u0005H\u0002J\u000e\u00103\u001a\u0002042\u0006\u00105\u001a\u00020\u0005J\u000e\u00106\u001a\u0002072\u0006\u00108\u001a\u00020\u0005J\u0016\u00109\u001a\u00020:2\u0006\u0010;\u001a\u00020\u00052\u0006\u0010<\u001a\u00020=J\"\u0010>\u001a\u00020\u001e2\u0006\u0010?\u001a\u00020:2\u0012\u0010@\u001a\u000e\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u00160AJ\u0016\u0010B\u001a\u00020\u00052\u0006\u0010?\u001a\u00020:2\u0006\u0010C\u001a\u00020\u001eJ\u0014\u0010D\u001a\b\u0012\u0004\u0012\u00020F0E2\u0006\u0010;\u001a\u00020\u0005J\u000e\u0010G\u001a\u00020H2\u0006\u0010;\u001a\u00020\u0005J\u0010\u0010I\u001a\u00020\u00012\u0006\u0010J\u001a\u00020HH\u0002J\u000e\u0010K\u001a\u00020H2\u0006\u0010;\u001a\u00020\u0005JD\u0010L\u001a\u00020M2\u0006\u0010N\u001a\u00020\u00052\u0006\u0010O\u001a\u00020\u00052\b\u0010<\u001a\u0004\u0018\u00010=2\u0018\u0010P\u001a\u0014\u0012\u0010\u0012\u000e\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u00050'0E2\u0006\u0010Q\u001a\u00020\u001eH\u0002J\u001a\u0010R\u001a\u0004\u0018\u00010H2\u0006\u0010S\u001a\u00020M2\u0006\u0010T\u001a\u00020\u0005H\u0002J\u0018\u0010U\u001a\u00020\u00052\u0006\u0010V\u001a\u00020H2\u0006\u00100\u001a\u00020\u0005H\u0002J\u0019\u0010W\u001a\u0004\u0018\u00010\u001c2\b\u00101\u001a\u0004\u0018\u00010\u0001H\u0002¢\u0006\u0002\u0010XR\u000e\u0010\u0004\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R\u000e\u0010\u0007\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R \u0010\b\u001a\u0014\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u0005\u0012\u0004\u0012\u00020\u00050\tX\u0082\u0004¢\u0006\u0002\n\u0000R\u001a\u0010\n\u001a\u00020\u0005X\u0086\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u000b\u0010\f\"\u0004\b\r\u0010\u000eR\u001a\u0010\u000f\u001a\u00020\u0005X\u0086\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0010\u0010\f\"\u0004\b\u0011\u0010\u000eR\u001a\u0010\u0012\u001a\u00020\u0005X\u0086\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0013\u0010\f\"\u0004\b\u0014\u0010\u000eR\u000e\u0010\u0017\u001a\u00020\u0005X\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\u0018\u001a\u00020\u0005X\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\u0019\u001a\u00020\u0005X\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\u001a\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R\u000e\u0010\u001b\u001a\u00020\u001cX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\u001d\u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\u001f\u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010 \u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010!\u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010\"\u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000R\u000e\u0010#\u001a\u00020\u001eX\u0082T¢\u0006\u0002\n\u0000¨\u0006Z"}, d2 = {"Ldev/raccoon2api/provider/RaccoonProvider;", "", "<init>", "()V", "LLM_BASE", "", "AUTH_BASE", "MAIN_ORIGIN", "defaults", "Lkotlin/Triple;", "llmBaseUrl", "getLlmBaseUrl", "()Ljava/lang/String;", "setLlmBaseUrl", "(Ljava/lang/String;)V", "authApiBase", "getAuthApiBase", "setAuthApiBase", "mainSiteUrl", "getMainSiteUrl", "setMainSiteUrl", "resetBaseUrls", "", "AUTHORIZE_PATH", "LOGIN_SOURCE", "APP_NAME", "CALLBACK_PREFIX", "CODE_EXPIRED", "", "CONNECT_TIMEOUT_MS", "", "AUTH_TIMEOUT_MS", "CATALOG_TIMEOUT_MS", "BALANCE_TIMEOUT_MS", "BILLS_TIMEOUT_MS", "GRANT_CHECK_LIMIT", "buildAuthorizeUrl", "state", "parseCallbackCode", "Lkotlin/Pair;", "rawUrl", "parseQuery", "", "query", "decode", "map", "Ljava/util/LinkedHashMap;", "Lkotlin/collections/LinkedHashMap;", "key", "value", "URLDecoder", "exchangeCode", "Ldev/raccoon2api/provider/ExchangeResult;", "code", "refresh", "Ldev/raccoon2api/provider/RefreshResult;", "refreshToken", "openChat", "Ljava/net/HttpURLConnection;", "token", "body", "", "forEachSseEvent", "connection", "onEvent", "Lkotlin/Function1;", "describeError", "status", "fetchModelCatalog", "", "Ldev/raccoon2api/provider/ModelInfo;", "queryBalance", "Lorg/json/JSONObject;", "normalizeSubscription", "data", "checkin", "requestJson", "Ldev/raccoon2api/provider/RaccoonProvider$RawResponse;", "method", "url", "headers", "timeoutMs", "unwrapData", "response", "structureMessage", "textOf", "obj", "toInt", "(Ljava/lang/Object;)Ljava/lang/Long;", "RawResponse", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final class RaccoonProvider {
    private static final String APP_NAME = "办公小浣熊客户端";
    private static final String AUTHORIZE_PATH = "/code/authorize";
    private static final int AUTH_TIMEOUT_MS = 30000;
    private static final int BALANCE_TIMEOUT_MS = 20000;
    private static final int BILLS_TIMEOUT_MS = 25000;
    public static final String CALLBACK_PREFIX = "office-raccoon://auth/callback";
    private static final int CATALOG_TIMEOUT_MS = 10000;
    private static final long CODE_EXPIRED = 200035;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int GRANT_CHECK_LIMIT = 20;
    private static final String LOGIN_SOURCE = "desktop";
    public static final RaccoonProvider INSTANCE = new RaccoonProvider();
    public static final String LLM_BASE = "https://xiaohuanxiong.com/api/web/llm/v2";
    public static final String AUTH_BASE = "https://xiaohuanxiong.com/api/web/auth/v1";
    public static final String MAIN_ORIGIN = "https://xiaohuanxiong.com";
    private static final Triple<String, String, String> defaults = new Triple<>(LLM_BASE, AUTH_BASE, MAIN_ORIGIN);
    private static volatile String llmBaseUrl = LLM_BASE;
    private static volatile String authApiBase = AUTH_BASE;
    private static volatile String mainSiteUrl = MAIN_ORIGIN;

    private RaccoonProvider() {
    }

    public final String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public final void setLlmBaseUrl(String str) {
        Intrinsics.checkNotNullParameter(str, "<set-?>");
        llmBaseUrl = str;
    }

    public final String getAuthApiBase() {
        return authApiBase;
    }

    public final void setAuthApiBase(String str) {
        Intrinsics.checkNotNullParameter(str, "<set-?>");
        authApiBase = str;
    }

    public final String getMainSiteUrl() {
        return mainSiteUrl;
    }

    public final void setMainSiteUrl(String str) {
        Intrinsics.checkNotNullParameter(str, "<set-?>");
        mainSiteUrl = str;
    }

    public final void resetBaseUrls() {
        Triple<String, String, String> triple = defaults;
        llmBaseUrl = triple.getFirst();
        authApiBase = triple.getSecond();
        mainSiteUrl = triple.getThird();
    }

    public final String buildAuthorizeUrl(String state) throws UnsupportedEncodingException {
        Intrinsics.checkNotNullParameter(state, "state");
        return mainSiteUrl + "/code/authorize?login_source=desktop&appname=" + URLEncoder.encode(APP_NAME, "UTF-8") + "&state=" + URLEncoder.encode(state, "UTF-8");
    }

    public final Pair<String, String> parseCallbackCode(String rawUrl) {
        String string;
        String str;
        String string2;
        Intrinsics.checkNotNullParameter(rawUrl, "rawUrl");
        String string3 = StringsKt.trim((CharSequence) rawUrl).toString();
        if (!Intrinsics.areEqual(string3, CALLBACK_PREFIX) && !StringsKt.startsWith$default(string3, "office-raccoon://auth/callback?", false, 2, (Object) null)) {
            return null;
        }
        String strSubstring = string3.substring(30);
        Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
        Map<String, String> query = parseQuery(StringsKt.substringBefore$default(StringsKt.removePrefix(strSubstring, (CharSequence) "?"), '#', (String) null, 2, (Object) null));
        String str2 = query.get("code");
        if (str2 == null || (string = StringsKt.trim((CharSequence) str2).toString()) == null || (str = query.get("state")) == null || (string2 = StringsKt.trim((CharSequence) str).toString()) == null || string.length() == 0 || string.length() > 8192 || string2.length() == 0 || string2.length() > 512) {
            return null;
        }
        return TuplesKt.to(string, string2);
    }

    private final Map<String, String> parseQuery(String query) {
        LinkedHashMap<String, String> linkedHashMap;
        String str = query;
        if (str.length() == 0) {
            return MapsKt.emptyMap();
        }
        LinkedHashMap<String, String> linkedHashMap2 = new LinkedHashMap<>();
        for (String str2 : StringsKt.split$default((CharSequence) str, new char[]{Typography.amp}, false, 0, 6, (Object) null)) {
            int iIndexOf$default = StringsKt.indexOf$default((CharSequence) str2, '=', 0, false, 6, (Object) null);
            if (iIndexOf$default < 0) {
                linkedHashMap = linkedHashMap2;
                decode$default(this, linkedHashMap, str2, null, 4, null);
            } else {
                linkedHashMap = linkedHashMap2;
                String strSubstring = str2.substring(0, iIndexOf$default);
                Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
                String strSubstring2 = str2.substring(iIndexOf$default + 1);
                Intrinsics.checkNotNullExpressionValue(strSubstring2, "substring(...)");
                decode(linkedHashMap, strSubstring, strSubstring2);
            }
            linkedHashMap2 = linkedHashMap;
        }
        return linkedHashMap2;
    }

    static /* synthetic */ void decode$default(RaccoonProvider raccoonProvider, LinkedHashMap linkedHashMap, String str, String str2, int i, Object obj) {
        if ((i & 4) != 0) {
            str2 = "";
        }
        raccoonProvider.decode(linkedHashMap, str, str2);
    }

    private final void decode(LinkedHashMap<String, String> map, String key, String value) {
        try {
            map.put(URLDecoder(key), URLDecoder(value));
        } catch (Exception unused) {
            map.put(key, value);
        }
    }

    private final String URLDecoder(String value) throws UnsupportedEncodingException {
        String strDecode = URLDecoder.decode(value, "UTF-8");
        Intrinsics.checkNotNullExpressionValue(strDecode, "decode(...)");
        return strDecode;
    }

    public final ExchangeResult exchangeCode(String code) throws UpstreamError, IOException {
        Intrinsics.checkNotNullParameter(code, "code");
        String str = authApiBase + "/login_with_authorization_code";
        String string = new JSONObject().put("authorization_code", code).toString();
        Intrinsics.checkNotNullExpressionValue(string, "toString(...)");
        byte[] bytes = string.getBytes(Charsets.UTF_8);
        Intrinsics.checkNotNullExpressionValue(bytes, "getBytes(...)");
        RawResponse rawResponseRequestJson = requestJson("POST", str, bytes, CollectionsKt.emptyList(), AUTH_TIMEOUT_MS);
        JSONObject payload = rawResponseRequestJson.getPayload();
        if (payload == null) {
            payload = new JSONObject();
        }
        Long lValueOf = Long.valueOf(payload.optLong("code", 0L));
        lValueOf.longValue();
        if (!payload.has("code")) {
            lValueOf = null;
        }
        if (lValueOf != null && lValueOf.longValue() == CODE_EXPIRED) {
            throw new UpstreamError(400, "授权码已失效，请重新发起网页登录", lValueOf);
        }
        int status = rawResponseRequestJson.getStatus();
        int status2 = 502;
        if (200 > status || status >= 300) {
            String strOptString = payload.optString("message");
            if (strOptString.length() == 0) {
                String strOptString2 = payload.optString("msg");
                if (strOptString2.length() == 0) {
                    strOptString2 = "网页登录换取凭证失败（HTTP " + rawResponseRequestJson.getStatus() + "）";
                }
                strOptString = strOptString2;
            }
            String str2 = strOptString;
            if (Intrinsics.areEqual(str2, "internal_server_error")) {
                str2 = "登录服务异常，请稍后重试";
            }
            int status3 = rawResponseRequestJson.getStatus();
            if (400 <= status3 && status3 < 500) {
                status2 = rawResponseRequestJson.getStatus();
            }
            Intrinsics.checkNotNull(str2);
            throw new UpstreamError(status2, str2, lValueOf);
        }
        JSONObject jSONObjectOptJSONObject = payload.optJSONObject("data");
        if (jSONObjectOptJSONObject == null) {
            jSONObjectOptJSONObject = new JSONObject();
        }
        String strTextOf = textOf(jSONObjectOptJSONObject, "access_token");
        String strTextOf2 = textOf(jSONObjectOptJSONObject, "refresh_token");
        if (strTextOf.length() == 0 || strTextOf2.length() == 0) {
            throw new UpstreamError(502, "网页登录换取凭证失败（HTTP " + rawResponseRequestJson.getStatus() + "）", lValueOf);
        }
        return new ExchangeResult(strTextOf, strTextOf2, StringsKt.trim((CharSequence) textOf(jSONObjectOptJSONObject, "office_identity")).toString(), StringsKt.trim((CharSequence) textOf(jSONObjectOptJSONObject, "office_org_name")).toString(), StringsKt.trim((CharSequence) textOf(jSONObjectOptJSONObject, "office_org_role")).toString(), RaccoonJwt.INSTANCE.displayNameOf(jSONObjectOptJSONObject));
    }

    public final RefreshResult refresh(String refreshToken) {
        Intrinsics.checkNotNullParameter(refreshToken, "refreshToken");
        String str = authApiBase + "/refresh";
        String string = new JSONObject().put("refresh_token", refreshToken).toString();
        Intrinsics.checkNotNullExpressionValue(string, "toString(...)");
        byte[] bytes = string.getBytes(Charsets.UTF_8);
        Intrinsics.checkNotNullExpressionValue(bytes, "getBytes(...)");
        RawResponse rawResponseRequestJson = requestJson("POST", str, bytes, CollectionsKt.listOf(TuplesKt.to("Content-Type", "application/json")), AUTH_TIMEOUT_MS);
        JSONObject payload = rawResponseRequestJson.getPayload();
        if (payload == null) {
            payload = new JSONObject();
        }
        int status = rawResponseRequestJson.getStatus();
        if (200 > status || status >= 300) {
            String strOptString = payload.optString("message");
            if (strOptString.length() == 0) {
                String strOptString2 = payload.optString("msg");
                if (strOptString2.length() == 0) {
                    strOptString2 = "服务器未返回错误说明";
                }
                strOptString = strOptString2;
            }
            throw new UpstreamError(rawResponseRequestJson.getStatus() != 401 ? 502 : 401, "小浣熊刷新接口失败（HTTP " + rawResponseRequestJson.getStatus() + "）: " + strOptString, null, 4, null);
        }
        JSONObject jSONObjectOptJSONObject = payload.optJSONObject("data");
        if (jSONObjectOptJSONObject == null) {
            jSONObjectOptJSONObject = new JSONObject();
        }
        String strTextOf = textOf(jSONObjectOptJSONObject, "access_token");
        if (strTextOf.length() == 0) {
            String strOptString3 = payload.optString("code", "null");
            String strOptString4 = payload.optString("message");
            if (strOptString4.length() == 0) {
                String strOptString5 = payload.optString("msg");
                if (strOptString5.length() == 0) {
                    strOptString5 = "服务器未返回新 token";
                }
                strOptString4 = strOptString5;
            }
            throw new UpstreamError(401, "小浣熊 token 刷新失败 code=" + strOptString3 + " msg=" + ((Object) strOptString4), null, 4, null);
        }
        String strTextOf2 = textOf(jSONObjectOptJSONObject, "refresh_token");
        if (strTextOf2.length() != 0) {
            refreshToken = strTextOf2;
        }
        return new RefreshResult(strTextOf, refreshToken);
    }

    public final HttpURLConnection openChat(String token, byte[] body) throws IOException {
        Intrinsics.checkNotNullParameter(token, "token");
        Intrinsics.checkNotNullParameter(body, "body");
        URLConnection uRLConnectionOpenConnection = new URL(llmBaseUrl + "/chat/completions").openConnection();
        Intrinsics.checkNotNull(uRLConnectionOpenConnection, "null cannot be cast to non-null type java.net.HttpURLConnection");
        HttpURLConnection httpURLConnection = (HttpURLConnection) uRLConnectionOpenConnection;
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        httpURLConnection.setReadTimeout(0);
        httpURLConnection.setDoOutput(true);
        httpURLConnection.setRequestProperty("Content-Type", "application/json");
        httpURLConnection.setRequestProperty("Accept", "*/*");
        httpURLConnection.setRequestProperty("Authorization", "Bearer " + RaccoonJwt.INSTANCE.stripBearer(token));
        OutputStream outputStream = httpURLConnection.getOutputStream();
        try {
            outputStream.write(body);
            Unit unit = Unit.INSTANCE;
            CloseableKt.closeFinally(outputStream, null);
            return httpURLConnection;
        } finally {
        }
    }

    public final int forEachSseEvent(HttpURLConnection connection, Function1<? super String, Unit> onEvent) throws UpstreamError, IOException {
        boolean z;
        Intrinsics.checkNotNullParameter(connection, "connection");
        Intrinsics.checkNotNullParameter(onEvent, "onEvent");
        int responseCode = connection.getResponseCode();
        if (200 > responseCode || responseCode >= 300) {
            throw new UpstreamError(responseCode, describeError(connection, responseCode), null);
        }
        InputStream inputStream = connection.getInputStream();
        Intrinsics.checkNotNullExpressionValue(inputStream, "getInputStream(...)");
        Reader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
        BufferedReader bufferedReader = inputStreamReader instanceof BufferedReader ? (BufferedReader) inputStreamReader : new BufferedReader(inputStreamReader, ConstantsKt.DEFAULT_BUFFER_SIZE);
        try {
            BufferedReader bufferedReader2 = bufferedReader;
            StringBuilder sb = new StringBuilder();
            loop0: while (true) {
                z = false;
                while (true) {
                    String line = bufferedReader2.readLine();
                    if (line == null) {
                        break loop0;
                    }
                    if (StringsKt.startsWith$default(line, "data:", false, 2, (Object) null)) {
                        String strSubstring = line.substring(5);
                        Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
                        if (StringsKt.startsWith$default(strSubstring, " ", false, 2, (Object) null)) {
                            strSubstring = strSubstring.substring(1);
                            Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
                        }
                        if (z) {
                            sb.append('\n');
                        }
                        sb.append(strSubstring);
                        z = true;
                    } else if (line.length() != 0) {
                        line.charAt(0);
                    } else if (z) {
                        break;
                    }
                }
                String string = sb.toString();
                Intrinsics.checkNotNullExpressionValue(string, "toString(...)");
                onEvent.invoke(string);
                sb.setLength(0);
            }
            if (z) {
                String string2 = sb.toString();
                Intrinsics.checkNotNullExpressionValue(string2, "toString(...)");
                onEvent.invoke(string2);
            }
            Unit unit = Unit.INSTANCE;
            CloseableKt.closeFinally(bufferedReader, null);
            return responseCode;
        } finally {
        }
    }

    public final String describeError(HttpURLConnection connection, int status) {
        String strTake;
        String strOptString;
        Intrinsics.checkNotNullParameter(connection, "connection");
        String str = "";
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) {
                errorStream = connection.getInputStream();
                Intrinsics.checkNotNullExpressionValue(errorStream, "getInputStream(...)");
            }
            Reader inputStreamReader = new InputStreamReader(errorStream, Charsets.UTF_8);
            strTake = StringsKt.take(TextStreamsKt.readText(inputStreamReader instanceof BufferedReader ? (BufferedReader) inputStreamReader : new BufferedReader(inputStreamReader, ConstantsKt.DEFAULT_BUFFER_SIZE)), ConstantsKt.DEFAULT_BUFFER_SIZE);
        } catch (Exception unused) {
            strTake = "";
        }
        try {
            JSONObject jSONObject = new JSONObject(strTake);
            String strOptString2 = jSONObject.optString("message");
            if (strOptString2.length() == 0) {
                String strOptString3 = jSONObject.optString("msg");
                if (strOptString3.length() == 0) {
                    JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("error");
                    strOptString3 = (jSONObjectOptJSONObject == null || (strOptString = jSONObjectOptJSONObject.optString("message")) == null) ? "" : strOptString;
                }
                strOptString2 = strOptString3;
            }
            str = strOptString2;
        } catch (Exception unused2) {
        }
        String str2 = str;
        if (str2.length() == 0) {
            String string = StringsKt.trim((CharSequence) strTake).toString();
            if (string.length() == 0) {
                string = "上游错误";
            }
            str2 = string;
        }
        return "上游返回 " + status + ": " + str2;
    }

    public final List<ModelInfo> fetchModelCatalog(String token) {
        Intrinsics.checkNotNullParameter(token, "token");
        String str = llmBaseUrl + "/model_catalog";
        List<Pair<String, String>> listMutableListOf = CollectionsKt.mutableListOf(TuplesKt.to("Accept", "application/json"));
        if (token.length() > 0) {
            listMutableListOf.add(TuplesKt.to("Authorization", "Bearer " + RaccoonJwt.INSTANCE.stripBearer(token)));
        }
        RawResponse rawResponseRequestJson = requestJson("GET", str, null, listMutableListOf, CATALOG_TIMEOUT_MS);
        int status = rawResponseRequestJson.getStatus();
        if (200 > status || status >= 300) {
            throw new UpstreamError(rawResponseRequestJson.getStatus(), "上游返回 HTTP " + rawResponseRequestJson.getStatus(), null);
        }
        JSONObject payload = rawResponseRequestJson.getPayload();
        return payload == null ? CollectionsKt.emptyList() : RaccoonModels.INSTANCE.parseCatalog(payload);
    }

    /* JADX WARN: Removed duplicated region for block: B:32:0x016e  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x0178  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final org.json.JSONObject queryBalance(java.lang.String r20) throws dev.raccoon2api.provider.UpstreamError, org.json.JSONException {
        /*
            Method dump skipped, instructions count: 385
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: dev.raccoon2api.provider.RaccoonProvider.queryBalance(java.lang.String):org.json.JSONObject");
    }

    /* JADX WARN: Removed duplicated region for block: B:20:0x0042  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final java.lang.Object normalizeSubscription(org.json.JSONObject r7) throws org.json.JSONException {
        /*
            r6 = this;
            java.lang.String r0 = "office"
            org.json.JSONObject r0 = r7.optJSONObject(r0)
            java.lang.String r1 = "code"
            org.json.JSONObject r7 = r7.optJSONObject(r1)
            if (r0 != 0) goto L18
            if (r7 != 0) goto L18
            java.lang.Object r7 = org.json.JSONObject.NULL
            java.lang.String r0 = "NULL"
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(r7, r0)
            return r7
        L18:
            java.lang.String r1 = "pro_enable"
            if (r0 == 0) goto L21
            boolean r1 = r0.optBoolean(r1)
            goto L29
        L21:
            if (r7 == 0) goto L28
            boolean r1 = r7.optBoolean(r1)
            goto L29
        L28:
            r1 = 0
        L29:
            java.lang.String r2 = "active_plan"
            java.lang.Object r3 = normalizeSubscription$pick(r0, r7, r2)
            boolean r4 = r3 instanceof org.json.JSONObject
            r5 = 0
            if (r4 == 0) goto L37
            org.json.JSONObject r3 = (org.json.JSONObject) r3
            goto L38
        L37:
            r3 = r5
        L38:
            if (r3 == 0) goto L42
            java.lang.String r4 = "type"
            java.lang.Object r3 = r3.opt(r4)
            if (r3 != 0) goto L48
        L42:
            java.lang.String r3 = "plan_type"
            java.lang.Object r3 = normalizeSubscription$pick(r0, r7, r3)
        L48:
            java.lang.String r4 = "pro_expired_time"
            java.lang.Object r4 = normalizeSubscription$pick(r0, r7, r4)
            if (r4 != 0) goto L66
            java.lang.Object r7 = normalizeSubscription$pick(r0, r7, r2)
            boolean r0 = r7 instanceof org.json.JSONObject
            if (r0 == 0) goto L5b
            org.json.JSONObject r7 = (org.json.JSONObject) r7
            goto L5c
        L5b:
            r7 = r5
        L5c:
            if (r7 == 0) goto L65
            java.lang.String r0 = "subscription_expired_at"
            java.lang.Object r4 = r7.opt(r0)
            goto L66
        L65:
            r4 = r5
        L66:
            org.json.JSONObject r7 = new org.json.JSONObject
            r7.<init>()
            boolean r0 = r3 instanceof java.lang.String
            if (r0 == 0) goto L72
            r5 = r3
            java.lang.String r5 = (java.lang.String) r5
        L72:
            if (r5 != 0) goto L76
            java.lang.String r5 = ""
        L76:
            java.lang.String r0 = "planName"
            org.json.JSONObject r7 = r7.put(r0, r5)
            if (r1 == 0) goto L81
            java.lang.String r0 = "active"
            goto L83
        L81:
            java.lang.String r0 = "none"
        L83:
            java.lang.String r1 = "status"
            org.json.JSONObject r7 = r7.put(r1, r0)
            if (r4 != 0) goto L8d
            java.lang.Object r4 = org.json.JSONObject.NULL
        L8d:
            java.lang.String r0 = "expireAt"
            org.json.JSONObject r7 = r7.put(r0, r4)
            java.lang.String r0 = "put(...)"
            kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(r7, r0)
            return r7
        */
        throw new UnsupportedOperationException("Method not decompiled: dev.raccoon2api.provider.RaccoonProvider.normalizeSubscription(org.json.JSONObject):java.lang.Object");
    }

    private static final Object normalizeSubscription$pick(JSONObject jSONObject, JSONObject jSONObject2, String str) {
        Object objOpt;
        Object objOpt2;
        if (jSONObject == null || (objOpt = jSONObject.opt(str)) == null || Intrinsics.areEqual(objOpt, JSONObject.NULL)) {
            objOpt = null;
        }
        if (objOpt != null) {
            return objOpt;
        }
        if (jSONObject2 == null || (objOpt2 = jSONObject2.opt(str)) == null || Intrinsics.areEqual(objOpt2, JSONObject.NULL)) {
            return null;
        }
        return objOpt2;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(46:0|2|(12:163|3|143|4|5|155|6|7|157|8|(1:10)|11)|23|149|24|141|25|26|147|27|(1:29)|30|(1:32)|33|42|145|43|44|(8:153|45|(1:47)|48|(1:50)|51|(1:53)|54)|61|151|62|63|(6:161|64|(1:66)|67|(3:69|(3:71|(2:73|169)(4:74|165|75|(4:77|159|78|(2:80|(4:82|(1:84)|85|171)(2:87|170))(0))(2:90|168))|91)|167)|94)|101|(2:104|102)|172|105|(1:107)|108|(5:110|(1:112)(1:113)|114|(1:116)(1:117)|118)|119|(1:121)(1:(1:123)(1:124))|(1:126)|127|(1:129)|130|(1:132)|133|(1:135)|136|(1:138)|139|140|(1:(0))) */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x0351, code lost:
    
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:98:0x0352, code lost:
    
        r17 = 0;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:104:0x0371 A[LOOP:1: B:102:0x036b->B:104:0x0371, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:107:0x0390  */
    /* JADX WARN: Removed duplicated region for block: B:110:0x039b  */
    /* JADX WARN: Removed duplicated region for block: B:121:0x03ce  */
    /* JADX WARN: Removed duplicated region for block: B:122:0x03e1  */
    /* JADX WARN: Removed duplicated region for block: B:126:0x03f0  */
    /* JADX WARN: Removed duplicated region for block: B:129:0x0408  */
    /* JADX WARN: Removed duplicated region for block: B:132:0x0413  */
    /* JADX WARN: Removed duplicated region for block: B:135:0x041f  */
    /* JADX WARN: Removed duplicated region for block: B:138:0x045f  */
    /* JADX WARN: Removed duplicated region for block: B:29:0x0177 A[Catch: UpstreamError -> 0x0197, TryCatch #3 {UpstreamError -> 0x0197, blocks: (B:27:0x016d, B:29:0x0177, B:30:0x017c, B:32:0x0190, B:33:0x0192), top: B:147:0x016d }] */
    /* JADX WARN: Removed duplicated region for block: B:32:0x0190 A[Catch: UpstreamError -> 0x0197, TryCatch #3 {UpstreamError -> 0x0197, blocks: (B:27:0x016d, B:29:0x0177, B:30:0x017c, B:32:0x0190, B:33:0x0192), top: B:147:0x016d }] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x01ed A[Catch: UpstreamError -> 0x0214, TryCatch #6 {UpstreamError -> 0x0214, blocks: (B:45:0x01e3, B:47:0x01ed, B:48:0x01f2, B:50:0x01ff, B:51:0x0201, B:53:0x020d, B:54:0x020f), top: B:153:0x01e3 }] */
    /* JADX WARN: Removed duplicated region for block: B:50:0x01ff A[Catch: UpstreamError -> 0x0214, TryCatch #6 {UpstreamError -> 0x0214, blocks: (B:45:0x01e3, B:47:0x01ed, B:48:0x01f2, B:50:0x01ff, B:51:0x0201, B:53:0x020d, B:54:0x020f), top: B:153:0x01e3 }] */
    /* JADX WARN: Removed duplicated region for block: B:53:0x020d A[Catch: UpstreamError -> 0x0214, TryCatch #6 {UpstreamError -> 0x0214, blocks: (B:45:0x01e3, B:47:0x01ed, B:48:0x01f2, B:50:0x01ff, B:51:0x0201, B:53:0x020d, B:54:0x020f), top: B:153:0x01e3 }] */
    /* JADX WARN: Removed duplicated region for block: B:66:0x0290 A[Catch: UpstreamError -> 0x034d, TryCatch #10 {UpstreamError -> 0x034d, blocks: (B:64:0x0284, B:66:0x0290, B:67:0x0295, B:69:0x029d, B:71:0x02a4), top: B:161:0x0284 }] */
    /* JADX WARN: Removed duplicated region for block: B:69:0x029d A[Catch: UpstreamError -> 0x034d, TryCatch #10 {UpstreamError -> 0x034d, blocks: (B:64:0x0284, B:66:0x0290, B:67:0x0295, B:69:0x029d, B:71:0x02a4), top: B:161:0x0284 }] */
    /* JADX WARN: Removed duplicated region for block: B:87:0x0328  */
    /* JADX WARN: Type inference failed for: r0v34, types: [java.lang.Object] */
    /* JADX WARN: Type inference failed for: r11v13 */
    /* JADX WARN: Type inference failed for: r11v14 */
    /* JADX WARN: Type inference failed for: r11v3 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final org.json.JSONObject checkin(java.lang.String r35) {
        /*
            Method dump skipped, instructions count: 1133
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: dev.raccoon2api.provider.RaccoonProvider.checkin(java.lang.String):org.json.JSONObject");
    }

    /* compiled from: RaccoonProvider.kt */
    @Metadata(d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0007\b\u0002\u0018\u00002\u00020\u0001B\u0019\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\b\u0010\u0004\u001a\u0004\u0018\u00010\u0005¢\u0006\u0004\b\u0006\u0010\u0007R\u0011\u0010\u0002\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\b\u0010\tR\u0013\u0010\u0004\u001a\u0004\u0018\u00010\u0005¢\u0006\b\n\u0000\u001a\u0004\b\n\u0010\u000b¨\u0006\f"}, d2 = {"Ldev/raccoon2api/provider/RaccoonProvider$RawResponse;", "", "status", "", "payload", "Lorg/json/JSONObject;", "<init>", "(ILorg/json/JSONObject;)V", "getStatus", "()I", "getPayload", "()Lorg/json/JSONObject;", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
    private static final class RawResponse {
        private final JSONObject payload;
        private final int status;

        public RawResponse(int i, JSONObject jSONObject) {
            this.status = i;
            this.payload = jSONObject;
        }

        public final JSONObject getPayload() {
            return this.payload;
        }

        public final int getStatus() {
            return this.status;
        }
    }

    private final RawResponse requestJson(String method, String url, byte[] body, List<Pair<String, String>> headers, int timeoutMs) throws IOException {
        String text;
        URLConnection uRLConnectionOpenConnection = new URL(url).openConnection();
        Intrinsics.checkNotNull(uRLConnectionOpenConnection, "null cannot be cast to non-null type java.net.HttpURLConnection");
        HttpURLConnection httpURLConnection = (HttpURLConnection) uRLConnectionOpenConnection;
        httpURLConnection.setRequestMethod(method);
        httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        httpURLConnection.setReadTimeout(timeoutMs);
        for (Pair<String, String> pair : headers) {
            httpURLConnection.setRequestProperty(pair.component1(), pair.component2());
        }
        JSONObject jSONObject = null;
        if (body != null) {
            httpURLConnection.setDoOutput(true);
            OutputStream outputStream = httpURLConnection.getOutputStream();
            try {
                outputStream.write(body);
                Unit unit = Unit.INSTANCE;
                CloseableKt.closeFinally(outputStream, null);
            } finally {
            }
        }
        try {
            int responseCode = httpURLConnection.getResponseCode();
            if (200 <= responseCode && responseCode < 300) {
                InputStream inputStream = httpURLConnection.getInputStream();
                Intrinsics.checkNotNullExpressionValue(inputStream, "getInputStream(...)");
                Reader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
                text = TextStreamsKt.readText(inputStreamReader instanceof BufferedReader ? (BufferedReader) inputStreamReader : new BufferedReader(inputStreamReader, ConstantsKt.DEFAULT_BUFFER_SIZE));
            } else {
                InputStream errorStream = httpURLConnection.getErrorStream();
                if (errorStream != null) {
                    Reader inputStreamReader2 = new InputStreamReader(errorStream, Charsets.UTF_8);
                    text = TextStreamsKt.readText(inputStreamReader2 instanceof BufferedReader ? (BufferedReader) inputStreamReader2 : new BufferedReader(inputStreamReader2, ConstantsKt.DEFAULT_BUFFER_SIZE));
                } else {
                    text = null;
                }
                if (text == null) {
                    text = "";
                }
            }
            try {
                if (!StringsKt.isBlank(text)) {
                    jSONObject = new JSONObject(text);
                }
            } catch (Exception unused) {
            }
            return new RawResponse(responseCode, jSONObject);
        } finally {
            httpURLConnection.disconnect();
        }
    }

    private final JSONObject unwrapData(RawResponse response, String structureMessage) throws UpstreamError {
        if (response.getStatus() == 401) {
            throw new UpstreamError(401, "登录态已过期，无法查询积分", null, 4, null);
        }
        int status = response.getStatus();
        if (200 > status || status >= 300) {
            throw new UpstreamError(response.getStatus(), "积分查询返回 HTTP " + response.getStatus(), null);
        }
        JSONObject payload = response.getPayload();
        if (payload == null) {
            throw new UpstreamError(502, structureMessage, null, 4, null);
        }
        if (payload.has("code")) {
            long jOptLong = payload.optLong("code", 0L);
            if (jOptLong != 0) {
                String strOptString = payload.optString("message");
                if (strOptString.length() == 0) {
                    strOptString = "积分查询返回异常 code=" + jOptLong;
                }
                String str = strOptString;
                Intrinsics.checkNotNull(str);
                throw new UpstreamError(502, str, Long.valueOf(jOptLong));
            }
        }
        JSONObject jSONObjectOptJSONObject = payload.optJSONObject("data");
        return jSONObjectOptJSONObject == null ? payload : jSONObjectOptJSONObject;
    }

    private final String textOf(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) {
            return "";
        }
        Object objOpt = obj.opt(key);
        return objOpt instanceof String ? (String) objOpt : ((objOpt instanceof Number) || (objOpt instanceof Boolean)) ? objOpt.toString() : "";
    }

    private final Long toInt(Object value) {
        Double doubleOrNull;
        double dDoubleValue;
        if (!(value instanceof Number)) {
            if ((value instanceof String) && (doubleOrNull = StringsKt.toDoubleOrNull((String) value)) != null) {
                dDoubleValue = doubleOrNull.doubleValue();
            }
            return null;
        }
        dDoubleValue = ((Number) value).doubleValue();
        if (!Double.isInfinite(dDoubleValue) && !Double.isNaN(dDoubleValue)) {
            return Long.valueOf((long) Math.floor(dDoubleValue));
        }
        return null;
    }
}
