.class public Lcom/agui/server/AServiceManager;
.super Ljava/lang/Object;
.source "AServiceManager.java"

# Stub for the Agui system service (on stock it lives in framework.jar/system_server; the GSI has none).
# The camera got it via getSystemService(AServiceManager.class); those calls are replaced with stub().

.field private static sInstance:Lcom/agui/server/AServiceManager;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static stub()Lcom/agui/server/AServiceManager;
    .registers 1
    sget-object v0, Lcom/agui/server/AServiceManager;->sInstance:Lcom/agui/server/AServiceManager;
    if-nez v0, :done
    new-instance v0, Lcom/agui/server/AServiceManager;
    invoke-direct {v0}, Lcom/agui/server/AServiceManager;-><init>()V
    sput-object v0, Lcom/agui/server/AServiceManager;->sInstance:Lcom/agui/server/AServiceManager;
    :done
    return-object v0
.end method

.method public isCampingLampSupport(I)Z
    .registers 2
    const/4 v0, 0x0
    return v0
.end method

.method public SendMessageToIoctl(IIII)I
    .registers 6
    const/4 v0, 0x0
    return v0
.end method

.method public setTwoFlashLight(Ljava/lang/String;Ljava/lang/String;)V
    .registers 3
    return-void
.end method

.method public getADaemonController()Lcom/agui/server/IADaemonController;
    .registers 2
    new-instance v0, Lcom/agui/server/StubADaemonController;
    invoke-direct {v0}, Lcom/agui/server/StubADaemonController;-><init>()V
    return-object v0
.end method
