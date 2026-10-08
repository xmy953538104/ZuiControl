package com.zui.server.control;

/** A committed generation's active runtime delta; never a persistent policy owner. */
final class PolicyRuntimePlan {
    final boolean full, refresh, uperf;
    PolicyRuntimePlan(boolean full,boolean refresh,boolean uperf){this.full=full;this.refresh=refresh;this.uperf=uperf;}
    static String mode(AppPolicyStore.State state,int user,String pkg,boolean interactive){
        return interactive?state.resolved(user,pkg).uperfMode:"powersave";
    }
    static GpuRange gpu(AppPolicyStore.State state,int user,String pkg,String mode){
        AppPolicyStore.Row row=state.apps.get(AppPolicyStore.key(user,pkg));
        if(row!=null&&row.gpuPolicy.equals("CUSTOM"))return new GpuRange(row.gpuMinMHz,row.gpuMaxMHz);
        return state.defaults.getOrDefault(AppPolicyStore.key(user,mode),AppPolicyStore.factory(mode,state.schema<3));
    }
    static PolicyRuntimePlan between(AppPolicyStore.State before,AppPolicyStore.State after,
            int refreshUser,String refreshPkg,int modeUser,String modePkg,boolean interactive,boolean force){
        if(before==null||force)return new PolicyRuntimePlan(true,true,true);
        boolean refresh=before.resolved(refreshUser,refreshPkg).refreshHz!=after.resolved(refreshUser,refreshPkg).refreshHz;
        String oldMode=mode(before,modeUser,modePkg,interactive),newMode=mode(after,modeUser,modePkg,interactive);
        boolean uperf=!oldMode.equals(newMode)||!gpu(before,modeUser,modePkg,oldMode).same(gpu(after,modeUser,modePkg,newMode));
        // A mixed active-owner update keeps the established full reconciliation.
        boolean full=refresh&&uperf;
        return new PolicyRuntimePlan(full,full||refresh,full||uperf);
    }
}
