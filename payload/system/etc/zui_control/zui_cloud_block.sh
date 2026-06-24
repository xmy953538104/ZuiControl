#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
CLOUD_DIR=$DATA_ROOT/cloud
LOG_DIR=$DATA_ROOT/log
LOG=$LOG_DIR/cloud_block.log
STATUS=$CLOUD_DIR/status.txt
DOMAIN_LIST=$CLOUD_DIR/domain_block.txt
HOSTS_FILE=/system/etc/hosts
CHAIN=zui_cloud_block

cloud_domains() {
    cat <<'EOF'
a.lenovo.com.cn
activeicon.weather.zui.com
adapi.lenovomm.com
air.lenovows.com
ams.lenovomm.com
antitheft.zui.com
api.ldrmp.lenovomm.com
api.naea1.uds.lenovo.com
apizui.lenovomm.com
apk6.lenovomm.com
app.lenovo.com
cloud.lenovo.com
cloud-service.lenovomm.com
cms-lecloud.lenovo.com
com.motorola.cn
contact.cloud.lps.lenovo.com
content-yunpan.lenovows.com
cos.lenovows.com
fus.lenovomm.com
fw.zui.com
gateway-dev.xue.lenovo.com.cn
gr.lenovomm.com
hao.lenovo.com
hao.lenovo.com.cn
hubv1.lenovo.com.cn
image.zuk.com
iocos.lenovows.com
lcs.dev.surepush.cn
lcs.lenovomm.com
lcs.test.lenovomm.cn
lds.lenovomm.com
lecloud.lenovo.com
lecloud-fe-test.lenovomm.cn
lefile.lenovo.com
lefile-h5-test.lenovomm.cn
lefile-test.mbgstore.lenovo.com.cn
lenovo.com
lenovomm.cn
lenovomm.com
lenovows.com
osfsr.lenovomm.com
ota.lenovo.com
passport.lenovo.com
pay.lenovomm.com
pbs.lenovomm.com
pcsupport.lenovo.com
photo.cloud.lps.lenovo.com
pim.lenovo.com
pimapi.lenovomm.com
privacy.lenovo.com.cn
prw.lenovomm.com
psb.lenovomm.com
push-rest.zui.com
pvtshpv1.lenovo.com.cn
s1.lenovomm.cn
sams.lenovomm.com
sdac.lenovo.com
servicezui.lenovo.com.cn
servicezuitest.lenovo.com.cn
shop.lenovo.com.cn
sms.cloud.lps.lenovo.com
sss.lenovomm.com
support.lenovo.com
supportapi.lenovo.com
susapi.dev.surepush.cn
susapi.lenovomm.com
tbcloud.lenovo.com
tbdata.lenovo.com
test.surepush.cn
uss.lenovomm.com
uss.zui.com
uss-pid.lenovomm.com
uss-us.lenovomm.com
vantage.csw.lenovo.com
wth.lenovomm.com
www.lenovo.com
www.lenovo.com.cn
www.lenovomm.com
www.zuk.com
yunpan.lenovo.com
zui.com
zui.lenovo.com
zui.zuk.com
zui-test.lenovo.com
zuk.com
EOF
}

PACKAGES="
com.zui.game.service
com.zui.engine
com.lenovo.tbengine
com.lenovo.leos.cloud.sync
com.tblenovo.tabpushout
com.tblenovo.center
"

UID1000_PACKAGES="
com.zui.pp
com.zui.cores
com.zui.safecenter
"

ts() {
    date '+%Y-%m-%d %H:%M:%S'
}

log_msg() {
    mkdir -p "$LOG_DIR"
    printf '%s %s\n' "$(ts)" "$*" >> "$LOG"
}

status_begin() {
    mkdir -p "$CLOUD_DIR" "$LOG_DIR"
    chmod 0775 "$DATA_ROOT" "$CLOUD_DIR" "$LOG_DIR" 2>/dev/null || true
    {
        echo "time=$(ts)"
        echo "mode=$1"
        echo "chain=$CHAIN"
    } > "$STATUS"
}

status_add() {
    echo "$*" >> "$STATUS"
}

settings_state() {
    settings put system zui_control_cloud_block_state "$1" >/dev/null 2>&1 || true
}

check_hosts_block() {
    cloud_domains > "$DOMAIN_LIST" 2>/dev/null || true
    chmod 0644 "$DOMAIN_LIST" 2>/dev/null || true
    present=0
    missing=0
    total=0
    for domain in $(cloud_domains); do
        total=$((total + 1))
        if grep -F -q " $domain" "$HOSTS_FILE" 2>/dev/null; then
            present=$((present + 1))
        else
            missing=$((missing + 1))
        fi
    done
    status_add "hosts_file=$HOSTS_FILE"
    status_add "hosts_domains_present=$present missing=$missing total=$total"
    log_msg "hosts domain block status: present=$present missing=$missing total=$total"
}

iptables_cmds() {
    if [ -x /system/bin/iptables ]; then
        echo /system/bin/iptables
    elif command -v iptables >/dev/null 2>&1; then
        command -v iptables
    fi
    if [ -x /system/bin/ip6tables ]; then
        echo /system/bin/ip6tables
    elif command -v ip6tables >/dev/null 2>&1; then
        command -v ip6tables
    fi
}

ensure_chain() {
    cmd="$1"
    "$cmd" -N "$CHAIN" >/dev/null 2>&1 || true
    "$cmd" -F "$CHAIN" >/dev/null 2>&1 || return 1
    "$cmd" -C OUTPUT -j "$CHAIN" >/dev/null 2>&1 ||
        "$cmd" -I OUTPUT 1 -j "$CHAIN" >/dev/null 2>&1 ||
        return 1
    return 0
}

delete_chain() {
    cmd="$1"
    while "$cmd" -C OUTPUT -j "$CHAIN" >/dev/null 2>&1; do
        "$cmd" -D OUTPUT -j "$CHAIN" >/dev/null 2>&1 || break
    done
    "$cmd" -F "$CHAIN" >/dev/null 2>&1 || true
    "$cmd" -X "$CHAIN" >/dev/null 2>&1 || true
}

uid_for_pkg() {
    pkg="$1"
    line="$(pm list packages -U "$pkg" 2>/dev/null | grep "^package:$pkg " | head -n 1)"
    uid="${line##*uid:}"
    if [ -n "$uid" ] && [ "$uid" != "$line" ]; then
        printf '%s\n' "$uid"
        return 0
    fi
    dumpsys package "$pkg" 2>/dev/null |
        sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' |
        head -n 1 |
        grep '^[0-9][0-9]*$' && return 0
    if [ -r /data/system/packages.list ]; then
        awk -v wanted="$pkg" '$1 == wanted {print $2; exit}' /data/system/packages.list 2>/dev/null |
            grep '^[0-9][0-9]*$' && return 0
    fi
    case "$pkg" in
        com.zui.game.service) echo 10114 ;;
        com.zui.engine) echo 10123 ;;
        com.lenovo.tbengine) echo 10100 ;;
        com.lenovo.leos.cloud.sync) echo 10104 ;;
        com.tblenovo.tabpushout) echo 10095 ;;
        com.tblenovo.center) echo 10226 ;;
        com.zui.pp|com.zui.cores|com.zui.safecenter) echo 1000 ;;
    esac
}

apply_rules() {
    status_begin apply
    log_msg "apply cloud block start"
    check_hosts_block
    cmds="$(iptables_cmds)"
    if [ -z "$cmds" ]; then
        status_add "error=no_iptables"
        settings_state "error=no_iptables;hosts=checked"
        log_msg "apply failed: iptables missing"
        return 1
    fi

    for cmd in $cmds; do
        if ensure_chain "$cmd"; then
            status_add "table=$cmd ready"
        else
            status_add "table=$cmd error=chain_prepare_failed"
            log_msg "chain prepare failed: $cmd"
        fi
    done

    blocked_count=0
    for pkg in $PACKAGES; do
        uid="$(uid_for_pkg "$pkg")"
        if [ -z "$uid" ]; then
            status_add "skip package=$pkg reason=uid_not_found"
            continue
        fi
        if [ "$uid" = "1000" ]; then
            status_add "skip package=$pkg uid=1000 reason=shared_system_uid"
            continue
        fi
        for cmd in $cmds; do
            "$cmd" -A "$CHAIN" -m owner --uid-owner "$uid" -j REJECT >/dev/null 2>&1 || {
                status_add "rule package=$pkg uid=$uid table=$cmd result=failed"
                continue
            }
            status_add "rule package=$pkg uid=$uid table=$cmd result=reject"
        done
        blocked_count=$((blocked_count + 1))
    done

    for pkg in $UID1000_PACKAGES; do
        uid="$(uid_for_pkg "$pkg")"
        status_add "skip package=$pkg uid=${uid:-unknown} reason=uid1000_safety_boundary"
    done

    chmod 0664 "$STATUS" 2>/dev/null || true
    settings_state "enabled;blocked=$blocked_count;chain=$CHAIN;hosts=static"
    log_msg "apply cloud block done: blocked=$blocked_count hosts=static"
}

restore_rules() {
    status_begin restore
    log_msg "restore cloud block start"
    for cmd in $(iptables_cmds); do
        delete_chain "$cmd"
        status_add "table=$cmd restored"
    done
    status_add "hosts_domain_block=static_system_image"
    chmod 0664 "$STATUS" 2>/dev/null || true
    settings_state "restored;hosts=static_system_image"
    log_msg "restore cloud block done; hosts remain system-image static"
}

case "$1" in
    restore) restore_rules ;;
    apply|"") apply_rules ;;
    *)
        echo "usage: $0 [apply|restore]" >&2
        exit 2
        ;;
esac
