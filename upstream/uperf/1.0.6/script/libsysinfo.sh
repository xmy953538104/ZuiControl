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
# See the License for the specific language governing permissions and
# limitations under the License.
#

###############################
# Platform info functions
###############################

# $1:"4.14" return:string_in_version
match_linux_version() {
    echo "$(cat /proc/version | grep -i "$1")"
}

soc_model(){
soc_model=$(getprop ro.soc.model)
echo "$soc_model"
}

get_socid() {
    if [ -f /sys/devices/soc0/soc_id ]; then
        echo "$(cat /sys/devices/soc0/soc_id)"
    else
        echo "$(cat /sys/devices/system/soc/soc0/id)"
    fi
}

get_nr_core() {
    echo "$(cat /proc/stat | grep cpu[0-9] | wc -l)"
}

# $1:cpuid
get_maxfreq() {
    echo "$(cat "/sys/devices/system/cpu/cpu$1/cpufreq/cpuinfo_max_freq")"
}

is_aarch64() {
    if [ "$(getprop ro.product.cpu.abi)" == "arm64-v8a" ]; then
        echo "true"
    else
        echo "false"
    fi
}

is_eas() {
    if [ "$(grep sched /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors)" != "" ]; then
        echo "true"
    else
        echo "false"
    fi
}

is_mtk() {
    local soc_model="$(getprop ro.soc.model)"
    local hw_check="$(getprop ro.hardware)
                    $(getprop ro.board.platform)
                    $(getprop ro.boot.hardware)
                    $(getprop ro.soc.manufacturer)
                    $soc_model"
                    
    local pattern='(mt[0-9]{4}|mediatek|dimensity|helio[ _-]g?[0-9]{2})'

    echo "$hw_check" | tr '[:upper:]' '[:lower:]' | grep -qE "$pattern"

    [ $? -eq 0 ] && echo "true" || echo "false"
}

_get_pinekala_type() {
    if [ "$(soc_model)" == "SM8650" ]; then
        echo "sdm8g3"
    else
        echo "sdm8g3q"
    fi
}

_get_cliffs_type() {
    if [ "$(soc_model)" == "SM8635" ]; then
        echo "sdm8sg3"
    else
        echo "sdm7+g3"
    fi
}

_get_sun_type() {
    if [ "$(soc_model)" == "SM8735" ]; then
        echo "sdm8sg4"
    else
        echo "sdm8e"
    fi
}

_get_volcano_type() {
    if [ "$(soc_model)" == "SM7635" ]; then
        echo "sdm7sg3"
    else
        echo "sdm7sg4"
    fi
}

_get_taro_type() {
    if [ "$(soc_model)" == "SM8450" ]; then
        echo "sdm8g1"
    elif [ "$(soc_model)" == "SM8475" ]; then
        echo "sdm8+g1"
    elif [ "$(soc_model)" == "SM7475" ]; then
        echo "sdm7+g2"    
    else
        echo "sdm7g1"
    fi
}

_get_canoe_type() {
    if [ "$(soc_model)" == "SM8850" ]; then
        echo "sdm8eg5"   
    else
        echo "sdm8g5"
    fi
}

_get_mt6899_type() {
    if [ "$(get_maxfreq 7)" -gt 3400000 ]; then
        echo "d8500"
    else
        echo "d8400"        
    fi
}

# $1:board_name
get_config_name() {
    case "$1" in
    "canoe") echo "$(_get_canoe_type)" ;;
    "sun") echo "$(_get_sun_type)" ;;    
    "cliffs") echo "$(_get_cliffs_type)" ;;       
    "pineapple") echo "$(_get_pinekala_type)" ;;
    "kalama") echo "sdm8g2" ;;
    "taro") echo "$(_get_taro_type)" ;;
    "volcano") echo "$(_get_volcano_type)" ;;
    "parrot") echo "sdm7sg2" ;;
    "mt6895") echo "d8100" ;;
    "mt6896") echo "d8200" ;;
    "mt6897") echo "d8300" ;;
    "mt6899") echo "$(_get_mt6899_type)" ;;
    "mt6983") echo "d9000" ;;
    "mt6985") echo "d9200" ;;
    "mt6989") echo "d9300" ;;
    "mt6991") echo "d9400" ;;
    "mt6993") echo "d9500" ;;
    *) echo "unsupported" ;;
    esac
}
