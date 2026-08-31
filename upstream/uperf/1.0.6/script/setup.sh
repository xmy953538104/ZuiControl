#!/system/bin/sh
#
# Copyright (C) 2021-2022 Matt Yang
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language ygoverning wpermissions xand
# limitations under the License.
#

check_lang(){
   sys_lang=$(getprop persist.sys.language 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop persist.sys.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.product.locale.language 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.product.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.build.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=""


echo "当前检测到的系统语言: $sys_lang"
echo "Detected system language: $sys_lang"


    if [ -n "$sys_lang" ] && echo "$sys_lang" | grep -qi '^zh'; then
        LANG_CHOICE="cn"
        echo "检测到系统语言为中文，已为您选择中文~"
    elif getprop | grep -qiE 'ro.product.locale|persist.sys.locale' | grep -qi 'zh.*cn'; then
        LANG_CHOICE="cn"
        echo "检测到系统语言为中文，已为您选择中文~"
    else
        echo "无法识别您的语言/Cannot recognize your language"
        echo "请选择您的语言/Please select your language"
        count=5
        key_choice=""
        while [ $count -gt 0 ]; do
            sleep 0.5
            key_event=$(timeout 0.1 getevent -qlc 1 2>/dev/null | awk '{ print $3 }' | grep 'KEY_')
            if [ -n "$key_event" ]; then
                key_choice=$key_event
                break
            fi
            count=$((count - 1))
        done
        if [ -z "$key_choice" ]; then
            key_choice="KEY_VOLUMEDOWN"
        fi
        case "$key_choice" in
            "KEY_VOLUMEUP")
                LANG_CHOICE="cn"
                ;;
            "KEY_VOLUMEDOWN")
                LANG_CHOICE="en"
                ;;
            *)
                LANG_CHOICE="en"
                ;;
        esac
    fi
}


check_lang

print_msg() {
    if [ "$LANG_CHOICE" = "cn" ]; then
         echo "$1"
    else
         echo "$2"
    fi
}

BASEDIR="$(dirname $(readlink -f "$0"))"
. "$BASEDIR/pathinfo.sh"
. "$BASEDIR/libsysinfo.sh"

abort() {
    print_msg "$1" "$1"
    print_msg "Uperf 游戏增强安装失败" 
    exit 1
}

set_perm() {
    chown $2:$3 "$1"
    chmod $4 "$1"
    chcon $5 "$1"
}

set_perm_recursive() {
    find "$1" -type d 2>/dev/null | while read dir; do
        set_perm "$dir" $2 $3 $4 $6
    done
    find "$1" -type f -o -type l 2>/dev/null | while read file; do
        set_perm "$file" $2 $3 $5 $6
    done
}

install_uperf() {
    print_msg "- 当前调度版本：1.0.6正式版"
    print_msg "- 正在查找平台指定的配置" "- Finding platform specified config"
    print_msg "- ro.board.platform=$(getprop ro.board.platform)" "- ro.board.platform=$(getprop ro.board.platform)"
    print_msg "- ro.product.board=$(getprop ro.product.board)" "- ro.product.board=$(getprop ro.product.board)"
    local target
    local cfgname
    target="$(getprop ro.board.platform)"
    cfgname="$(get_config_name $target)"
    if [ "$cfgname" = "unsupported" ]; then
        target="$(getprop ro.product.board)"
        cfgname="$(get_config_name $target)"
    fi
    if [ "$cfgname" = "unsupported" ] || [ ! -f "$MODULE_PATH/config/$cfgname.json" ]; then
        abort "! 处理器[$target]暂不支持 Target [$target] not supported."
    fi
    print_msg "- Uperf 配置位于 $USER_PATH" "- Uperf config is located at $USER_PATH"
    mkdir -p "$USER_PATH"
    mv -f "$USER_PATH/uperf.json" "$USER_PATH/uperf.json.bak"
    cp -f "$MODULE_PATH/config/$cfgname.json" "$USER_PATH/uperf.json"
    [ ! -e "$USER_PATH/perapp_powermode.txt" ] && cp "$MODULE_PATH/config/perapp_powermode.txt" "$USER_PATH/perapp_powermode.txt"
    rm -rf "$MODULE_PATH/config"
    set_perm_recursive "$BIN_PATH" 0 0 0755 0755 u:object_r:system_file:s0
}

fix_module_prop() {
    mkdir -p /data/adb/modules/uperf/
    cp -f "$MODULE_PATH/module.prop" /data/adb/modules/uperf/module.prop
}

unlock_limit(){
    if [ ! -d "$MODPATH/system/vendor/etc/perf/" ]; then
      mkdir -p "$MODPATH/system/vendor/etc/perf/"
    fi
    for i in $(ls /system/vendor/etc/perf/); do
      touch "$MODPATH/system/vendor/etc/perf/$i"
    done
}

print_msg "- 正在安装Uperf 游戏增强"
print_msg "- 模块已内置as线程优化，如有需要请解压模块手动安装"
echo "-----------------------------------------------------"
echo "-----------------------------------------------------"
install_uperf
print_msg "* 重启即可"
print_msg "* 欢迎使用Uperf 游戏增强"
fix_module_prop