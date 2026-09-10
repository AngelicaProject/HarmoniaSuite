#!/bin/sh
log={{log}}
printf '%s wait %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "{{pid}}" >> "$log"
while kill -0 {{pid}} 2>/dev/null; do sleep 1; done
printf '%s start\n' "$(date '+%Y-%m-%d %H:%M:%S')" >> "$log"
cd {{cwd}}
rm -- "$0"
exec {{command}}
