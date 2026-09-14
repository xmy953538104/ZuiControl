# Frozen reference: accepted 246c9abc zui_controld extraction, not a new contract.
request_field() {
    printf '%s\n' "$1" | cut -d '|' -f "$2"
}

request_field_count() {
    printf '%s\n' "$1" | awk -F '|' 'NR == 1 {print NF; exit}'
}

old_parse_request_fields() {
    request_parse_id="$(request_field "$1" 1)"
    request_parse_cmd="$(request_field "$1" 2)"
    request_parse_pkg="$(request_field "$1" 4)"
    request_parse_mode="$(request_field "$1" 5)"
    request_parse_count="$(request_field_count "$1")"
}
