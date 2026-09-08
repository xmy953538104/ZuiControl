// Test-only declaration subset; never on the production NDK include path.
#pragma once
#include <cstdint>
#include <sys/types.h>
using binder_status_t=int32_t;
using transaction_code_t=uint32_t;
constexpr binder_status_t STATUS_OK=0,STATUS_BAD_VALUE=-22,STATUS_NO_MEMORY=-12,
    STATUS_PERMISSION_DENIED=-1,STATUS_UNKNOWN_TRANSACTION=-74;
struct AIBinder;struct AIBinder_Class;struct AIBinder_DeathRecipient;struct AParcel;struct AStatus;
extern "C" {
void AParcel_delete(AParcel*);
binder_status_t AParcel_readInt32(const AParcel*,int32_t*);
binder_status_t AParcel_readInt64(const AParcel*,int64_t*);
binder_status_t AParcel_readString(const AParcel*,void*,bool(*)(void*,int32_t,char**));
binder_status_t AParcel_readStatusHeader(const AParcel*,AStatus**);
binder_status_t AParcel_writeInt32(AParcel*,int32_t);
binder_status_t AParcel_writeStrongBinder(AParcel*,AIBinder*);
int32_t AParcel_getDataPosition(const AParcel*);
int32_t AParcel_getDataSize(const AParcel*);
bool AStatus_isOk(const AStatus*);
void AStatus_delete(AStatus*);
uid_t AIBinder_getCallingUid();
void* AIBinder_getUserData(AIBinder*);
AIBinder_Class* AIBinder_Class_define(const char*,void*(*)(void*),void(*)(void*),binder_status_t(*)(AIBinder*,transaction_code_t,const AParcel*,AParcel*));
bool AIBinder_associateClass(AIBinder*,const AIBinder_Class*);
AIBinder* AIBinder_new(const AIBinder_Class*,void*);
AIBinder_DeathRecipient* AIBinder_DeathRecipient_new(void(*)(void*));
void AIBinder_DeathRecipient_setOnUnlinked(AIBinder_DeathRecipient*,void(*)(void*));
binder_status_t AIBinder_linkToDeath(AIBinder*,AIBinder_DeathRecipient*,void*);
binder_status_t AIBinder_unlinkToDeath(AIBinder*,AIBinder_DeathRecipient*,void*);
void AIBinder_DeathRecipient_delete(AIBinder_DeathRecipient*);
void AIBinder_decStrong(AIBinder*);
binder_status_t AIBinder_prepareTransaction(AIBinder*,AParcel**);
binder_status_t AIBinder_transact(AIBinder*,transaction_code_t,AParcel**,AParcel**,uint32_t);
}
