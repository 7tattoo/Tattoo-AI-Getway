package dev.raccoon2api.provider;

import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;

/* compiled from: RaccoonProvider.kt */
@Metadata(d1 = {"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0016\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\b\u0086\b\u0018\u00002\u00020\u0001B7\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u0012\u0006\u0010\u0005\u001a\u00020\u0003\u0012\u0006\u0010\u0006\u001a\u00020\u0003\u0012\u0006\u0010\u0007\u001a\u00020\u0003\u0012\u0006\u0010\b\u001a\u00020\u0003¢\u0006\u0004\b\t\u0010\nJ\t\u0010\u0012\u001a\u00020\u0003HÆ\u0003J\t\u0010\u0013\u001a\u00020\u0003HÆ\u0003J\t\u0010\u0014\u001a\u00020\u0003HÆ\u0003J\t\u0010\u0015\u001a\u00020\u0003HÆ\u0003J\t\u0010\u0016\u001a\u00020\u0003HÆ\u0003J\t\u0010\u0017\u001a\u00020\u0003HÆ\u0003JE\u0010\u0018\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00032\b\b\u0002\u0010\u0005\u001a\u00020\u00032\b\b\u0002\u0010\u0006\u001a\u00020\u00032\b\b\u0002\u0010\u0007\u001a\u00020\u00032\b\b\u0002\u0010\b\u001a\u00020\u0003HÆ\u0001J\u0013\u0010\u0019\u001a\u00020\u001a2\b\u0010\u001b\u001a\u0004\u0018\u00010\u0001HÖ\u0003J\t\u0010\u001c\u001a\u00020\u001dHÖ\u0001J\t\u0010\u001e\u001a\u00020\u0003HÖ\u0001R\u0011\u0010\u0002\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u000b\u0010\fR\u0011\u0010\u0004\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\r\u0010\fR\u0011\u0010\u0005\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u000e\u0010\fR\u0011\u0010\u0006\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\fR\u0011\u0010\u0007\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0010\u0010\fR\u0011\u0010\b\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010\f¨\u0006\u001f"}, d2 = {"Ldev/raccoon2api/provider/ExchangeResult;", "", "accessToken", "", "refreshToken", "officeIdentity", "orgName", "officeRole", "displayName", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", "getAccessToken", "()Ljava/lang/String;", "getRefreshToken", "getOfficeIdentity", "getOrgName", "getOfficeRole", "getDisplayName", "component1", "component2", "component3", "component4", "component5", "component6", "copy", "equals", "", "other", "hashCode", "", "toString", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final /* data */ class ExchangeResult {
    private final String accessToken;
    private final String displayName;
    private final String officeIdentity;
    private final String officeRole;
    private final String orgName;
    private final String refreshToken;

    public static /* synthetic */ ExchangeResult copy$default(ExchangeResult exchangeResult, String str, String str2, String str3, String str4, String str5, String str6, int i, Object obj) {
        if ((i & 1) != 0) {
            str = exchangeResult.accessToken;
        }
        if ((i & 2) != 0) {
            str2 = exchangeResult.refreshToken;
        }
        if ((i & 4) != 0) {
            str3 = exchangeResult.officeIdentity;
        }
        if ((i & 8) != 0) {
            str4 = exchangeResult.orgName;
        }
        if ((i & 16) != 0) {
            str5 = exchangeResult.officeRole;
        }
        if ((i & 32) != 0) {
            str6 = exchangeResult.displayName;
        }
        String str7 = str5;
        String str8 = str6;
        return exchangeResult.copy(str, str2, str3, str4, str7, str8);
    }

    /* renamed from: component1, reason: from getter */
    public final String getAccessToken() {
        return this.accessToken;
    }

    /* renamed from: component2, reason: from getter */
    public final String getRefreshToken() {
        return this.refreshToken;
    }

    /* renamed from: component3, reason: from getter */
    public final String getOfficeIdentity() {
        return this.officeIdentity;
    }

    /* renamed from: component4, reason: from getter */
    public final String getOrgName() {
        return this.orgName;
    }

    /* renamed from: component5, reason: from getter */
    public final String getOfficeRole() {
        return this.officeRole;
    }

    /* renamed from: component6, reason: from getter */
    public final String getDisplayName() {
        return this.displayName;
    }

    public final ExchangeResult copy(String accessToken, String refreshToken, String officeIdentity, String orgName, String officeRole, String displayName) {
        Intrinsics.checkNotNullParameter(accessToken, "accessToken");
        Intrinsics.checkNotNullParameter(refreshToken, "refreshToken");
        Intrinsics.checkNotNullParameter(officeIdentity, "officeIdentity");
        Intrinsics.checkNotNullParameter(orgName, "orgName");
        Intrinsics.checkNotNullParameter(officeRole, "officeRole");
        Intrinsics.checkNotNullParameter(displayName, "displayName");
        return new ExchangeResult(accessToken, refreshToken, officeIdentity, orgName, officeRole, displayName);
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExchangeResult)) {
            return false;
        }
        ExchangeResult exchangeResult = (ExchangeResult) other;
        return Intrinsics.areEqual(this.accessToken, exchangeResult.accessToken) && Intrinsics.areEqual(this.refreshToken, exchangeResult.refreshToken) && Intrinsics.areEqual(this.officeIdentity, exchangeResult.officeIdentity) && Intrinsics.areEqual(this.orgName, exchangeResult.orgName) && Intrinsics.areEqual(this.officeRole, exchangeResult.officeRole) && Intrinsics.areEqual(this.displayName, exchangeResult.displayName);
    }

    public int hashCode() {
        return (((((((((this.accessToken.hashCode() * 31) + this.refreshToken.hashCode()) * 31) + this.officeIdentity.hashCode()) * 31) + this.orgName.hashCode()) * 31) + this.officeRole.hashCode()) * 31) + this.displayName.hashCode();
    }

    public String toString() {
        return "ExchangeResult(accessToken=" + this.accessToken + ", refreshToken=" + this.refreshToken + ", officeIdentity=" + this.officeIdentity + ", orgName=" + this.orgName + ", officeRole=" + this.officeRole + ", displayName=" + this.displayName + ")";
    }

    public ExchangeResult(String accessToken, String refreshToken, String officeIdentity, String orgName, String officeRole, String displayName) {
        Intrinsics.checkNotNullParameter(accessToken, "accessToken");
        Intrinsics.checkNotNullParameter(refreshToken, "refreshToken");
        Intrinsics.checkNotNullParameter(officeIdentity, "officeIdentity");
        Intrinsics.checkNotNullParameter(orgName, "orgName");
        Intrinsics.checkNotNullParameter(officeRole, "officeRole");
        Intrinsics.checkNotNullParameter(displayName, "displayName");
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.officeIdentity = officeIdentity;
        this.orgName = orgName;
        this.officeRole = officeRole;
        this.displayName = displayName;
    }

    public final String getAccessToken() {
        return this.accessToken;
    }

    public final String getRefreshToken() {
        return this.refreshToken;
    }

    public final String getOfficeIdentity() {
        return this.officeIdentity;
    }

    public final String getOrgName() {
        return this.orgName;
    }

    public final String getOfficeRole() {
        return this.officeRole;
    }

    public final String getDisplayName() {
        return this.displayName;
    }
}
