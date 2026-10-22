.class public final Lcom/agui/server/GsiCompat;
.super Ljava/lang/Object;
.source "GsiCompat.java"

# Settings.System.getIntForUser(USER_CURRENT) requires INTERACT_ACROSS_USERS_FULL (signature),
# which a GSI cannot grant to an app with the stock signature. Single user, so read without a user.

.method public static getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I
    .registers 5
    invoke-static {p0, p1, p2}, Landroid/provider/Settings$System;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I
    move-result v0
    return v0
.end method
