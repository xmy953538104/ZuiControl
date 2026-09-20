"""Owner-authorized ROM-controller exemption; exact OEM method, no SystemUI changes."""
import hashlib

BUILDER_SHA='8f354feb87f23c0f2b9368a48640e640b15ebf7e7f003327827dac923307e4c3'
ORIGINAL_DEX_SHA='0c6f8568d4dfb06b1db0a59ba727fc958159dd581b670d06eee467175ca94299'
METHOD='.method private blacklist fullyCustomViewRequiresDecoration(Z)Z'
CLASS='android/app/Notification$Builder.smali'
GUARD='''    iget-object v1, p0, Landroid/app/Notification$Builder;->mContext:Landroid/content/Context;
    invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v1
    const-string v2, "com.zui.zuicontrol"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :zui_standard_decoration
    iget-object v1, p0, Landroid/app/Notification$Builder;->mContext:Landroid/content/Context;
    invoke-virtual {v1}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
    move-result-object v1
    iget v1, v1, Landroid/content/pm/ApplicationInfo;->flags:I
    and-int/lit8 v1, v1, 0x1
    if-eqz v1, :zui_standard_decoration
    iget-object v1, p0, Landroid/app/Notification$Builder;->mN:Landroid/app/Notification;
    invoke-virtual {v1}, Landroid/app/Notification;->getChannelId()Ljava/lang/String;
    move-result-object v1
    const-string v2, "zui_control_monitor_v1"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :zui_standard_decoration
    return v0
    :zui_standard_decoration
'''

def method_bounds(text):
    if text.count(METHOD)!=1: raise ValueError('unexpected notification method count')
    start=text.index(METHOD);end=text.index('.end method',start)+len('.end method')
    return start,end

def patch_builder(data):
    text=data.decode('utf8').replace('\r\n','\n')
    if hashlib.sha256(text.encode()).hexdigest()!=BUILDER_SHA:
        raise ValueError('unqualified OEM Notification.Builder')
    start,end=method_bounds(text);method=text[start:end]
    needle='    :cond_14\n'
    if method.count(needle)!=1 or '.registers 5' not in method:
        raise ValueError('unexpected notification method layout')
    out=text[:start]+method.replace(needle,needle+GUARD,1)+text[end:]
    assert out.replace(GUARD,'',1)==text
    return out.encode('utf8')
