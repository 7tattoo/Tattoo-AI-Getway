package dev.raccoon2api.provider;

import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;

/* compiled from: RaccoonProvider.kt */
@Metadata(d1 = {"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\n\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\b\u0086\b\u0018\u00002\u00020\u0001B\u0017\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003¢\u0006\u0004\b\u0005\u0010\u0006J\t\u0010\n\u001a\u00020\u0003HÆ\u0003J\t\u0010\u000b\u001a\u00020\u0003HÆ\u0003J\u001d\u0010\f\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u0003HÆ\u0001J\u0013\u0010\r\u001a\u00020\u000e2\b\u0010\u000f\u001a\u0004\u0018\u00010\u0001HÖ\u0003J\t\u0010\u0010\u001a\u00020\u0011HÖ\u0001J\t\u0010\u0012\u001a\u00020\u0003HÖ\u0001R\u0011\u0010\u0002\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0007\u0010\bR\u0011\u0010\u0004\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\t\u0010\b¨\u0006\u0013"}, d2 = {"Ldev/raccoon2api/provider/RefreshResult;", "", "accessToken", "", "refreshToken", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", "getAccessToken", "()Ljava/lang/String;", "getRefreshToken", "component1", "component2", "copy", "equals", "", "other", "hashCode", "", "toString", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final /* data */ class RefreshResult {
    private final String accessToken;
    private final String refreshToken;

    public static /* synthetic */ RefreshResult copy$default(RefreshResult refreshResult, String str, String str2, int i, Object obj) {
        if ((i & 1) != 0) {
            str = refreshResult.accessToken;
        }
        if ((i & 2) != 0) {
            str2 = refreshResult.refreshToken;
        }
        return refreshResult.copy(str, str2);
    }

    /* renamed from: component1, reason: from getter */
    public final String getAccessToken() {
        return this.accessToken;
    }

    /* renamed from: component2, reason: from getter */
    public final String getRefreshToken() {
        return this.refreshToken;
    }

    public final RefreshResult copy(String accessToken, String refreshToken) {
        Intrinsics.checkNotNullParameter(accessToken, "accessToken");
        Intrinsics.checkNotNullParameter(refreshToken, "refreshToken");
        return new RefreshResult(accessToken, refreshToken);
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RefreshResult)) {
            return false;
        }
        RefreshResult refreshResult = (RefreshResult) other;
        return Intrinsics.areEqual(this.accessToken, refreshResult.accessToken) && Intrinsics.areEqual(this.refreshToken, refreshResult.refreshToken);
    }

    public int hashCode() {
        return (this.accessToken.hashCode() * 31) + this.refreshToken.hashCode();
    }

    public String toString() {
        return "RefreshResult(accessToken=" + this.accessToken + ", refreshToken=" + this.refreshToken + ")";
    }

    public RefreshResult(String accessToken, String refreshToken) {
        Intrinsics.checkNotNullParameter(accessToken, "accessToken");
        Intrinsics.checkNotNullParameter(refreshToken, "refreshToken");
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public final String getAccessToken() {
        return this.accessToken;
    }

    public final String getRefreshToken() {
        return this.refreshToken;
    }
}
