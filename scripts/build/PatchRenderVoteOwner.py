"""Patch the supported OEM VotesStorage read copy; stock identity is checked by the caller."""
def patch(text):
    signature='.method public getVotes(I)Landroid/util/SparseArray;'
    assert text.count(signature)==1 and 'ZUI_CONTROL_RENDER_HOOK' not in text
    start=text.index(signature);end=text.index('.end method',start)+len('.end method')
    old=text[start:end]
    assert old.count('    .registers 5')==1 and 'v3' not in old
    anchor='    iget-object v0, p0, Lcom/android/server/display/mode/VotesStorage;->mStorageLock:Ljava/lang/Object;'
    assert old.count(anchor)==1 and old.count('    return-object p1')==1
    new=old.replace('    .registers 5','    .registers 6',1).replace(anchor,'    move-object v3, p0\n\n'+anchor,1)
    new=new.replace('    return-object p1','    invoke-static {v3, p1}, Lcom/zui/server/control/RenderVoteOwner;->merge(Ljava/lang/Object;Landroid/util/SparseArray;)Landroid/util/SparseArray;\n\n    move-result-object p1\n\n    return-object p1',1)
    result=text[:start]+new+text[end:]
    assert result.count('# instance fields')==1
    return result.replace('# instance fields','.field public static final ZUI_CONTROL_RENDER_HOOK:I = 0x1\n\n# instance fields',1)
